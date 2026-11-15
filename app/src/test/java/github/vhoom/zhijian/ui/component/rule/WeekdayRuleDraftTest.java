package github.vhoom.zhijian.ui.component.rule;

import github.vhoom.zhijian.data.model.policy.RuleDayParameterAdapter;
import github.vhoom.zhijian.data.model.policy.RuleDayParameters;
import github.vhoom.zhijian.data.model.policy.RuleGroup;
import github.vhoom.zhijian.data.model.policy.RuleConfigurationDefaults;
import github.vhoom.zhijian.data.model.policy.RuleGroupValidator;
import github.vhoom.zhijian.data.model.policy.RuleType;
import github.vhoom.zhijian.data.model.policy.RuleWindow;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * 验证单日提交、复制、取消与页面重建，失败候选不污染已保存配置。
 *
 * 维护日期：2026年11月13日
 */
public final class WeekdayRuleDraftTest {
    /** 新限次经单日保存、复制、重建和取消仍保留各日额度，未保存的 99 次不会污染来源。 */
    @Test
    public void newCountRulesSurviveSingleDaySaveCopyRecreationAndCancel() {
        RuleGroup group = emptyGroup();
        WeekdayRuleDraft draft = new WeekdayRuleDraft(group, null);
        draft.select(1);
        editCountRules(group, draft, 2, 1);
        draft.onSaved(draft.prepareSave());
        draft.select(2);
        assertTrue(draft.copyPrevious());
        group.frequencyWindowMillis = 12_000L;
        group.frequencyCount = 99;
        group.windows.get(0).limitCount = 99;
        RuleGroup restoredGroup = group.copy();
        WeekdayRuleDraft restored = new WeekdayRuleDraft(restoredGroup, draft.snapshot());
        assertEquals(12_000L, restoredGroup.frequencyWindowMillis);
        assertEquals(99, restoredGroup.frequencyCount);
        assertEquals(99, restoredGroup.windows.get(0).limitCount);
        restored.cancel();
        assertEquals(0L, restoredGroup.frequencyWindowMillis);
        assertEquals(0, restoredGroup.frequencyCount);
        assertTrue(restoredGroup.windows.isEmpty());
        assertTrue(restored.copyPrevious());
        restored.onSaved(restored.prepareSave());
        for (int day : new int[]{1, 2}) {
            assertEquals(6_000L, restoredGroup.weekdayRules.get(day).frequencyWindowMillis);
            assertEquals(2, restoredGroup.weekdayRules.get(day).frequencyCount);
            assertEquals(1, restoredGroup.weekdayRules.get(day).windows.get(0).limitCount);
        }
        assertTrue(restoredGroup.weekdayRules.get(3).types.isEmpty());
    }

    /** 超范围的当前日候选被拒绝后仍保留输入、原保存来源及其他星期的正确次数。 */
    @Test
    public void failedCountCandidateKeepsInputAndPreviouslySavedWeekdays() {
        RuleGroup group = emptyGroup();
        WeekdayRuleDraft draft = new WeekdayRuleDraft(group, null);
        draft.select(5);
        editCountRules(group, draft, 2, 3);
        draft.onSaved(draft.prepareSave());
        draft.select(6);
        draft.copyPrevious();
        group.frequencyCount = 100;
        RuleGroupValidator.Result result = RuleGroupValidator.validate(draft.prepareSave());
        assertEquals(RuleGroupValidator.Error.COUNT_OUT_OF_RANGE, result.error);
        assertEquals(6, result.weekday);
        assertEquals(100, group.frequencyCount);
        assertTrue(draft.hasPendingChanges());
        assertFalse(draft.isConfigured(6));
        assertEquals(2, group.weekdayRules.get(5).frequencyCount);
        assertEquals(3, group.weekdayRules.get(5).windows.get(0).limitCount);
        draft.cancel();
        assertEquals(0, group.frequencyCount);
        assertTrue(draft.copyPrevious());
        assertEquals(2, group.frequencyCount);
        assertEquals(3, group.windows.get(0).limitCount);
    }

