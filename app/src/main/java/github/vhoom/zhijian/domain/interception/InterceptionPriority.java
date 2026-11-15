package github.vhoom.zhijian.domain.interception;

import github.vhoom.zhijian.data.model.runtime.PendingInterception;

/**
 * 未完成会话与当前规则的优先级策略：新的禁用不能被旧提醒覆盖。
 *
 * 维护日期：2026年11月08日
 */
public final class InterceptionPriority {
    /** 优先级策略不持有运行状态。 */
    private InterceptionPriority() { }

    /**
     * @param pending 已确认仍有效、尚未完成的会话
     * @param current 当前证据重新计算的规则结果；有效破例放行由运行层先处理
     * @return 新禁用结果或原会话；未触发规则和新提醒均不丢弃原会话身份
     */
    public static InterceptionEvaluation beforeRestoring(PendingInterception pending,
            InterceptionEvaluation current) {
        if (pending.decision.kind == InterceptionDecision.Kind.INTERRUPT
                && current.decision != null && current.decision.kind == InterceptionDecision.Kind.BLOCK) {
            return current;
        }
        return InterceptionEvaluation.restore(pending);
    }
}
