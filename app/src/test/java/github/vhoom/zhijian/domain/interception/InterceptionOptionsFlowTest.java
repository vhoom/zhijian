package github.vhoom.zhijian.domain.interception;

import github.vhoom.zhijian.data.model.interception.InterceptionChallenge;
import github.vhoom.zhijian.data.model.interception.InterceptionEffect;
import github.vhoom.zhijian.data.model.interception.InterceptionEvent;
import github.vhoom.zhijian.data.model.interception.InterceptionMode;
import github.vhoom.zhijian.data.model.interception.InterceptionStage;
import github.vhoom.zhijian.data.model.policy.ControlLevel;
import github.vhoom.zhijian.data.model.settings.InterceptionOptions;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * 以模式、触发类别和流程开关的组合验证真实路由，避免只隐藏设置而仍执行旧流程。
 *
 * 维护日期：2026年11月14日
 */
public class InterceptionOptionsFlowTest {
    /** 关闭反问后两种挑战只进入原页完成态，重复完成事件不能放行，必须另行主动继续。 */
    @Test public void disabledConfirmationWaitsForNewContinueOnCompletedChallenge() {
        assertTrue(InterceptionOptions.defaultsFor(InterceptionMode.REPAIR).confirmationEnabled);
        for (InterceptionDecision.Kind kind : InterceptionDecision.Kind.values()) {
            for (boolean pass : new boolean[]{false, true}) {
                for (InterceptionChallenge challenge : InterceptionChallenge.values()) {
                    InterceptionFlow flow = new InterceptionFlow(kind, ControlLevel.REPAIR, challenge,
                            new InterceptionOptions(pass, true, true, false));
                    InterceptionFlow.Transition started = flow.dispatch(flow.initialStage(), InterceptionEvent.CONTINUE);
                    assertEquals(challenge.stage(), started.stage);
                    assertEquals(InterceptionEffect.NONE, flow.dispatch(started.stage, InterceptionEvent.CONTINUE).effect);
                    InterceptionFlow.Transition ready = flow.dispatch(started.stage, InterceptionEvent.CHALLENGE_COMPLETED);
                    assertEquals(challenge.stage().completedChallenge(), ready.stage);
                    assertTrue(flow.containsStage(ready.stage));
                    assertFalse(flow.containsStage(InterceptionStage.FINAL_CONFIRM));
                    assertEquals(InterceptionEffect.NONE, ready.effect);
                    assertEquals(ready.stage, flow.dispatch(ready.stage, InterceptionEvent.CHALLENGE_COMPLETED).stage);
                    assertEquals(InterceptionEffect.NONE, flow.dispatch(ready.stage, InterceptionEvent.CHALLENGE_COMPLETED).effect);
                    assertEquals(completion(pass), flow.dispatch(ready.stage, InterceptionEvent.CONTINUE).effect);
                    assertEquals(InterceptionEffect.RETURN_HOME, flow.dispatch(ready.stage, InterceptionEvent.ABANDON).effect);
                    InterceptionFlow noObstacle = new InterceptionFlow(kind, ControlLevel.REPAIR, challenge,
                            new InterceptionOptions(pass, false, true, false));
                    assertFalse(noObstacle.containsStage(ready.stage));
                    assertEquals(completion(pass), noObstacle.dispatch(noObstacle.initialStage(), InterceptionEvent.CONTINUE).effect);
                }
            }
        }
    }
    /** 默认值同时决定禁用及提醒的继续结果。 */
    @Test public void defaultsAreOffForWuweiAndOnForRepair() {
        assertFalse(InterceptionOptions.defaultsFor(InterceptionMode.WUWEI).temporaryPassEnabled);
        assertFalse(InterceptionOptions.defaultsFor(InterceptionMode.WUWEI).obstaclePagesEnabled);
        assertTrue(InterceptionOptions.defaultsFor(InterceptionMode.REPAIR).temporaryPassEnabled);
        assertTrue(InterceptionOptions.defaultsFor(InterceptionMode.REPAIR).obstaclePagesEnabled);
        for (InterceptionDecision.Kind kind : InterceptionDecision.Kind.values()) {
            InterceptionFlow flow = new InterceptionFlow(kind, ControlLevel.MORTAL, null);
            assertEquals(InterceptionEffect.RETURN_TO_TARGET,
                    flow.dispatch(flow.initialStage(), InterceptionEvent.CONTINUE).effect);
        }
    }

    /** 无为一直只有一般页；提醒与禁用都由自己的临时放行开关决定结果。 */
    @Test public void wuweiTemporaryPassSwitchControlsBothTriggerKinds() {
        for (InterceptionDecision.Kind kind : InterceptionDecision.Kind.values()) {
            for (boolean pass : new boolean[]{false, true}) {
                InterceptionFlow flow = new InterceptionFlow(kind, ControlLevel.MORTAL,
                        InterceptionChallenge.WORD_TRANSLATION, new InterceptionOptions(pass, true));
                assertEquals(InterceptionStage.INITIAL, flow.initialStage());
                assertFalse(flow.containsStage(InterceptionStage.HOLD));
                assertFalse(flow.containsStage(InterceptionStage.FINAL_CONFIRM));
                assertEquals(completion(pass), flow.dispatch(flow.initialStage(), InterceptionEvent.CONTINUE).effect);
            }
        }
    }

