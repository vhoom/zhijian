package github.vhoom.zhijian.data.model.policy;

import github.vhoom.zhijian.R;

/**
 * 时间管理规则类型；枚举顺序保持稳定，配置页独立定义展示顺序。
 *
 * 维护日期：2026年11月12日
 */
public enum RuleType {

    /** 全天使用限时：今日累计使用达到 minutes 分钟后拦截。 */
    DAILY_LIMIT(R.string.rule_type_daily_limit),
    /** 全天使用限次：允许额度内的打开次数，下一次真实打开才拦截。 */
    DAILY_COUNT(R.string.rule_type_daily_count),
    /** 指定时间段限时：时段内累计使用达到 minutes 分钟后拦截。 */
    WINDOW_LIMIT(R.string.rule_type_window_limit),
    /** 指定时间段禁用：进入时段即拦截，不看用量。 */
    WINDOW_BAN(R.string.rule_type_window_ban),
    /** 全天连续使用提醒：达到阈值后休息，临时继续不清零用量。 */
    CONTINUOUS(R.string.rule_type_continuous),
    /** 时段连续使用提醒。 */
    WINDOW_CONTINUOUS(R.string.rule_type_window_continuous),
    /** 指定时段限次：各时段独立限制真实打开次数。 */
    WINDOW_COUNT(R.string.rule_type_window_count),
    /** 全天频率限次：滚动时间窗口内限制真实打开次数。 */
    ROLLING_COUNT(R.string.rule_type_frequency_count);

    public final int nameRes;

    /**
     * 判断条件是否需要独立的时间窗口。
     */
    public boolean isWindow() {
        return this == WINDOW_LIMIT || this == WINDOW_BAN || this == WINDOW_CONTINUOUS || this == WINDOW_COUNT;
    }

    /** @return 是否使用整数次数额度，供参数校验和控件工厂复用。 */
    public boolean isCount() { return this == DAILY_COUNT || this == WINDOW_COUNT || this == ROLLING_COUNT; }

    /** @return 是否属于连续使用提醒，供参数标签和运行策略复用。 */
    public boolean isReminder() { return this == CONTINUOUS || this == WINDOW_CONTINUOUS; }

    /**
     * 创建 RuleType 实例，并初始化本次操作依赖或不可变字段。
     *
     * @param nameRes 界面显示名称对应的字符串资源 ID
     */
    RuleType(int nameRes) {
        this.nameRes = nameRes;
    }

    /**
     * 从输入值构造当前类型；枚举序号非法时使用规定的缺省值。
     *
     * @param ordinal 从持久化文件读取的枚举序号
     */
    public static RuleType of(int ordinal) {
        return ordinal >= 0 && ordinal < values().length ? values()[ordinal] : DAILY_LIMIT;
    }
}
