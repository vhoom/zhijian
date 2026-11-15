package github.vhoom.zhijian.data.model.policy;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 覆盖 RuleGroupValidator 的边界输入、状态变化与回归行为。
 *
 * 维护日期：2026年11月13日
 */
public class RuleGroupValidatorTest {

    /**
     * 验证完整合法的规则组能通过全部校验。
     */
    @Test
    public void validGroupPasses() {
        RuleGroup group = validGroup();

        assertTrue(RuleGroupValidator.validate(group).isValid());
    }

    /**
     * 验证已启用限次未填写次数时返回范围错误和对应条件类型。
     */
    @Test
    public void missingSelectedParameterIdentifiesType() {
        RuleGroup group = validGroup();
        group.types.add(RuleType.DAILY_COUNT);

        RuleGroupValidator.Result result = RuleGroupValidator.validate(group);

        assertEquals(RuleGroupValidator.Error.COUNT_OUT_OF_RANGE, result.error);
        assertEquals(RuleType.DAILY_COUNT, result.type);
    }

    /** 三种限次统一接受 1 至 99，零、负数及超过 99 的整数都定位到对应参数。 */
    @Test
    public void allCountRulesEnforceTheSameEnabledRange() {
        for (RuleType type : new RuleType[]{RuleType.DAILY_COUNT, RuleType.ROLLING_COUNT, RuleType.WINDOW_COUNT}) {
            for (int count : new int[]{-1, 0, 1, 99, 100, Integer.MAX_VALUE}) {
                RuleGroup group = validGroup();
                group.types.clear();
                group.types.add(type);
                if (type == RuleType.DAILY_COUNT) group.count = count;
                if (type == RuleType.ROLLING_COUNT) {
                    group.frequencyWindowMillis = 6_000L;
                    group.frequencyCount = count;
                }
                if (type == RuleType.WINDOW_COUNT) {
                    RuleWindow window = new RuleWindow("count", type, 540, 541, 0L);
                    window.limitCount = count;
                    group.windows.add(window);
                }
                RuleGroupValidator.Result result = RuleGroupValidator.validate(group);
                if (count == 1 || count == 99) assertTrue(result.isValid());
                else {
                    assertEquals(RuleGroupValidator.Error.COUNT_OUT_OF_RANGE, result.error);
                    assertEquals(type, result.type);
                    assertEquals(RuleGroupValidator.Field.COUNT, result.field);
                    assertEquals(count > 99, result.tooLarge);
                    if (type == RuleType.WINDOW_COUNT) assertEquals("count", result.windowId);
                }
            }
        }
    }

    /** 频率次数合法后仍需独立填写具有一分钟小数精度的正数窗口时长。 */
    @Test
    public void rollingCountRequiresItsOwnValidDuration() {
        RuleGroup group = validGroup();
        group.types.add(RuleType.ROLLING_COUNT);
        group.frequencyCount = 2;
        for (long duration : new long[]{0L, 1_000L}) {
            group.frequencyWindowMillis = duration;
            RuleGroupValidator.Result result = RuleGroupValidator.validate(group);
            assertEquals(RuleGroupValidator.Error.PARAM_REQUIRED, result.error);
            assertEquals(RuleType.ROLLING_COUNT, result.type);
        }
        group.frequencyWindowMillis = 6_000L;
        assertTrue(RuleGroupValidator.validate(group).isValid());
    }

