package github.vhoom.zhijian.data.store;

import android.content.Context;

import github.vhoom.zhijian.R;
import github.vhoom.zhijian.data.model.policy.RuleGroup;
import github.vhoom.zhijian.data.model.policy.RuleDayParameters;
import github.vhoom.zhijian.data.model.policy.RuleType;
import github.vhoom.zhijian.data.model.policy.RuleWindow;
import github.vhoom.zhijian.data.model.policy.RuleGroupNames;
import github.vhoom.zhijian.data.model.policy.BuiltInGroupPreset;
import github.vhoom.zhijian.util.time.DecimalDuration;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 规则组入口：读取已提交快照，后台事务完成后再发布修改。
 *
 * 维护日期：2026年11月14日
 */
public final class RuleGroupStore {

    /** 各规则编辑器共用的建议值与新草稿默认值。 */
    public static final long DEFAULT_DURATION_MILLIS = 60 * DecimalDuration.MINUTE_MILLIS;
    public static final int DEFAULT_COUNT = 5;
    public static final long DEFAULT_CONTINUOUS_DURATION_MILLIS = 30 * DecimalDuration.MINUTE_MILLIS;
    public static final int DEFAULT_START = 8 * 60;
    public static final int DEFAULT_END = 23 * 60;
    public static final long DEFAULT_PASS_DURATION_MILLIS = 3 * DecimalDuration.MINUTE_MILLIS;

    private final ZhijianStorage storage;
    private final Context context;

    /**
     * 创建 RuleGroupStore 实例，并初始化本次操作依赖或不可变字段。
     *
     * @param context 访问系统服务、资源或持久化文件的上下文
     */
    public RuleGroupStore(Context context) {
        this.context = context.getApplicationContext();
        this.storage = ZhijianStorage.get(context);
    }

    /**
     * 造一个未落盘的规则组草稿：阈值留空，临时放行预填默认 3 分钟。
     * 编辑页校验通过并明确保存后才会把它写进去，
     * 进来就退出不会留下一条空的「新建规则」。
     */
    public RuleGroup create() {
        // 草稿 id 跨重建稳定；创建顺序由数据库的 creationOrder 决定。
        long id = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
        if (id == 0) id = 1;
        while (BuiltInGroupPreset.isBuiltIn(id)) id++;
        RuleGroup group = new RuleGroup(id, nextDefaultName(id));
        group.passDurationMillis = DEFAULT_PASS_DURATION_MILLIS;
        return group;
    }

    /**
     * 为新草稿选择没有与现有规则组冲突的默认名称。
     *
     * @param excludedId 本次命名时排除的草稿 ID
     */
    public String nextDefaultName(long excludedId) {
        return RuleGroupNames.next(context.getString(R.string.rule_default_name), list(), excludedId);
    }

    /**
     * 按 id 覆盖保存；id 不在表里（草稿首次保存）时追加到末尾，维持创建顺序。
     *
     * @param group 待读取、校验或编辑的规则组
     */
    public CompletableFuture<Void> save(RuleGroup group) {
        return storage.saveGroup(group);
    }

    /** @param group 规则组草稿 @param removedApps 明确移除的默认组应用；取消编辑不调用此入口 */
    public CompletableFuture<Void> save(RuleGroup group, List<String> removedApps) {
        return storage.saveGroup(group, removedApps);
    }

    /**
     * 删除指定规则组并撤销对应旧放行。
     *
     * @param id 当前记录、规则或视图的稳定标识
     */
    public CompletableFuture<Void> remove(long id) {
        return storage.removePolicy(PolicyCodec.groupKey(id));
    }

    /**
     * 按 id 找组；编辑页传错 id 或组已被删时返回 null。
     *
     * @param id 当前记录、规则或视图的稳定标识
     */
    public RuleGroup byId(long id) {
        return storage.snapshot().group(id);
    }

    /** 全部组，顺序即创建顺序。 */
    public List<RuleGroup> list() {
        return storage.snapshot().groups();
    }

    /** 返回后台成员同步信号，列表在成功后读取最新的实际成员数量。 */
    public CompletableFuture<Void> refreshBuiltInGroups() { return storage.refreshBuiltInGroups(); }

    /**
     * 解析某应用当前生效的规则组：创建最早的命中组获胜，停用的组跳过。
     * 返回 null 表示没有任何组管控它，由调用方再看独立策略或全局档位。
     *
     * @param packageName 需要查询或管控的应用包名
     */
    public RuleGroup effectiveFor(String packageName) {
        return storage.snapshot().firstGroupFor(packageName);
    }

