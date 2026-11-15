package github.vhoom.zhijian.data.model.policy;

/**
 * 现有规则编辑草稿与星期参数之间的单向适配，不改变规则组身份或成员。
 *
 * 维护日期：2026年11月12日
 */
public final class RuleDayParameterAdapter {
    /** 适配器只提供无状态转换。 */
    private RuleDayParameterAdapter() { }

    /** @param group 当前编辑的规则组 @return 可独立保存的完整参数副本 */
    public static RuleDayParameters capture(RuleGroup group) {
        RuleDayParameters parameters = new RuleDayParameters();
        parameters.types.addAll(group.types);
        for (RuleWindow window : group.windows) parameters.windows.add(window.copy());
        parameters.dailyLimitMillis = group.dailyLimitMillis;
        parameters.count = group.count;
        parameters.frequencyWindowMillis = group.frequencyWindowMillis;
        parameters.frequencyCount = group.frequencyCount;
        parameters.continuousLimitMillis = group.continuousLimitMillis;
        parameters.interruptionPauseMillis = group.interruptionPauseMillis;
        parameters.passDurationMillis = group.passDurationMillis;
        return parameters;
    }

    /** @param group 接收编辑参数的草稿 @param parameters 不与草稿共享集合的来源参数 */
    public static void bind(RuleGroup group, RuleDayParameters parameters) {
        group.types.clear();
        group.types.addAll(parameters.types);
        group.windows.clear();
        for (RuleWindow window : parameters.windows) group.windows.add(window.copy());
        group.dailyLimitMillis = parameters.dailyLimitMillis;
        group.count = parameters.count;
        group.frequencyWindowMillis = parameters.frequencyWindowMillis;
        group.frequencyCount = parameters.frequencyCount;
        group.continuousLimitMillis = parameters.continuousLimitMillis;
        group.interruptionPauseMillis = parameters.interruptionPauseMillis;
        group.passDurationMillis = parameters.passDurationMillis;
    }

    /**
     * 为参数校验和运行创建没有星期子配置的草稿，避免递归解析。
     * @param source 提供身份、成员及启用状态的策略
     * @param parameters 本次使用的单日参数
     * @return 可独立修改的单日规则组
     */
    public static RuleGroup withParameters(RuleGroup source, RuleDayParameters parameters) {
        RuleGroup group = new RuleGroup(source.id, source.name);
        group.apps.addAll(source.apps);
        group.enabled = source.enabled;
        bind(group, parameters);
        return group;
    }

    /** @param group 已保存或待编辑的策略 @param weekday 星期一为 1，星期日为 7 @return 当天参数副本 */
    public static RuleDayParameters forDay(RuleGroup group, int weekday) {
        if (weekday < 1 || weekday > 7) throw new IllegalArgumentException("Invalid weekday");
        RuleDayParameters day = group.weekdayRules.get(weekday);
        return day == null ? capture(group) : day.copy();
    }

    /**
     * 首次编辑按原有每天配置补齐七天，然后只覆盖选中的日期。
     * @param group 接收批量应用的草稿
     * @param mask 星期一至星期日使用第零至第六位，非零且不超过 127
     * @param parameters 将复制到每个选中日期的参数
     * @param initialParameters 原有每天配置，首次应用时用于保留未选中日期
     */
    public static void applyToDays(RuleGroup group, int mask, RuleDayParameters parameters,
            RuleDayParameters initialParameters) {
        if (mask <= 0 || (mask & ~127) != 0) throw new IllegalArgumentException("Invalid weekday selection");
        for (int day = 1; day <= 7; day++) {
            if (!group.weekdayRules.containsKey(day)) group.weekdayRules.put(day, initialParameters.copy());
            if ((mask & (1 << (day - 1))) != 0) group.weekdayRules.put(day, parameters.copy());
        }
    }
}
