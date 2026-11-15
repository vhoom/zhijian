package github.vhoom.zhijian.domain.interception;

import github.vhoom.zhijian.data.model.interception.InterceptionEffect;

import github.vhoom.zhijian.data.model.interception.InterceptionEvent;

import github.vhoom.zhijian.data.model.interception.InterceptionChallenge;

import github.vhoom.zhijian.data.model.interception.InterceptionStage;

import github.vhoom.zhijian.data.model.policy.ControlLevel;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * 覆盖 InterceptionFlow 的边界输入、状态变化与回归行为。
 *
 * 维护日期：2026年11月14日
 */
public class InterceptionFlowTest {
    /** 修心空摩擦配置先进入按压挑战，完成挑战后才进入最终确认。 */
    @Test
    public void repairWithoutChallengesStillRequiresFinalConfirmation() {
        InterceptionFlow flow = block(ControlLevel.REPAIR, null);
        InterceptionFlow.Transition hold = flow.dispatch(flow.initialStage(), InterceptionEvent.CONTINUE);
        assertEquals(InterceptionStage.HOLD, hold.stage);
        assertEquals(InterceptionEffect.NONE, hold.effect);
        assertEquals(InterceptionEffect.NONE, flow.dispatch(hold.stage, InterceptionEvent.CONTINUE).effect);
        InterceptionFlow.Transition confirm = flow.dispatch(hold.stage, InterceptionEvent.CHALLENGE_COMPLETED);
        assertEquals(InterceptionStage.FINAL_CONFIRM,
                flow.dispatch(confirm.stage, InterceptionEvent.CHALLENGE_COMPLETED).stage);
        assertEquals(InterceptionStage.FINAL_CONFIRM, confirm.stage);
        assertEquals(InterceptionEffect.NONE, confirm.effect);
        assertEquals(InterceptionEffect.ISSUE_GRANT,
                flow.dispatch(confirm.stage, InterceptionEvent.CONTINUE).effect);
    }

    /** 无为提醒的继续直接返回原应用，退出回桌面；两者均不受原休息期限约束。 */
    @Test public void mortalReminderReturnsWithoutGrantOrRestDeadline() {
        InterceptionFlow flow = new InterceptionFlow(InterceptionDecision.Kind.INTERRUPT, ControlLevel.MORTAL, null);
        assertEquals(InterceptionStage.INITIAL, flow.initialStage());
        for (boolean rested : new boolean[]{false, true}) {
            InterceptionFlow.Transition continued = flow.dispatch(flow.initialStage(), InterceptionEvent.CONTINUE, rested);
            assertEquals(InterceptionStage.COMPLETE, continued.stage);
            assertEquals(InterceptionEffect.RETURN_TO_TARGET, continued.effect);
            assertEquals(InterceptionEffect.RETURN_HOME,
                    flow.dispatch(flow.initialStage(), InterceptionEvent.ABANDON, rested).effect);
        }
    }

    /** 闭关不能由任何恢复阶段的继续或挑战完成事件签发通行证。 */
    @Test
    public void sealCannotGrantFromAnyRestoredStage() {
        InterceptionFlow flow = block(ControlLevel.SEAL);
        for (InterceptionStage stage : InterceptionStage.values()) {
            assertEquals(InterceptionEffect.NONE,
                    flow.dispatch(stage, InterceptionEvent.CONTINUE).effect);
            assertEquals(InterceptionEffect.NONE,
                    flow.dispatch(stage, InterceptionEvent.CHALLENGE_COMPLETED).effect);
        }
    }

