package github.vhoom.zhijian.data.model.policy;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * 规则组成员约束，供应用选择器、页面保存和存储事务共同使用。
 *
 * 维护日期：2026年11月08日
 */
public final class RuleGroupMembershipPolicy {
    /** 一个规则组至少管控两个不同的应用；单应用独立策略不使用此约束。 */
    public static final int MINIMUM_APPS = 2;

    /** 成员策略只提供无状态判定。 */
    private RuleGroupMembershipPolicy() { }

    /**
     * 内置组自动匹配，允许零成员或单成员；卸载全部成员后仍可显示及编辑配置。
     * @param group 当前规则组草稿
     * @return 内置组成员可为空，普通组仍要求至少两个不同应用
     */
    public static boolean hasValidGroupMembers(RuleGroup group) {
        return group != null && hasDistinctApps(group.apps,
                BuiltInGroupPreset.isBuiltIn(group.id) ? 0 : MINIMUM_APPS);
    }

    /** @param apps 待选择或保存的应用包名 @return 至少包含两个非空且不同的包名 */
    public static boolean hasMinimumApps(Collection<String> apps) {
        return hasDistinctApps(apps, MINIMUM_APPS);
    }

    /** @param apps 待校验的包名 @param minimum 最少不同应用数 @return 无空包名且满足数量要求 */
    private static boolean hasDistinctApps(Collection<String> apps, int minimum) {
        if (apps == null) return false;
        Set<String> distinct = new HashSet<>();
        for (String app : apps) {
            if (app == null || app.trim().isEmpty()) return false;
            distinct.add(app);
        }
        return distinct.size() >= minimum;
    }
}
