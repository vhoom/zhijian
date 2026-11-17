package github.vhoom.zhijian.domain.interception;

import github.vhoom.zhijian.data.model.policy.RuleDayParameterAdapter;
import github.vhoom.zhijian.data.model.policy.RuleGroup;
import github.vhoom.zhijian.data.model.policy.RuleType;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * 验证名称、原样保存和七日等价展开保留版本，真正规则修改仍使旧会话失效。
 *
 * 维护日期：2026年11月16日
 */
public final class RuleConfigurationComparisonTest {
    /** 名称不属于运行规则，原样保存或展开等价七日参数不改变行为。 */
    @Test public void renameAndEquivalentWeekdaySavePreserveBehavior() {
        RuleGroup original = group();
        RuleGroup draft = original.copy();
        draft.name = "新名称";
        assertTrue(RuleConfigurationComparison.sameBehavior(original, draft));
        RuleDayParameterAdapter.applyToDays(draft, 127, RuleDayParameterAdapter.capture(draft),
                RuleDayParameterAdapter.capture(draft));
        assertTrue(RuleConfigurationComparison.sameBehavior(original, draft));
    }

    /** 修改参数、成员或开关需要重新判定。 */
    @Test public void ruleAndMembershipChangesInvalidateBehavior() {
        RuleGroup original = group();
        RuleGroup draft = original.copy();
        draft.count++;
        assertFalse(RuleConfigurationComparison.sameBehavior(original, draft));
        draft = original.copy();
        draft.apps.remove("b");
        assertFalse(RuleConfigurationComparison.sameBehavior(original, draft));
        draft = original.copy();
        draft.enabled = false;
        assertFalse(RuleConfigurationComparison.sameBehavior(original, draft));
    }

    /** @return 固定身份与两成员的已启用规则 */
    private static RuleGroup group() {
        RuleGroup group = new RuleGroup(1L, "原名称");
        group.apps.add("a");
        group.apps.add("b");
        group.types.add(RuleType.DAILY_COUNT);
        group.count = 1;
        group.passDurationMillis = 60_000L;
        return group;
    }
}
