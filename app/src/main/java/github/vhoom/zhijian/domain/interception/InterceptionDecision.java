package github.vhoom.zhijian.domain.interception;

import github.vhoom.zhijian.data.model.policy.ControlLevel;

import java.util.Objects;

/**
 * 规则模块输出的完整拦截决定，足够让运行层启动页面而无需再次解释规则。
 *
 * 维护日期：2026年11月12日
 */
public final class InterceptionDecision {
    /**
     * 区分普通规则拦截与连续使用打断的决定类型。
     */
    public enum Kind { INTERRUPT, BLOCK }

    /**
     * 当前规则判断命中的触发条件及其稳定记录键。
     */
    public enum Trigger {
        DAILY_LIMIT("daily_limit"),
        DAILY_COUNT("daily_count"),
        WINDOW_LIMIT("window_limit"),
        WINDOW_BAN("window_ban"),
        CONTINUOUS("continuous"),
        WINDOW_CONTINUOUS("window_continuous"),
        WINDOW_COUNT("window_count"),
        ROLLING_COUNT("rolling_count");

        public final String code;

        /**
         * 创建 Trigger 实例，并初始化本次操作依赖或不可变字段。
         *
         * @param code 供长期存储或诊断使用的稳定文本键
         */
        Trigger(String code) { this.code = code; }
    }

    public final String packageName;
    public final String policyId;
    public final long policyRevision;
    public final Kind kind;
    public final Trigger trigger;
    public final ControlLevel level;
    public final String windowId;
    public final long currentValue;
    public final long limitValue;
    public final long resetAtMs;
    public final long passDurationMs;
    /** 中断摩擦的最低持续时间；具体页面可以因自身要求而更长。 */
    public final long minimumInterruptionPauseDurationMs;

    /**
     * 创建一次可直接交给拦截页面执行的决定。
     *
     * @param packageName 被拦截应用的包名
     * @param policyId 命中的策略标识
     * @param policyRevision 判定时的策略修订号，用于临时放行签发时核对配置
     * @param kind 打断或禁止类型
     * @param trigger 触发本次拦截的规则
     * @param level 当前控制档位
     * @param windowId 命中的时段标识；非时段规则传空值
     * @param currentValue 当前用量或次数
     * @param limitValue 规则限制值
     * @param resetAtMs 规则恢复的墙钟时间；没有固定恢复点时为零
     * @param passDurationMs 完成流程后发放的临时放行时长
     * @param minimumInterruptionPauseDurationMs 打断摩擦的最低时长；页面可延长
     */
    public InterceptionDecision(String packageName, String policyId, long policyRevision,
            Kind kind, Trigger trigger, ControlLevel level, String windowId, long currentValue,
            long limitValue, long resetAtMs, long passDurationMs,
            long minimumInterruptionPauseDurationMs) {
        this.packageName = Objects.requireNonNull(packageName);
        this.policyId = Objects.requireNonNull(policyId);
        this.kind = Objects.requireNonNull(kind);
        this.trigger = Objects.requireNonNull(trigger);
        this.level = Objects.requireNonNull(level);
        this.windowId = windowId == null ? "" : windowId;
        this.policyRevision = policyRevision;
        this.currentValue = currentValue;
        this.limitValue = limitValue;
        this.resetAtMs = resetAtMs;
        this.passDurationMs = passDurationMs;
        this.minimumInterruptionPauseDurationMs = minimumInterruptionPauseDurationMs;
        if (packageName.isEmpty() || policyId.isEmpty() || policyRevision < 1 || currentValue < 0
                || limitValue < 0 || resetAtMs < 0 || passDurationMs <= 0
                || (kind == Kind.INTERRUPT && minimumInterruptionPauseDurationMs <= 0)
                || (kind == Kind.BLOCK && minimumInterruptionPauseDurationMs != 0)) {
            throw new IllegalArgumentException("Invalid interception decision");
        }
    }
}
