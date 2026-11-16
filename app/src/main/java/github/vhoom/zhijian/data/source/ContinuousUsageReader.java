package github.vhoom.zhijian.data.source;

import android.app.usage.UsageStatsManager;
import android.app.usage.UsageEvents;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import github.vhoom.zhijian.data.model.policy.RuleGroup;
import github.vhoom.zhijian.data.model.runtime.RuntimeCheckpoint;
import github.vhoom.zhijian.data.model.runtime.PendingInterception;
import github.vhoom.zhijian.data.store.PolicySnapshot;
import github.vhoom.zhijian.data.store.ZhijianStorage;
import github.vhoom.zhijian.data.store.RuntimeStateStore;
import github.vhoom.zhijian.data.store.usage.ContinuousUsageStore;
import github.vhoom.zhijian.domain.usage.ContinuousUsageCycle;
import github.vhoom.zhijian.domain.usage.ContinuousRestPolicy;
import github.vhoom.zhijian.domain.usage.ForegroundObservation;
import github.vhoom.zhijian.domain.usage.UsageTimeline;
import github.vhoom.zhijian.domain.usage.UsageTrackingStart;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 系统事件到规则组周期的适配器；共享策略身份决定哪些应用共用使用与休息。
 *
 * 维护日期：2026年11月16日
 */
public final class ContinuousUsageReader {
    private static final Object LOCK = new Object();
    private final UsageStatsManager manager;
    private final PackageManager packages;
    private final String selfPackage;
    private final ContinuousUsageStore store;
    private final Function<String, RuntimeCheckpoint> checkpointFor;
    private final Supplier<PolicySnapshot> currentPolicies;
    private final Map<String, Boolean> eligible = new HashMap<>();

    /** @param context 系统服务与持久化入口 */
    public ContinuousUsageReader(Context context) {
        this(context, key -> ZhijianStorage.get(context).state().runtime.checkpoint(key),
                () -> ZhijianStorage.get(context).snapshot());
    }

    /**
     * 注入只读状态接口，配置写入队列可同步结算周期而不反向等待自身队列。
     * @param context 系统及私有存储上下文 @param checkpointFor 已提交的恢复检查点
     * @param currentPolicies 最新已提交配置，避免旧异步请求覆盖切档后的周期
     */
    public ContinuousUsageReader(Context context, Function<String, RuntimeCheckpoint> checkpointFor,
            Supplier<PolicySnapshot> currentPolicies) {
        manager = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        packages = context.getPackageManager();
        selfPackage = context.getPackageName();
        store = new ContinuousUsageStore(context);
        this.checkpointFor = checkpointFor;
        this.currentPolicies = currentPolicies;
    }

    /** @param operation 周期结算及配置发布操作 @param <T> 返回类型 @return 同一周期锁内的操作结果 */
    public static <T> T withCycleLock(Supplier<T> operation) {
        synchronized (LOCK) { return operation.get(); }
    }

    /** @param policies 已提交配置 @param policyId 实际策略 @param cycleKey 原周期键
     * @return 本次统计身份下已验证的进度，不凭当前墙钟补计休息 */
    public ContinuousUsageCycle saved(PolicySnapshot policies, String policyId, String cycleKey) {
        UsageTrackingStart start = policies.usageStart(policyId);
        return withCycleLock(() -> store.read(cycleKey, start == null ? "" : start.id));
    }

    /**
     * 重放尚未处理的区间并同步保存，重建实例不会丢失累计或重复处理历史。
     * @param policies 同次检查的策略快照
     * @param policyId 本组或独立应用的稳定主键
     * @param policy 本次生效配置
     * @param target 当前观察到的前台应用
     * @param observedAtMs 无障碍确认前台的墙钟时间，零表示仅用系统证据
     * @param bootId 当前启动身份
     * @param nowWallMs 当前墙钟时间
     * @param nowElapsedMs 当前单调时间
     * @param pending 已保存的休息事实，检查点丢失时保留已确认用量
     * @return 本周期累计与足额休息代次
     */
    public ContinuousUsageCycle read(PolicySnapshot policies, String policyId, RuleGroup policy, String target,
            long observedAtMs, String bootId, long nowWallMs, long nowElapsedMs, PendingInterception pending) {
        return readScoped(policies, policyId, policyId, 0L, policy.interruptionPauseMillis, target,
                observedAtMs, bootId, nowWallMs, nowElapsedMs, pending);
    }

