package github.vhoom.zhijian.data.store;

import github.vhoom.zhijian.data.db.entity.policy.PolicyAppEntity;
import github.vhoom.zhijian.data.db.dao.PolicyDao;
import github.vhoom.zhijian.data.db.entity.policy.PolicyEntity;
import github.vhoom.zhijian.data.db.entity.policy.PolicyRuleEntity;
import github.vhoom.zhijian.data.db.entity.policy.PolicyWindowEntity;
import github.vhoom.zhijian.data.model.policy.ControlLevel;
import github.vhoom.zhijian.data.model.policy.RuleGroup;
import github.vhoom.zhijian.data.model.policy.RuleGroupValidator;
import github.vhoom.zhijian.data.model.policy.RuleType;
import github.vhoom.zhijian.data.model.policy.RuleWindow;
import github.vhoom.zhijian.util.time.DecimalDuration;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 数据库字段与现有编辑草稿之间的唯一转换处。
 *
 * 维护日期：2026年11月12日
 */
final class PolicyCodec {
    static final String GLOBAL = "global";

    /**
     * 工具类不允许实例化，调用方直接使用静态方法。
     */
    private PolicyCodec() {}

    /**
     * 把规则组 ID 转换为数据库中稳定的策略主键。
     *
     * @param id 当前记录、规则或视图的稳定标识
     */
    static String groupKey(long id) { return "group:" + id; }
    /**
     * 把应用包名转换为数据库中稳定的独立策略主键。
     *
     * @param pkg 需要查询或绑定的应用包名
     */
    static String appKey(String pkg) { return "app:" + pkg; }

    /**
     * 把规则类型转换为长期存储使用的稳定文本键。
     *
     * @param type 当前规则条件的类型
     */
    static String typeKey(RuleType type) {
        switch (type) {
            case DAILY_LIMIT: return "daily_limit";
            case DAILY_COUNT: return "daily_count";
            case WINDOW_LIMIT: return "window_limit";
            case WINDOW_BAN: return "window_ban";
            case CONTINUOUS: return "continuous";
            case WINDOW_CONTINUOUS: return "window_continuous";
            case WINDOW_COUNT: return "window_count";
            case ROLLING_COUNT: return "rolling_count";
            default: throw new IllegalArgumentException("Unknown rule type: " + type);
        }
    }

    /**
     * 把存储中的规则类型文本键还原为领域枚举。
     *
     * @param key 稳定标识或待读取的持久化设置键
     */
    static RuleType ruleType(String key) {
        switch (key) {
            case "daily_limit": return RuleType.DAILY_LIMIT;
            case "daily_count": return RuleType.DAILY_COUNT;
            case "window_limit": return RuleType.WINDOW_LIMIT;
            case "window_ban": return RuleType.WINDOW_BAN;
            case "continuous": return RuleType.CONTINUOUS;
            case "window_continuous": return RuleType.WINDOW_CONTINUOUS;
            case "window_count": return RuleType.WINDOW_COUNT;
            case "rolling_count": return RuleType.ROLLING_COUNT;
            default: throw new IllegalArgumentException("Unsupported stored rule: " + key);
        }
    }

    /**
     * 把管控档位转换为长期存储使用的稳定文本键。
     *
     * @param level 全局管控档位
     */
    static String levelKey(ControlLevel level) {
        switch (level) {
            case MORTAL: return "mortal";
            case REPAIR: return "repair";
            case SEAL: return "seal";
            default: throw new IllegalArgumentException("Unknown control level");
        }
    }

    /**
     * 把存储中的管控档位文本还原为领域枚举。
     *
     * @param key 稳定标识或待读取的持久化设置键
     */
    static ControlLevel controlLevel(String key) {
        switch (key) {
            case "mortal": return ControlLevel.MORTAL;
            case "repair": return ControlLevel.REPAIR;
            case "seal": return ControlLevel.SEAL;
            default: throw new IllegalArgumentException("Unsupported stored level: " + key);
        }
    }

    /**
     * 把领域记录转换为对应的 Room 实体，保留稳定标识与时间字段。
     *
     * @param group 待读取、校验或编辑的规则组
     * @param key 稳定标识或待读取的持久化设置键
     * @param scope 策略对应的全局、规则组或应用作用域
     * @param revision 当前记录对应的策略修订号
     * @param order 记录稳定的创建或展示顺序
     */
    static PolicyEntity entity(RuleGroup group, String key, String scope,
            long revision, long order) {
        if (group.name == null || !validMinuteDuration(group.passDurationMillis)) {
            throw new IllegalArgumentException("Invalid policy fields");
        }
        PolicyEntity row = new PolicyEntity();
        row.id = key;
        row.scope = scope;
        row.name = group.name;
        row.groupId = group.id;
        row.creationOrder = order;
        row.revision = revision;
        row.enabled = group.enabled;
        row.weekdayRulesJson = WeekdayPolicyCodec.encode(group.weekdayRules);
        row.passDurationMs = group.passDurationMillis;
        return row;
    }

