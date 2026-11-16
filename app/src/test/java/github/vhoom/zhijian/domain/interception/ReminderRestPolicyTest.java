package github.vhoom.zhijian.domain.interception;

import github.vhoom.zhijian.data.model.policy.ControlLevel;
import github.vhoom.zhijian.data.model.runtime.PassGrant;
import github.vhoom.zhijian.data.model.runtime.PendingInterception;
import github.vhoom.zhijian.domain.usage.ContinuousUsageCycle;
import github.vhoom.zhijian.domain.usage.ContinuousRestPolicy;
import github.vhoom.zhijian.domain.usage.UsageTimeline;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * 使用真实事件重放验证放行继续统计、休息被打断及完成后不重复休息。
 *
 * 维护日期：2026年11月16日
 */
public class ReminderRestPolicyTest {
    /** 无敌时间到期并不完成休息，组内切换仍继续累计。 */
    @Test public void grantExpiryKeepsUsageAndOriginalRestRequirement() {
        PendingInterception rest = pending();
        PassGrant grant = new PassGrant("a", "group:1", 1L, "boot", 1_000L, 60_000L, 61_000L);
        UsageTimeline timeline = new UsageTimeline("zhijian");
        timeline.resumed("a", "Main", 1_000L);
        timeline.resumed("b", "Main", 31_000L);
        ContinuousUsageCycle cycle = used().advance(timeline, pkg -> pkg.equals("a") || pkg.equals("b"),
                61_000L, 61_000L, 120_000L);
        assertEquals(0L, grant.remainingMs("boot", 61_000L, 61_000L));
        assertEquals(180_000L, cycle.usedMs);
        assertFalse(ReminderRestPolicy.satisfied(rest, cycle));
        assertEquals(120_000L, ReminderRestPolicy.remaining(rest, cycle));
    }

    /** 放行期间离组一分钟后再次使用，下一次必须从头休息完整两分钟。 */
    @Test public void temporaryUseInterruptsPartialRest() {
        UsageTimeline timeline = new UsageTimeline("zhijian");
        timeline.resumed("other", "Main", 1_000L);
        timeline.resumed("a", "Main", 61_000L);
        timeline.resumed("other", "Main", 62_000L);
        ContinuousUsageCycle cycle = used().advance(timeline, "a"::equals, 122_000L, 122_000L, 120_000L);
        assertEquals(121_000L, cycle.usedMs);
        assertEquals(60_000L, ReminderRestPolicy.remaining(pending(), cycle));
        assertFalse(ReminderRestPolicy.satisfied(pending(), cycle));
    }

    /** 真正休息完成后即使已开始新一轮使用，原休息也不能再次要求。 */
    @Test public void completedRestDoesNotBlockNewGeneration() {
        UsageTimeline timeline = new UsageTimeline("zhijian");
        timeline.resumed("other", "Main", 1_000L);
        timeline.resumed("a", "Main", 121_000L);
        ContinuousUsageCycle cycle = used().advance(timeline, "a"::equals, 131_000L, 131_000L, 120_000L);
        assertEquals(10_000L, cycle.usedMs);
        assertTrue(ReminderRestPolicy.satisfied(pending(), cycle));
        assertEquals(0L, ReminderRestPolicy.remaining(pending(), cycle));
    }

    /** 缺少可靠用量证据不能凭墙钟假定休息已经完成。 */
    @Test public void missingEvidenceKeepsFullRest() {
        assertFalse(ReminderRestPolicy.satisfied(pending(), null));
        assertEquals(120_000L, ReminderRestPolicy.remaining(pending(), null));
    }

    /** 原提醒休息调大后，旧三分钟不能解除；五分钟严格边界统一生效。 */
    @Test public void enlargedRestUsesCurrentRoundRequirement() {
        for (String id : new String[]{"app:a", "group:1", "global"}) {
            PendingInterception rest = pending(id, 180_000L);
            ContinuousUsageCycle partial = new ContinuousUsageCycle("boot", 181_000L, 181_000L,
                    120_000L, 180_000L, "other", "Main", "", 0L, 600_000L);
            assertFalse(ReminderRestPolicy.satisfied(rest, partial));
            assertEquals(120_001L, ReminderRestPolicy.remaining(rest, partial));
            ContinuousUsageCycle exact = new ContinuousUsageCycle("boot", 301_000L, 301_000L,
                    120_000L, 300_000L, "other", "Main", "", 0L, 600_000L);
            assertFalse(ReminderRestPolicy.satisfied(rest, exact));
            assertEquals(1L, ReminderRestPolicy.remaining(rest, exact));
            ContinuousUsageCycle full = exact.advance(new UsageTimeline("zhijian"), "a"::equals,
                    301_001L, 301_001L, 600_000L);
            assertTrue(ReminderRestPolicy.satisfied(rest, full));
        }
    }

    /** 有效休息不足五分钟时，满足配置即完成，独立应用也相同。 */
    @Test public void configuredShortRestCanCompleteBeforeFiveMinutes() {
        PendingInterception rest = pending("app:a", 60_000L);
        assertEquals(60_000L, ReminderRestPolicy.remaining(rest, null));
        ContinuousUsageCycle full = new ContinuousUsageCycle("boot", 61_000L, 61_000L,
                0L, 60_000L, "other", "", 1L);
        assertTrue(ReminderRestPolicy.satisfied(rest, full));
    }

    /** @return 已连续使用两分钟的检查点 */
    private static ContinuousUsageCycle used() { return new ContinuousUsageCycle("boot", 1_000L, 1_000L,
            120_000L, 0L, "a", "", 0L); }
    /** @return 要求两分钟连续休息的原触发 */
    private static PendingInterception pending() { return pending("group:1", 120_000L); }
    /** @param policyId 原触发的稳定归属 @param requiredRestMs 原配置休息 @return 固定时钟的原触发 */
    private static PendingInterception pending(String policyId, long requiredRestMs) {
        return new PendingInterception(new InterceptionDecision("a",
            policyId, 1L, InterceptionDecision.Kind.INTERRUPT, InterceptionDecision.Trigger.WINDOW_CONTINUOUS,
            ControlLevel.REPAIR, "night", 120_000L, 120_000L, 0L, 60_000L, requiredRestMs), "rest", "boot",
            1_000L, 1_000L, 0L); }
}
