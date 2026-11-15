package github.vhoom.zhijian.domain.interception;

import github.vhoom.zhijian.data.model.interception.InterceptionChallenge;
import github.vhoom.zhijian.data.model.interception.InterceptionEffect;
import github.vhoom.zhijian.data.model.interception.InterceptionEvent;
import github.vhoom.zhijian.data.model.interception.InterceptionMode;
import github.vhoom.zhijian.data.model.interception.InterceptionStage;
import github.vhoom.zhijian.data.model.policy.ControlLevel;
import github.vhoom.zhijian.data.model.settings.InterceptionOptions;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 模式的页面组合及结束效果；统一状态机按组合前进，不关心页面如何交互。
 *
 * 维护日期：2026年11月14日
 */
public final class ModeFlowPlan {
    public final List<InterceptionStage> pages;
    public final InterceptionEffect completion;

    /**
     * 保存不可变流程，禁止展示层修改模式组合。
     * @param completion 最后一页完成后的效果
     * @param pages 顺序引用的独立页面阶段
     */
    private ModeFlowPlan(InterceptionEffect completion, InterceptionStage... pages) {
        this.pages = Collections.unmodifiableList(Arrays.asList(pages.clone()));
        this.completion = completion;
    }

    /**
     * 禁用与提醒共用三档展示模式，具体结束效果由会话开关决定。
     * @param kind 规则输出类别
     * @param level 当前管控档位
     * @return 对应模式
     */
    public static InterceptionMode modeFor(InterceptionDecision.Kind kind, ControlLevel level) {
        if (level == ControlLevel.SEAL) return InterceptionMode.SEAL;
        return level == ControlLevel.REPAIR
                ? InterceptionMode.REPAIR : InterceptionMode.WUWEI;
    }

    /**
     * 为三档强度组合页面，提醒的自然恢复由运行层判断。
     * @param mode 要展示的模式
     * @param challenge 稳定选中的挑战，空配置使用按压挑战
     * @param options 本会话冻结的流程开关
     * @return 仅包含页面引用与结束约束的组合
     */
    public static ModeFlowPlan forMode(InterceptionMode mode, InterceptionChallenge challenge,
            InterceptionOptions options) {
        InterceptionStage challengePage = challenge == null ? InterceptionStage.HOLD : challenge.stage();
        InterceptionEffect completion = options.temporaryPassEnabled
                ? InterceptionEffect.ISSUE_GRANT : InterceptionEffect.RETURN_TO_TARGET;
        switch (mode) {
            case REPAIR:
                if (!options.obstaclePagesEnabled) return new ModeFlowPlan(completion, InterceptionStage.INITIAL);
                if (challenge == InterceptionChallenge.SUDOKU && options.confirmationEnabled) {
                    return new ModeFlowPlan(completion, InterceptionStage.INITIAL, InterceptionStage.SUDOKU,
                            InterceptionStage.SUDOKU_COMPLETED, InterceptionStage.FINAL_CONFIRM);
                }
                return new ModeFlowPlan(completion,
                        InterceptionStage.INITIAL,
                        challengePage,
                        options.confirmationEnabled ? InterceptionStage.FINAL_CONFIRM : challengePage.completedChallenge());
            case SEAL:
                return new ModeFlowPlan(InterceptionEffect.NONE, InterceptionStage.LOCKED);
            case WUWEI:
                return new ModeFlowPlan(completion, InterceptionStage.INITIAL);
            default:
                throw new IllegalArgumentException("Mode has no interception flow: " + mode);
        }
    }

    /**
     * 返回当前页允许向后推进的事件，挑战报告完成，完成态等待另一次主动继续。
     * @param page 当前页面阶段
     * @return 该页的推进事件
     */
    public InterceptionEvent completionEvent(InterceptionStage page) {
        return page == InterceptionStage.INITIAL || page == InterceptionStage.FINAL_CONFIRM || page.isCompletedChallenge()
                ? InterceptionEvent.CONTINUE : InterceptionEvent.CHALLENGE_COMPLETED;
    }

}
