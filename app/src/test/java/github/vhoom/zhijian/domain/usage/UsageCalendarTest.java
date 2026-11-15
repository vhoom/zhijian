package github.vhoom.zhijian.domain.usage;

import org.junit.Test;
import java.time.LocalDate;
import static org.junit.Assert.*;

/**
 * 日期选择跨周、未来禁选和重建行为的业务回归。
 *
 * 维护日期：2026年11月08日
 */
public class UsageCalendarTest {
    /** 星期四的今天保留所在列，周五到周日仍显示真实的不可选日期。 */
    @Test public void thursdayStaysInFourthColumnAndFutureCannotChangeSelection() {
        LocalDate today = LocalDate.of(2026, 11, 5);
        UsageCalendar calendar = new UsageCalendar(today, null);
        assertEquals(LocalDate.of(2026, 11, 2), calendar.dateAt(0));
        assertEquals(today, calendar.dateAt(3));
        assertEquals(LocalDate.of(2026, 11, 8), calendar.dateAt(6));
        assertFalse(calendar.select(calendar.dateAt(4)));
        assertEquals(today, calendar.selected());
    }

    /** 周日的今天位于最右列，未来整周不能翻入。 */
    @Test public void sundayIsLastColumnAndNextWeekIsUnavailable() {
        UsageCalendar calendar = new UsageCalendar(LocalDate.of(2026, 11, 8), null);
        assertEquals(calendar.selected(), calendar.dateAt(6));
        assertFalse(calendar.moveWeek(1));
    }

    /** 按周翻页跨年时保留星期列，回到本周时未来选择收敛到今天。 */
    @Test public void weekNavigationCrossesYearAndClampsFutureWeekdayToToday() {
        UsageCalendar calendar = new UsageCalendar(LocalDate.of(2027, 1, 1), LocalDate.of(2026, 12, 27));
        assertTrue(calendar.moveWeek(1));
        assertEquals(LocalDate.of(2026, 12, 28), calendar.weekStart());
        assertEquals(LocalDate.of(2027, 1, 1), calendar.selected());
    }

    /** 今日选择跨日自动推进，历史选择保持原日期。 */
    @Test public void midnightAdvancesTodayButPreservesHistoricalSelection() {
        LocalDate today = LocalDate.of(2026, 11, 8);
        UsageCalendar following = new UsageCalendar(today, null);
        following.updateToday(today.plusDays(1));
        assertEquals(today.plusDays(1), following.selected());
        assertEquals(today.plusDays(1), following.weekStart());
        UsageCalendar history = new UsageCalendar(today, today.minusDays(2));
        history.updateToday(today.plusDays(1));
        assertEquals(today.minusDays(2), history.selected());
    }

    /** 时钟回拨后旧的未来选择回退到新的今天。 */
    @Test public void restoredFutureDateAndClockRollbackCannotExposeFutureSelection() {
        LocalDate today = LocalDate.of(2026, 11, 5);
        UsageCalendar calendar = new UsageCalendar(today, today.plusDays(1));
        assertEquals(today, calendar.selected());
        calendar.updateToday(today.minusDays(1));
        assertEquals(today.minusDays(1), calendar.selected());
    }
}
