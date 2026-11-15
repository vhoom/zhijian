package github.vhoom.zhijian.data.model.policy;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;

/**
 * 覆盖 RuleGroupNames 的边界输入、状态变化与回归行为。
 *
 * 维护日期：2026年11月08日
 */
public class RuleGroupNamesTest {
    /**
     * 验证第一个默认规则名称也带明确编号。
     */
    @Test
    public void firstDefaultNameHasAnExplicitNumber() {
        assertEquals("规则组 1", RuleGroupNames.next("规则组", Collections.emptyList(), -1));
    }

    /**
     * 验证停用组仍占用名称，匹配前去除首尾空白。
     */
    @Test
    public void disabledGroupsAndSurroundingSpacesStillReserveTheirNames() {
        RuleGroup disabled = new RuleGroup(2, " 规则组 2 ");
        disabled.enabled = false;
        assertEquals("规则组 3", RuleGroupNames.next("规则组", Arrays.asList(
                new RuleGroup(1, "规则组 1"), disabled, new RuleGroup(4, "规则组 4")), -1));
    }

    /**
     * 验证可复用空闲编号且不改动已有组名。
     */
    @Test
    public void unusedNumbersCanBeReusedWithoutRenamingExistingGroups() {
        RuleGroup custom = new RuleGroup(8, "晚间专注");
        assertEquals("规则组 2", RuleGroupNames.next("规则组", Arrays.asList(
                new RuleGroup(1, "规则组 1"), new RuleGroup(3, "规则组 3"), custom), -1));
        assertEquals("晚间专注", custom.name);
    }

    /**
     * 验证当前编辑草稿不会被自己的名称判为冲突。
     */
    @Test
    public void excludedDraftDoesNotCollideWithItself() {
        assertEquals("规则组 1", RuleGroupNames.next("规则组", Arrays.asList(
                new RuleGroup(7, "规则组 1"), new RuleGroup(8, "规则组 2")), 7));
    }
}