    /** 闭关提醒在任何恢复阶段都不能提前申请放行。 */
    @Test public void sealReminderNeverGrantsFromRestoredStages() {
        InterceptionFlow flow = new InterceptionFlow(InterceptionDecision.Kind.INTERRUPT,
                ControlLevel.SEAL, InterceptionChallenge.BUTTON_HOLD);
        for (InterceptionStage stage : InterceptionStage.values()) {
            assertEquals(InterceptionEffect.NONE, flow.dispatch(stage, InterceptionEvent.RESET).effect);
            assertEquals(InterceptionEffect.NONE, flow.dispatch(stage, InterceptionEvent.CONTINUE, false).effect);
            assertEquals(InterceptionEffect.NONE, flow.dispatch(stage, InterceptionEvent.CHALLENGE_COMPLETED, false).effect);
        }
    }
    /** 修心提醒提前继续需要完整交互，真实休息完成可直接正常恢复。 */
    @Test public void repairReminderRequiresChallengeForTemporaryTime() {
        InterceptionFlow flow = new InterceptionFlow(InterceptionDecision.Kind.INTERRUPT,
                ControlLevel.REPAIR, InterceptionChallenge.BUTTON_HOLD);
        InterceptionFlow.Transition hold = flow.dispatch(flow.initialStage(), InterceptionEvent.CONTINUE, false);
        assertEquals(InterceptionStage.HOLD, hold.stage);
        assertEquals(InterceptionEffect.NONE, flow.dispatch(hold.stage, InterceptionEvent.CONTINUE, false).effect);
        InterceptionFlow.Transition confirm = flow.dispatch(hold.stage, InterceptionEvent.CHALLENGE_COMPLETED, false);
        assertEquals(InterceptionStage.FINAL_CONFIRM, confirm.stage);
        assertEquals(InterceptionEffect.ISSUE_GRANT, flow.dispatch(confirm.stage, InterceptionEvent.CONTINUE, false).effect);
        assertEquals(InterceptionEffect.RESUME_USAGE, flow.dispatch(hold.stage, InterceptionEvent.CONTINUE, true).effect);
    }
    /**
     * 无为默认关闭临时放行，禁用页继续只产生导航结果，禁用仍由运行层判定。
     */
    @Test
    public void wuweiContinuesWithoutGrantByDefault() {
        InterceptionFlow flow = block(ControlLevel.MORTAL);

        InterceptionFlow.Transition result = flow.dispatch(flow.initialStage(), InterceptionEvent.CONTINUE);

        assertEquals(InterceptionStage.COMPLETE, result.stage);
        assertEquals(InterceptionEffect.RETURN_TO_TARGET, result.effect);
    }

    /**
     * 验证修心顺序经过一般页、长按页和最终确认页。
     */
    @Test
    public void xiuxinComposesGeneralHoldAndGeneralPages() {
        InterceptionFlow flow = block(ControlLevel.REPAIR,
                InterceptionChallenge.BUTTON_HOLD);

        InterceptionFlow.Transition hold = flow.dispatch(flow.initialStage(), InterceptionEvent.CONTINUE);
        InterceptionFlow.Transition confirm = flow.dispatch(hold.stage, InterceptionEvent.CHALLENGE_COMPLETED);
        assertEquals(InterceptionStage.FINAL_CONFIRM,
                flow.dispatch(confirm.stage, InterceptionEvent.CHALLENGE_COMPLETED).stage);
        InterceptionFlow.Transition grant = flow.dispatch(confirm.stage, InterceptionEvent.CONTINUE);

        assertEquals(InterceptionStage.HOLD, hold.stage);
        assertEquals(InterceptionStage.FINAL_CONFIRM, confirm.stage);
        assertEquals(InterceptionEffect.ISSUE_GRANT, grant.effect);
    }

    /** 修心可替换为单词摩擦，前后的两张一般页保持不变。 */
    @Test
    public void xiuxinCanComposeWordFrictionBetweenGeneralPages() {
        InterceptionFlow flow = block(ControlLevel.REPAIR,
                InterceptionChallenge.WORD_TRANSLATION);

        InterceptionFlow.Transition word = flow.dispatch(flow.initialStage(),
                InterceptionEvent.CONTINUE);
        InterceptionFlow.Transition confirm = flow.dispatch(word.stage,
                InterceptionEvent.CHALLENGE_COMPLETED);
        assertEquals(InterceptionEffect.NONE, confirm.effect);
        assertEquals(InterceptionStage.WORD_TRANSLATION,
                flow.dispatch(word.stage, InterceptionEvent.CONTINUE).stage);

        assertEquals(InterceptionStage.WORD_TRANSLATION, word.stage);
        assertEquals(InterceptionStage.FINAL_CONFIRM, confirm.stage);
    }

