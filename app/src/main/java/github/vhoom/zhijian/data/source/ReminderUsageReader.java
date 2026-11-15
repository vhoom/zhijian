package github.vhoom.zhijian.data.source;

import android.content.Context;
import github.vhoom.zhijian.data.model.policy.RuleGroup;
import github.vhoom.zhijian.data.model.policy.RuleWindow;
import github.vhoom.zhijian.data.model.policy.RuleType;
import github.vhoom.zhijian.data.model.runtime.PendingInterception;
import github.vhoom.zhijian.data.store.PolicySnapshot;
import github.vhoom.zhijian.domain.interception.InterceptionDecision;
import github.vhoom.zhijian.domain.interception.ReminderSchedule;
import github.vhoom.zhijian.domain.interception.ReminderRestPolicy;
import github.vhoom.zhijian.domain.interception.RuleWindowSchedule;
import github.vhoom.zhijian.domain.usage.ContinuousUsageCycle;
import github.vhoom.zhijian.util.permission.PermissionStatusReader;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 提醒计时适配器：全天持续统计，活动时段与已触发的跨时段休息各自持久化。
 *
 * 维护日期：2026年11月15日
 */
public final class ReminderUsageReader {
    private final Context context;
    private final ContinuousUsageReader reader;

    /** @param context 系统用量与周期存储入口 */
    public ReminderUsageReader(Context context) {
        this(context, new ContinuousUsageReader(context));
    }

    /** @param context 权限查询上下文 @param reader 已注入状态来源的周期读取器 */
    public ReminderUsageReader(Context context, ContinuousUsageReader reader) {
        this.context = context.getApplicationContext();
        this.reader = reader;
    }

    /** @param policies 提交快照 @param policyId 实际归属 @param source 完整配置 @param policy 当次星期配置
     * @param target 当前目标 @param observedMs 最近前台观察 @param bootId 启动身份 @param wall 当前墙钟
     * @param elapsed 当前单调时间 @param zoneId 时区 @param rests 未完成休息
     * @return 同次检查使用的全部周期，放行期间也调用以保留统计 */
    public Snapshot read(PolicySnapshot policies, String policyId, RuleGroup source, RuleGroup policy,
            String target, long observedMs, String bootId, long wall, long elapsed, String zoneId,
            List<PendingInterception> rests) {
        Snapshot snapshot = new Snapshot();
        snapshot.allDayKey = policyId;
        boolean available = PermissionStatusReader.hasUsageAccess(context);
        Map<String, Scope> scopes = new HashMap<>();
        long trackingFloor = policies.usageFloorMillis(policyId, bootId, wall, elapsed);
        if (ReminderSchedule.hasAllDay(source)) {
            scopes.put(policyId, new Scope(trackingFloor, Long.MAX_VALUE, Math.max(1L, policy.interruptionPauseMillis), null));
        }
        RuleWindow active = ReminderSchedule.activeWindow(policy, wall, zoneId);
        if (active != null) {
            RuleWindowSchedule.Interval interval = RuleWindowSchedule.activeInterval(active, wall, zoneId);
            snapshot.activeKey = ReminderSchedule.cycleKey(policyId, active, wall, zoneId);
            snapshot.activeWindowId = active.id;
            scopes.put(snapshot.activeKey, new Scope(Math.max(trackingFloor, interval.startMs),
                    interval.endMs, active.interruptionPauseMillis, null));
        }
        if (policy.has(RuleType.WINDOW_CONTINUOUS)) {
            for (RuleWindow window : policy.windowsFor(RuleType.WINDOW_CONTINUOUS)) {
                RuleWindowSchedule.Interval interval = RuleWindowSchedule.latestCompletedInterval(window, wall, zoneId);
                if (interval == null || interval.endMs <= trackingFloor) continue;
                String key = ReminderSchedule.cycleKey(policyId, window, interval.endMs - 1L, zoneId);
                scopes.put(key, new Scope(Math.max(trackingFloor, interval.startMs),
                        interval.endMs, window.interruptionPauseMillis, null));
                snapshot.completedWindows.add(new CompletedWindow(window, key, interval));
            }
        }
        for (PendingInterception rest : rests) {
            String key = key(policyId, policy, rest, zoneId);
            Scope existing = scopes.get(key);
            long floor = 0L;
            long ceiling = Long.MAX_VALUE;
            if (rest.decision.trigger == InterceptionDecision.Trigger.WINDOW_CONTINUOUS) {
                RuleWindow window = ReminderSchedule.window(policy, rest.decision.windowId);
                RuleWindowSchedule.Interval interval = RuleWindowSchedule.activeInterval(window, rest.occurredAtMs, zoneId);
                floor = !rest.continuousKey.isEmpty() ? rest.continuousFloorMs
                        : interval == null ? rest.startedAtMs : interval.startMs;
                ceiling = existing != null ? existing.ceilingMs : interval == null ? Long.MAX_VALUE : interval.endMs;
            }
            scopes.put(key, new Scope(floor, ceiling, Math.max(rest.decision.minimumInterruptionPauseDurationMs,
                    existing == null ? 0L : existing.restMs), rest));
            snapshot.restKeys.put(rest.sessionId, key);
        }
        for (Map.Entry<String, Scope> entry : scopes.entrySet()) {
            Scope scope = entry.getValue();
            snapshot.floors.put(entry.getKey(), scope.floorMs);
            snapshot.cycles.put(entry.getKey(), available ? reader.readScoped(policies, policyId, entry.getKey(),
                    scope.floorMs, scope.ceilingMs, scope.restMs, target, observedMs, bootId, wall, elapsed, scope.pending)
                    : reader.saved(policies, policyId, entry.getKey()));
        }
        snapshot.allDay = snapshot.cycles.get(policyId);
        return snapshot;
    }

