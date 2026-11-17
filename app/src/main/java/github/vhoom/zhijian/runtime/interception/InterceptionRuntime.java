package github.vhoom.zhijian.runtime.interception;

import android.content.Context;
import android.os.SystemClock;

import github.vhoom.zhijian.data.model.runtime.PassGrant;
import github.vhoom.zhijian.data.model.runtime.PendingInterception;
import github.vhoom.zhijian.data.model.runtime.InterceptionRecord;
import github.vhoom.zhijian.data.store.interception.PendingInterceptionStore;
import github.vhoom.zhijian.data.store.interception.BlockContinuationStore;
import github.vhoom.zhijian.data.model.runtime.BlockContinuation;
import github.vhoom.zhijian.data.model.policy.ControlLevel;
import github.vhoom.zhijian.data.model.policy.RuleGroup;
import github.vhoom.zhijian.domain.interception.WeekdayRuleSchedule;
import github.vhoom.zhijian.data.source.ReminderUsageReader;
import github.vhoom.zhijian.data.source.ContinuousUsageReader;
import github.vhoom.zhijian.domain.interception.ReminderSchedule;
import github.vhoom.zhijian.domain.interception.ReminderRepeatPolicy;
import github.vhoom.zhijian.data.source.UsageEvidenceReader;
import github.vhoom.zhijian.data.store.ZhijianStorage;
import github.vhoom.zhijian.data.store.settings.GlobalSettings;
import github.vhoom.zhijian.data.store.PolicySnapshot;
import github.vhoom.zhijian.data.store.RuntimeStateStore;
import github.vhoom.zhijian.domain.interception.InterceptionEvaluation;
import github.vhoom.zhijian.domain.interception.InterceptionDecision;
import github.vhoom.zhijian.domain.interception.RuleEngine;
import github.vhoom.zhijian.domain.interception.UsageEvidence;
import github.vhoom.zhijian.util.config.AppConfig;
import github.vhoom.zhijian.util.time.BootIdentity;
import github.vhoom.zhijian.domain.usage.ContinuousUsageCycle;
import github.vhoom.zhijian.domain.usage.ContinuousRestPolicy;
import github.vhoom.zhijian.domain.usage.UsageTrackingStart;

import java.time.ZoneId;
import java.util.UUID;
import java.util.List;
import java.util.Collections;

/**
 * 规则模块的 Android 运行入口：组合配置快照、系统用量和持久化放行。
 *
 * 维护日期：2026年11月16日
 */
public final class InterceptionRuntime {
    private static final long MINUTE_MS = 60_000L;

    private final ZhijianStorage storage;
    private final RuntimeStateStore runtimeStore;
    private final UsageEvidenceReader evidenceReader;
    private final AppConfig config;
    private final GlobalSettings settings;
    private final String bootId;
    private final PendingInterceptionStore pendingStore;
    private final ReminderUsageReader reminderReader;
    private final BlockContinuationStore continuations;

    /**
     * 创建 InterceptionRuntime 实例，并初始化本次操作依赖或不可变字段。
     *
     * @param context 访问系统服务、资源或持久化文件的上下文
     */
    public InterceptionRuntime(Context context) {
        storage = ZhijianStorage.get(context);
        runtimeStore = new RuntimeStateStore(context);
        evidenceReader = new UsageEvidenceReader(context);
        config = new AppConfig(context);
        settings = new GlobalSettings(context, config);
        bootId = BootIdentity.read(context);
        pendingStore = new PendingInterceptionStore(context);
        reminderReader = new ReminderUsageReader(context);
        continuations = new BlockContinuationStore(context);
    }

    /** @param packageName 真实前台包名，调用方在后台串行提交，拦截返回不结束访问 */
    public void observeForeground(String packageName) { continuations.observeForeground(packageName); }

