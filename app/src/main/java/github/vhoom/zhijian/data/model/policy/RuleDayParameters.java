package github.vhoom.zhijian.data.model.policy;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * 一个星期日的完整规则参数；不持有规则组或页面，避免父子数据循环引用。
 *
 * 维护日期：2026年11月12日
 */
public final class RuleDayParameters {
    /** 当天启用的规则类型，空集合表示当天不启用规则。 */
    public final EnumSet<RuleType> types = EnumSet.noneOf(RuleType.class);
    /** 当天的时段草稿，稳定标识只在当天配置内部唯一。 */
    public final List<RuleWindow> windows = new ArrayList<>();
    /** 当天全天时长额度，单位为毫秒。 */
    public long dailyLimitMillis;
    /** 当天打开次数额度。 */
    public int count;
    /** 当天全天频率限次的滚动统计时长，单位为毫秒。 */
    public long frequencyWindowMillis;
    /** 当天全天频率限次在滚动窗口内允许的打开次数。 */
    public int frequencyCount;
    /** 当天连续使用额度，单位为毫秒。 */
    public long continuousLimitMillis;
    /** 当天连续提醒的默认休息时长为一分钟，单位为毫秒。 */
    public long interruptionPauseMillis = 60_000L;
    /** 当天临时放行时长，单位为毫秒。 */
    public long passDurationMillis;

    /** 创建没有共享集合的参数副本，供批量应用、取消和快照使用。 */
    public RuleDayParameters copy() {
        RuleDayParameters copy = new RuleDayParameters();
        copy.types.addAll(types);
        for (RuleWindow window : windows) copy.windows.add(window.copy());
        copy.dailyLimitMillis = dailyLimitMillis;
        copy.count = count;
        copy.frequencyWindowMillis = frequencyWindowMillis;
        copy.frequencyCount = frequencyCount;
        copy.continuousLimitMillis = continuousLimitMillis;
        copy.interruptionPauseMillis = interruptionPauseMillis;
        copy.passDurationMillis = passDurationMillis;
        return copy;
    }
}