    /**
     * 填入一条滚动配置和一个跨午夜限次时段，标记为尚未保存。
     *
     * @param group 接收测试输入的页面草稿
     * @param draft 当前星期编辑状态
     * @param frequencyCount 滚动窗口允许的次数
     * @param windowCount 跨午夜时段允许的次数
     */
    private static void editCountRules(RuleGroup group, WeekdayRuleDraft draft,
            int frequencyCount, int windowCount) {
        group.types.add(RuleType.ROLLING_COUNT);
        group.types.add(RuleType.WINDOW_COUNT);
        group.frequencyWindowMillis = 6_000L;
        group.frequencyCount = frequencyCount;
        RuleWindow window = new RuleWindow("count-night", RuleType.WINDOW_COUNT, 1320, 120, 0L);
        window.limitCount = windowCount;
        group.windows.add(window);
        draft.onParametersChanged();
    }

    /** 每天标题按执行范围变化，单日选择和未提交清除不提前改变标题。 */
    @Test public void everyDayTitleTracksCommittedDaysInsteadOfCurrentSelection() {
        RuleGroup group = emptyGroup();
        WeekdayRuleDraft draft = new WeekdayRuleDraft(group, null);
        assertTrue(draft.isEveryDay());
        editCount(group, draft, 3);
        draft.onSaved(draft.prepareSave());
        draft.select(4);
        assertTrue(draft.isEveryDay());
        RuleGroup cleared = draft.prepareClear();
        assertTrue(draft.isEveryDay());
        draft.onCleared(cleared);
        assertFalse(draft.isEveryDay());
        draft.copyPrevious();
        assertFalse(draft.isEveryDay());
        draft.onSaved(draft.prepareSave());
        assertTrue(draft.isEveryDay());
    }

    /** @return 具有有效放行参数和成员的新建空策略 */
    private static RuleGroup emptyGroup() {
        RuleGroup group = new RuleGroup(9, "星期规则");
        group.apps.add("app.example");
        group.passDurationMillis = 180_000;
        return group;
    }

    /** @param group 接收输入的草稿 @param draft 编辑协调状态 @param count 当天次数 */
    private static void editCount(RuleGroup group, WeekdayRuleDraft draft, int count) {
        group.types.add(RuleType.DAILY_COUNT);
        group.count = count;
        draft.onParametersChanged();
    }

    /** 首次空配置全选，提交前七天不亮绿，成功后切换为单日编辑。 */
    @Test public void emptyConfigurationInitializesAllDaysOnlyAfterSuccessfulSave() {
        RuleGroup group = emptyGroup();
        WeekdayRuleDraft draft = new WeekdayRuleDraft(group, null);
        assertEquals(WeekdaySelection.ALL_DAYS, draft.selection().mask);
        assertFalse(draft.canCopyPrevious());
        editCount(group, draft, 3);
        RuleGroup candidate = draft.prepareSave();
        assertTrue(group.weekdayRules.isEmpty());
        for (int day = 1; day <= 7; day++) {
            assertFalse(draft.isConfigured(day));
            assertEquals(3, candidate.weekdayRules.get(day).count);
        }
        draft.onSaved(candidate);
        assertEquals(WeekdaySelection.single(1).mask, draft.selection().mask);
        assertFalse(draft.hasPendingChanges());
        for (int day = 1; day <= 7; day++) assertTrue(draft.isConfigured(day));
        assertTrue(draft.canCopyPrevious());
    }

    /** 已有初始参数时先进入单日，修改只覆盖该日，保留其他六天参数。 */
    @Test public void initialParametersArePreservedWhenSavingOneDay() {
        RuleGroup group = emptyGroup();
        group.types.add(RuleType.DAILY_COUNT);
        group.count = 2;
        WeekdayRuleDraft draft = new WeekdayRuleDraft(group, null);
        assertEquals(WeekdaySelection.single(1).mask, draft.selection().mask);
        assertTrue(draft.select(3));
        editCount(group, draft, 5);
        draft.onSaved(draft.prepareSave());
        for (int day = 1; day <= 7; day++) {
            assertEquals(day == 3 ? 5 : 2, group.weekdayRules.get(day).count);
        }
    }

    /** 首次只编辑一日时其他日期仍为空；选择标记与绿色已有配置标记互不影响。 */
    @Test public void selectingAnEmptyDayDoesNotMarkItConfigured() {
        RuleGroup group = emptyGroup();
        WeekdayRuleDraft draft = new WeekdayRuleDraft(group, null);
        assertTrue(draft.select(7));
        editCount(group, draft, 2);
        draft.onSaved(draft.prepareSave());
        assertTrue(draft.isConfigured(7));
        assertFalse(draft.isConfigured(1));
        assertTrue(draft.select(1));
        assertTrue(group.types.isEmpty());
        assertFalse(draft.isConfigured(1));
        RuleGroup reopenedGroup = group.copy();
        WeekdayRuleDraft reopened = new WeekdayRuleDraft(reopenedGroup, null);
        assertEquals(WeekdaySelection.single(7).mask, reopened.selection().mask);
        assertEquals(2, reopenedGroup.count);
    }