    /**
     * 在后台持久化同组禁用的当前目标身份，完成落盘后才能启动页面或接受继续操作。
     * @param original 已保存的原组员展示 @param target 当前组员包名
     * @return 当前目标的完整展示；配置、原展示或放行变化时返回空
     */
    public PendingInterception redirectSharedBlock(PendingInterception original, String target) {
        return ContinuousUsageReader.withCycleLock(() -> {
            PolicySnapshot policies = storage.snapshot();
            String policyId = policies.effectivePolicyId(target);
            if (!policyId.startsWith("group:") || policies.level() == ControlLevel.SEAL
                    || original.decision.kind != InterceptionDecision.Kind.BLOCK
                    || !original.isCurrent(policyId, policies.revisionOf(policyId), policies.level(),
                            System.currentTimeMillis())) return null;
            PendingInterception current = pendingStore.read(original.decision.packageName);
            if (current == null || !current.sessionId.equals(original.sessionId)
                    || current.decision.trigger != original.decision.trigger
                    || !current.decision.windowId.equals(original.decision.windowId)
                    || current.decision.policyRevision != original.decision.policyRevision) return null;
            PendingInterception selected = current.forTarget(target);
            if (isPresentationSuppressed(selected.decision)) return null;
            pendingStore.save(selected);
            return selected;
        });
    }

    /** @param decision 候选展示 @return 是否属于同档位本次已确认禁用，后续开关变化不撤销确认 */
    public boolean isContinuedBlock(InterceptionDecision decision) {
        return decision.level != ControlLevel.SEAL
                && continuations.read(decision.packageName, bootId).stream().anyMatch(value -> value.matches(decision));
    }

    /**
     * 在保存展示或实际启动页面前复查已提交的放行事实，阻止旧检查结果覆盖新确认。
     * @param decision 待展示的冻结规则决定
     * @return 当前有效通行证或同规则访问确认是否已使该展示失效
     */
    public boolean isPresentationSuppressed(InterceptionDecision decision) {
        ZhijianStorage.State state = storage.state();
        long wall = System.currentTimeMillis();
        long elapsed = SystemClock.elapsedRealtime();
        PassGrant grant = state.runtime.grantFor(decision.packageName, decision.policyId, bootId, wall, elapsed);
        return (grant != null && grant.covers(decision.packageName, decision.policyId)
                && grant.remainingMs(bootId, wall, elapsed) > 0L) || isContinuedBlock(decision);
    }

    /**
     * 按条件优先级检查使用证据，并返回拦截或下次检查时间。
     *
     * @param packageName 需要查询或管控的应用包名
     */
    public InterceptionEvaluation evaluate(String packageName) {
        return evaluate(packageName, 0L);
    }