    /** 关闭的次数参数可保留零值，超过范围的草稿仍不能进入持久化。 */
    @Test
    public void inactiveCountFieldsAllowZeroButRejectUnstorableValues() {
        RuleGroup group = validGroup();
        assertTrue(RuleGroupValidator.validateStoredFields(group).isValid());
        group.count = 100;
        assertEquals(RuleType.DAILY_COUNT, RuleGroupValidator.validateStoredFields(group).type);
        group.count = 0;
        group.frequencyCount = 100;
        assertEquals(RuleType.ROLLING_COUNT, RuleGroupValidator.validateStoredFields(group).type);
        group.frequencyCount = 0;
        RuleWindow window = new RuleWindow("inactive", RuleType.WINDOW_COUNT, 540, 600, 0L);
        group.windows.add(window);
        assertTrue(RuleGroupValidator.validateStoredFields(group).isValid());
        window.limitCount = 100;
        RuleGroupValidator.Result result = RuleGroupValidator.validateStoredFields(group);
        assertEquals(RuleGroupValidator.Error.COUNT_OUT_OF_RANGE, result.error);
        assertEquals("inactive", result.windowId);
    }

    /** 完整七日候选中的错误次数保留星期和时段身份，不误指向当前日的其他参数。 */
    @Test
    public void invalidWeekdayWindowCountIdentifiesItsDayAndWindow() {
        RuleGroup group = validGroup();
        RuleDayParameters initial = RuleDayParameterAdapter.capture(group);
        RuleDayParameters selected = initial.copy();
        selected.types.add(RuleType.WINDOW_COUNT);
        selected.windows.add(new RuleWindow("weekday-count", RuleType.WINDOW_COUNT, 540, 600, 0L));
        RuleDayParameterAdapter.applyToDays(group, 4, selected, initial);
        RuleGroupValidator.Result result = RuleGroupValidator.validate(group);
        assertEquals(RuleGroupValidator.Error.COUNT_OUT_OF_RANGE, result.error);
        assertEquals(3, result.weekday);
        assertEquals("weekday-count", result.windowId);
    }


    /** 连续提醒的休息时长必须为正数分钟。 */
    @Test
    public void continuousRuleRequiresRestMinutes() {
        RuleGroup group = validGroup();
        group.types.clear();
        group.types.add(RuleType.CONTINUOUS);
        group.continuousLimitMillis = 30 * 60_000L;
        group.interruptionPauseMillis = 0;

        RuleGroupValidator.Result result = RuleGroupValidator.validate(group);

        assertEquals(RuleGroupValidator.Error.INTERRUPTION_PAUSE_REQUIRED, result.error);
        assertEquals(RuleType.CONTINUOUS, result.type);
    }

    /**
     * 验证开始结束相同的时段被拒绝。
     */
    @Test
    public void equalWindowEndpointsAreRejected() {
        RuleGroup group = validGroup();
        group.types.clear();
        group.types.add(RuleType.WINDOW_BAN);
        group.windows.add(new RuleWindow("same-time", RuleType.WINDOW_BAN, 480, 480, 0));

        RuleGroupValidator.Result result = RuleGroupValidator.validate(group);
        assertEquals(RuleGroupValidator.Error.WINDOW_INVALID, result.error);
        assertEquals("same-time", result.windowId);
    }

    /**
     * 验证每个限时窗口必须有自己的额度。
     */
    @Test
    public void everyLimitWindowRequiresItsOwnQuota() {
        RuleGroup group = validGroup();
        group.types.add(RuleType.WINDOW_LIMIT);
        group.windows.add(new RuleWindow("morning", RuleType.WINDOW_LIMIT,
                480, 720, 30 * 60_000L));
        RuleWindow evening = new RuleWindow("evening", RuleType.WINDOW_LIMIT, 1080, 1320, 0);
        group.windows.add(evening);

        RuleGroupValidator.Result result = RuleGroupValidator.validate(group);
        assertEquals(RuleGroupValidator.Error.PARAM_REQUIRED, result.error);
        assertEquals(RuleType.WINDOW_LIMIT, result.type);
        assertEquals("evening", result.windowId);

        evening.limitDurationMillis = 60 * 60_000L;
        assertTrue(RuleGroupValidator.validate(group).isValid());
        assertEquals(30 * 60_000L, group.windows.get(0).limitDurationMillis);
    }

