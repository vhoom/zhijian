package github.vhoom.zhijian.domain.interception;

import github.vhoom.zhijian.data.model.runtime.PendingInterception;

/**
 * 一次判定结果以及仍在前台时的最迟复查间隔。
 *
 * 维护日期：2026年11月14日
 */
public final class InterceptionEvaluation {
    public static final long DEFAULT_RECHECK_MS = 60_000L;

    public final InterceptionDecision decision;
    public final PendingInterception pending;
    public final long recheckAfterMs;

    /**
     * 创建 InterceptionEvaluation 实例，并初始化本次操作依赖或不可变字段。
     *
     * @param decision 规则引擎给出的拦截决定
     * @param recheckAfterMs 下一次检查规则前等待的毫秒数
     */
    private InterceptionEvaluation(InterceptionDecision decision, long recheckAfterMs) {
        this(decision, null, recheckAfterMs);
    }

    /**
     * 合并纯规则结果与运行层已持久化的会话身份。
     *
     * @param decision 本次规则决定
     * @param pending 已保存的未完成会话，纯规则计算时为空
     * @param recheckAfterMs 下一次检查间隔
     */
    private InterceptionEvaluation(InterceptionDecision decision, PendingInterception pending,
            long recheckAfterMs) {
        this.decision = decision;
        this.pending = pending;
        this.recheckAfterMs = Math.max(1L, Math.min(DEFAULT_RECHECK_MS, recheckAfterMs));
    }

    /**
     * 构造携带拦截决定和下一次检查时间的判断结果。
     *
     * @param decision 规则引擎给出的拦截决定
     */
    public static InterceptionEvaluation block(InterceptionDecision decision) {
        return new InterceptionEvaluation(decision, DEFAULT_RECHECK_MS);
    }

    /** @param decision 本次拦截结果 @param recheckAfterMs 最早额度释放或规则切换所要求的复查间隔
     * @return 能及时复查短窗口限次的拦截结果 */
    public static InterceptionEvaluation block(InterceptionDecision decision, long recheckAfterMs) {
        return new InterceptionEvaluation(decision, recheckAfterMs);
    }

    /**
     * 构造恢复原会话的运行结果。
     *
     * @param pending 尚未主动完成的持久化会话
     */
    public static InterceptionEvaluation restore(PendingInterception pending) {
        return restore(pending, DEFAULT_RECHECK_MS);
    }

    /**
     * 恢复会话时保留规则计算出的复查间隔，及时检查较短的额度释放边界。
     *
     * @param pending 已持久化的展示会话
     * @param recheckAfterMs 规则计算出的最迟复查间隔，单位为毫秒
     * @return 同时包含会话身份和复查要求的运行结果
     */
    public static InterceptionEvaluation restore(PendingInterception pending, long recheckAfterMs) {
        return new InterceptionEvaluation(pending.decision, pending, recheckAfterMs);
    }

    /**
     * 构造本次允许使用但需要再次检查的判断结果。
     *
     * @param recheckAfterMs 下一次检查规则前等待的毫秒数
     */
    public static InterceptionEvaluation allow(long recheckAfterMs) {
        return new InterceptionEvaluation(null, recheckAfterMs);
    }

    /**
     * 按原判定期限和当地午夜中较早的边界安排复查，扣除后台计算及主线程排队耗时。
     *
     * @param evaluatedAtWallMs 本轮规则计算开始时的墙上时钟，用于确定当日结束边界
     * @param evaluatedAtElapsedMs 同次计算开始时的单调时钟
     * @param nowElapsedMs 准备安排下一次检查时的单调时钟
     * @param zoneId 规则使用的当地时区标识
     * @return 尚需等待的毫秒数；期限已过时返回 1，尽快重新读取规则
     */
    public long recheckDelayMs(long evaluatedAtWallMs, long evaluatedAtElapsedMs,
            long nowElapsedMs, String zoneId) {
        long untilMidnight = RuleWindowSchedule.nextDayStart(evaluatedAtWallMs, zoneId) - evaluatedAtWallMs;
        long spent = Math.max(0L, nowElapsedMs - evaluatedAtElapsedMs);
        return Math.max(1L, Math.min(recheckAfterMs, untilMidnight) - spent);
    }
}
