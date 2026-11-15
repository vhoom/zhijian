package github.vhoom.zhijian.data.model.policy;

/**
 * 只恢复规则参数的默认草稿，保留规则组身份、名称、成员及启用状态。
 *
 * 维护日期：2026年11月08日
 */
public final class RuleConfigurationDefaults {
    /** 默认草稿工厂不需要实例。 */
    private RuleConfigurationDefaults() { }

    /**
     * 创建重置后的独立草稿，避免修改当前快照或共享集合。
     * @param source 保留身份及成员的原始规则组
     * @param passDurationMillis 重置后的默认放行时长，单位为毫秒
     * @return 尚未提交的重置草稿
     */
    public static RuleGroup resetCopy(RuleGroup source, long passDurationMillis) {
        RuleGroup reset = new RuleGroup(source.id, source.name);
        reset.apps.addAll(source.apps);
        reset.enabled = source.enabled;
        reset.passDurationMillis = passDurationMillis;
        return reset;
    }
}
