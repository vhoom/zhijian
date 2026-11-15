package github.vhoom.zhijian.data.model.policy;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * 验证全部重置的边界，防止清空名称、成员或修改原始策略。
 *
 * 维护日期：2026年11月08日
 */
public class RuleConfigurationDefaultsTest {
    /** 重置草稿清空规则和参数，保留组身份、成员及关闭状态。 */
    @Test public void resetClearsConfigurationAndPreservesGroupMetadata() {
        RuleGroup source = new RuleGroup(7, "工作");
        source.apps.add("app.example");
        source.enabled = false;
        source.types.add(RuleType.DAILY_LIMIT);
        source.dailyLimitMillis = 60_000;
        source.count = 8;
        source.continuousLimitMillis = 120_000;
        source.interruptionPauseMillis = 240_000;
        source.windows.add(new RuleWindow("window", RuleType.WINDOW_BAN, 480, 720, 0));
        RuleDayParameters parameters = RuleDayParameterAdapter.capture(source);
        RuleDayParameterAdapter.applyToDays(source, 127, parameters, parameters);
        RuleGroup reset = RuleConfigurationDefaults.resetCopy(source, 180_000);
        assertEquals(7, reset.id);
        assertEquals("工作", reset.name);
        assertEquals(source.apps, reset.apps);
        assertFalse(reset.enabled);
        assertTrue(reset.types.isEmpty());
        assertTrue(reset.windows.isEmpty());
        assertTrue(reset.weekdayRules.isEmpty());
        assertEquals(7, source.weekdayRules.size());
        assertEquals(0, reset.dailyLimitMillis);
        assertEquals(0, reset.count);
        assertEquals(0, reset.continuousLimitMillis);
        assertEquals(RuleGroup.DEFAULT_INTERRUPTION_PAUSE_MILLIS, reset.interruptionPauseMillis);
        assertEquals(180_000, reset.passDurationMillis);
    }

    /** 重置后的可变集合独立于原始策略，取消或继续编辑不污染已提交快照。 */
    @Test public void resetDraftDoesNotModifyOriginalOrShareMembers() {
        RuleGroup source = new RuleGroup(3, "阅读");
        source.apps.add("app.reader");
        source.types.add(RuleType.DAILY_COUNT);
        source.count = 5;
        RuleGroup reset = RuleConfigurationDefaults.resetCopy(source, 180_000);
        reset.apps.clear();
        assertEquals(1, source.apps.size());
        assertTrue(source.has(RuleType.DAILY_COUNT));
        assertEquals(5, source.count);
    }
}
