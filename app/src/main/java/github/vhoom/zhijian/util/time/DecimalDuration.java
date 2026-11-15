package github.vhoom.zhijian.util.time;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 在一位小数的界面单位与整数毫秒之间进行无损转换。
 *
 * 维护日期：2026年11月08日
 */
public final class DecimalDuration {
    public static final long SECOND_MILLIS = 1_000L;
    public static final long MINUTE_MILLIS = 60_000L;

    /**
     * 工具类不允许实例化，调用方直接使用静态方法。
     */
    private DecimalDuration() { }

    /**
     * 解析最多一位小数的正数时长。
     *
     * @param value 用户输入的数字文本
     * @param unitMillis 一个界面单位对应的毫秒数
     * @return 精确换算后的毫秒数；格式非法或无法精确换算时返回 -1
     */
    public static long parseMillis(String value, long unitMillis) {
        if (value == null || value.length() > 64 || unitMillis <= 0) return -1;
        String normalized = value.trim().replace(',', '.');
        if (!normalized.matches("[0-9]+(?:\\.[0-9])?")) return -1;
        try {
            return new BigDecimal(normalized).multiply(BigDecimal.valueOf(unitMillis)).longValueExact();
        } catch (ArithmeticException invalid) {
            return -1;
        }
    }

    /**
     * 将整数毫秒格式化为最多一位小数，不显示多余的“.0”。
     *
     * @param millis 需要显示的非负毫秒数
     * @param unitMillis 一个界面单位对应的毫秒数
     * @return 适合回填输入框或摘要的数字文本
     */
    public static String format(long millis, long unitMillis) {
        if (millis < 0 || unitMillis <= 0) throw new IllegalArgumentException("Invalid duration");
        return BigDecimal.valueOf(millis)
                .divide(BigDecimal.valueOf(unitMillis), 1, RoundingMode.UNNECESSARY)
                .stripTrailingZeros()
                .toPlainString();
    }

    /**
     * 判断毫秒数能否由指定单位的一位小数精确表达。
     *
     * @param millis 待检查的毫秒数
     * @param unitMillis 一个界面单位对应的毫秒数
     * @return 能精确显示并再次解析时返回 true
     */
    public static boolean hasOneDecimalPrecision(long millis, long unitMillis) {
        return millis >= 0 && unitMillis >= 10L && unitMillis % 10L == 0
                && millis % (unitMillis / 10L) == 0;
    }
}
