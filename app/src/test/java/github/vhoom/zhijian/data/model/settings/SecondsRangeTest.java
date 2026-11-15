package github.vhoom.zhijian.data.model.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 验证拦截计时随机区间的边界与稳定性。
 *
 * 维护日期：2026年11月08日
 */
public final class SecondsRangeTest {
    /** 模型、存储和输入控件使用同一组首尾边界与精度要求。 */
    @Test public void commonBoundsIncludeMinimumAndMaximum() {
        assertTrue(SecondsRange.isValid(100, 3_600_000));
        org.junit.Assert.assertFalse(SecondsRange.isValid(0, 100));
        org.junit.Assert.assertFalse(SecondsRange.isValid(100, 3_600_100));
        org.junit.Assert.assertFalse(SecondsRange.isValid(101, 200));
        org.junit.Assert.assertFalse(SecondsRange.isValid(200, 100));
    }

    /** 页面状态不能直接构造超出保存边界的区间。 */
    @Test(expected = IllegalArgumentException.class)
    public void modelRejectsOutOfBoundsRestoredRange() { new SecondsRange(100, Long.MAX_VALUE - 7); }

    /** 固定区间应始终返回唯一时长。 */
    @Test
    public void valueForFixedRangeAlwaysReturnsFixedValue() {
        SecondsRange range = new SecondsRange(75_000L, 75_000L);

        assertEquals(75_000L, range.valueFor("session-a"));
        assertEquals(75_000L, range.valueFor("session-b"));
    }

    /** 同一会话应稳定取值，且结果必须落在包含端点的区间内。 */
    @Test
    public void valueForRandomRangeIsStableAndBounded() {
        SecondsRange range = new SecondsRange(15_000L, 30_000L);

        long first = range.valueFor("session-a");
        long second = range.valueFor("session-a");

        assertEquals(first, second);
        assertTrue(first >= 15_000L);
        assertTrue(first <= 30_000L);
    }
}