    /**
     * 验证一种条件不能借用另一种条件的时间窗口。
     */
    @Test
    public void oneTypeCannotBorrowAnotherTypesWindows() {
        RuleGroup group = validGroup();
        group.types.add(RuleType.WINDOW_BAN);
        group.windows.add(new RuleWindow("limit", RuleType.WINDOW_LIMIT,
                480, 720, 30 * 60_000L));

        RuleGroupValidator.Result result = RuleGroupValidator.validate(group);
        assertEquals(RuleGroupValidator.Error.WINDOW_REQUIRED, result.error);
        assertEquals(RuleType.WINDOW_BAN, result.type);
    }

    /**
     * 验证跨午夜时段合法，禁止条件不要求时长额度。
     */
    @Test
    public void crossMidnightWindowsAreValidAndBanNeedsNoQuota() {
        RuleGroup group = validGroup();
        group.types.add(RuleType.WINDOW_LIMIT);
        group.types.add(RuleType.WINDOW_BAN);
        group.windows.add(new RuleWindow("limit", RuleType.WINDOW_LIMIT,
                1320, 60, 30 * 60_000L));
        group.windows.add(new RuleWindow("ban", RuleType.WINDOW_BAN, 1380, 480, 0));

        assertTrue(RuleGroupValidator.validate(group).isValid());
    }

    /**
     * 验证时间超出一天范围时定位到确切窗口。
     */
    @Test
    public void outOfDayMinuteReportsTheExactWindow() {
        RuleGroup group = validGroup();
        group.types.add(RuleType.WINDOW_BAN);
        group.windows.add(new RuleWindow("first", RuleType.WINDOW_BAN, 480, 720, 0));
        group.windows.add(new RuleWindow("invalid", RuleType.WINDOW_BAN, 1320, 1440, 0));

        RuleGroupValidator.Result result = RuleGroupValidator.validate(group);
        assertEquals(RuleGroupValidator.Error.WINDOW_INVALID, result.error);
        assertEquals("invalid", result.windowId);
    }

    /**
     * 验证未启用条件的草稿窗口不妨碍保存其他条件。
     */
    @Test
    public void inactiveWindowsDoNotBlockSavingActiveRules() {
        RuleGroup group = validGroup();
        group.windows.add(new RuleWindow("draft", RuleType.WINDOW_LIMIT, 480, 480, 0));

        assertTrue(RuleGroupValidator.validate(group).isValid());
    }

    /** 截图中的完全重复时段必须定位到后添加的一行。 */
    @Test public void duplicateWindowsIdentifyLaterRow() {
        RuleGroup group = windowGroup();
        group.windows.add(new RuleWindow("first", RuleType.WINDOW_BAN, 480, 1380, 0));
        group.windows.add(new RuleWindow("second", RuleType.WINDOW_BAN, 480, 1380, 0));
        assertWindowError(group, RuleGroupValidator.Error.WINDOW_DUPLICATE, "second");
    }

    /** 部分相交与完整包含都不能产生同类的重复生效区间。 */
    @Test public void partialAndContainedOverlapsAreRejected() {
        RuleGroup group = windowGroup();
        group.windows.add(new RuleWindow("first", RuleType.WINDOW_BAN, 480, 720, 0));
        group.windows.add(new RuleWindow("second", RuleType.WINDOW_BAN, 600, 840, 0));
        assertWindowError(group, RuleGroupValidator.Error.WINDOW_OVERLAP, "second");
        group.windows.get(1).endMinute = 660;
        assertWindowError(group, RuleGroupValidator.Error.WINDOW_OVERLAP, "second");
    }

