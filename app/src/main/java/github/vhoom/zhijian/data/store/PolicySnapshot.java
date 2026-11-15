package github.vhoom.zhijian.data.store;

import github.vhoom.zhijian.data.db.entity.policy.PolicyAppEntity;
import github.vhoom.zhijian.data.db.dao.PolicyDao;
import github.vhoom.zhijian.data.db.entity.policy.PolicyEntity;
import github.vhoom.zhijian.data.db.entity.policy.PolicyRuleEntity;
import github.vhoom.zhijian.data.db.entity.policy.PolicyWindowEntity;
import github.vhoom.zhijian.data.model.policy.ControlLevel;
import github.vhoom.zhijian.data.model.policy.RuleGroup;
import github.vhoom.zhijian.data.model.policy.RuleSource;
import github.vhoom.zhijian.domain.usage.UsageTrackingStart;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 已提交配置的不可变快照；公开的草稿均为副本，包名索引只在配置变更时重建。
 *
 * 维护日期：2026年11月15日
 */
public final class PolicySnapshot {
    private final Map<Long, RuleGroup> groups = new LinkedHashMap<>();
    private final Map<String, RuleGroup> overrides = new HashMap<>();
    private final Map<String, RuleGroup> firstGroupByApp = new HashMap<>();
    private final Map<String, Long> revisions = new HashMap<>();
    private final ControlLevel level;
    private final RuleGroup global;
    private final Map<String, UsageTrackingStart> usageStarts;

    /**
     * 创建 PolicySnapshot 实例，并初始化本次操作依赖或不可变字段。
     *
     * @param policies 同次提交中读取的策略集合或快照
     * @param rules 策略选择的规则条件集合
     * @param apps 应用列表或策略绑定的应用集合
     * @param windows 按规则类型分类的时间窗口集合
     * @param usageStarts 同一配置事务已提交的固定统计起点
     */
    private PolicySnapshot(List<PolicyEntity> policies, List<PolicyRuleEntity> rules,
            List<PolicyAppEntity> apps, List<PolicyWindowEntity> windows,
            Map<String, UsageTrackingStart> usageStarts) {
        this.usageStarts = Collections.unmodifiableMap(new HashMap<>(usageStarts));
        Map<String, List<PolicyRuleEntity>> rulesByPolicy = new HashMap<>();
        Map<String, List<PolicyAppEntity>> appsByPolicy = new HashMap<>();
        Map<String, List<PolicyWindowEntity>> windowsByPolicy = new HashMap<>();
        for (PolicyRuleEntity rule : rules) {
            rulesByPolicy.computeIfAbsent(rule.policyId, key -> new ArrayList<>()).add(rule);
        }
        for (PolicyAppEntity app : apps) {
            appsByPolicy.computeIfAbsent(app.policyId, key -> new ArrayList<>()).add(app);
        }
        for (PolicyWindowEntity window : windows) {
            windowsByPolicy.computeIfAbsent(window.policyId, key -> new ArrayList<>()).add(window);
        }
        RuleGroup globalGroup = null;
        ControlLevel globalLevel = null;
        for (PolicyEntity row : policies) {
            RuleGroup group = PolicyCodec.group(row,
                    rulesByPolicy.getOrDefault(row.id, Collections.emptyList()),
                    appsByPolicy.getOrDefault(row.id, Collections.emptyList()),
                    windowsByPolicy.getOrDefault(row.id, Collections.emptyList()));
            revisions.put(row.id, row.revision);
            switch (row.scope) {
                case "global":
                    globalGroup = group;
                    globalLevel = PolicyCodec.controlLevel(row.controlLevel);
                    break;
                case "group":
                    groups.put(group.id, group);
                    if (group.enabled && group.hasAnyRules()) {
                        for (String pkg : group.apps) firstGroupByApp.putIfAbsent(pkg, group);
                    }
                    break;
                case "app":
                    if (group.apps.size() != 1) throw new IllegalStateException("Invalid app policy binding");
                    if (group.enabled) overrides.put(group.apps.get(0), group);
                    break;
                default: throw new IllegalStateException("Unsupported policy scope: " + row.scope);
            }
        }
        if (globalGroup == null || globalLevel == null) throw new IllegalStateException("Missing global policy");
        global = globalGroup;
        level = globalLevel;
    }

    /**
     * 从已提交数据库数据组装当前模块的不可变内存快照。
     *
     * @param dao 负责当前数据表查询与写入的 DAO
     */
    static PolicySnapshot load(PolicyDao dao) {
        return load(dao, Collections.emptyMap());
    }

    /** @param dao 同一事务的策略 DAO @param starts 已保存的起点 @return 可整体发布的只读策略快照 */
    static PolicySnapshot load(PolicyDao dao, Map<String, UsageTrackingStart> starts) {
        return new PolicySnapshot(dao.policies(), dao.rules(), dao.apps(), dao.windows(), starts);
    }

    /** @param policyId 实际策略身份 @return 首次生效起点，尚未启用时为空 */
    public UsageTrackingStart usageStart(String policyId) { return usageStarts.get(policyId); }

