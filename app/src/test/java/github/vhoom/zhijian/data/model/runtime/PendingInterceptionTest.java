package github.vhoom.zhijian.data.model.runtime;

import github.vhoom.zhijian.data.model.policy.ControlLevel;
import github.vhoom.zhijian.domain.interception.InterceptionDecision;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * 验证未完成拦截的双时钟恢复、策略失效和休息归零后的保留。
 *
 * 维护日期：2026年11月08日
 */
public class PendingInterceptionTest {
    /** 同启动修改墙钟不能缩短休息；跨启动恢复不会超过原要求。 */
    @Test
    public void pauseUsesMonotonicClockAndBoundsRebootRecovery() {
        PendingInterception pending = pending(InterceptionDecision.Kind.INTERRUPT);
        assertEquals(20_000L, pending.remainingPauseMs("boot", 999_999L, 11_000L));
        assertEquals(29_000L, pending.remainingPauseMs("next", 110_000L, 1_000L));
        assertEquals(29_000L, pending.remainingPauseMs("next", 90_000L, 1_000L));
        assertEquals(0L, pending.remainingPauseMs("boot", 100_000L, 50_000L));
    }

    /** 中断归零保留起点供恢复时结算；普通规则在恢复时间到达时失效。 */
    @Test
    public void interruptedRestRemainsPendingButOrdinaryRuleExpires() {
        assertTrue(pending(InterceptionDecision.Kind.INTERRUPT)
                .isCurrent("policy", 1L, ControlLevel.REPAIR, 999_999L));
        assertFalse(pending(InterceptionDecision.Kind.BLOCK)
                .isCurrent("policy", 1L, ControlLevel.REPAIR, 160_000L));
    }

    /** 身份、修订或档位变化后不沿用旧拦截。 */
    @Test
    public void changingPolicyOrLevelInvalidatesPendingSession() {
        PendingInterception pending = pending(InterceptionDecision.Kind.INTERRUPT);
        assertFalse(pending.isCurrent("other", 1L, ControlLevel.REPAIR, 100_000L));
        assertFalse(pending.isCurrent("policy", 2L, ControlLevel.REPAIR, 100_000L));
        assertFalse(pending.isCurrent("policy", 1L, ControlLevel.SEAL, 100_000L));
    }

    /**
     * 创建固定时钟与策略身份的待办。
     *
     * @param kind 本次待办的拦截类型
     */
    private static PendingInterception pending(InterceptionDecision.Kind kind) {
        InterceptionDecision decision = new InterceptionDecision("target", "policy", 1L,
                kind, kind == InterceptionDecision.Kind.INTERRUPT
                ? InterceptionDecision.Trigger.CONTINUOUS : InterceptionDecision.Trigger.WINDOW_BAN,
                ControlLevel.REPAIR, "window", 1L, 1L, 160_000L, 60_000L,
                kind == InterceptionDecision.Kind.INTERRUPT ? 30_000L : 0L);
        return new PendingInterception(decision, "session", "boot", 100_000L, 1_000L);
    }
}
