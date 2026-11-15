package github.vhoom.zhijian.data.source;

import android.content.Context;

import github.vhoom.zhijian.data.model.policy.RuleGroup;
import github.vhoom.zhijian.data.model.policy.RuleType;
import github.vhoom.zhijian.data.model.policy.RuleWindow;
import github.vhoom.zhijian.data.model.runtime.PendingInterception;
import github.vhoom.zhijian.data.store.PolicySnapshot;
import github.vhoom.zhijian.data.store.interception.PendingInterceptionStore;
import github.vhoom.zhijian.domain.interception.InterceptionDecision;
import github.vhoom.zhijian.domain.interception.InterceptionEvaluation;
import github.vhoom.zhijian.domain.interception.ReminderSchedule;
import github.vhoom.zhijian.domain.interception.RuleEngine;
import github.vhoom.zhijian.domain.interception.RuleWindowSchedule;
import github.vhoom.zhijian.domain.interception.UsageEvidence;
import github.vhoom.zhijian.domain.interception.WeekdayRuleSchedule;
import github.vhoom.zhijian.domain.interception.count.CountRuleEvaluator;
import github.vhoom.zhijian.domain.usage.UsageTrackingStart;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 在配置写入前复查触发事实；只读已提交状态，不向写入队列递归提交任务。
 *
 * 维护日期：2026年11月15日
 */
public final class RetreatProtectionReader {
    private final PendingInterceptionStore pending;
    private final ReminderUsageReader reminders;
    private final UsageEvidenceReader evidence;

    /** @param context 私有存储与系统证据上下文 @param reminders 已注入快照读取的周期适配器 */
    public RetreatProtectionReader(Context context, ReminderUsageReader reminders) {
        pending = new PendingInterceptionStore(context);
        evidence = new UsageEvidenceReader(context);
        this.reminders = reminders;
    }

    /**
     * 所有触发独立复查，页面消失或仅解除其中一个条件不会提前解锁。
     * @param policies 原配置 @param bootId 当前启动 @param wall 当前墙钟 @param elapsed 当前单调时间
     * @param zoneId 当前时区 @param resetMinute 每日恢复分钟
     * @return 尚未解除的触发事实；证据缺失时保留原约束
     */
    public List<PendingInterception> active(PolicySnapshot policies, String bootId, long wall,
            long elapsed, String zoneId, int resetMinute) {
        List<PendingInterception> facts = pending.restrictions();
        Map<String, RuleGroup> configuredPolicies = policies.configuredPolicies();
        List<PendingInterception> active = new ArrayList<>();
        Map<String, ReminderUsageReader.Snapshot> cycles = new HashMap<>();
        for (PendingInterception fact : facts) {
            InterceptionDecision decision = fact.decision;
            UsageTrackingStart tracking = policies.usageStart(decision.policyId);
            if (tracking != null && !tracking.includes(fact.bootId, fact.occurredAtMs, fact.startedElapsedMs)) continue;
            RuleGroup configured = configuredPolicies.get(decision.policyId);
            if (configured == null || !configured.enabled) continue;
            String target = targetFor(policies, decision.policyId, configured, decision.packageName);
            if (target == null) continue;
            RuleGroup policy = WeekdayRuleSchedule.resolve(configured, wall, zoneId);
            if (decision.kind == InterceptionDecision.Kind.INTERRUPT) {
                if (!ReminderSchedule.stillConfigured(configured, decision, fact.occurredAtMs, zoneId)) continue;
                ReminderUsageReader.Snapshot usage = cycles.get(decision.policyId);
                if (usage == null) {
                    usage = reminders.read(policies, decision.policyId, configured, policy, target,
                            0L, bootId, wall, elapsed, zoneId, restsFor(facts, decision.policyId));
                    cycles.put(decision.policyId, usage);
                }
                if (!usage.rested(fact)) active.add(fact);
            } else {
                if (decision.resetAtMs > 0L && wall >= decision.resetAtMs) {
                    pending.clearBlock(fact);
                    continue;
                }
                UsageEvidence used = evidence.read(target, policy, wall, 0L, resetMinute, 0L,
                        policies.usageScopeFor(target), policies.usageFloorMillis(decision.policyId, bootId, wall, elapsed));
                InterceptionEvaluation current = RuleEngine.evaluate(decision.packageName, decision.policyId,
                        policies.revisionOf(decision.policyId), policy, policies.level(), used,
                        Math.max(1L, policy.passDurationMillis), resetMinute);
                if (current.decision != null && current.decision.kind == InterceptionDecision.Kind.BLOCK
                        || !used.available && needsUsageForBlocks(policy, wall, zoneId)) active.add(fact);
                else pending.clearBlock(fact);
            }
        }
        return active;
    }

    /** @param policy 本次生效参数 @param wall 当前墙钟 @param zoneId 时区
     * @return 是否有仍需用量证据复查的禁用；已删除的条件或非活动时段不能因缺权限而锁定 */
    private static boolean needsUsageForBlocks(RuleGroup policy, long wall, String zoneId) {
        if (policy.has(RuleType.DAILY_LIMIT) || CountRuleEvaluator.needsUsage(policy, wall, zoneId)) return true;
        if (policy.has(RuleType.WINDOW_LIMIT)) {
            for (RuleWindow window : policy.windowsFor(RuleType.WINDOW_LIMIT)) {
                if (RuleWindowSchedule.activeInterval(window, wall, zoneId) != null) return true;
            }
        }
        return false;
    }

    /**
     * 切档前按旧强度结算到同一时间边界，避免随后用新策略追溯改写旧休息。
     * @param policies 仍在生效的旧配置 @param bootId 当前启动 @param wall 切换墙钟
     * @param elapsed 同一边界单调时间 @param zoneId 当前时区
     */
    public void checkpointBeforeLevelChange(PolicySnapshot policies, String bootId, long wall,
            long elapsed, String zoneId) {
        List<PendingInterception> facts = pending.restrictions();
        for (Map.Entry<String, RuleGroup> entry : policies.configuredPolicies().entrySet()) {
            String policyId = entry.getKey();
            RuleGroup source = entry.getValue();
            if (!source.enabled) continue;
            String target = targetFor(policies, policyId, source, "");
            if (target == null) continue;
            reminders.read(policies, policyId, source, WeekdayRuleSchedule.resolve(source, wall, zoneId),
                    target, 0L, bootId, wall, elapsed, zoneId, restsFor(facts, policyId));
        }
    }

    /** @param policies 原配置 @param policyId 事实所属策略 @param source 原策略配置
     * @param preferred 原触发应用 @return 仍属该策略的目标；全局无具体目标时为空串，无成员时为 null */
    private static String targetFor(PolicySnapshot policies, String policyId, RuleGroup source, String preferred) {
        if (policyId.equals(policies.effectivePolicyId(preferred))) return preferred;
        if ("global".equals(policyId)) return "";
        for (String pkg : source.apps) {
            if (policyId.equals(policies.effectivePolicyId(pkg))) return pkg;
        }
        return null;
    }

    /** @param facts 全部触发事实 @param policyId 策略键 @return 同一策略尚待复查的休息 */
    private static List<PendingInterception> restsFor(List<PendingInterception> facts, String policyId) {
        List<PendingInterception> rests = new ArrayList<>();
        for (PendingInterception fact : facts) {
            if (fact.decision.kind == InterceptionDecision.Kind.INTERRUPT
                    && fact.decision.policyId.equals(policyId)) rests.add(fact);
        }
        return rests;
    }
}