    /**
     * 联合系统用量与最近真实窗口事件检查前台应用，补足延迟到达的恢复事件。
     *
     * @param packageName 需要管控的前台应用包名
     * @param foregroundObservedAtMs 服务最近确认该应用处于前台的墙钟时间
     */
    public InterceptionEvaluation evaluate(String packageName, long foregroundObservedAtMs) {
        long nowWall = System.currentTimeMillis();
        long nowElapsed = SystemClock.elapsedRealtime();
        PolicySnapshot policies = storage.snapshot();
        String policyId = policies.effectivePolicyId(packageName);
        long revision = policies.revisionOf(policyId);
        RuleGroup configured = policies.effectiveFor(packageName);
        String zoneId = ZoneId.systemDefault().getId();
        RuleGroup policy = WeekdayRuleSchedule.resolve(configured, nowWall, zoneId);
        UsageTrackingStart tracking = policies.usageStart(policyId);
        PendingInterception presentation = pendingStore.read(packageName);
        java.util.List<PendingInterception> rests = pendingStore.rests(policyId);
        java.util.Iterator<PendingInterception> iterator = rests.iterator();
        while (iterator.hasNext()) {
            PendingInterception rest = iterator.next();
            if (runtimeStore.wasAllowed(rest.sessionId).join()
                    || tracking != null && !tracking.includes(rest.bootId, rest.occurredAtMs, rest.startedElapsedMs)
                    || !ReminderSchedule.stillConfigured(configured, rest.decision, rest.occurredAtMs, zoneId)) {
                pendingStore.clear(rest.decision.packageName, rest.sessionId);
                iterator.remove();
            }
        }
        ReminderUsageReader.Snapshot usage = reminderReader.read(policies, policyId, configured, policy,
                packageName, foregroundObservedAtMs, bootId, nowWall, nowElapsed, zoneId, rests);
        if (storage.snapshot() != policies) return InterceptionEvaluation.allow(InterceptionEvaluation.DEFAULT_RECHECK_MS);
        iterator = rests.iterator();
        while (iterator.hasNext()) {
            PendingInterception rest = iterator.next();
            if (usage.rested(rest)) {
                resumeAfterRest(currentRest(rest, policy, packageName, policyId, revision, policies.level(),
                        ReminderSchedule.passDurationMillis(policy, rest.decision, passDurationMillis(policy))),
                        nowWall, nowElapsed);
                pendingStore.clear(rest.decision.packageName, rest.sessionId);
                iterator.remove();
            }
        }
        // 结束边界先保存独立休息事实，禁用或旧放行都不能让已经达到的提醒丢失。
        if (configured.enabled) {
            for (ReminderUsageReader.CompletedWindow window : usage.completedWindows()) {
                ContinuousUsageCycle cycle = usage.cycle(window);
                if (cycle == null || hasRest(rests, window.cycleKey)) continue;
                InterceptionDecision due = RuleEngine.completedWindowReminder(packageName, policyId, revision,
                        policies.level(), window.window, window.interval, cycle.usedMs, nowWall, passDurationMillis(policy));
                if (due == null) continue;
                PendingInterception triggered = new PendingInterception(due, UUID.randomUUID().toString(),
                        bootId, nowWall, nowElapsed, cycle.generation, window.interval.endMs - 1L,
                         window.cycleKey, window.interval.startMs, -1L, window.interval.endMs);
                if (!saveRest(policies, triggered)) return InterceptionEvaluation.allow(InterceptionEvaluation.DEFAULT_RECHECK_MS);
                rests.add(triggered);
                usage.track(triggered);
            }
        }
        // 无敌时间在恢复页面之前检查；修改参数不延长原授权，期限内仍持续统计。
        PassGrant grant = runtimeStore.grantFor(packageName, bootId, nowWall, nowElapsed);
        if (grant != null) {
            long remaining = grant.covers(packageName, policyId)
                    ? grant.remainingMs(bootId, nowWall, nowElapsed) : 0L;
            if (remaining > 0L) {
                if (presentation != null) pendingStore.clearPresentation(presentation.sessionId);
                return InterceptionEvaluation.allow(remaining);
            }
            runtimeStore.revokeGrant(grant.packageName).join();
        }
        if (presentation != null && (runtimeStore.wasAllowed(presentation.sessionId).join()
                || runtimeStore.wasAllowed("pass:" + presentation.sessionId).join()
                || !presentation.decision.policyId.equals(policyId))) {
            pendingStore.clearPresentation(presentation.sessionId);
            presentation = null;
        }
        if (!configured.enabled || revision < 1 || policy.types.isEmpty() && rests.isEmpty()) {
            if (presentation != null) pendingStore.clearPresentation(presentation.sessionId);
            return InterceptionEvaluation.allow(InterceptionEvaluation.DEFAULT_RECHECK_MS);
        }
        int resetMinute = settings.dailyResetMinute();
        UsageEvidence evidence = readEvidence(packageName, policy, policies, nowWall, nowElapsed, resetMinute,
                foregroundObservedAtMs, usage.allDay()).withWindowContinuousMillis(usage.windowUsage());
        List<PendingInterception> activeRests = new java.util.ArrayList<>();
        for (PendingInterception rest : rests) if (usage.appliesToCurrentUsage(rest)) activeRests.add(rest);
        UsageEvidence reminderEvidence = ReminderRepeatPolicy.forEvaluation(evidence, activeRests, policies.level());
        List<BlockContinuation> ignored = policies.level() != ControlLevel.SEAL
                ? continuations.readForVisit(packageName, bootId,
                start -> evidenceReader.reopenedSince(packageName, start, nowWall, foregroundObservedAtMs))
                : Collections.emptyList();
        InterceptionEvaluation current = RuleEngine.evaluate(packageName, policyId, revision, policy,
                policies.level(), reminderEvidence, passDurationMillis(policy), resetMinute,
                block -> ignored.stream().anyMatch(value -> value.matches(block)));
        if (current.decision != null && current.decision.kind == InterceptionDecision.Kind.BLOCK) {
            return present(policies, current.decision, presentation, null, null, nowWall, nowElapsed,
                    current.recheckAfterMs);
        }
        if (current.decision != null) {
            boolean known = false;
            for (PendingInterception rest : rests) {
                if (rest.decision.trigger == current.decision.trigger
                        && rest.decision.windowId.equals(current.decision.windowId)
                        && usage.sameOccurrence(rest, current.decision)) known = true;
            }
            if (!known) {
                ContinuousUsageCycle cycle = usage.cycle(current.decision);
                PendingInterception triggered = new PendingInterception(current.decision,
                        UUID.randomUUID().toString(), bootId, nowWall, nowElapsed,
                        cycle == null ? -1L : cycle.generation, nowWall,
                        usage.keyFor(current.decision), usage.floorFor(current.decision), -1L,
                        usage.ceilingFor(current.decision));
                if (!saveRest(policies, triggered)) return InterceptionEvaluation.allow(InterceptionEvaluation.DEFAULT_RECHECK_MS);
                rests.add(triggered);
                usage.track(triggered);
            }
        }
        PendingInterception selected = null;
        long nextCheck = current.recheckAfterMs;
        for (PendingInterception rest : rests) {
            if (usage.replacedOccurrence(rest) && rest.acknowledgedUsageMs >= 0L
                    && policies.level() != ControlLevel.SEAL) continue;
            long remainingUsage = ReminderRepeatPolicy.remainingUsage(rest, usage.cycle(rest), policies.level(),
                    ReminderRepeatPolicy.limitMillis(rest, policy));
            if (remainingUsage > 0L) {
                nextCheck = Math.min(nextCheck, remainingUsage);
                continue;
            }
            if (selected == null || usage.remaining(rest) > usage.remaining(selected)) selected = rest;
        }
        if (selected != null) {
            PendingInterception normalized = currentRest(selected, policy, packageName, policyId, revision,
                    policies.level(), ReminderSchedule.passDurationMillis(
                            policy, selected.decision, passDurationMillis(policy)));
            return present(policies, normalized.decision, presentation, selected, usage.cycle(selected),
                    nowWall, nowElapsed, nextCheck);
        }
        if (presentation != null) pendingStore.clearPresentation(presentation.sessionId);
        return InterceptionEvaluation.allow(nextCheck);
    }

