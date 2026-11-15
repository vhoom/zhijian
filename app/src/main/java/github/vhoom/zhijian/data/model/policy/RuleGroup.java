package github.vhoom.zhijian.data.model.policy;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一个规则组：一批应用 + 若干同时生效的规则类型（任一触发即生效）。
 * 参数按类型分开存；规则阈值以 0 表示未填写，暂停与临时放行提供明确默认值。
 *
 * 维护日期：2026年11月12日
 */
public final class RuleGroup {

    /** 连续提醒默认至少休息 1 分钟；旧策略升级和新草稿共用。 */
    public static final long DEFAULT_INTERRUPTION_PAUSE_MILLIS = 60_000L;

    public final long id;
    public String name;

    /** 同时生效的类型集合，允许多个；空的组在引擎里视为不匹配。 */
    public final EnumSet<RuleType> types = EnumSet.noneOf(RuleType.class);

    public long dailyLimitMillis; // 全天限时（毫秒）
    public int count; // 全天限次（次）
    /** 全天频率限次的滚动统计时长，单位为毫秒；零表示尚未填写。 */
    public long frequencyWindowMillis;
    /** 全天频率限次在单个滚动窗口内允许的打开次数。 */
    public int frequencyCount;
    public long continuousLimitMillis; // 连续使用提醒（毫秒）
    public long interruptionPauseMillis = DEFAULT_INTERRUPTION_PAUSE_MILLIS; // 我将休息（毫秒）
    public long passDurationMillis; // 放行时长（毫秒），0 表示尚未填写
    public boolean enabled = true;

    public final List<String> apps = new ArrayList<>();
    public final List<RuleWindow> windows = new ArrayList<>();
    /** 星期一为 1、星期日为 7；全局配置和首次编辑草稿使用空映射。 */
    public final Map<Integer, RuleDayParameters> weekdayRules = new LinkedHashMap<>();

    /**
     * 创建 RuleGroup 实例，并初始化本次操作依赖或不可变字段。
     *
     * @param id 当前记录、规则或视图的稳定标识
     * @param name 数据库文件名或规则显示名称
     */
    public RuleGroup(long id, String name) {
        this.id = id;
        this.name = name;
    }

    /** 编辑草稿与已提交快照之间不共享可变集合。 */
    public RuleGroup copy() {
        RuleGroup copy = new RuleGroup(id, name);
        copy.types.addAll(types);
        copy.apps.addAll(apps);
        copy.dailyLimitMillis = dailyLimitMillis;
        copy.count = count;
        copy.frequencyWindowMillis = frequencyWindowMillis;
        copy.frequencyCount = frequencyCount;
        copy.continuousLimitMillis = continuousLimitMillis;
        copy.interruptionPauseMillis = interruptionPauseMillis;
        for (RuleWindow window : windows) copy.windows.add(window.copy());
        copy.passDurationMillis = passDurationMillis;
        copy.enabled = enabled;
        for (Map.Entry<Integer, RuleDayParameters> day : weekdayRules.entrySet()) {
            copy.weekdayRules.put(day.getKey(), day.getValue().copy());
        }
        return copy;
    }

    /** 返回全局参数或任意星期是否存在启用规则，供策略来源匹配使用。 */
    public boolean hasAnyRules() {
        if (weekdayRules.isEmpty()) return !types.isEmpty();
        for (RuleDayParameters day : weekdayRules.values()) if (!day.types.isEmpty()) return true;
        return false;
    }

    /**
     * 判断规则组是否选择了指定条件类型。
     *
     * @param type 当前规则条件的类型
     */
    public boolean has(RuleType type) {
        return types.contains(type);
    }

    /**
     * 判断规则组是否包含指定应用包名。
     *
     * @param packageName 需要查询或管控的应用包名
     */
    public boolean contains(String packageName) {
        return apps.contains(packageName);
    }

    /**
     * 返回当前类型的时段草稿，保持该类型内部的添加顺序。
     *
     * @param type 当前规则条件的类型
     */
    public List<RuleWindow> windowsFor(RuleType type) {
        List<RuleWindow> selected = new ArrayList<>();
        for (RuleWindow window : windows) if (window.type == type) selected.add(window);
        return selected;
    }
}
