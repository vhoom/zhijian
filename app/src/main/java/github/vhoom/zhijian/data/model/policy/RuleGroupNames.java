package github.vhoom.zhijian.data.model.policy;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 为新草稿生成未占用的默认名称，不修改用户已有的组名。
 *
 * 维护日期：2026年11月08日
 */
public final class RuleGroupNames {
    /**
     * 工具类不允许实例化，调用方直接使用静态方法。
     */
    private RuleGroupNames() { }

    /**
     * 查找尚未被占用的最小编号，保持已有规则名称不变。
     *
     * @param base 规则默认名称的前缀
     * @param groups 按创建顺序排列的规则组集合
     * @param excludedId 本次命名时排除的草稿 ID
     */
    public static String next(String base, List<RuleGroup> groups, long excludedId) {
        Set<String> used = new HashSet<>();
        for (RuleGroup group : groups) {
            if (group.id != excludedId) used.add(group.name.trim());
        }
        for (int suffix = 1; ; suffix++) {
            String name = base + " " + suffix;
            if (!used.contains(name)) return name;
        }
    }
}