    /** 修心两个开关独立；开启阻碍时必须完成一项挑战及最终反问，关闭时直接结束。 */
    @Test public void repairOptionsComposeAllPageAndCompletionCombinations() {
        for (InterceptionDecision.Kind kind : InterceptionDecision.Kind.values()) {
            for (boolean pass : new boolean[]{false, true}) {
                for (boolean obstacles : new boolean[]{false, true}) {
                    for (InterceptionChallenge challenge : InterceptionChallenge.values()) {
                        InterceptionFlow flow = new InterceptionFlow(kind, ControlLevel.REPAIR,
                                challenge, new InterceptionOptions(pass, obstacles));
                        InterceptionFlow.Transition next = flow.dispatch(flow.initialStage(), InterceptionEvent.CONTINUE);
                        if (obstacles) {
                            assertEquals(challenge.stage(), next.stage);
                            assertEquals(InterceptionEffect.NONE, next.effect);
                            assertEquals(InterceptionEffect.NONE,
                                    flow.dispatch(next.stage, InterceptionEvent.CONTINUE).effect);
                            next = flow.dispatch(next.stage, InterceptionEvent.CHALLENGE_COMPLETED);
                            if (challenge == InterceptionChallenge.SUDOKU) {
                                assertEquals(InterceptionStage.SUDOKU_COMPLETED, next.stage);
                                assertEquals(InterceptionEffect.NONE, next.effect);
                                assertEquals(next.stage, flow.dispatch(next.stage, InterceptionEvent.CHALLENGE_COMPLETED).stage);
                                next = flow.dispatch(next.stage, InterceptionEvent.CONTINUE);
                            }
                            assertEquals(InterceptionStage.FINAL_CONFIRM, next.stage);
                            next = flow.dispatch(next.stage, InterceptionEvent.CONTINUE);
                        } else {
                            assertFalse(flow.containsStage(challenge.stage()));
                            assertFalse(flow.containsStage(InterceptionStage.FINAL_CONFIRM));
                        }
                        assertEquals(InterceptionStage.COMPLETE, next.stage);
                        assertEquals(completion(pass), next.effect);
                        assertEquals(InterceptionEffect.RETURN_HOME,
                                flow.dispatch(flow.initialStage(), InterceptionEvent.ABANDON).effect);
                    }
                }
            }
        }
    }

    /** 阻碍开启但挑战集合异常为空时仍必须走按压；临时放行关闭也不能绕过挑战。 */
    @Test public void enabledObstaclesKeepHoldFallbackWithoutTemporaryPass() {
        InterceptionFlow flow = new InterceptionFlow(InterceptionDecision.Kind.BLOCK,
                ControlLevel.REPAIR, null, new InterceptionOptions(false, true));
        assertEquals(InterceptionStage.HOLD,
                flow.dispatch(flow.initialStage(), InterceptionEvent.CONTINUE).stage);
    }

    /** 足额真实休息仍可复查恢复，不能因临时放行关闭而额外要求已无必要的挑战。 */
    @Test public void completedRestCanRecoverWithTemporaryPassDisabled() {
        InterceptionFlow flow = new InterceptionFlow(InterceptionDecision.Kind.INTERRUPT,
                ControlLevel.REPAIR, null, new InterceptionOptions(false, true));
        assertEquals(InterceptionEffect.RESUME_USAGE,
                flow.dispatch(flow.initialStage(), InterceptionEvent.CONTINUE, true).effect);
    }

    /** 闭关忽略传入的流程开关；损坏或伪造的开启值不能生成通行证。 */
    @Test public void sealIgnoresAllSwitchCombinations() {
        for (InterceptionDecision.Kind kind : InterceptionDecision.Kind.values()) {
            for (boolean pass : new boolean[]{false, true}) {
                for (boolean obstacles : new boolean[]{false, true}) {
                    InterceptionFlow flow = new InterceptionFlow(kind, ControlLevel.SEAL,
                            InterceptionChallenge.BUTTON_HOLD, new InterceptionOptions(pass, obstacles));
                    assertEquals(InterceptionStage.LOCKED, flow.initialStage());
                    for (InterceptionStage stage : InterceptionStage.values()) {
                        assertEquals(InterceptionEffect.NONE, flow.dispatch(stage, InterceptionEvent.CONTINUE).effect);
                        assertEquals(InterceptionEffect.NONE, flow.dispatch(stage, InterceptionEvent.CHALLENGE_COMPLETED).effect);
                    }
                }
            }
        }
    }

    /** @param pass 是否启用临时放行 @return 本次主动继续的预期效果 */
    private static InterceptionEffect completion(boolean pass) {
        return pass ? InterceptionEffect.ISSUE_GRANT : InterceptionEffect.RETURN_TO_TARGET;
    }
}
