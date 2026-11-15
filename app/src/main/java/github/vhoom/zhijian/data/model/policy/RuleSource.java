package github.vhoom.zhijian.data.model.policy;

/**
 * 应用当前采用的非全局规则来源，供列表标签与排序共用。
 *
 * 维护日期：2026年11月08日
 */
public enum RuleSource {
    NONE,
    GROUP,
    INDEPENDENT;

    /**
     * 判断规则来源是否实际启用且包含可执行条件。
     */
    public boolean isActive() { return this != NONE; }

    /**
     * 按独立策略优先、规则组次之的顺序解析生效来源。
     *
     * @param independent 优先于规则组生效的应用独立策略
     * @param group 待读取、校验或编辑的规则组
     */
    public static RuleSource resolve(RuleGroup independent, RuleGroup group) {
        if (independent != null && independent.enabled) {
            return independent.types.isEmpty() ? NONE : INDEPENDENT;
        }
        return group != null && group.enabled && !group.types.isEmpty() ? GROUP : NONE;
    }
}