    /** @param policyId 实际策略 @param policy 解析后窗口 @param rest 原休息 @param zoneId 时区 @return 周期键 */
    private static String key(String policyId, RuleGroup policy, PendingInterception rest, String zoneId) {
        if (!rest.continuousKey.isEmpty()) return rest.continuousKey;
        return rest.decision.trigger == InterceptionDecision.Trigger.WINDOW_CONTINUOUS
                ? ReminderSchedule.cycleKey(policyId, ReminderSchedule.window(policy, rest.decision.windowId),
                        rest.occurredAtMs, zoneId) : policyId;
    }

    /** 单次重放所需的不可变参数。 */
    private static final class Scope {
        private final long floorMs, ceilingMs, restMs;
        private final PendingInterception pending;
        /** @param floorMs 首次使用起点 @param ceilingMs 原使用终点 @param restMs 足额休息 @param pending 原触发 */
        private Scope(long floorMs, long ceilingMs, long restMs, PendingInterception pending) {
            this.floorMs = floorMs;
            this.ceilingMs = ceilingMs;
            this.restMs = restMs;
            this.pending = pending;
        }
    }

    /** 已结束窗口的发生身份，与同名窗口下一次发生的周期分开。 */
    public static final class CompletedWindow {
        public final RuleWindow window;
        public final String cycleKey;
        public final RuleWindowSchedule.Interval interval;

        /** @param window 本次窗口参数 @param cycleKey 本次发生的周期键 @param interval 原实际区间 */
        private CompletedWindow(RuleWindow window, String cycleKey, RuleWindowSchedule.Interval interval) {
            this.window = window;
            this.cycleKey = cycleKey;
            this.interval = interval;
        }
    }

    /** 全部周期由适配器构造，外部只读取，不修改持久化数据。 */
    public static final class Snapshot {
        private final Map<String, ContinuousUsageCycle> cycles = new HashMap<>();
        private final Map<String, String> restKeys = new HashMap<>();
        private final Map<String, Long> floors = new HashMap<>();
        private final List<CompletedWindow> completedWindows = new ArrayList<>();
        private String activeKey, activeWindowId, allDayKey;
        private ContinuousUsageCycle allDay;

        /** 只由适配器创建检查快照。 */
        private Snapshot() { }
        /** @return 只读的全天连续周期，未配置时为空 */
        public ContinuousUsageCycle allDay() { return allDay; }
        /** @return 最近已结束窗口的只读发生列表，供边界兜底复查 */
        public List<CompletedWindow> completedWindows() { return Collections.unmodifiableList(completedWindows); }
        /** @param window 已结束窗口 @return 已裁剪到原窗口终点的周期 */
        public ContinuousUsageCycle cycle(CompletedWindow window) { return cycles.get(window.cycleKey); }
        /** @return 本次活动时段的连续用量；累计时段额度不使用此接口 */
        public Map<String, Long> windowUsage() {
            Map<String, Long> values = new HashMap<>();
            ContinuousUsageCycle active = cycles.get(activeKey);
            if (active != null) values.put(activeWindowId, active.usedMs);
            return values;
        }
        /** @param rest 原未完成休息 @return 对应周期；缺权限时只使用已验证的存量进度 */
        public ContinuousUsageCycle cycle(PendingInterception rest) { return cycles.get(restKeys.get(rest.sessionId)); }
        /** @param decision 当次新触发 @return 对应当前活动周期 */
        public ContinuousUsageCycle cycle(InterceptionDecision decision) {
            return decision.trigger == InterceptionDecision.Trigger.WINDOW_CONTINUOUS ? cycles.get(activeKey) : allDay;
        }
        /** @param decision 新触发提醒 @return 原始周期键 */
        public String keyFor(InterceptionDecision decision) {
            return decision.trigger == InterceptionDecision.Trigger.WINDOW_CONTINUOUS ? activeKey : allDayKey;
        }
        /** @param decision 新触发提醒 @return 原始窗口起点 */
        public long floorFor(InterceptionDecision decision) { return floors.getOrDefault(keyFor(decision), 0L); }
        /** @param rest 本次新增的提醒事实，沿用已经重放的当前周期 */
        public void track(PendingInterception rest) {
            restKeys.put(rest.sessionId, rest.continuousKey.isEmpty() ? keyFor(rest.decision) : rest.continuousKey);
        }
        /** @param rest 原触发事实 @return 是否已经被真实连续休息满足 */
        public boolean rested(PendingInterception rest) {
            return ReminderRestPolicy.satisfied(rest, cycle(rest));
        }
        /** @param rest 未完成休息 @return 本次真实剩余休息，提前使用会重新变为完整时长 */
        public long remaining(PendingInterception rest) {
            return ReminderRestPolicy.remaining(rest, cycle(rest));
        }
    }
}