    /** 跨午夜在午夜前后都参与相同的冲突检测。 */
    @Test public void overnightOverlapIsRejectedOnBothSidesOfMidnight() {
        RuleGroup group = windowGroup();
        group.windows.add(new RuleWindow("night", RuleType.WINDOW_BAN, 1320, 120, 0));
        RuleWindow other = new RuleWindow("other", RuleType.WINDOW_BAN, 60, 180, 0);
        group.windows.add(other);
        assertWindowError(group, RuleGroupValidator.Error.WINDOW_OVERLAP, "other");
        other.startMinute = 1260;
        other.endMinute = 1380;
        assertWindowError(group, RuleGroupValidator.Error.WINDOW_OVERLAP, "other");
    }

    /** 两个都跨午夜的窗口也会检测到共同覆盖的午夜时间。 */
    @Test public void twoOvernightWindowsCannotOverlap() {
        RuleGroup group = windowGroup();
        group.windows.add(new RuleWindow("first", RuleType.WINDOW_BAN, 1200, 60, 0));
        group.windows.add(new RuleWindow("second", RuleType.WINDOW_BAN, 1320, 180, 0));
        assertWindowError(group, RuleGroupValidator.Error.WINDOW_OVERLAP, "second");
    }

    /** 半开区间允许相邻和午夜端点相邻，不把共用端点当成重叠。 */
    @Test public void touchingWindowsRemainValid() {
        RuleGroup group = windowGroup();
        group.windows.add(new RuleWindow("first", RuleType.WINDOW_BAN, 480, 720, 0));
        group.windows.add(new RuleWindow("second", RuleType.WINDOW_BAN, 720, 1320, 0));
        group.windows.add(new RuleWindow("night", RuleType.WINDOW_BAN, 1320, 480, 0));
        assertTrue(RuleGroupValidator.validate(group).isValid());
    }

    /** 禁用与限时的同端点时段是独立规则，可以共存。 */
    @Test public void differentTypesMayUseIdenticalWindows() {
        RuleGroup group = windowGroup();
        group.types.add(RuleType.WINDOW_LIMIT);
        group.windows.add(new RuleWindow("ban", RuleType.WINDOW_BAN, 480, 720, 0));
        group.windows.add(new RuleWindow("limit", RuleType.WINDOW_LIMIT, 480, 720, 6_000));
        assertTrue(RuleGroupValidator.validate(group).isValid());
    }

    /** 时段稳定标识在不同类型间也不能重复，避免 SQL 冲突和错误定位覆盖。 */
    @Test public void duplicateIdentitiesAcrossTypesAreRejected() {
        RuleGroup group = windowGroup();
        group.windows.add(new RuleWindow("same", RuleType.WINDOW_BAN, 480, 720, 0));
        group.windows.add(new RuleWindow("same", RuleType.WINDOW_LIMIT, 720, 840, 0));
        assertWindowError(group, RuleGroupValidator.Error.WINDOW_ID_DUPLICATE, "same");
    }

    /** 跨午夜额度按完整长度判断，等于长度可保存，超出不会改写输入。 */
    @Test public void quotaCannotExceedOvernightWindowLength() {
        RuleGroup group = validGroup();
        group.types.add(RuleType.WINDOW_LIMIT);
        RuleWindow window = new RuleWindow("night", RuleType.WINDOW_LIMIT, 1380, 60, 120 * 60_000L);
        group.windows.add(window);
        assertTrue(RuleGroupValidator.validate(group).isValid());
        window.limitDurationMillis += 6_000L;
        assertWindowError(group, RuleGroupValidator.Error.WINDOW_LIMIT_EXCEEDS, "night");
        assertEquals(7_206_000L, window.limitDurationMillis);
    }

    /** 页面与存储使用同一分钟精度，错误时长不会先过页面再在落盘阶段失败。 */
    @Test public void durationPrecisionUsesStorageConstraints() {
        RuleGroup group = validGroup();
        group.dailyLimitMillis = 1_000;
        assertEquals(RuleGroupValidator.Error.PARAM_REQUIRED, RuleGroupValidator.validate(group).error);
        group.dailyLimitMillis = 6_000;
        group.passDurationMillis = 1_000;
        assertEquals(RuleGroupValidator.Error.PASS_REQUIRED, RuleGroupValidator.validate(group).error);
        group.passDurationMillis = 6_000;
        assertTrue(RuleGroupValidator.validate(group).isValid());
    }

