package github.vhoom.zhijian.data.store;

import github.vhoom.zhijian.data.model.usage.UsageDay;
import github.vhoom.zhijian.data.model.usage.UsageSession;

import org.junit.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * 覆盖 UsageBuckets 的边界输入、状态变化与回归行为。
 *
 * 维护日期：2026年11月08日
 */
public class UsageBucketsTest {
    /**
     * 验证跨午夜拆分时长，但只记录一次应用打开。
     */
    @Test
    public void midnightSplitsDurationButCountsOpeningOnlyOnce() {
        long start = Instant.parse("2026-10-31T15:59:30Z").toEpochMilli();
        UsageSession session = UsageSession.builder("one", "app", start).zone("Asia/Shanghai")
                .visit("visit", true).checkpoint(start + 60_000, 60_000, 1).finished("switched").build();
        List<UsageDay> days = UsageBuckets.split(session);
        assertEquals(2, days.size());
        assertEquals("2026-10-31", days.get(0).localDate);
        assertEquals(30_000, days.get(0).foregroundMs);
        assertEquals(1, days.get(0).openCount);
        assertEquals("2026-11-01", days.get(1).localDate);
        assertEquals(30_000, days.get(1).foregroundMs);
        assertEquals(0, days.get(1).openCount);
    }

    /**
     * 验证夏令时结束日按真实二十五小时拆分用量。
     */
    @Test
    public void daylightSavingDayIsTwentyFiveHours() {
        long start = Instant.parse("2026-11-01T04:00:00Z").toEpochMilli();
        long duration = 25 * 60 * 60_000L;
        List<UsageDay> days = UsageBuckets.split(UsageSession.builder("dst", "app", start)
                .zone("America/New_York").checkpoint(start + duration, duration, 1).finished("day_end").build());
        assertEquals(1, days.size());
        assertEquals("2026-11-01", days.get(0).localDate);
        assertEquals(duration, days.get(0).foregroundMs);
    }

    /**
     * 验证比例拆分后毫秒总数完全守恒。
     */
    @Test
    public void proportionalSplitKeepsEveryMillisecond() {
        long start = Instant.parse("2026-10-31T23:59:59.999Z").toEpochMilli();
        List<UsageDay> days = UsageBuckets.split(UsageSession.builder("round", "app", start)
                .checkpoint(start + 3, 2, 1).finished("paused").build());
        assertEquals(2, days.size());
        assertEquals(2, days.get(0).foregroundMs + days.get(1).foregroundMs);
    }

    /**
     * 验证零时长访问仍可保留一次打开记录。
     */
    @Test
    public void zeroLengthOpeningRemainsCountable() {
        long start = Instant.parse("2026-10-31T10:00:00Z").toEpochMilli();
        List<UsageDay> days = UsageBuckets.split(UsageSession.builder("open", "app", start)
                .visit("visit", true).build());
        assertEquals(1, days.size());
        assertEquals(0, days.get(0).foregroundMs);
        assertEquals(1, days.get(0).openCount);
    }

    /**
     * 验证负用量输入被明确拒绝。
     */
    @Test(expected = IllegalArgumentException.class)
    public void negativeDurationIsRejected() {
        UsageSession.builder("bad", "app", 100).checkpoint(200, -1, 1).build();
    }
}