    /** @param rests 已触发休息 @param cycleKey 实际窗口发生身份 @return 同次发生是否仍有未完成休息 */
    private static boolean hasRest(java.util.List<PendingInterception> rests, String cycleKey) {
        for (PendingInterception rest : rests) {
            if (rest.decision.trigger == InterceptionDecision.Trigger.WINDOW_CONTINUOUS
                    && rest.continuousKey.equals(cycleKey)) return true;
        }
        return false;
    }

    /** @param policies 判定时的配置 @param rest 新触发事实 @return 配置仍相同时原子保存，过期计算交给下次复查 */
    private boolean saveRest(PolicySnapshot policies, PendingInterception rest) {
        return ContinuousUsageReader.withCycleLock(() -> {
            if (storage.snapshot() != policies || pendingStore.restFor(rest) != null) return false;
            pendingStore.saveRest(rest);
            return true;
        });
    }

    /**
     * 原休息保留要求及发生时刻，展示使用最新使用阈值与配置身份。
     * @param rest 原休息 @param policy 当前已解析规则 @param target 当前应用
     * @param policyId 当前策略 @param revision 当前修订
     * @param level 当前强度 @param pass 当前临时时长 @return 当前版本下的休息事实
     */
    private static PendingInterception currentRest(PendingInterception rest, RuleGroup policy, String target,
            String policyId, long revision, ControlLevel level, long pass) {
        InterceptionDecision old = rest.decision;
        InterceptionDecision decision = new InterceptionDecision(target, policyId, revision, old.kind,
                old.trigger, level, old.windowId, old.currentValue,
                ReminderRepeatPolicy.limitMillis(rest, policy), 0L, pass,
                old.minimumInterruptionPauseDurationMs);
        return new PendingInterception(decision, rest.sessionId, rest.bootId, rest.startedAtMs,
                rest.startedElapsedMs, rest.cycleGeneration, rest.occurredAtMs, rest.continuousKey,
                rest.continuousFloorMs, rest.acknowledgedUsageMs, rest.continuousCeilingMs);
    }

