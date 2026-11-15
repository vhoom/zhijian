package github.vhoom.zhijian.data.model.policy;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * 完整七日保存校验与持久化结构校验。
 *
 * 维护日期：2026年11月08日
 */
public class ReminderWindowValidationTest {
    /** 周一跨午夜禁用与周二凌晨提醒实际相交，必须阻止保存。 */
    @Test public void adjacentWeekdaysDetectCrossTypeOverlap() {
        RuleGroup group = week();
        add(group, 1, RuleType.WINDOW_BAN, "night", 1380, 60);
        add(group, 2, RuleType.WINDOW_CONTINUOUS, "morning", 30, 120);
        RuleGroupValidator.Result result = RuleGroupValidator.validateConfiguration(group);
        assertEquals(RuleGroupValidator.Error.CROSS_RULE_OVERLAP, result.error);
        assertEquals(2, result.weekday);
        assertEquals("morning", result.windowId);
        assertTrue(RuleGroupValidator.validateStoredFields(group).isValid());
    }

    /** 周日跨午夜也与下周一凌晨参与同一个循环周校验。 */
    @Test public void sundayWrapsIntoMonday() {
        RuleGroup group = week();
        add(group, 7, RuleType.WINDOW_BAN, "sunday", 1380, 60);
        add(group, 1, RuleType.WINDOW_CONTINUOUS, "monday", 30, 120);
        assertEquals(RuleGroupValidator.Error.CROSS_RULE_OVERLAP,
                RuleGroupValidator.validateConfiguration(group).error);
    }

    /** 前一日结束恰好等于下一日开始，半开区间允许保存。 */
    @Test public void adjacentWeekdayEndpointsCanTouch() {
        RuleGroup group = week();
        add(group, 1, RuleType.WINDOW_BAN, "night", 1380, 60);
        add(group, 2, RuleType.WINDOW_CONTINUOUS, "morning", 60, 120);
        assertTrue(RuleGroupValidator.validateConfiguration(group).isValid());
    }

    /** 同一开始星期的凌晨与次日凌晨是不同日期，不能误判重叠。 */
    @Test public void sameStartWeekdayDoesNotFoldNextDayBackwards() {
        RuleGroup group = week();
        add(group, 1, RuleType.WINDOW_CONTINUOUS, "early", 30, 120);
        add(group, 1, RuleType.WINDOW_CONTINUOUS, "late", 1380, 60);
        assertTrue(RuleGroupValidator.validateConfiguration(group).isValid());
    }

    /** 同类时段跨相邻星期的相交也必须被检测。 */
    @Test public void adjacentSameTypeWindowsCannotOverlap() {
        RuleGroup group = week();
        add(group, 1, RuleType.WINDOW_CONTINUOUS, "night", 1380, 60);
        add(group, 2, RuleType.WINDOW_CONTINUOUS, "early", 30, 120);
        assertEquals(RuleGroupValidator.Error.WINDOW_OVERLAP,
                RuleGroupValidator.validateConfiguration(group).error);
    }

    /** 累计额度与连续提醒属于不同条件，可以在同一时间范围配置。 */
    @Test public void windowQuotaCanCoexistWithReminder() {
        RuleGroup group = week();
        add(group, 1, RuleType.WINDOW_LIMIT, "quota", 600, 660);
        add(group, 1, RuleType.WINDOW_CONTINUOUS, "reminder", 600, 660);
        group.weekdayRules.get(1).types.add(RuleType.CONTINUOUS);
        group.weekdayRules.get(1).continuousLimitMillis = 60_000L;
        assertTrue(RuleGroupValidator.validateConfiguration(group).isValid());
    }

    /** 每段必须提供自己的休息，不能借全天参数掩盖空值。 */
    @Test public void eachReminderRequiresItsOwnRest() {
        RuleGroup group = week();
        add(group, 1, RuleType.WINDOW_CONTINUOUS, "reminder", 600, 660).interruptionPauseMillis = 0L;
        RuleGroupValidator.Result result = RuleGroupValidator.validateConfiguration(group);
        assertEquals(RuleGroupValidator.Error.INTERRUPTION_PAUSE_REQUIRED, result.error);
        assertEquals("reminder", result.windowId);
    }

