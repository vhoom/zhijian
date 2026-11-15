package github.vhoom.zhijian.domain.interception;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.time.Instant;

/**
 * 验证全天规则自定义恢复时间形成的统计周期。
 *
 * 维护日期：2026年11月08日
 */
public final class DailyResetScheduleTest {
    private static final String UTC = "UTC";
    private static final int SIX_AM = 6 * 60;

    /** 当前时间早于恢复点时，应归入前一天开始的统计周期。 */
    @Test
    public void beforeResetUsesPreviousDayAsPeriodStart() {
        long now = Instant.parse("2026-10-31T05:00:00Z").toEpochMilli();

        assertEquals(Instant.parse("2026-10-30T06:00:00Z").toEpochMilli(),
                DailyResetSchedule.periodStart(now, UTC, SIX_AM));
        assertEquals(Instant.parse("2026-10-31T06:00:00Z").toEpochMilli(),
                DailyResetSchedule.nextReset(now, UTC, SIX_AM));
    }

    /** 当前时间晚于恢复点时，应使用当天开始、次日结束的统计周期。 */
    @Test
    public void afterResetUsesCurrentDayAsPeriodStart() {
        long now = Instant.parse("2026-10-31T07:00:00Z").toEpochMilli();

        assertEquals(Instant.parse("2026-10-31T06:00:00Z").toEpochMilli(),
                DailyResetSchedule.periodStart(now, UTC, SIX_AM));
        assertEquals(Instant.parse("2026-11-01T06:00:00Z").toEpochMilli(),
                DailyResetSchedule.nextReset(now, UTC, SIX_AM));
    }
}
