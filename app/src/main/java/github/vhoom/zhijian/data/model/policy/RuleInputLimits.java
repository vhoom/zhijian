package github.vhoom.zhijian.data.model.policy;

import github.vhoom.zhijian.util.time.DecimalDuration;

import java.math.BigDecimal;

/**
 * 规则输入约束工厂；原始文本与模型保存共用上界，不依赖页面或存储。
 *
 * 维护日期：2026年11月13日
 */
public final class RuleInputLimits {
    /** 名称按 Unicode 字符计数，保留空名称的自动命名行为。 */
    public static final int MAX_NAME_CHARACTERS = 20;
    /** 使用与休息的统一时长上界，单位为整数毫秒。 */
    public static final long MAX_DURATION_MILLIS = 12L * 60L * DecimalDuration.MINUTE_MILLIS;
    /** 频率统计窗口独立于使用时长，单位为整数毫秒。 */
    public static final long MAX_FREQUENCY_MILLIS = 60L * DecimalDuration.MINUTE_MILLIS;

    /** 校验只返回分类，展示层不读取或拼接实际范围。 */
    public enum Status { VALID, INVALID, TOO_LARGE }

    /** 不可变数值约束，包含单位、零值语义及上界是否可取。 */
    public static final class Range {
        private final long unitMillis;
        private final long maximumMillis;
        private final boolean maximumInclusive;
        private final boolean enabledZeroAllowed;

        /**
         * 保存工厂生成的数值约束。
         * @param unitMillis 输入单位对应的毫秒数；次数使用 1
         * @param maximumMillis 以内部单位表示的上界
         * @param maximumInclusive 是否允许恰好等于上界
         * @param enabledZeroAllowed 启用时是否允许明确填写零
         */
        private Range(long unitMillis, long maximumMillis, boolean maximumInclusive, boolean enabledZeroAllowed) {
            this.unitMillis = unitMillis;
            this.maximumMillis = maximumMillis;
            this.maximumInclusive = maximumInclusive;
            this.enabledZeroAllowed = enabledZeroAllowed;
        }

        /**
         * 校验未经截断的输入，格式检查先于范围检查。
         * @param text 用户当前原始文本
         * @param required 当前规则是否要求填写；关闭参数允许空值
         * @return 格式或下界错误、超过上界，或者合法
         */
        public Status checkText(String text, boolean required) {
            if (text == null) return required ? Status.INVALID : Status.VALID;
            String value = text.trim();
            if (value.isEmpty()) return required ? Status.INVALID : Status.VALID;
            String pattern = unitMillis == 1L ? "[0-9]+" : "[0-9]+(?:[.,][0-9])?";
            if (!value.matches(pattern)) return Status.INVALID;
            // 长数值先拒绝，避免为明显无法保存的文本构造大型精确数。
            if (value.length() > 64) return Status.TOO_LARGE;
            try {
                long millis = new BigDecimal(value.replace(',', '.'))
                        .multiply(BigDecimal.valueOf(unitMillis)).longValueExact();
                return checkValue(millis, required);
            } catch (ArithmeticException overflow) {
                return Status.TOO_LARGE;
            }
        }

        /**
         * 校验模型的整数次数或毫秒值，关闭参数可保留零值。
         * @param value 模型中已经解析的内部数值
         * @param required 当前参数是否属于启用规则或必填放行设置
         * @return 保存所需的校验分类
         */
        public Status checkValue(long value, boolean required) {
            if (value < 0L || unitMillis != 1L
                    && !DecimalDuration.hasOneDecimalPrecision(value, unitMillis)) return Status.INVALID;
            if (value == 0L && required && !enabledZeroAllowed) return Status.INVALID;
            return value > maximumMillis || value == maximumMillis && !maximumInclusive
                    ? Status.TOO_LARGE : Status.VALID;
        }
    }

    private static final Range COUNT = new Range(1L, CountLimit.MAX_COUNT, true, false);
    private static final Range DAILY = duration(MAX_DURATION_MILLIS, true, true);
    private static final Range DURATION = duration(MAX_DURATION_MILLIS, true, false);
    private static final Range FREQUENCY = duration(MAX_FREQUENCY_MILLIS, true, false);
    private static final Range PASS = duration(MAX_DURATION_MILLIS, false, false);

    /** 约束工厂不保存可变输入。 */
    private RuleInputLimits() { }

    /** @return 三种限次共用的整数约束 */
    public static Range count() { return COUNT; }

    /** @return 全天与时段连续提醒共用的休息约束 */
    public static Range rest() { return DURATION; }

    /** @return 放行时长的独立严格上界约束 */
    public static Range pass() { return PASS; }

    /**
     * 按规则类型与当前时段长度装配使用参数约束。
     * @param type 当前规则类型
     * @param windowMillis 有效时段长度；没有有效时段时使用 0，端点错误由时段校验处理
     * @return 当前参数的固定或时段约束
     */
    public static Range usage(RuleType type, long windowMillis) {
        if (type == RuleType.DAILY_LIMIT) return DAILY;
        if (type == RuleType.ROLLING_COUNT) return FREQUENCY;
        if (type == RuleType.WINDOW_LIMIT && windowMillis > 0L) {
            return duration(Math.min(MAX_DURATION_MILLIS, windowMillis), true, false);
        }
        if (type == RuleType.WINDOW_CONTINUOUS && windowMillis > 0L) {
            return duration(Math.min(MAX_DURATION_MILLIS, windowMillis),
                    MAX_DURATION_MILLIS < windowMillis, false);
        }
        return DURATION;
    }

    /**
     * 检查去除首尾空格后的名称长度，空值交由原自动命名流程处理。
     * @param name 当前名称原文
     * @return 名称有效或需要修改；名称错误不使用数值过大的提示
     */
    public static Status checkName(String name) {
        if (name == null) return Status.INVALID;
        String value = name.trim();
        return value.codePointCount(0, value.length()) <= MAX_NAME_CHARACTERS ? Status.VALID : Status.INVALID;
    }

    /**
     * 生成以分钟输入的时长约束。
     * @param maximumMillis 内部毫秒上界
     * @param inclusive 上界是否可取
     * @param zeroAllowed 启用时是否允许明确输入零
     * @return 不可变时长约束
     */
    private static Range duration(long maximumMillis, boolean inclusive, boolean zeroAllowed) {
        return new Range(DecimalDuration.MINUTE_MILLIS, maximumMillis, inclusive, zeroAllowed);
    }
}