    /** 提醒阈值不能大于时段长度，避免保存永远无法触发的时段。 */
    @Test public void reminderThresholdCannotExceedWindowDuration() {
        RuleGroup group = week();
        add(group, 1, RuleType.WINDOW_CONTINUOUS, "reminder", 600, 660).limitDurationMillis = 61 * 60_000L;
        assertEquals(RuleGroupValidator.Error.WINDOW_LIMIT_EXCEEDS,
                RuleGroupValidator.validateConfiguration(group).error);
    }

    /** 相等阈值拒绝新保存，但旧配置仍可完整读取。 */
    @Test public void equalReminderThresholdIsRejectedWithoutInvalidatingStoredData() {
        RuleGroup group = week();
        add(group, 1, RuleType.WINDOW_CONTINUOUS, "reminder", 600, 660).limitDurationMillis = 60 * 60_000L;
        RuleGroupValidator.Result result = RuleGroupValidator.validateConfiguration(group);
        assertEquals(RuleGroupValidator.Error.WINDOW_LIMIT_EXCEEDS, result.error);
        assertEquals(1, result.weekday);
        assertEquals("reminder", result.windowId);
        assertTrue(RuleGroupValidator.validateStoredFields(group).isValid());
    }

    /** 比时段短 0.1 分钟的提醒合法，累计限时仍允许与时段等长。 */
    @Test public void shorterReminderAndEqualQuotaRemainValid() {
        RuleGroup group = week();
        add(group, 1, RuleType.WINDOW_CONTINUOUS, "reminder", 600, 660).limitDurationMillis = 3_594_000L;
        add(group, 1, RuleType.WINDOW_LIMIT, "quota", 600, 660).limitDurationMillis = 3_600_000L;
        assertTrue(RuleGroupValidator.validateConfiguration(group).isValid());
    }

    /** 跨午夜窗口使用完整长度校验，相等阈值仍被拒绝。 */
    @Test public void overnightReminderMustAlsoBeShorterThanWindow() {
        RuleGroup group = week();
        add(group, 7, RuleType.WINDOW_CONTINUOUS, "night", 1380, 60).limitDurationMillis = 120 * 60_000L;
        assertEquals(RuleGroupValidator.Error.WINDOW_LIMIT_EXCEEDS,
                RuleGroupValidator.validateConfiguration(group).error);
    }

    /** 关闭但保留的窗口参数不参与冲突，重新打开再校验。 */
    @Test public void disabledWindowDraftDoesNotConflict() {
        RuleGroup group = week();
        add(group, 1, RuleType.WINDOW_BAN, "ban", 600, 660);
        add(group, 1, RuleType.WINDOW_CONTINUOUS, "reminder", 600, 660);
        group.weekdayRules.get(1).types.remove(RuleType.WINDOW_CONTINUOUS);
        assertTrue(RuleGroupValidator.validateConfiguration(group).isValid());
    }

    /** @return 七天都具有默认放行参数的空配置 */
    private static RuleGroup week() {
        RuleGroup group = new RuleGroup(1L, "week");
        for (int day = 1; day <= 7; day++) {
            RuleDayParameters parameters = new RuleDayParameters();
            parameters.passDurationMillis = 180_000L;
            group.weekdayRules.put(day, parameters);
        }
        return group;
    }
    /** @param group 完整配置 @param day 开始星期 @param type 类型 @param id 稳定身份
     * @param start 起点分钟 @param end 终点分钟 @return 可调整的窗口草稿 */
    private static RuleWindow add(RuleGroup group, int day, RuleType type, String id, int start, int end) {
        RuleDayParameters parameters = group.weekdayRules.get(day);
        parameters.types.add(type);
        RuleWindow window = new RuleWindow(id, type, start, end, type == RuleType.WINDOW_BAN ? 0L : 60_000L);
        parameters.windows.add(window);
        return window;
    }
}