    /** 关闭条件允许空草稿，结构非法的负数仍会被页面和编解码共同拒绝。 */
    @Test public void inactiveDraftsMustStillHaveStorableFields() {
        RuleGroup group = validGroup();
        group.continuousLimitMillis = -1;
        assertEquals(RuleGroupValidator.Error.PARAM_REQUIRED, RuleGroupValidator.validateStoredFields(group).error);
    }

    /** 全局额度不需要应用绑定，但启用的额度仍要满足共同参数约束。 */
    @Test public void globalConfigurationCanOmitBindings() {
        RuleGroup group = validGroup();
        group.apps.clear();
        assertTrue(RuleGroupValidator.validateConfiguration(group).isValid());
        group.dailyLimitMillis = 0;
        assertTrue(RuleGroupValidator.validateConfiguration(group).isValid());
    }

    /** 全天使用与连续休息可取绝对上界，超出时保留各自参数角色。 */
    @Test
    public void durationCeilingsPreserveUsageAndRestTargets() {
        RuleGroup group = validGroup();
        group.types.add(RuleType.CONTINUOUS);
        group.dailyLimitMillis = 720L * 60_000L;
        group.continuousLimitMillis = 720L * 60_000L;
        group.interruptionPauseMillis = 720L * 60_000L;
        assertTrue(RuleGroupValidator.validate(group).isValid());
        group.dailyLimitMillis += 6_000L;
        RuleGroupValidator.Result result = RuleGroupValidator.validate(group);
        assertEquals(RuleType.DAILY_LIMIT, result.type);
        assertEquals(RuleGroupValidator.Field.VALUE, result.field);
        assertTrue(result.tooLarge);
        assertEquals(43_206_000L, group.dailyLimitMillis);
        group.dailyLimitMillis = 0L;
        group.continuousLimitMillis += 6_000L;
        result = RuleGroupValidator.validate(group);
        assertEquals(RuleType.CONTINUOUS, result.type);
        assertEquals(RuleGroupValidator.Field.VALUE, result.field);
        group.continuousLimitMillis -= 6_000L;
        group.interruptionPauseMillis += 6_000L;
        result = RuleGroupValidator.validate(group);
        assertEquals(RuleType.CONTINUOUS, result.type);
        assertEquals(RuleGroupValidator.Field.REST, result.field);
        assertTrue(result.tooLarge);
    }

    /** 频率窗口和放行参数分别采用独立上界与严格上界。 */
    @Test
    public void frequencyAndTemporaryPassUseTheirOwnCeilings() {
        RuleGroup group = validGroup();
        group.types.add(RuleType.ROLLING_COUNT);
        group.frequencyCount = 1;
        group.frequencyWindowMillis = 60L * 60_000L;
        group.passDurationMillis = 43_194_000L;
        assertTrue(RuleGroupValidator.validate(group).isValid());
        group.frequencyWindowMillis += 6_000L;
        RuleGroupValidator.Result result = RuleGroupValidator.validate(group);
        assertEquals(RuleType.ROLLING_COUNT, result.type);
        assertEquals(RuleGroupValidator.Field.VALUE, result.field);
        assertTrue(result.tooLarge);
        group.frequencyWindowMillis -= 6_000L;
        group.passDurationMillis = 720L * 60_000L;
        result = RuleGroupValidator.validate(group);
        assertEquals(RuleGroupValidator.Field.PASS, result.field);
        assertEquals(RuleGroupValidator.Error.VALUE_TOO_LARGE, result.error);
        assertTrue(result.tooLarge);
    }

