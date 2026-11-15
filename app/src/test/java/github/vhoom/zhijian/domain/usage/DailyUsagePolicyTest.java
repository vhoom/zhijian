package github.vhoom.zhijian.domain.usage;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * 一分钟门槛和重复、缺失查询下的永久累计保护。
 *
 * 维护日期：2026年11月08日
 */
public class DailyUsagePolicyTest {
    /** 恰好一分钟开始保存；单次访问短于一分钟可以在当天累计达标。 */
    @Test public void thresholdUsesDailyAccumulationAndIncludesExactlyOneMinute() {
        assertFalse(DailyUsagePolicy.shouldRecord(59_999));
        assertTrue(DailyUsagePolicy.shouldRecord(60_000));
        assertTrue(DailyUsagePolicy.shouldRecord(20_000 + 40_000));
    }

    /** 重复读取的累计量不相加，新增用量只推进累计值。 */
    @Test public void repeatedSnapshotIsIdempotentAndLaterUsageAdvances() {
        assertEquals(120_000, DailyUsagePolicy.merge(120_000, 120_000));
        assertEquals(150_000, DailyUsagePolicy.merge(120_000, 150_000));
    }

    /** 系统原始事件过期或迟到短快照不会擦掉已有记录。 */
    @Test public void incompleteAndExpiredEvidenceCannotShrinkSavedHistory() {
        assertEquals(120_000, DailyUsagePolicy.merge(120_000, 0));
        assertEquals(120_000, DailyUsagePolicy.merge(120_000, 90_000));
    }

    /** 拒绝无效负数，不把采集错误保存为真实用量。 */
    @Test(expected = IllegalArgumentException.class) public void negativeUsageIsRejected() {
        DailyUsagePolicy.merge(120_000, -1);
    }
}
