package github.vhoom.zhijian.domain.interception;

import github.vhoom.zhijian.data.model.policy.RuleType;
import github.vhoom.zhijian.data.model.policy.RuleWindow;
import org.junit.Test;
import java.time.ZonedDateTime;
import static org.junit.Assert.*;

/**
 * 用户设置的钟点在夏令时和午夜两侧都应保持当地时间语义。
 *
 * 维护日期：2026年11月08日
 */
public class RuleWindowScheduleTest {
    /** 春季少一小时的日期，上午八点时段仍从八点开始。 */
    @Test public void springTransitionKeepsConfiguredWallTimes() {
        assertMorningInterval("2026-03-08T09:00:00-04:00[America/New_York]", "2026-03-08T08:00:00-04:00[America/New_York]",
                "2026-03-08T10:00:00-04:00[America/New_York]");
    }

    /** 秋季多一小时的日期也不会把时段提前一个钟点。 */
    @Test public void autumnTransitionKeepsConfiguredWallTimes() {
        assertMorningInterval("2026-11-01T09:00:00-05:00[America/New_York]", "2026-11-01T08:00:00-05:00[America/New_York]",
                "2026-11-01T10:00:00-05:00[America/New_York]");
    }

    /** 未到开始和恰好结束时不属于半开区间。 */
    @Test public void touchingEndpointsAreInactive() {
        RuleWindow window = new RuleWindow("morning", RuleType.WINDOW_BAN, 480, 600, 0);
        assertNull(RuleWindowSchedule.activeInterval(window, time("2026-03-08T07:59:00-04:00[America/New_York]"), "America/New_York"));
        assertNull(RuleWindowSchedule.activeInterval(window, time("2026-03-08T10:00:00-04:00[America/New_York]"), "America/New_York"));
    }

    /** 夏令时跳过的钟点不产生已经到期的零长度拦截区间。 */
    @Test public void skippedHourDoesNotReturnExpiredInterval() {
        RuleWindow window = new RuleWindow("skipped", RuleType.WINDOW_BAN, 150, 210, 0);
        assertNull(RuleWindowSchedule.activeInterval(window, time("2026-03-08T03:00:00-04:00[America/New_York]"), "America/New_York"));
    }

    /** 跨午夜结束时间归次日，当日夏令时变化不改变设定钟点。 */
    @Test public void overnightIntervalUsesPreviousLocalStartDate() {
        RuleWindow window = new RuleWindow("night", RuleType.WINDOW_BAN, 1320, 480, 0);
        RuleWindowSchedule.Interval interval = RuleWindowSchedule.activeInterval(window,
                time("2026-03-08T07:00:00-04:00[America/New_York]"), "America/New_York");
        assertNotNull(interval);
        assertEquals(time("2026-03-07T22:00:00-05:00[America/New_York]"), interval.startMs);
        assertEquals(time("2026-03-08T08:00:00-04:00[America/New_York]"), interval.endMs);
    }

    /** 恰好结束和延迟复查定位同一次窗口，不改变半开区间。 */
    @Test public void completedWindowIsAvailableAtAndAfterTheEnd() {
        RuleWindow window = new RuleWindow("noon", RuleType.WINDOW_CONTINUOUS, 720, 780, 3_600_000L);
        long end = time("2026-11-06T13:00:00+08:00[Asia/Shanghai]");
        assertNull(RuleWindowSchedule.activeInterval(window, end, "Asia/Shanghai"));
        RuleWindowSchedule.Interval exact = RuleWindowSchedule.latestCompletedInterval(window, end, "Asia/Shanghai");
        RuleWindowSchedule.Interval delayed = RuleWindowSchedule.latestCompletedInterval(window, end + 60_000L, "Asia/Shanghai");
        assertEquals(end - 3_600_000L, exact.startMs);
        assertEquals(end, exact.endMs);
        assertEquals(exact.startMs, delayed.startMs);
    }

    /** 周日跨午夜的结束边界在周一仍归属于周日，下一周另开身份。 */
    @Test public void completedOvernightWeekdayKeepsItsOriginalStart() {
        RuleWindow window = new RuleWindow("night", RuleType.WINDOW_CONTINUOUS, 1380, 60, 60_000L);
        window.weekday = 7;
        long end = time("2026-11-09T01:00:00+08:00[Asia/Shanghai]");
        RuleWindowSchedule.Interval interval = RuleWindowSchedule.latestCompletedInterval(window, end, "Asia/Shanghai");
        assertEquals(time("2026-11-08T23:00:00+08:00[Asia/Shanghai]"), interval.startMs);
        assertEquals(end, interval.endMs);
    }

    /** @param now 判断时刻 @param start 预期开始 @param end 预期结束 */
    private static void assertMorningInterval(String now, String start, String end) {
        RuleWindowSchedule.Interval interval = RuleWindowSchedule.activeInterval(
                new RuleWindow("morning", RuleType.WINDOW_BAN, 480, 600, 0), time(now), "America/New_York");
        assertNotNull(interval);
        assertEquals(time(start), interval.startMs);
        assertEquals(time(end), interval.endMs);
    }

    /** @param value 完整当地时间与时区 @return 对应的墙钟毫秒 */
    private static long time(String value) { return ZonedDateTime.parse(value).toInstant().toEpochMilli(); }
}
