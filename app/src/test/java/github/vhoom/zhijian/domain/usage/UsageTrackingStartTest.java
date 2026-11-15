package github.vhoom.zhijian.domain.usage;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * 固定统计起点的墙钟投影和恢复事实边界回归。
 *
 * 维护日期：2026年11月15日
 */
public final class UsageTrackingStartTest {
    /** 增量复查与实例恢复均保持原起点，不从每次查询时刻重新开始。 */
    @Test public void repeatedChecksAndReconstructionKeepFirstStart() {
        UsageTrackingStart first = new UsageTrackingStart("installation:policy", "boot", 10_000L, 2_000L);
        assertEquals(10_000L, first.floorMillis("boot", 15_000L, 7_000L));
        UsageTrackingStart restored = new UsageTrackingStart(first.id, first.bootId, first.wallMs, first.elapsedMs);
        assertEquals(10_000L, restored.floorMillis("boot", 20_000L, 12_000L));
    }

    /** 同次开机投影保留生效墙钟下界，回拨时不能重新纳入启用前的系统记录。 */
    @Test public void wallClockChangesDoNotMoveQueryBeforePersistedActivation() {
        UsageTrackingStart start = new UsageTrackingStart("tracking", "boot", 10_000L, 2_000L);
        assertEquals(8_000L, start.floorMillis("boot", 8_000L, 5_000L));
        assertEquals(10_000L, start.floorMillis("boot", 12_000L, 8_000L));
        assertEquals(47_000L, start.floorMillis("boot", 50_000L, 5_000L));
        assertEquals(1_000L, start.floorMillis("boot", 1_000L, 5_000L));
    }

    /** 重启保留原墙钟边界；墙钟落在起点前时本次不补计未来区间。 */
    @Test public void anotherBootRetainsStartWithoutFutureQueryBoundary() {
        UsageTrackingStart start = new UsageTrackingStart("tracking", "old", 10_000L, 2_000L);
        assertEquals(10_000L, start.floorMillis("new", 20_000L, 1_000L));
        assertEquals(8_000L, start.floorMillis("new", 8_000L, 1_000L));
    }

    /** 旧安装的休息及恢复检查点不能重建新规则的历史累计。 */
    @Test public void factsBeforeActivationAreRejectedByTheirClockDomain() {
        UsageTrackingStart start = new UsageTrackingStart("tracking", "boot", 10_000L, 2_000L);
        assertFalse(start.includes("boot", 50_000L, 1_999L));
        assertTrue(start.includes("boot", 1_000L, 2_000L));
        assertFalse(start.includes("old-boot", 9_999L, 50_000L));
        assertTrue(start.includes("new-boot", 10_000L, 1_000L));
    }
}
