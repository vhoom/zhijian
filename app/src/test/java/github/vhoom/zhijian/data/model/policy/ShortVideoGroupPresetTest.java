package github.vhoom.zhijian.data.model.policy;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

import static org.junit.Assert.*;

/**
 * 固定目录、安装交集及每天默认参数的回归。
 *
 * 维护日期：2026年11月08日
 */
public class ShortVideoGroupPresetTest {
    /** 目录保持 27 个不同包名，包含额外指定的哔哩哔哩和小红书。 */
    @Test public void catalogContainsTwentySevenDistinctPackages() {
        assertEquals(27, BuiltInGroupPreset.VIDEO.packages.size());
        assertEquals(27, new HashSet<>(BuiltInGroupPreset.VIDEO.packages).size());
        assertTrue(BuiltInGroupPreset.VIDEO.packages.contains("tv.danmaku.bili"));
        assertTrue(BuiltInGroupPreset.VIDEO.packages.contains("com.xingin.xhs"));
    }

    /** 实际成员去掉未安装、重复及目录外包名，并保持目录顺序。 */
    @Test public void intersectionUsesOnlyInstalledCatalogMembers() {
        assertEquals(Arrays.asList("com.ss.android.ugc.aweme", "com.xingin.xhs"),
                BuiltInGroupPreset.VIDEO.members(Arrays.asList("com.xingin.xhs", "other.app",
                        "com.ss.android.ugc.aweme", "com.xingin.xhs")));
        assertTrue(BuiltInGroupPreset.VIDEO.members(Collections.emptyList()).isEmpty());
    }

    /** 七天默认规则有效，修改某一天不会污染其他日期或其他工厂结果。 */
    @Test public void defaultsHaveIndependentValidDays() {
        RuleGroup group = configured();
        assertFalse(group.enabled);
        assertTrue(RuleGroupValidator.validate(group).isValid());
        assertEquals(7, group.weekdayRules.size());
        for (RuleDayParameters day : group.weekdayRules.values()) {
            assertEquals(900_000L, day.continuousLimitMillis);
            assertEquals(600_000L, day.interruptionPauseMillis);
            assertEquals(23 * 60, day.windows.get(0).startMinute);
            assertEquals(6 * 60 + 30, day.windows.get(0).endMinute);
            assertEquals(RuleType.WINDOW_BAN, day.windows.get(0).type);
        }
        group.weekdayRules.get(1).windows.get(0).startMinute = 22 * 60;
        group.weekdayRules.get(1).continuousLimitMillis = 300_000L;
        assertEquals(23 * 60, group.weekdayRules.get(2).windows.get(0).startMinute);
        assertEquals(900_000L, configured().weekdayRules.get(1).continuousLimitMillis);
    }

    /** 内置组允许零成员及单成员，普通用户组仍要求至少两个应用。 */
    @Test public void builtInMembershipDoesNotRelaxUserGroupMinimum() {
        RuleGroup builtIn = configured();
        assertTrue(RuleGroupMembershipPolicy.hasValidGroupMembers(builtIn));
        RuleGroup userGroup = new RuleGroup(1, "用户组");
        userGroup.apps.addAll(builtIn.apps);
        assertFalse(RuleGroupMembershipPolicy.hasValidGroupMembers(userGroup));
        builtIn.apps.clear();
        assertTrue(RuleGroupMembershipPolicy.hasValidGroupMembers(builtIn));
        builtIn.apps.add(null);
        assertFalse(RuleGroupMembershipPolicy.hasValidGroupMembers(builtIn));
    }

    /** @return 仅安装抖音的默认组草稿 */
    private static RuleGroup configured() {
        return BuiltInGroupPreset.VIDEO.create("默认短视频组",
                Collections.singletonList("com.ss.android.ugc.aweme"), 180_000L);
    }
}
