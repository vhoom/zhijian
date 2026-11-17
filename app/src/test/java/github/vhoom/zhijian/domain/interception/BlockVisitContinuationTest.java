package github.vhoom.zhijian.domain.interception;

import github.vhoom.zhijian.data.model.policy.ControlLevel;
import github.vhoom.zhijian.data.model.policy.RuleGroup;
import github.vhoom.zhijian.data.model.policy.RuleType;
import github.vhoom.zhijian.data.model.runtime.BlockContinuation;
import org.junit.Test;
import java.util.Collections;
import static org.junit.Assert.*;

/**
 * 确认本次访问后全部禁用停止打断，但新达到的连续提醒仍然生效。
 *
 * 维护日期：2026年11月16日
 */
public final class BlockVisitContinuationTest {
    /** 同时限时限次，继续后不限次；后续连续用量达到提醒阈值仍打断。 */
    @Test public void visitConfirmationSuppressesAllBlocksButKeepsContinuousReminder() {
        RuleGroup policy = new RuleGroup(1L, "test");
        policy.types.add(RuleType.DAILY_LIMIT);
        policy.types.add(RuleType.DAILY_COUNT);
        policy.types.add(RuleType.CONTINUOUS);
        policy.dailyLimitMillis = 60_000L;
        policy.count = 1;
        policy.continuousLimitMillis = 180_000L;
        BlockContinuation confirmation = new BlockContinuation("a", "group:1", 1L,
                ControlLevel.REPAIR, InterceptionDecision.Trigger.DAILY_LIMIT, "", "boot", 1_000L);
        assertNull(evaluate(policy, 120_000L, confirmation).decision);
        InterceptionDecision reminder = evaluate(policy, 180_000L, confirmation).decision;
        assertNotNull(reminder);
        assertEquals(InterceptionDecision.Kind.INTERRUPT, reminder.kind);
        assertEquals(InterceptionDecision.Trigger.CONTINUOUS, reminder.trigger);
    }

    /** @param policy 当前组合规则 @param continuousMs 连续用量 @param confirmation 本次访问确认 @return 正式引擎结果 */
    private static InterceptionEvaluation evaluate(RuleGroup policy, long continuousMs, BlockContinuation confirmation) {
        UsageEvidence evidence = new UsageEvidence(true, 10_000_000L, "UTC", 600_000L,
                600_000L, 3L, continuousMs, Collections.emptyMap());
        return RuleEngine.evaluate("a", "group:1", 1L, policy, ControlLevel.REPAIR,
                evidence, 60_000L, 0, confirmation::matches);
    }
}
