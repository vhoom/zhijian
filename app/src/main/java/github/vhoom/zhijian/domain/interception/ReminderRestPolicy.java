package github.vhoom.zhijian.domain.interception;

import github.vhoom.zhijian.data.model.runtime.PendingInterception;
import github.vhoom.zhijian.domain.usage.ContinuousUsageCycle;
import github.vhoom.zhijian.domain.usage.ContinuousRestPolicy;

/**
 * 以真实周期判断休息，临时授权或时段结束不能替代连续离组休息。
 *
 * 维护日期：2026年11月11日
 */
public final class ReminderRestPolicy {
    /** 休息判断不持有系统或界面对象。 */
    private ReminderRestPolicy() { }

    /** @param rest 原触发事实 @param cycle 已重放的独立周期 @return 是否真正完成原休息 */
    public static boolean satisfied(PendingInterception rest, ContinuousUsageCycle cycle) {
        return cycle != null && (cycle.usedMs == 0L || rest.cycleGeneration >= 0L
                && cycle.generation > rest.cycleGeneration
                || cycle.restMs >= ContinuousRestPolicy.requiredRestMillis(rest.decision.policyId,
                        rest.decision.minimumInterruptionPauseDurationMs));
    }

    /** @param rest 原触发事实 @param cycle 已重放周期，缺失时保留完整要求 @return 剩余连续休息 */
    public static long remaining(PendingInterception rest, ContinuousUsageCycle cycle) {
        return satisfied(rest, cycle) ? 0L : Math.max(0L,
                ContinuousRestPolicy.requiredRestMillis(rest.decision.policyId,
                        rest.decision.minimumInterruptionPauseDurationMs) - (cycle == null ? 0L : cycle.restMs));
    }
}
