package github.vhoom.zhijian.data.model.policy;

import org.junit.Test;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import static org.junit.Assert.*;

/**
 * 三组目录归属与默认参数回归。
 *
 * 维护日期：2026年11月08日
 */
public class BuiltInGroupPresetTest {
    /** 三组不争抢同包名；百度去重，贴吧与今日看点归信息流。 */
    @Test public void catalogsHaveEightySevenUniquePackagesAndStableIdentities() {
        assertEquals(27, BuiltInGroupPreset.VIDEO.packages.size());
        assertEquals(25, BuiltInGroupPreset.MEDIA.packages.size());
        assertEquals(35, BuiltInGroupPreset.READING.packages.size());
        assertEquals(87, BuiltInGroupPreset.ALL_PACKAGES.size());
        Set<String> packages = new HashSet<>();
        Set<Long> ids = new HashSet<>();
        for (BuiltInGroupPreset preset : BuiltInGroupPreset.values()) {
            assertTrue(ids.add(preset.groupId));
            for (String pkg : preset.packages) assertTrue(packages.add(pkg));
        }
        assertEquals(0x464C4F575356L, BuiltInGroupPreset.VIDEO.groupId);
        assertEquals("built_in_short_video_dismissed", BuiltInGroupPreset.VIDEO.dismissedKey);
        assertTrue(BuiltInGroupPreset.MEDIA.packages.contains("com.baidu.tieba"));
        assertTrue(BuiltInGroupPreset.MEDIA.packages.contains("com.jinrikd.news"));
    }

    /** 七天完整参数有效且互不共享，包含夜间和午间的不同提醒。 */
    @Test public void everyDayHasTheConfirmedRulesAndIndependentCopies() {
        for (BuiltInGroupPreset preset : BuiltInGroupPreset.values()) {
            RuleGroup group = preset.create("默认组", Collections.emptyList(), 180_000L);
            assertFalse(group.enabled);
            assertTrue(RuleGroupValidator.validateConfiguration(group).isValid());
            assertTrue(RuleGroupMembershipPolicy.hasValidGroupMembers(group));
            assertEquals(7, group.weekdayRules.size());
            for (RuleDayParameters day : group.weekdayRules.values()) {
                int use = preset == BuiltInGroupPreset.VIDEO ? 15 : preset == BuiltInGroupPreset.MEDIA ? 20 : 25;
                assertEquals(use * 60_000L, day.continuousLimitMillis);
                assertEquals((preset == BuiltInGroupPreset.READING ? 30 : 10) * 60_000L, day.interruptionPauseMillis);
                assertEquals(180_000L, day.passDurationMillis);
                assertEquals(preset == BuiltInGroupPreset.READING ? 3 : 2, day.windows.size());
                if (preset == BuiltInGroupPreset.VIDEO) {
                    assertWindow(day.windows.get(0), RuleType.WINDOW_BAN, 1380, 390, 0, 1);
                    assertWindow(day.windows.get(1), RuleType.WINDOW_CONTINUOUS, 390, 480, 5, 20);
                } else if (preset == BuiltInGroupPreset.MEDIA) {
                    assertWindow(day.windows.get(0), RuleType.WINDOW_BAN, 30, 390, 0, 1);
                    assertWindow(day.windows.get(1), RuleType.WINDOW_CONTINUOUS, 420, 480, 10, 10);
                } else {
                    assertWindow(day.windows.get(0), RuleType.WINDOW_BAN, 420, 480, 0, 1);
                    assertWindow(day.windows.get(1), RuleType.WINDOW_CONTINUOUS, 0, 390, 5, 10);
                    assertWindow(day.windows.get(2), RuleType.WINDOW_CONTINUOUS, 720, 780, 5, 10);
                }
            }
            group.weekdayRules.get(1).windows.get(0).startMinute = 123;
            assertNotEquals(123, group.weekdayRules.get(2).windows.get(0).startMinute);
            assertNotEquals(123, preset.create("新草稿", Collections.emptyList(), 180_000L)
                    .weekdayRules.get(1).windows.get(0).startMinute);
        }
    }

    /** @param window 时段 @param type 类型 @param start 起始分钟 @param end 结束分钟
     * @param use 使用分钟 @param rest 休息分钟（禁用类型不执行该值） */
    private static void assertWindow(RuleWindow window, RuleType type, int start, int end, int use, int rest) {
        assertEquals(type, window.type);
        assertEquals(start, window.startMinute);
        assertEquals(end, window.endMinute);
        assertEquals(use * 60_000L, window.limitDurationMillis);
        assertEquals(rest * 60_000L, window.interruptionPauseMillis);
    }
}