    /** 应用上一份配置只复制参数；取消恢复目标日，继续复制仍使用原保存来源。 */
    @Test public void copyingPreviousCanBeCancelledWithoutChangingEitherDay() {
        RuleGroup group = emptyGroup();
        WeekdayRuleDraft draft = new WeekdayRuleDraft(group, null);
        draft.select(1);
        editCount(group, draft, 3);
        draft.onSaved(draft.prepareSave());
        draft.select(2);
        assertTrue(draft.copyPrevious());
        assertEquals(3, group.count);
        assertFalse(draft.isConfigured(2));
        group.count = 6;
        draft.cancel();
        assertEquals(0, group.count);
        assertTrue(group.types.isEmpty());
        assertTrue(draft.select(3));
        assertTrue(draft.copyPrevious());
        assertEquals(3, group.count);
        draft.onSaved(draft.prepareSave());
        assertEquals(3, group.weekdayRules.get(1).count);
        assertTrue(group.weekdayRules.get(2).types.isEmpty());
        assertEquals(3, group.weekdayRules.get(3).count);
    }

    /** 不执行成功回调模拟写入失败：输入仍在，绿色标记和上一份配置保持已提交值。 */
    @Test public void failedSaveKeepsInputAndPreviousCommittedTemplate() {
        RuleGroup group = emptyGroup();
        WeekdayRuleDraft draft = new WeekdayRuleDraft(group, null);
        draft.select(1);
        editCount(group, draft, 3);
        draft.onSaved(draft.prepareSave());
        draft.select(2);
        editCount(group, draft, 6);
        RuleGroup failedCandidate = draft.prepareSave();
        assertEquals(6, failedCandidate.weekdayRules.get(2).count);
        assertEquals(6, group.count);
        assertTrue(draft.hasPendingChanges());
        assertFalse(draft.isConfigured(2));
        assertTrue(draft.copyPrevious());
        assertEquals(3, group.count);
    }

    /** 有未保存输入时不允许静默切换，取消后才可读取另一个星期。 */
    @Test public void switchingRequiresSavingOrCancellingCurrentEdits() {
        RuleGroup group = emptyGroup();
        WeekdayRuleDraft draft = new WeekdayRuleDraft(group, null);
        draft.select(1);
        editCount(group, draft, 3);
        assertFalse(draft.select(2));
        assertEquals(WeekdaySelection.single(1).mask, draft.selection().mask);
        assertEquals(3, group.count);
        draft.cancel();
        assertTrue(draft.select(2));
        assertEquals(0, group.count);
        assertFalse(draft.hasPendingChanges());
    }

    /** 页面重建保留未保存参数、目标星期、取消基线和深复制的上一份时段配置。 */
    @Test public void recreationPreservesDraftCancelBaselineAndIndependentCopySource() {
        RuleGroup group = emptyGroup();
        WeekdayRuleDraft draft = new WeekdayRuleDraft(group, null);
        draft.select(1);
        group.types.add(RuleType.WINDOW_BAN);
        group.windows.add(new RuleWindow("night", RuleType.WINDOW_BAN, 1320, 240, 0));
        draft.onParametersChanged();
        draft.onSaved(draft.prepareSave());
        draft.select(2);
        draft.copyPrevious();
        group.windows.get(0).endMinute = 300;
        RuleGroup restoredGroup = group.copy();
        WeekdayRuleDraft.State snapshot = draft.snapshot();
        WeekdayRuleDraft restored = new WeekdayRuleDraft(restoredGroup, snapshot);
        snapshot.previous.windows.clear();
        assertEquals(WeekdaySelection.single(2).mask, restored.selection().mask);
        assertEquals(300, restoredGroup.windows.get(0).endMinute);
        assertTrue(restored.hasPendingChanges());
        restored.cancel();
        assertTrue(restoredGroup.windows.isEmpty());
        restored.copyPrevious();
        assertEquals(240, restoredGroup.windows.get(0).endMinute);
        restoredGroup.windows.get(0).endMinute = 360;
        assertEquals(240, restoredGroup.weekdayRules.get(1).windows.get(0).endMinute);
    }

