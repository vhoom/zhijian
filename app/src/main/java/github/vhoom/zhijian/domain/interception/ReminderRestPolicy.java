package github.vhoom.zhijian.domain.interception;

import github.vhoom.zhijian.data.model.runtime.PendingInterception;
import github.vhoom.zhijian.domain.usage.ContinuousUsageCycle;
import github.vhoom.zhijian.domain.usage.ContinuousRestPolicy;

/**
 * 以真实周期判断休息，临时授权或时段结束不能替代连续离组休息。
 *
 * 维护日期：2026年11月16日
 */
public final class ReminderRestPolicy {
    /** 休息判断不持有系统或界面对象。 */
    private ReminderRestPolicy() { }

    /** @param rest 原触发事实 @param cycle 已重放的独立周期 @return 是否真正完成原休息 */
    public static boolean satisfied(PendingInterception rest, ContinuousUsageCycle cycle) {
        return cycle != null && (cycle.usedMs == 0L || rest.cycleGeneration >= 0L
                && cycle.generation > rest.cycleGeneration
                || cycle.restMs >= required(rest, cycle));
    }

    /** @param rest 原触发事实 @param cycle 已重放周期，缺失时保留完整要求 @return 剩余连续休息 */
    public static long remaining(PendingInterception rest, ContinuousUsageCycle cycle) {
        return satisfied(rest, cycle) ? 0L : Math.max(0L,
                required(rest, cycle) - (cycle == null ? 0L : cycle.restMs));
    }

    /** @param rest 原触发要求 @param cycle 本轮已持久化的有效要求 @return OR 结束条件对应的毫秒阈值 */
    private static long required(PendingInterception rest, ContinuousUsageCycle cycle) {
        return ContinuousRestPolicy.requiredRestMillis(rest.decision.policyId,
                Math.max(rest.decision.minimumInterruptionPauseDurationMs, cycle == null ? 0L : cycle.requiredRestMs));
    }
}
