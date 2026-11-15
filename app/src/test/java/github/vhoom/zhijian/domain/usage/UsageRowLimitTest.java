package github.vhoom.zhijian.domain.usage;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 首页手动条数依据实际扫描容量的语义边界回归。
 *
 * 维护日期：2026年11月13日
 */
public class UsageRowLimitTest {
    /** 一行及完整扫描数量都可取，超出扫描结果没有显示意义。 */
    @Test public void acceptsInclusiveScanBoundariesAndRejectsOverflow() {
        assertTrue(UsageRowLimit.isValid(1, 37));
        assertTrue(UsageRowLimit.isValid(37, 37));
        assertFalse(UsageRowLimit.isValid(38, 37));
        assertFalse(UsageRowLimit.isValid(Long.MAX_VALUE, 37));
        assertFalse(UsageRowLimit.isValid(0, 37));
        assertFalse(UsageRowLimit.isValid(-1, 37));
    }

    /** 缺失、读取失败或空扫描结果不能虚构一行容量。 */
    @Test public void rejectsMissingUnreadableOrEmptyScan() {
        for (int count : new int[]{-2, -1, 0}) assertFalse(UsageRowLimit.isValid(1, count));
        assertTrue(UsageRowLimit.isValid(1, 1));
        assertFalse(UsageRowLimit.isValid(2, 1));
    }

    /** 最新扫描缩减容量后，原有超上限草稿不能再次保存。 */
    @Test public void usesCurrentCapacityAfterScanShrinks() {
        assertTrue(UsageRowLimit.isValid(20, 20));
        assertFalse(UsageRowLimit.isValid(20, 19));
    }
}
