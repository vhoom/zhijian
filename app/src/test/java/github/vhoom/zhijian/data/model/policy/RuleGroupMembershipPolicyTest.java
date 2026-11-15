package github.vhoom.zhijian.data.model.policy;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

/**
 * 验证两个独立应用的下限，重复及空包名不能用来凑数。
 *
 * 维护日期：2026年11月08日
 */
public final class RuleGroupMembershipPolicyTest {
    /** 空选择和一个应用都不能完成规则组添加。 */
    @Test public void fewerThanTwoAppsCannotCompleteSelection() {
        assertFalse(RuleGroupMembershipPolicy.hasMinimumApps(Collections.emptyList()));
        assertFalse(RuleGroupMembershipPolicy.hasMinimumApps(Collections.singletonList("app.one")));
        assertFalse(RuleGroupMembershipPolicy.hasMinimumApps(null));
    }

    /** 至少两个不同应用允许完成，更多应用也可加入。 */
    @Test public void twoOrMoreDistinctAppsAreAccepted() {
        assertTrue(RuleGroupMembershipPolicy.hasMinimumApps(Arrays.asList("app.one", "app.two")));
        assertTrue(RuleGroupMembershipPolicy.hasMinimumApps(Arrays.asList("app.one", "app.two", "app.three")));
    }

    /** 同一应用重复两次不满足最低成员数，空包名也不能通过保存约束。 */
    @Test public void duplicateAndInvalidAppsDoNotSatisfyMinimum() {
        assertFalse(RuleGroupMembershipPolicy.hasMinimumApps(Arrays.asList("app.one", "app.one")));
        assertFalse(RuleGroupMembershipPolicy.hasMinimumApps(Arrays.asList("app.one", " ")));
        assertFalse(RuleGroupMembershipPolicy.hasMinimumApps(Arrays.asList("app.one", null)));
    }
}
