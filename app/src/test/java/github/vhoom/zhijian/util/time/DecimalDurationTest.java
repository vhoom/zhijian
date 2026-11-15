package github.vhoom.zhijian.util.time;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * 验证一位小数时长与整数毫秒之间的无损转换。
 *
 * 维护日期：2026年11月08日
 */
public final class DecimalDurationTest {
    /** 过长数字提前判为非法，不送入正则和大整数计算。 */
    @Test public void oversizedNumericInputIsRejected() {
        org.junit.Assert.assertEquals(-1L, DecimalDuration.parseMillis("9".repeat(10_000), DecimalDuration.MINUTE_MILLIS));
    }

    /** 非整十毫秒单位不能用截断除法误判为具有一位小数精度。 */
    @Test public void unsupportedPrecisionUnitIsRejected() {
        org.junit.Assert.assertFalse(DecimalDuration.hasOneDecimalPrecision(1L, 11L));
    }

    /**
     * 验证一位小数分钟精确换算为毫秒。
     */
    @Test
    public void parsesOneDecimalMinuteExactly() {
        assertEquals(90_000L,
                DecimalDuration.parseMillis("1.5", DecimalDuration.MINUTE_MILLIS));
    }

    /**
     * 验证一位小数秒精确换算为毫秒。
     */
    @Test
    public void parsesOneDecimalSecondExactly() {
        assertEquals(800L,
                DecimalDuration.parseMillis("0.8", DecimalDuration.SECOND_MILLIS));
    }

    /**
     * 验证超过一位小数的输入被明确拒绝。
     */
    @Test
    public void rejectsMoreThanOneDecimalPlace() {
        assertEquals(-1L,
                DecimalDuration.parseMillis("1.25", DecimalDuration.MINUTE_MILLIS));
    }

    /**
     * 验证整数时长不显示多余的小数零。
     */
    @Test
    public void formatOmitsRedundantDecimalZero() {
        assertEquals("1.5", DecimalDuration.format(90_000L, DecimalDuration.MINUTE_MILLIS));
        assertEquals("2", DecimalDuration.format(2_000L, DecimalDuration.SECOND_MILLIS));
    }
}