    /** 全部重置后重新绑定空草稿，旧星期规则与复制来源不能在继续编辑时恢复。 */
    @Test public void resetDropsWeekdayConfigurationsAndCopySource() {
        RuleGroup source = emptyGroup();
        WeekdayRuleDraft original = new WeekdayRuleDraft(source, null);
        original.select(1);
        editCount(source, original, 3);
        original.onSaved(original.prepareSave());
        original.select(2);
        original.copyPrevious();
        RuleGroup reset = RuleConfigurationDefaults.resetCopy(source, 180_000);
        WeekdayRuleDraft draft = new WeekdayRuleDraft(reset, null);
        assertEquals(WeekdaySelection.ALL_DAYS, draft.selection().mask);
        assertFalse(draft.canCopyPrevious());
        for (int day = 1; day <= 7; day++) assertFalse(draft.isConfigured(day));
        draft.select(5);
        editCount(reset, draft, 4);
        draft.onSaved(draft.prepareSave());
        assertEquals(source.id, reset.id);
        assertEquals(source.name, reset.name);
        assertEquals(source.apps, reset.apps);
        assertEquals(4, reset.weekdayRules.get(5).count);
        assertTrue(reset.weekdayRules.get(1).types.isEmpty());
        assertEquals(3, source.weekdayRules.get(1).count);
        assertTrue(source.weekdayRules.get(2).types.isEmpty());
    }

    /** 清除候选不写回页面；成功后只清空该日并保留上一份已应用参数。 */
    @Test public void clearCandidateOnlyCommitsSelectedDayAndKeepsPreviousTemplate() {
        RuleGroup group = emptyGroup();
        WeekdayRuleDraft draft = new WeekdayRuleDraft(group, null);
        editCount(group, draft, 3);
        draft.onSaved(draft.prepareSave());
        draft.select(4);
        group.count = -1;
        draft.onParametersChanged();
        RuleGroup clear = draft.prepareClear();
        assertTrue(RuleGroupValidator.validate(clear).isValid());
        assertTrue(draft.isConfigured(4));
        assertTrue(draft.hasPendingChanges());
        assertEquals(-1, group.count);
        assertTrue(clear.weekdayRules.get(4).types.isEmpty());
        assertEquals(0, clear.weekdayRules.get(4).count);
        assertEquals(3, clear.weekdayRules.get(3).count);
        draft.onCleared(clear);
        assertFalse(draft.isConfigured(4));
        assertTrue(draft.isConfigured(3));
        assertFalse(draft.hasPendingChanges());
        assertTrue(draft.copyPrevious());
        assertEquals(3, group.count);
    }

    /** 最后一天也可明确清除，保存完整空配置，不删除规则组或其他星期参数。 */
    @Test public void lastConfiguredDayCanBeCleared() {
        RuleGroup group = emptyGroup();
        WeekdayRuleDraft draft = new WeekdayRuleDraft(group, null);
        draft.select(1);
        editCount(group, draft, 3);
        draft.onSaved(draft.prepareSave());
        RuleGroup clear = draft.prepareClear();
        assertTrue(RuleGroupValidator.validate(clear).isValid());
        draft.onCleared(clear);
        assertEquals(7, group.weekdayRules.size());
        assertFalse(group.hasAnyRules());
        assertEquals(9, group.id);
        assertEquals(1, group.apps.size());
    }

    /** 首次全选时清除不能误删七天，需先单选一个星期。 */
    @Test(expected = IllegalStateException.class) public void initialAllSelectionCannotClearAllDays() {
        new WeekdayRuleDraft(emptyGroup(), null).prepareClear();
    }

    /** 该日清空规则并保存后变为无配置，其他日期仍保留各自保存结果。 */
    @Test public void clearingRulesOnlyRemovesCurrentDaysConfiguredMarker() {
        RuleGroup group = emptyGroup();
        RuleDayParameters configured = RuleDayParameterAdapter.capture(group);
        configured.types.add(RuleType.DAILY_COUNT);
        configured.count = 3;
        RuleDayParameterAdapter.applyToDays(group, 127, configured, configured);
        WeekdayRuleDraft draft = new WeekdayRuleDraft(group, null);
        draft.select(4);
        group.types.clear();
        draft.onParametersChanged();
        draft.onSaved(draft.prepareSave());
        assertFalse(draft.isConfigured(4));
        assertTrue(draft.isConfigured(3));
        assertTrue(group.hasAnyRules());
    }
}
