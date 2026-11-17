package github.vhoom.zhijian.domain.interception;

import github.vhoom.zhijian.data.model.policy.ControlLevel;
import github.vhoom.zhijian.data.model.policy.RuleType;
import github.vhoom.zhijian.data.model.policy.RuleWindow;
import github.vhoom.zhijian.data.model.runtime.PendingInterception;
import org.junit.Test;
import java.time.Instant;
import static org.junit.Assert.*;

/**
 * 验证配置更替下的窗口发生身份、原统计边界与本轮有效休息。
 *
 * 维护日期：2026年11月16日
 */
public final class ReminderScheduleTest {
    /** 同一窗口改变结束点也独立统计，旧确认不能串入新窗口。 */
    @Test public void changingEitherEndpointChangesOccurrenceIdentity() {
        long now = Instant.parse("2026-11-16T09:30:00Z").toEpochMilli();
        RuleWindow old = new RuleWindow("same", RuleType.WINDOW_CONTINUOUS, 540, 600, 60_000L);
        RuleWindow changed = new RuleWindow("same", RuleType.WINDOW_CONTINUOUS, 540, 660, 60_000L);
        assertNotEquals(ReminderSchedule.cycleKey("group:1", old, now, "UTC"),
                ReminderSchedule.cycleKey("group:1", changed, now, "UTC"));
    }

    /** 旧时段在保存时封口，转交同组目标仍保留休息和已确认用量。 */
    @Test public void closingOldWindowPreservesAcknowledgementAndRest() {
        InterceptionDecision decision = new InterceptionDecision("a", "group:1", 1L,
                InterceptionDecision.Kind.INTERRUPT, InterceptionDecision.Trigger.WINDOW_CONTINUOUS,
                ControlLevel.REPAIR, "same", 120_000L, 60_000L, 0L, 60_000L, 180_000L);
        PendingInterception rest = new PendingInterception(decision, "session", "boot", 1_000L,
                1_000L, 0L, 1_000L, "old-window", 0L, 120_000L, 600_000L);
        PendingInterception changed = rest.closeUsageAt(200_000L).increaseRestRequirement(600_000L)
                .increaseRestRequirement(60_000L).forTarget("b");
        assertEquals(200_000L, changed.continuousCeilingMs);
        assertEquals(200_000L, changed.closeUsageAt(500_000L).continuousCeilingMs);
        assertEquals(120_000L, changed.acknowledgedUsageMs);
        assertEquals(600_000L, changed.decision.minimumInterruptionPauseDurationMs);
        assertEquals(rest.sessionId, changed.sessionId);
    }
}