    /**
     * 独立时段使用同一重放策略，但不与全天或其他时段共享持久化身份。
     * @param policies 配置快照 @param policyId 应用归属身份 @param cycleKey 独立周期键
     * @param floorMs 初次计时不得早于的时段起点 @param requiredRestMs 此周期要求的连续休息
     * @param target 当前目标 @param observedAtMs 最近窗口观察 @param bootId 启动身份
     * @param nowWallMs 当前墙钟 @param nowElapsedMs 当前单调时间 @param pending 对应的休息事实
     * @return 已保存的独立周期
     */
    public ContinuousUsageCycle readScoped(PolicySnapshot policies, String policyId, String cycleKey,
            long floorMs, long requiredRestMs, String target, long observedAtMs, String bootId,
            long nowWallMs, long nowElapsedMs, PendingInterception pending) {
        return readScoped(policies, policyId, cycleKey, floorMs, Long.MAX_VALUE, requiredRestMs,
                target, observedAtMs, bootId, nowWallMs, nowElapsedMs, pending);
    }

    /**
     * 结算有限时段的使用及持续到当前时刻的休息，结束后的前台时间不补进原窗口。
     * @param policies 配置快照 @param policyId 使用归属 @param cycleKey 周期键 @param floorMs 原起点
     * @param ceilingMs 原时段结束，全天为 Long.MAX_VALUE @param requiredRestMs 配置休息，所有作用域共用五分钟非使用边界
     * @param target 当前观察目标 @param observedAtMs 观察时刻 @param bootId 启动身份
     * @param nowWallMs 当前墙钟 @param nowElapsedMs 当前单调时间 @param pending 原休息事实
     * @return 增量结算后的周期
     */
    public ContinuousUsageCycle readScoped(PolicySnapshot policies, String policyId, String cycleKey,
            long floorMs, long ceilingMs, long requiredRestMs, String target, long observedAtMs, String bootId,
            long nowWallMs, long nowElapsedMs, PendingInterception pending) {
        synchronized (LOCK) {
            UsageTrackingStart tracking = policies.usageStart(policyId);
            String trackingId = tracking == null ? "" : tracking.id;
            long trackingFloor = policies.usageFloorMillis(policyId, bootId, nowWallMs, nowElapsedMs);
            floorMs = Math.max(floorMs, trackingFloor);
            ContinuousUsageCycle old = store.read(cycleKey, trackingId);
            ContinuousUsageCycle saved = old;
            if (currentPolicies.get() != policies) return old;
            if (old != null && bootId.equals(old.bootId) && nowElapsedMs < old.elapsedMs) return old;
            long bootWall = Math.max(0L, nowWallMs - nowElapsedMs);
            RuntimeCheckpoint resumed = checkpointFor.apply(RuntimeStateStore.continuousResumeKey(cycleKey));
            if (resumed != null && tracking != null
                    && !tracking.includes(resumed.bootId, resumed.recordedAtMs, resumed.recordedElapsedMs)) resumed = null;
            if (pending != null && tracking != null
                    && !tracking.includes(pending.bootId, pending.occurredAtMs, pending.startedElapsedMs)) pending = null;
            if (resumed != null && (old == null || !resumed.sessionId.equals(old.resumeId))) {
                long startElapsed = bootId.equals(resumed.bootId)
                        ? Math.min(nowElapsedMs, resumed.recordedElapsedMs) : 0L;
                old = new ContinuousUsageCycle(bootId, Math.max(0L, nowWallMs - (nowElapsedMs - startElapsed)),
                        startElapsed, 0L, 0L, null, resumed.sessionId, old == null ? 0L : old.generation + 1L);
            } else if (old == null) {
                if (pending != null && pending.decision.kind
                        == github.vhoom.zhijian.domain.interception.InterceptionDecision.Kind.INTERRUPT) {
                    long startElapsed = bootId.equals(pending.bootId)
                            ? Math.min(nowElapsedMs, pending.startedElapsedMs) : 0L;
                    old = new ContinuousUsageCycle(bootId, Math.max(0L, nowWallMs - (nowElapsedMs - startElapsed)),
                            startElapsed, pending.decision.currentValue, 0L, selfPackage, "",
                            Math.max(0L, pending.cycleGeneration));
                } else {
                    // 只回查起点之前的事件来确定前台种子，历史本身不计入新周期。
                    long startWall = Math.min(nowWallMs, Math.max(floorMs,
                            tracking == null ? nowWallMs : bootWall));
                    UsageTimeline seed = UsageEventTimeline.read(manager == null ? null : manager.queryEvents(
                            Math.max(bootWall, Math.max(0L, startWall - 86_400_000L)), startWall),
                            selfPackage, null, 0L);
                    old = new ContinuousUsageCycle(bootId, startWall, nowElapsedMs - (nowWallMs - startWall),
                            0L, 0L, seed.foregroundPackage(), seed.foregroundClass(), "", 0L);
                }
            } else if (!bootId.equals(old.bootId)) {
                // 跨开机保留用量和已确认休息，不用可修改的墙钟赠送关机期间的休息。
                old = new ContinuousUsageCycle(bootId, bootWall, 0L, old.usedMs, old.restMs,
                        null, null, old.resumeId, old.generation, old.requiredRestMs);
            }
            if (ceilingMs <= old.wallMs && old.usedMs == 0L && pending == null) {
                if (old != saved) store.save(cycleKey, old, trackingId);
                return old;
            }
            long queryStart = Math.min(old.wallMs, nowWallMs);
            UsageEvents events = manager == null ? null : manager.queryEvents(queryStart, nowWallMs);
            if (events == null) return old;
            UsageTimeline timeline = UsageEventTimeline.read(events,
                    selfPackage, target, ForegroundObservation.withinInterval(observedAtMs, queryStart, nowWallMs),
                    old.foregroundPackage, old.foregroundClass, queryStart);
            Set<String> homes = HomePackageSource.read(packages);
            Predicate<String> belongs = pkg -> belongs(policies, policyId, pkg, homes);
            ContinuousUsageCycle next = old.advance(timeline, belongs,
                    ContinuousRestPolicy.forScope(belongs), nowWallMs, nowElapsedMs,
                    requiredRestMs, ceilingMs);
            store.save(cycleKey, next, trackingId);
            return next;
        }
    }

    /** @param policies 配置快照 @param policyId 当前策略 @param pkg 事件包名 @param homes 系统桌面集合
     * @return 是否计入同组使用；各强度都排除桌面，休息约束由独立策略判断 */
    private boolean belongs(PolicySnapshot policies, String policyId, String pkg, Set<String> homes) {
        return !selfPackage.equals(pkg) && !homes.contains(pkg)
                && policies.effectivePolicyId(pkg).equals(policyId)
                && (!"global".equals(policyId) || isEligible(pkg));
    }

    /** @param pkg 待检查包名 @return 全局模式下排除桌面和系统浮层的管控资格 */
    private boolean isEligible(String pkg) {
        Boolean known = eligible.get(pkg);
        if (known != null) return known;
        boolean accepted = false;
        try {
            ApplicationInfo app = packages.getApplicationInfo(pkg, 0);
            accepted = ((app.flags & ApplicationInfo.FLAG_SYSTEM) == 0
                    || (app.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0)
                    && packages.getLaunchIntentForPackage(pkg) != null;
        } catch (PackageManager.NameNotFoundException ignored) { /* 已卸载应用不计入全局组。 */ }
        eligible.put(pkg, accepted);
        return accepted;
    }
}
