package github.vhoom.zhijian.data.model.policy;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * 验证批量应用仅修改选中日期，旧配置及各日可变集合相互独立。
 *
 * 维护日期：2026年11月13日
 */
public class RuleDayParameterAdapterTest {
    /** 新限次的捕获、七日复制和单日回填保持次数与时长独立，副本修改不污染原参数。 */
    @Test
    public void newCountParametersRemainIndependentAcrossCaptureCopyAndBind() {
        RuleGroup group = new RuleGroup(1L, "限次");
        group.apps.add("app.example");
        group.types.add(RuleType.ROLLING_COUNT);
        group.types.add(RuleType.WINDOW_COUNT);
        group.frequencyWindowMillis = 6_000L;
        group.frequencyCount = 2;
        RuleWindow window = new RuleWindow("count", RuleType.WINDOW_COUNT, 540, 600, 0L);
        window.limitCount = 3;
        group.windows.add(window);
        RuleDayParameters captured = RuleDayParameterAdapter.capture(group);
        RuleDayParameterAdapter.applyToDays(group, 127, captured, captured);
        RuleGroup copy = group.copy();
        copy.weekdayRules.get(1).frequencyWindowMillis = 12_000L;
        copy.weekdayRules.get(1).frequencyCount = 99;
        copy.weekdayRules.get(1).windows.get(0).limitCount = 99;
        RuleGroup rebound = RuleDayParameterAdapter.withParameters(group, copy.weekdayRules.get(1));
        assertEquals(12_000L, rebound.frequencyWindowMillis);
        assertEquals(99, rebound.frequencyCount);
        assertEquals(99, rebound.windows.get(0).limitCount);
        assertEquals(6_000L, group.weekdayRules.get(1).frequencyWindowMillis);
        assertEquals(2, group.weekdayRules.get(1).frequencyCount);
        assertEquals(3, group.weekdayRules.get(1).windows.get(0).limitCount);
        assertEquals(3, copy.weekdayRules.get(2).windows.get(0).limitCount);
        assertEquals(3, captured.windows.get(0).limitCount);
        assertEquals(group.apps, rebound.apps);
    }

    /** 首次按星期修改只覆盖目标日期，其他日期保留原有每天规则。 */
    @Test public void batchApplyPreservesUnselectedInitialDays() {
        RuleGroup group = new RuleGroup(9, "工作");
        group.apps.add("app.example");
        group.types.add(RuleType.DAILY_LIMIT);
        group.dailyLimitMillis = 60_000;
        group.passDurationMillis = 180_000;
        RuleDayParameters initialParameters = RuleDayParameterAdapter.capture(group);
        RuleDayParameters selected = initialParameters.copy();
        selected.dailyLimitMillis = 120_000;
        RuleDayParameterAdapter.applyToDays(group, 5, selected, initialParameters);
        assertEquals(7, group.weekdayRules.size());
        assertEquals(120_000, group.weekdayRules.get(1).dailyLimitMillis);
        assertEquals(120_000, group.weekdayRules.get(3).dailyLimitMillis);
        assertEquals(60_000, group.weekdayRules.get(2).dailyLimitMillis);
        assertEquals(60_000, group.weekdayRules.get(7).dailyLimitMillis);
        selected.dailyLimitMillis = 999_000;
        assertEquals(120_000, group.weekdayRules.get(1).dailyLimitMillis);
    }

    /** 同一批复制的时段在每个星期有独立集合，编辑或快照复制不会连带改变其他日期。 */
    @Test public void eachWeekdayAndSnapshotOwnIndependentWindows() {
        RuleGroup group = new RuleGroup(1, "夜间");
        group.passDurationMillis = 180_000;
        RuleDayParameters parameters = RuleDayParameterAdapter.capture(group);
        parameters.types.add(RuleType.WINDOW_BAN);
        parameters.windows.add(new RuleWindow("night", RuleType.WINDOW_BAN, 1320, 240, 0));
        RuleDayParameterAdapter.applyToDays(group, 127, parameters, parameters);
        RuleGroup copy = group.copy();
        copy.weekdayRules.get(1).windows.get(0).endMinute = 300;
        group.weekdayRules.get(2).windows.clear();
        assertEquals(240, group.weekdayRules.get(1).windows.get(0).endMinute);
        assertEquals(1, group.weekdayRules.get(3).windows.size());
        assertEquals(1, copy.weekdayRules.get(2).windows.size());
    }

    /** 某天可关闭所有类型，组仍因其他星期启用规则而匹配。 */
    @Test public void emptyDayDoesNotHideRulesConfiguredOnOtherDays() {
        RuleGroup group = new RuleGroup(2, "周末");
        group.apps.add("app.example");
        group.passDurationMillis = 180_000;
        RuleDayParameters empty = RuleDayParameterAdapter.capture(group);
        RuleDayParameters saturday = empty.copy();
        saturday.types.add(RuleType.DAILY_COUNT);
        saturday.count = 3;
        RuleDayParameterAdapter.applyToDays(group, 32, saturday, empty);
        assertTrue(group.hasAnyRules());
        assertTrue(RuleGroupValidator.validate(group).isValid());
        group.weekdayRules.get(6).count = 0;
        assertEquals(6, RuleGroupValidator.validate(group).weekday);
    }
}