    /**
     * 调用方持有数据库事务；保留未勾选类型的参数，重新勾选时仍能恢复。
     *
     * @param dao 负责当前数据表查询与写入的 DAO
     * @param row 待绑定或转换的数据库或列表行
     * @param group 待读取、校验或编辑的规则组
     */
    static void write(PolicyDao dao, PolicyEntity row, RuleGroup group) {
        // 结构校验与启用状态无关，未启用的草稿也必须使用合法字段。
        RuleGroupValidator.Result structural = RuleGroupValidator.validateStoredFields(group);
        if (!structural.isValid()) throw new IllegalArgumentException("Invalid stored policy: " + structural.error);
        if (dao.insertPolicy(row) == -1) dao.updatePolicy(row);
        dao.removeRules(row.id);
        dao.removeApps(row.id);
        dao.removeWindows(row.id);
        List<PolicyRuleEntity> rules = new ArrayList<>();
        for (RuleType type : RuleType.values()) {
            PolicyRuleEntity rule = new PolicyRuleEntity();
            rule.policyId = row.id;
            rule.type = typeKey(type);
            rule.selected = group.has(type);
            switch (type) {
                case DAILY_LIMIT: rule.durationMs = group.dailyLimitMillis; break;
                case DAILY_COUNT: rule.limitCount = group.count; break;
                case ROLLING_COUNT:
                    rule.durationMs = group.frequencyWindowMillis;
                    rule.limitCount = group.frequencyCount;
                    break;
                case CONTINUOUS:
                    rule.durationMs = group.continuousLimitMillis;
                    rule.secondaryDurationMs = group.interruptionPauseMillis;
                    break;
                default: break;
            }
            if (!validMinuteDuration(rule.durationMs)
                    || !validMinuteDuration(rule.secondaryDurationMs) || rule.limitCount < 0) {
                throw new IllegalArgumentException("Negative rule parameter");
            }
            rules.add(rule);
        }
        dao.insertRules(rules);
        List<PolicyAppEntity> apps = new ArrayList<>();
        for (String pkg : new LinkedHashSet<>(group.apps)) {
            if (pkg == null || pkg.isEmpty()) throw new IllegalArgumentException("Empty app binding");
            PolicyAppEntity app = new PolicyAppEntity();
            app.policyId = row.id;
            app.packageName = pkg;
            app.position = apps.size();
            apps.add(app);
        }
        dao.insertApps(apps);
        List<PolicyWindowEntity> windows = new ArrayList<>();
        for (RuleWindow window : group.windows) {
            if (window.startMinute < 0 || window.startMinute >= 1440 || window.endMinute < 0
                    || window.endMinute >= 1440
                    || !validMinuteDuration(window.limitDurationMillis)) {
                throw new IllegalArgumentException("Invalid window fields");
            }
            PolicyWindowEntity stored = new PolicyWindowEntity();
            stored.policyId = row.id;
            stored.windowId = window.id;
            stored.type = typeKey(window.type);
            stored.position = windows.size();
            stored.startMinute = window.startMinute;
            stored.endMinute = window.endMinute;
            stored.durationMs = window.type == RuleType.WINDOW_LIMIT || window.type == RuleType.WINDOW_CONTINUOUS
                    ? window.limitDurationMillis : 0;
            stored.interruptionPauseMs = window.interruptionPauseMillis;
            stored.limitCount = window.type == RuleType.WINDOW_COUNT ? window.limitCount : 0;
            windows.add(stored);
        }
        dao.insertWindows(windows);
    }

    /**
     * 读取或还原规则组副本，保持规则条件和时段分类。
     *
     * @param row 待绑定或转换的数据库或列表行
     * @param rules 策略选择的规则条件集合
     * @param apps 应用列表或策略绑定的应用集合
     * @param windows 按规则类型分类的时间窗口集合
     */
    static RuleGroup group(PolicyEntity row, List<PolicyRuleEntity> rules,
            List<PolicyAppEntity> apps, List<PolicyWindowEntity> windows) {
        RuleGroup group = new RuleGroup(row.groupId, row.name);
        group.enabled = row.enabled;
        group.passDurationMillis = row.passDurationMs;
        group.weekdayRules.putAll(WeekdayPolicyCodec.decode(row.weekdayRulesJson));
        for (PolicyRuleEntity rule : rules) {
            RuleType type = ruleType(rule.type);
            if (rule.selected) group.types.add(type);
            switch (type) {
                case DAILY_LIMIT: group.dailyLimitMillis = rule.durationMs; break;
                case DAILY_COUNT: group.count = rule.limitCount; break;
                case ROLLING_COUNT:
                    group.frequencyWindowMillis = rule.durationMs;
                    group.frequencyCount = rule.limitCount;
                    break;
                case CONTINUOUS:
                    group.continuousLimitMillis = rule.durationMs;
                    if (rule.secondaryDurationMs > 0) {
                        group.interruptionPauseMillis = rule.secondaryDurationMs;
                    }
                    break;
                default: break;
            }
        }
        for (PolicyAppEntity app : apps) group.apps.add(app.packageName);
        for (PolicyWindowEntity window : windows) {
            RuleWindow restored = new RuleWindow(window.windowId, ruleType(window.type),
                    window.startMinute, window.endMinute, window.durationMs);
            restored.interruptionPauseMillis = window.interruptionPauseMs;
            restored.limitCount = window.limitCount;
            group.windows.add(restored);
        }
        return group;
    }

    /**
     * 规则时长只允许使用 0.1 分钟精度，数据库仍保存整数毫秒。
     *
     * @param durationMillis 配置或记录的时长，单位为毫秒
     */
    private static boolean validMinuteDuration(long durationMillis) {
        return DecimalDuration.hasOneDecimalPrecision(
                durationMillis, DecimalDuration.MINUTE_MILLIS);
    }

}
