package github.vhoom.zhijian.domain.interception;

import github.vhoom.zhijian.data.model.policy.*;
import github.vhoom.zhijian.data.model.runtime.PendingInterception;
import org.junit.Test;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import static org.junit.Assert.*;

/**
 * 时段接管、独立连续量、统一禁用恢复及跨时段休息的领域回归。
 *
 * 维护日期：2026年11月08日
 */
public class ReminderRulesTest {
    private static final String ZONE = "Asia/Shanghai";
    private static final long MINUTE = 60_000L;

    /** 活动时段接管全天阈值，全天超限不能在时段内提前触发。 */
    @Test public void activeWindowOverridesReachedAllDayThreshold() {
        RuleGroup policy = reminders();
        InterceptionEvaluation result = evaluate(policy, at(5, 19, 9), 40 * MINUTE, 9 * MINUTE);
        assertNull(result.decision);
        assertEquals(MINUTE, result.recheckAfterMs);
    }

    /** 时段阈值达到时采用该段的独立休息参数。 */
    @Test public void windowUsesItsOwnThresholdAndRest() {
        InterceptionDecision decision = evaluate(reminders(), at(5, 19, 10), 40 * MINUTE, 10 * MINUTE).decision;
        assertEquals(InterceptionDecision.Trigger.WINDOW_CONTINUOUS, decision.trigger);
        assertEquals("evening", decision.windowId);
        assertEquals(5 * MINUTE, decision.minimumInterruptionPauseDurationMs);
    }

    /** 时段累计使用很长，但连续量不足时仍不能触发连续提醒。 */
    @Test public void cumulativeWindowUsageDoesNotReplaceContinuousUsage() {
        RuleGroup policy = reminders();
        UsageEvidence evidence = new UsageEvidence(true, at(5, 19, 9), ZONE, 40 * MINUTE, 0L, 1L,
                40 * MINUTE, Collections.singletonMap("evening", 40 * MINUTE),
                Collections.singletonMap("evening", 9 * MINUTE));
        assertNull(RuleEngine.evaluate("app", "app:app", 1L, policy, ControlLevel.REPAIR, evidence, 3 * MINUTE).decision);
    }

    /** 时段结束后恢复全天判断，保留全天已累计的连续量。 */
    @Test public void outsideWindowUsesPreservedAllDayUsage() {
        assertEquals(InterceptionDecision.Trigger.CONTINUOUS,
                evaluate(reminders(), at(5, 20, 0), 40 * MINUTE, 0L).decision.trigger);
    }

    /** 亚秒时段边界必须安排准确复查。 */
    @Test public void upcomingReminderWindowKeepsExactBoundary() {
        assertEquals(250L, evaluate(reminders(), at(5, 19, 0) - 250L, 0L, 0L).recheckAfterMs);
    }

    /** 多条禁用一起命中时，页面自然恢复点等待全部已命中禁用结束。 */
    @Test public void simultaneousBanAndDailyLimitWaitForBoth() {
        RuleGroup policy = reminders();
        policy.types.add(RuleType.WINDOW_BAN);
        policy.types.add(RuleType.DAILY_LIMIT);
        policy.dailyLimitMillis = MINUTE;
        policy.windows.add(new RuleWindow("ban", RuleType.WINDOW_BAN, 19 * 60, 20 * 60, 0L));
        InterceptionDecision decision = evaluate(policy, at(5, 19, 10), 40 * MINUTE, 10 * MINUTE).decision;
        assertEquals(InterceptionDecision.Trigger.WINDOW_BAN, decision.trigger);
        assertEquals(at(6, 0, 0), decision.resetAtMs);
    }

    /** 已触发休息在时段结束后继续存在，删除该规则才取消。 */
    @Test public void triggeredWindowRestSurvivesWindowEndButNotDeletion() {
        RuleGroup policy = reminders();
        InterceptionDecision decision = evaluate(policy, at(5, 19, 10), 0L, 10 * MINUTE).decision;
        assertTrue(ReminderSchedule.stillConfigured(policy, decision, at(5, 19, 10), ZONE));
        assertTrue(WeekdayRuleSchedule.stillApplies(policy, decision, at(5, 19, 10), at(6, 1, 0), ZONE));
        policy.types.remove(RuleType.WINDOW_CONTINUOUS);
        assertFalse(ReminderSchedule.stillConfigured(policy, decision, at(5, 19, 10), ZONE));
    }