    /** @param policyId 实际策略 @param bootId 当前启动 @param wallMs 查询墙钟 @param elapsedMs 同一单调时间
     * @return 统计最早起点；没有起点的隔离夹具沿用自然周期 */
    public long usageFloorMillis(String policyId, String bootId, long wallMs, long elapsedMs) {
        UsageTrackingStart start = usageStart(policyId);
        return start == null ? 0L : start.floorMillis(bootId, wallMs, elapsedMs);
    }

    /**
     * 按创建顺序返回规则组副本。
     */
    public List<RuleGroup> groups() {
        List<RuleGroup> copies = new ArrayList<>();
        for (RuleGroup group : groups.values()) copies.add(group.copy());
        return copies;
    }

    /** @return 以稳定策略键索引的配置副本，供切档结算与变更保护使用 */
    public Map<String, RuleGroup> configuredPolicies() {
        Map<String, RuleGroup> result = new LinkedHashMap<>();
        result.put(PolicyCodec.GLOBAL, global.copy());
        for (RuleGroup group : groups.values()) result.put(PolicyCodec.groupKey(group.id), group.copy());
        for (Map.Entry<String, RuleGroup> entry : overrides.entrySet()) {
            result.put(PolicyCodec.appKey(entry.getKey()), entry.getValue().copy());
        }
        return result;
    }

    /**
     * 读取或还原规则组副本，保持规则条件和时段分类。
     *
     * @param id 当前记录、规则或视图的稳定标识
     */
    public RuleGroup group(long id) { return copy(groups.get(id)); }
    /**
     * 返回指定应用独立策略的可编辑副本。
     *
     * @param pkg 需要查询或绑定的应用包名
     */
    public RuleGroup appPolicy(String pkg) { return copy(overrides.get(pkg)); }
    /**
     * 返回包含该应用的第一个生效规则组副本。
     *
     * @param pkg 需要查询或绑定的应用包名
     */
    public RuleGroup firstGroupFor(String pkg) { return copy(firstGroupByApp.get(pkg)); }
    /**
     * 检查指定应用是否存在已启用的独立策略。
     *
     * @param pkg 需要查询或绑定的应用包名
     */
    public boolean hasOverride(String pkg) { return overrides.containsKey(pkg); }
    /**
     * 返回本次已提交快照中应用实际生效的规则来源。
     *
     * @param pkg 需要查询或绑定的应用包名
     */
    public RuleSource ruleSourceFor(String pkg) {
        return RuleSource.resolve(overrides.get(pkg), firstGroupByApp.get(pkg));
    }
    /**
     * 返回应用所属首个生效规则组的名称。
     *
     * @param pkg 需要查询或绑定的应用包名
     */
    public String groupNameFor(String pkg) {
        RuleGroup group = firstGroupByApp.get(pkg);
        return group == null ? "" : group.name;
    }
    /**
     * 读取当前已提交的全局管控档位。
     */
    public ControlLevel level() { return level; }
    /**
     * 读取全局每日用量额度，单位为毫秒。
     */
    public long dailyQuotaMillis() { return global.dailyLimitMillis; }

    /**
     * 按独立策略、规则组、全局配置的顺序返回生效规则副本。
     *
     * @param pkg 需要查询或绑定的应用包名
     */
    public RuleGroup effectiveFor(String pkg) {
        RuleGroup policy = overrides.get(pkg);
        if (policy == null) policy = firstGroupByApp.get(pkg);
        return (policy == null ? global : policy).copy();
    }

    /**
     * 返回当前生效策略的稳定数据库主键。
     *
     * @param pkg 需要查询或绑定的应用包名
     */
    public String effectivePolicyId(String pkg) {
        if (overrides.containsKey(pkg)) return PolicyCodec.appKey(pkg);
        RuleGroup group = firstGroupByApp.get(pkg);
        return group == null ? PolicyCodec.GLOBAL : PolicyCodec.groupKey(group.id);
    }

    /**
     * 提供同次快照的额度统计策略；独立覆盖和被更早规则组接管的应用不消耗本组额度。
     * @param packageName 当前接受规则检查的应用
     * @return 规则组按实际生效来源共用额度，其他作用域仍按目标应用统计
     */
    public Predicate<String> usageScopeFor(String packageName) {
        String policyId = effectivePolicyId(packageName);
        return policyId.startsWith("group:")
                ? pkg -> policyId.equals(effectivePolicyId(pkg)) : packageName::equals;
    }

    /**
     * 读取策略修订号；不存在时返回无效版本标识。
     *
     * @param policyId 数据库中的稳定策略主键
     */
    public long revisionOf(String policyId) { return revisions.getOrDefault(policyId, -1L); }

    /**
     * 创建可独立修改的副本，避免草稿改动影响已提交快照。
     *
     * @param group 待读取、校验或编辑的规则组
     */
    private static RuleGroup copy(RuleGroup group) { return group == null ? null : group.copy(); }
}