    /**
     * 验证闭关模式从禁止页开始且无法继续。
     */
    @Test
    public void biguanStartsLockedAndCannotContinue() {
        InterceptionFlow flow = block(ControlLevel.SEAL);

        InterceptionFlow.Transition result = flow.dispatch(flow.initialStage(), InterceptionEvent.CONTINUE);

        assertEquals(InterceptionStage.LOCKED, result.stage);
        assertEquals(InterceptionEffect.NONE, result.effect);
    }

    /** 闭关提醒使用禁止页，挑战完成不能替代休息。 */
    @Test public void sealReminderKeepsLockedUntilNaturalRest() {
        InterceptionFlow flow = new InterceptionFlow(InterceptionDecision.Kind.INTERRUPT,
                ControlLevel.SEAL, InterceptionChallenge.WORD_TRANSLATION);
        assertEquals(InterceptionStage.LOCKED, flow.initialStage());
        assertEquals(InterceptionEffect.NONE, flow.dispatch(flow.initialStage(), InterceptionEvent.CHALLENGE_COMPLETED).effect);
        assertEquals(InterceptionEffect.NONE, flow.dispatch(flow.initialStage(), InterceptionEvent.CONTINUE, false).effect);
        assertEquals(InterceptionEffect.RESUME_USAGE, flow.dispatch(flow.initialStage(), InterceptionEvent.CONTINUE, true).effect);
    }

    /** 状态机不直接关闭页面，自然恢复统一交给运行层复查全部规则。 */
    @Test public void pauseCompletionDoesNotIssueTemporaryGrant() {
        InterceptionFlow flow = new InterceptionFlow(InterceptionDecision.Kind.INTERRUPT,
                ControlLevel.REPAIR, InterceptionChallenge.BUTTON_HOLD);
        InterceptionFlow.Transition result = flow.dispatch(flow.initialStage(), InterceptionEvent.PAUSE_COMPLETED);
        assertEquals(InterceptionStage.INITIAL, result.stage);
        assertEquals(InterceptionEffect.NONE, result.effect);
    }

    /** 连续提醒与禁用共享三档强度，不再固定为第四种执行强度。 */
    @Test public void remindersFollowThreeGlobalIntensities() {
        assertEquals(InterceptionStage.INITIAL, new InterceptionFlow(InterceptionDecision.Kind.INTERRUPT,
                ControlLevel.MORTAL, InterceptionChallenge.BUTTON_HOLD).initialStage());
        assertEquals(InterceptionStage.INITIAL, new InterceptionFlow(InterceptionDecision.Kind.INTERRUPT,
                ControlLevel.REPAIR, InterceptionChallenge.BUTTON_HOLD).initialStage());
        assertEquals(InterceptionStage.LOCKED, new InterceptionFlow(InterceptionDecision.Kind.INTERRUPT,
                ControlLevel.SEAL, InterceptionChallenge.BUTTON_HOLD).initialStage());
    }

    /** 同一个会话种子必须稳定选中同一种摩擦方式。 */
    @Test
    public void challengeSelectionIsStablePerSession() {
        assertEquals(InterceptionChallenge.selectFor("session"),
                InterceptionChallenge.selectFor("session"));
    }

    /** 所有挑战均可稳定选中，不把新增挑战排除在通用目录外。 */
    @Test
    public void challengeSelectionIncludesEveryRegisteredType() {
        java.util.EnumSet<InterceptionChallenge> selectedChallenges = java.util.EnumSet.noneOf(InterceptionChallenge.class);
        for (int index = 0; index < 300; index++) {
            InterceptionChallenge selected = InterceptionChallenge.selectFor(
                    "session-" + index);
            selectedChallenges.add(selected);
        }

        assertEquals(java.util.EnumSet.allOf(InterceptionChallenge.class), selectedChallenges);
    }

    /**
     * 构造携带拦截决定和下一次检查时间的判断结果。
     *
     * @param level 全局管控档位
     */
    private static InterceptionFlow block(ControlLevel level) {
        return block(level, InterceptionChallenge.BUTTON_HOLD);
    }

    /**
     * 用指定摩擦方式构造普通规则触发的流程。
     *
     * @param level 全局管控档位
     * @param challenge 本次会话选择的摩擦挑战
     */
    private static InterceptionFlow block(ControlLevel level,
            InterceptionChallenge challenge) {
        return new InterceptionFlow(InterceptionDecision.Kind.BLOCK, level, challenge);
    }
}