    /**
     * 列表页与拦截页共用的人话摘要。组里多个类型同时生效，就逐条列出来；
     * 参数没填的标「未设置」，让用户一眼看出这条规则还没配完。
     *
     * @param context 访问系统服务、资源或持久化文件的上下文
     * @param group 待读取、校验或编辑的规则组
     */
    public static String summary(Context context, RuleGroup group) {
        if (!group.weekdayRules.isEmpty()) {
            int enabledDays = 0;
            for (RuleDayParameters day : group.weekdayRules.values()) {
                if (!day.types.isEmpty()) enabledDays++;
            }
            return context.getString(R.string.rule_summary_weekdays, enabledDays);
        }
        if (group.types.isEmpty())
            return context.getString(R.string.rule_summary_empty);
        StringBuilder text = new StringBuilder();
        for (RuleType type : RuleType.values()) {
            if (!group.has(type))
                continue;
            if (text.length() > 0)
                text.append("；");
            text.append(describe(context, group, type));
        }
        return text.toString();
    }

    /**
     * 把已启用条件和参数格式化为界面摘要。
     *
     * @param context 访问系统服务、资源或持久化文件的上下文
     * @param group 待读取、校验或编辑的规则组
     * @param type 当前规则条件的类型
     */
    private static String describe(Context context, RuleGroup group, RuleType type) {
        switch (type) {
            case DAILY_COUNT:
                return group.count > 0
                        ? context.getString(R.string.rule_summary_daily_count, group.count)
                        : context.getString(R.string.rule_summary_unset,
                                context.getString(R.string.rule_type_daily_count));
            case WINDOW_LIMIT:
            case WINDOW_BAN:
            case WINDOW_CONTINUOUS:
                return describeWindows(context, group, type);
            case CONTINUOUS:
                return group.continuousLimitMillis > 0 && group.interruptionPauseMillis > 0
                        ? context.getString(R.string.rule_summary_continuous,
                                minutes(group.continuousLimitMillis),
                                minutes(group.interruptionPauseMillis))
                        : context.getString(R.string.rule_summary_unset,
                                context.getString(R.string.rule_type_continuous));
            default:
                return group.dailyLimitMillis > 0
                        ? context.getString(R.string.rule_summary_daily_limit,
                                minutes(group.dailyLimitMillis))
                        : context.getString(R.string.rule_summary_unset,
                                context.getString(R.string.rule_type_daily_limit));
        }
    }

    /**
     * 分别格式化各时段及其额度，保留条件分类。
     *
     * @param context 访问系统服务、资源或持久化文件的上下文
     * @param group 待读取、校验或编辑的规则组
     * @param type 当前规则条件的类型
     */
    private static String describeWindows(Context context, RuleGroup group, RuleType type) {
        List<RuleWindow> windows = group.windowsFor(type);
        if (windows.isEmpty()) return context.getString(R.string.rule_summary_unset, context.getString(type.nameRes));
        StringBuilder result = new StringBuilder();
        for (RuleWindow window : windows) {
            if (result.length() > 0) result.append("、");
            String end = window.endMinute < window.startMinute
                    ? context.getString(R.string.rule_time_next_day, time(window.endMinute)) : time(window.endMinute);
            result.append(type == RuleType.WINDOW_CONTINUOUS
                    ? context.getString(R.string.rule_summary_window_continuous, time(window.startMinute),
                            end, minutes(window.limitDurationMillis), minutes(window.interruptionPauseMillis))
                    : type == RuleType.WINDOW_LIMIT
                    ? context.getString(R.string.rule_summary_window_limit, time(window.startMinute),
                            end, minutes(window.limitDurationMillis))
                    : context.getString(R.string.rule_summary_window_ban, time(window.startMinute), end));
        }
        return result.toString();
    }

    /**
     * 把一天内的分钟值格式化为二十四小时制时间。
     *
     * @param minuteOfDay 一天内对应的分钟值
     */
    private static String time(int minuteOfDay) {
        return String.format(Locale.US, "%02d:%02d", minuteOfDay / 60, minuteOfDay % 60);
    }

    /**
     * 将规则中的整数毫秒格式化为最多一位小数的分钟。
     *
     * @param durationMillis 配置或记录的时长，单位为毫秒
     */
    private static String minutes(long durationMillis) {
        return DecimalDuration.format(durationMillis, DecimalDuration.MINUTE_MILLIS);
    }

}
