package github.vhoom.zhijian.data.model.policy;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * 覆盖 RuleSource 的边界输入、状态变化与回归行为。
 *
 * 维护日期：2026年11月08日
 */
public class RuleSourceTest {
    /**
     * 验证应用独立策略优先于规则组。
     */
    @Test
    public void independentRuleTakesPrecedenceOverGroup() {
        assertEquals(RuleSource.INDEPENDENT, RuleSource.resolve(rule(true), rule(true)));
    }

    /**
     * 验证停用独立规则后恢复生效规则组。
     */
    @Test
    public void disabledIndependentRuleFallsBackToEnabledGroup() {
        assertEquals(RuleSource.GROUP, RuleSource.resolve(rule(false), rule(true)));
        assertEquals(RuleSource.NONE, RuleSource.resolve(rule(false), rule(false)));
    }

    /**
     * 验证空配置不被展示为生效规则来源。
     */
    @Test
    public void emptyConfigurationDoesNotClaimAnActiveRule() {
        RuleGroup empty = new RuleGroup(1, "empty");
        assertEquals(RuleSource.NONE, RuleSource.resolve(null, empty));
        // 与配置来源解析一致：启用的独立配置优先，但未选规则时不能标为生效。
        assertEquals(RuleSource.NONE, RuleSource.resolve(empty, rule(true)));
        assertEquals(RuleSource.NONE, RuleSource.resolve(null, null));
    }

    /**
     * 构造测试需要的规则草稿。
     *
     * @param enabled 当前控件或配置项是否可以使用
     */
    private RuleGroup rule(boolean enabled) {
        RuleGroup group = new RuleGroup(1, "rule");
        group.enabled = enabled;
        group.types.add(RuleType.DAILY_LIMIT);
        group.dailyLimitMillis = 30 * 60_000L;
        return group;
    }
}