    /**
     * 多条禁用复用展示会话，休息起点由真实周期确定，重开页面不赠送休息。
     *
     * @param policies 判定使用的配置
     * @param decision 本次统一决定
     * @param previous 原展示
     * @param rest 可空的独立休息事实
     * @param cycle 可空的已更新周期
     * @param wall 当前墙钟
     * @param elapsed 当前单调时间
     * @param recheckAfterMs 本次规则要求的最迟复查间隔，单位为毫秒
     * @return 已持久化的唯一展示请求
     */
    private InterceptionEvaluation present(PolicySnapshot policies, InterceptionDecision decision, PendingInterception previous,
            PendingInterception rest, ContinuousUsageCycle cycle, long wall, long elapsed, long recheckAfterMs) {
        return ContinuousUsageReader.withCycleLock(() -> {
            if (storage.snapshot() != policies) return InterceptionEvaluation.allow(InterceptionEvaluation.DEFAULT_RECHECK_MS);
            if (isPresentationSuppressed(decision)) return InterceptionEvaluation.allow(1L);
            if (rest != null) {
                PendingInterception latest = pendingStore.restFor(rest);
                if (latest == null || !latest.sessionId.equals(rest.sessionId)) return InterceptionEvaluation.allow(1L);
                long remainingUsage = ReminderRepeatPolicy.remainingUsage(latest, cycle, policies.level(),
                        decision.limitValue);
                // 处理标记在本次计算期间变化，立即复查全部规则，避免推迟即将生效的禁用。
                if (remainingUsage > 0L) return InterceptionEvaluation.allow(1L);
            }
            PendingInterception reusable = previous;
            if (rest != null && decision.policyId.startsWith("group:")) {
                PendingInterception shared = pendingStore.sharedInterruption(decision.policyId);
                if (samePresentation(shared, decision, rest)) reusable = shared;
            }
            boolean same = samePresentation(reusable, decision, rest);
            long rested = cycle == null ? 0L : Math.min(cycle.restMs,
                    ContinuousRestPolicy.requiredRestMillis(decision.policyId,
                            Math.max(decision.minimumInterruptionPauseDurationMs, cycle.requiredRestMs)));
            InterceptionDecision displayed = decision;
            if (rest != null && cycle != null) {
                displayed = new InterceptionDecision(decision.packageName, decision.policyId, decision.policyRevision,
                        decision.kind, decision.trigger, decision.level, decision.windowId, cycle.usedMs,
                        decision.limitValue, decision.resetAtMs, decision.passDurationMs,
                        ContinuousRestPolicy.requiredRestMillis(decision.policyId,
                                Math.max(decision.minimumInterruptionPauseDurationMs, cycle.requiredRestMs)));
            }
            PendingInterception shown = new PendingInterception(displayed,
                    same ? reusable.sessionId : UUID.randomUUID().toString(), bootId,
                    Math.max(0L, wall - rested), Math.max(0L, elapsed - rested),
                    cycle == null ? -1L : cycle.generation,
                    rest == null ? same ? reusable.occurredAtMs : wall : rest.occurredAtMs,
                    rest == null ? "" : rest.continuousKey, rest == null ? 0L : rest.continuousFloorMs,
                    -1L, rest == null ? Long.MAX_VALUE : rest.continuousCeilingMs);
            pendingStore.save(shown);
            if (previous != null && !previous.sessionId.equals(shown.sessionId)) {
                pendingStore.clearPresentation(previous.sessionId);
            }
            return InterceptionEvaluation.restore(shown, recheckAfterMs);
        });
    }

