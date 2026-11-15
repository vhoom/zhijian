package github.vhoom.zhijian.domain.interception;

import github.vhoom.zhijian.data.model.interception.InterceptionChallenge;
import github.vhoom.zhijian.data.model.interception.InterceptionEffect;
import github.vhoom.zhijian.data.model.interception.InterceptionEvent;
import github.vhoom.zhijian.data.model.interception.InterceptionStage;
import github.vhoom.zhijian.data.model.policy.ControlLevel;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

/**
 * 普通中断独立于摩擦，但不能缩短规则设置的休息期限。
 *
 * 维护日期：2026年11月08日
 */
public class GeneralInterruptionFlowTest {
    /** 一般页在休息结束前留页，结束后用户确认才能恢复使用。 */
    @Test public void generalInterruptionWaitsForRestBeforeResuming() {
        InterceptionFlow flow = new InterceptionFlow(InterceptionDecision.Kind.INTERRUPT,
                ControlLevel.REPAIR, null);
        assertEquals(InterceptionStage.INITIAL, flow.initialStage());
        assertEquals(InterceptionEffect.NONE,
                flow.dispatch(InterceptionStage.INITIAL, InterceptionEvent.CONTINUE, false).effect);
        assertEquals(InterceptionEffect.NONE,
                flow.dispatch(InterceptionStage.INITIAL, InterceptionEvent.PAUSE_COMPLETED, true).effect);
        assertEquals(InterceptionEffect.RESUME_USAGE,
                flow.dispatch(InterceptionStage.INITIAL, InterceptionEvent.CONTINUE, true).effect);
    }

    /** 未选择挑战时修心仍保留按压步骤。 */
    @Test public void repairKeepsItsFrictionStep() {
        InterceptionFlow flow = new InterceptionFlow(InterceptionDecision.Kind.BLOCK,
                ControlLevel.REPAIR, null);
        assertEquals(InterceptionStage.HOLD,
                flow.dispatch(InterceptionStage.INITIAL, InterceptionEvent.CONTINUE).stage);
    }
}