    /** 关闭参数的过大值在保存时拒绝，当前格式的读取结构检查仍可保留原值供修改。 */
    @Test
    public void inactiveDurationBoundsDoNotPreventReadingCurrentStoredFields() {
        RuleGroup group = validGroup();
        group.continuousLimitMillis = 43_206_000L;
        assertTrue(RuleGroupValidator.validateStoredFields(group).isValid());
        RuleGroupValidator.Result result = RuleGroupValidator.validate(group);
        assertEquals(RuleType.CONTINUOUS, result.type);
        assertTrue(result.tooLarge);
        group.continuousLimitMillis = 0L;
        RuleWindow window = new RuleWindow("inactive-long", RuleType.WINDOW_LIMIT, 480, 480, 43_206_000L);
        group.windows.add(window);
        assertTrue(RuleGroupValidator.validateStoredFields(group).isValid());
        result = RuleGroupValidator.validate(group);
        assertEquals(RuleType.WINDOW_LIMIT, result.type);
        assertEquals("inactive-long", result.windowId);
        assertTrue(result.tooLarge);
    }

    /** 完整七日候选中的休息错误保留星期、时段身份和过大分类。 */
    @Test
    public void oversizedWeekdayRestKeepsItsDayWindowAndRole() {
        RuleGroup group = validGroup();
        RuleDayParameters initial = RuleDayParameterAdapter.capture(group);
        RuleDayParameters selected = initial.copy();
        selected.types.add(RuleType.WINDOW_CONTINUOUS);
        RuleWindow window = new RuleWindow("weekday-rest", RuleType.WINDOW_CONTINUOUS, 540, 660, 60L * 60_000L);
        window.interruptionPauseMillis = 43_206_000L;
        selected.windows.add(window);
        RuleDayParameterAdapter.applyToDays(group, 4, selected, initial);
        RuleGroupValidator.Result result = RuleGroupValidator.validate(group);
        assertEquals(3, result.weekday);
        assertEquals("weekday-rest", result.windowId);
        assertEquals(RuleGroupValidator.Field.REST, result.field);
        assertTrue(result.tooLarge);
        assertEquals(43_206_000L, group.weekdayRules.get(3).windows.get(0).interruptionPauseMillis);
    }

    /** 内部单应用策略名称是包名，手动名称上界不用于校验这个隐藏身份。 */
    @Test
    public void manualNameLimitsDoNotApplyToInternalPackageNames() {
        RuleGroup group = validGroup();
        group.name = "github.vhoom.zhijian.policy.long.package.name";
        assertEquals(RuleInputLimits.Status.INVALID, RuleInputLimits.checkName(group.name));
        assertTrue(RuleGroupValidator.validateConfiguration(group).isValid());
        assertFalse(RuleGroupValidator.validateConfiguration(group).tooLarge);
    }

    /** @return 只启用时段禁用且具有完整放行参数的草稿 */
    private static RuleGroup windowGroup() {
        RuleGroup group = validGroup();
        group.types.clear();
        group.types.add(RuleType.WINDOW_BAN);
        return group;
    }

    /**
     * 检查错误原因及窗口定位，不依赖 Android 页面。
     * @param group 待检查的草稿
     * @param error 预期的错误原因
     * @param id 预期出错窗口的身份
     */
    private static void assertWindowError(RuleGroup group, RuleGroupValidator.Error error, String id) {
        RuleGroupValidator.Result result = RuleGroupValidator.validate(group);
        assertEquals(error, result.error);
        assertEquals(id, result.windowId);
    }

    /**
     * 构造具有完整合法参数的最小测试规则组。
     */
    private static RuleGroup validGroup() {
        RuleGroup group = new RuleGroup(1L, "测试规则");
        group.apps.add("example.app");
        group.types.add(RuleType.DAILY_LIMIT);
        group.dailyLimitMillis = 60 * 60_000L;
        group.passDurationMillis = 10 * 60_000L;
        return group;
    }
}