    /** @param shown 已有展示 @param decision 当前决定 @param rest 可空原休息
     * @return 是否可复用同一展示；原触发身份不同的新一轮休息不会继承旧会话 */
    private static boolean samePresentation(PendingInterception shown, InterceptionDecision decision,
            PendingInterception rest) {
        return shown != null && shown.decision.policyId.equals(decision.policyId)
                && shown.decision.policyRevision == decision.policyRevision
                && shown.decision.kind == decision.kind && shown.decision.level == decision.level
                && (rest == null || shown.occurredAtMs == rest.occurredAtMs
                && shown.decision.trigger == decision.trigger && shown.decision.windowId.equals(decision.windowId)
                && shown.continuousKey.equals(rest.continuousKey));
    }

    /**
     * 统一新判断与旧提醒复查的用量证据及组级统计口径。
     * @param packageName 当前目标应用
     * @param policy 已解析的当日规则
     * @param policies 已提交配置快照
     * @param nowWall 当前墙钟
     * @param nowElapsed 同一查询边界的单调毫秒
     * @param dailyResetMinute 每日恢复分钟
     * @param observedAt 最近前台事件时间
     * @param cycle 连续用量周期，可为空
     * @return 共用同一统计口径的只读证据
     */
    private UsageEvidence readEvidence(String packageName, RuleGroup policy, PolicySnapshot policies,
            long nowWall, long nowElapsed, int dailyResetMinute, long observedAt, ContinuousUsageCycle cycle) {
        UsageEvidence evidence = evidenceReader.read(packageName, policy, nowWall,
                0L, dailyResetMinute, observedAt, policies.usageScopeFor(packageName),
                policies.usageFloorMillis(policies.effectivePolicyId(packageName), bootId, nowWall, nowElapsed));
        return cycle == null ? evidence : evidence.withContinuousMillis(cycle.usedMs);
    }

    /**
     * @param policy 当前策略
     * @return 有效放行毫秒数，未填写时使用全局默认值
     */
    private long passDurationMillis(RuleGroup policy) {
        return policy.passDurationMillis > 0 ? policy.passDurationMillis
                : config.defaultPassMinutes() * MINUTE_MS;
    }

    /**
     * 系统证据已确认足额离组休息，完成原中断并保留新周期已产生的使用量。
     *
     * @param pending 休息已经结束但尚未恢复使用的会话
     * @param nowWallMs 恢复使用时的墙钟时间
     * @param nowElapsedMs 恢复使用时的单调时间
     */
    private void resumeAfterRest(PendingInterception pending, long nowWallMs, long nowElapsedMs) {
        InterceptionDecision decision = pending.decision;
        InterceptionRecord record = new InterceptionRecord(pending.sessionId, decision.packageName,
                decision.policyId, decision.policyRevision, "interrupt", decision.level.code,
                decision.trigger.code, "rest_elapsed", "allowed", pending.occurredAtMs,
                Math.max(pending.occurredAtMs, nowWallMs));
        runtimeStore.recordInterruptionResume(record, bootId, nowElapsedMs, false).join();
    }

    /**
     * 返回本次系统启动身份，供放行倒计时恢复使用。
     */
    public String bootId() { return bootId; }

    /** 从真实用量事件读取当前前台包名，供服务首次连接时补查。 */
    public String currentForegroundPackage() {
        return evidenceReader.currentForegroundPackage(System.currentTimeMillis());
    }
}