    /** 跨午夜属于同一次窗口，下一次发生使用新的周期身份。 */
    @Test public void overnightWindowKeepsOccurrenceIdentity() {
        RuleWindow night = new RuleWindow("night", RuleType.WINDOW_CONTINUOUS, 23 * 60, 60, 10 * MINUTE);
        String key = ReminderSchedule.cycleKey("group:1", night, at(5, 23, 30), ZONE);
        assertEquals(key, ReminderSchedule.cycleKey("group:1", night, at(6, 0, 30), ZONE));
        assertNotEquals(key, ReminderSchedule.cycleKey("group:1", night, at(6, 23, 30), ZONE));
    }

    /** 修改端点后的恢复副本仍保留原周期身份，不重新清零用量。 */
    @Test public void restTargetCopyKeepsOriginalCycleMetadata() {
        InterceptionDecision decision = evaluate(reminders(), at(5, 19, 10), 0L, 10 * MINUTE).decision;
        PendingInterception pending = new PendingInterception(decision, "session", "boot", 100L, 100L,
                0L, at(5, 19, 10), "original-cycle", 90L);
        assertEquals("original-cycle", pending.forTarget("other").continuousKey);
        assertEquals(90L, pending.forTarget("other").continuousFloorMs);
    }

    /** 旧等长配置在结束边界及延迟检查都能补触发，三档保持同一阈值。 */
    @Test public void equalThresholdTriggersAtTheCompletedBoundary() {
        RuleWindow window = new RuleWindow("noon", RuleType.WINDOW_CONTINUOUS, 720, 780, 60 * MINUTE);
        window.interruptionPauseMillis = 5 * MINUTE;
        long end = at(5, 13, 0);
        RuleWindowSchedule.Interval interval = RuleWindowSchedule.latestCompletedInterval(window, end, ZONE);
        for (ControlLevel level : ControlLevel.values()) {
            for (long now : new long[]{end, end + 250L, end + MINUTE}) {
                InterceptionDecision due = RuleEngine.completedWindowReminder("app", "group:1", 1L,
                        level, window, interval, 60 * MINUTE, now, 3 * MINUTE);
                assertNotNull(due);
                assertEquals(InterceptionDecision.Trigger.WINDOW_CONTINUOUS, due.trigger);
                assertEquals(5 * MINUTE, due.minimumInterruptionPauseDurationMs);
                assertEquals(level, due.level);
            }
        }
        assertNull(RuleEngine.completedWindowReminder("app", "group:1", 1L, ControlLevel.SEAL,
                window, interval, 60 * MINUTE - 1_000L, end, 3 * MINUTE));
        assertNull(RuleEngine.completedWindowReminder("app", "group:1", 1L, ControlLevel.SEAL,
                window, interval, 60 * MINUTE, end - 1L, 3 * MINUTE));
        assertNull(RuleEngine.completedWindowReminder("app", "group:1", 1L, ControlLevel.SEAL,
                window, interval, 0L, end + MINUTE, 3 * MINUTE));
    }

    /** @return 全天和时段具有不同阈值、休息参数的配置 */
    private static RuleGroup reminders() {
        RuleGroup group = new RuleGroup(1L, "reminders");
        group.types.add(RuleType.CONTINUOUS);
        group.types.add(RuleType.WINDOW_CONTINUOUS);
        group.continuousLimitMillis = 20 * MINUTE;
        group.interruptionPauseMillis = 2 * MINUTE;
        group.passDurationMillis = 3 * MINUTE;
        RuleWindow window = new RuleWindow("evening", RuleType.WINDOW_CONTINUOUS, 19 * 60, 20 * 60, 10 * MINUTE);
        window.interruptionPauseMillis = 5 * MINUTE;
        group.windows.add(window);
        return group;
    }

    /** @param policy 配置 @param now 当前时刻 @param allDay 全天连续用量 @param window 时段连续用量 @return 判断结果 */
    private static InterceptionEvaluation evaluate(RuleGroup policy, long now, long allDay, long window) {
        return RuleEngine.evaluate("app", "app:app", 1L, policy, ControlLevel.REPAIR,
                new UsageEvidence(true, now, ZONE, allDay, allDay, 1L, allDay,
                        Collections.emptyMap(), Collections.singletonMap("evening", window)), 3 * MINUTE);
    }
    /** @param day 十一月日期 @param hour 小时 @param minute 分钟 @return 固定设备时区墙钟 */
    private static long at(int day, int hour, int minute) {
        return LocalDateTime.of(2026, 11, day, hour, minute).atZone(ZoneId.of(ZONE)).toInstant().toEpochMilli();
    }
}
