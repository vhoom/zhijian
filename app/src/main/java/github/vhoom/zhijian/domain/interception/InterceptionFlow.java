package github.vhoom.zhijian.domain.interception;

import github.vhoom.zhijian.data.model.interception.InterceptionChallenge;
import github.vhoom.zhijian.data.model.interception.InterceptionEffect;
import github.vhoom.zhijian.data.model.interception.InterceptionEvent;
import github.vhoom.zhijian.data.model.interception.InterceptionStage;
import github.vhoom.zhijian.data.model.policy.ControlLevel;
import github.vhoom.zhijian.data.model.settings.InterceptionOptions;

/**
 * 拦截交互的纯状态机。
 *
 * <p>无为使用一般页；修心按开关组合挑战、反问或原页完成态；闭关使用禁止页。
 * 结束效果取决于会话的临时放行开关，中断保留独立休息期限。</p>
 *
 * 维护日期：2026年11月09日
 */
public final class InterceptionFlow {
    /**
     * 一次状态机判断的不可变结果，包含下一阶段与待执行效果。
     */
    public static final class Transition {
        public final InterceptionStage stage;
        public final InterceptionEffect effect;

        /**
         * 创建 Transition 实例，并初始化本次操作依赖或不可变字段。
         *
         * @param stage 当前或目标交互阶段
         * @param effect 状态转移要求执行的效果
         */
        Transition(InterceptionStage stage, InterceptionEffect effect) {
            this.stage = stage;
            this.effect = effect;
        }
    }

    private final InterceptionDecision.Kind kind;
    private final ModeFlowPlan plan;

    /**
     * 创建 InterceptionFlow 实例，并初始化本次操作依赖或不可变字段。
     *
     * @param kind 普通拦截或打断拦截的类别
     * @param level 全局管控档位
     * @param challenge 本次会话选择的摩擦挑战
     */
    public InterceptionFlow(InterceptionDecision.Kind kind, ControlLevel level, InterceptionChallenge challenge) {
        this(kind, level, challenge, InterceptionOptions.defaultsFor(ModeFlowPlan.modeFor(kind, level)));
    }

    /**
     * 使用冻结选项组合本次流程，设置变化不会改写进行中的会话。
     * @param kind 提醒或禁用
     * @param level 当前执行强度
     * @param challenge 本次稳定选中的挑战
     * @param options 会话放行和阻碍页面开关
     */
    public InterceptionFlow(InterceptionDecision.Kind kind, ControlLevel level,
            InterceptionChallenge challenge, InterceptionOptions options) {
        this.kind = kind;
        this.plan = ModeFlowPlan.forMode(ModeFlowPlan.modeFor(kind, level), challenge, options);
    }

    /** 返回模式组合的首个独立页面。 */
    public InterceptionStage initialStage() {
        return plan.pages.get(0);
    }

    /** @param stage 待恢复阶段 @return 阶段是否属于当前会话的页面组合 */
    public boolean containsStage(InterceptionStage stage) { return plan.pages.contains(stage); }

    /**
     * 将当前阶段和输入事件转换为下一阶段及待执行效果。
     *
     * @param stage 当前或目标交互阶段
     * @param event 系统事件或当前状态机收到的交互事件
     */
    public Transition dispatch(InterceptionStage stage, InterceptionEvent event) {
        return dispatch(stage, event, false);
    }

    /**
     * 根据休息完成状态处理主动继续；提前完成挑战不能缩短规则休息时间。
     *
     * @param stage 当前页面阶段
     * @param event 用户操作或计时事件
     * @param pauseCompleted 中断休息时间是否已经全部经过
     */
    public Transition dispatch(InterceptionStage stage, InterceptionEvent event, boolean pauseCompleted) {
        if (event == InterceptionEvent.RESET) return kind == InterceptionDecision.Kind.INTERRUPT
                ? unchanged(stage) : new Transition(InterceptionStage.COMPLETE, InterceptionEffect.DISMISS);
        if (event == InterceptionEvent.ABANDON) return new Transition(InterceptionStage.COMPLETE, InterceptionEffect.RETURN_HOME);
        if (event == InterceptionEvent.PAUSE_COMPLETED) return unchanged(stage);
        if (kind == InterceptionDecision.Kind.INTERRUPT && pauseCompleted && event == InterceptionEvent.CONTINUE
                && (plan.completion != InterceptionEffect.RETURN_TO_TARGET || plan.pages.size() > 1)) {
            return completed(InterceptionEffect.RESUME_USAGE);
        }
        if (plan.completion == InterceptionEffect.NONE) return unchanged(stage);
        int index = plan.pages.indexOf(stage);
        if (index < 0 || event != plan.completionEvent(stage)) return unchanged(stage);
        return index + 1 < plan.pages.size()
                ? new Transition(plan.pages.get(index + 1), InterceptionEffect.NONE)
                : completed(plan.completion);
    }

    /**
     * 构造保持当前阶段且不执行副作用的转移结果。
     *
     * @param stage 当前或目标交互阶段
     */
    private static Transition unchanged(InterceptionStage stage) { return new Transition(stage, InterceptionEffect.NONE); }
    /**
     * 构造完成阶段并携带需要执行的效果。
     *
     * @param effect 状态转移要求执行的效果
     */
    private static Transition completed(InterceptionEffect effect) { return new Transition(InterceptionStage.COMPLETE, effect); }
}
