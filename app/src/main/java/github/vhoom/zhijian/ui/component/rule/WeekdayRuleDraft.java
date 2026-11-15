package github.vhoom.zhijian.ui.component.rule;

import github.vhoom.zhijian.data.model.policy.RuleDayParameterAdapter;
import github.vhoom.zhijian.data.model.policy.RuleDayParameters;
import github.vhoom.zhijian.data.model.policy.RuleGroup;

/**
 * 单日编辑状态；候选保存与提交成功分开，失败时不改变已保存标记和复制来源。
 *
 * 维护日期：2026年11月08日
 */
public final class WeekdayRuleDraft {
    private final RuleGroup group;
    private final RuleDayParameters initialParameters;
    private WeekdaySelection selection;
    private RuleDayParameters baseline;
    private RuleDayParameters previous;
    private boolean modified;

    /** 页面重建使用的独立参数快照，不持有页面或规则组。 */
    public static final class State {
        public final WeekdaySelection selection;
        public final RuleDayParameters initialParameters;
        public final RuleDayParameters baseline;
        public final RuleDayParameters previous;
        public final boolean modified;

        /**
         * @param selection 当前编辑星期
         * @param initialParameters 首次编辑时各日使用的初始参数
         * @param baseline 当前星期取消操作的恢复参数
         * @param previous 上一次成功保存的参数，没有保存记录时为 null
         * @param modified 是否存在尚未保存的用户修改
         */
        public State(WeekdaySelection selection, RuleDayParameters initialParameters, RuleDayParameters baseline,
                RuleDayParameters previous, boolean modified) {
            this.selection = selection;
            this.initialParameters = initialParameters.copy();
            this.baseline = baseline.copy();
            this.previous = previous == null ? null : previous.copy();
            this.modified = modified;
        }
    }

    /** @param group 页面持有的编辑草稿 @param state 页面重建快照，首次进入为 null */
    public WeekdayRuleDraft(RuleGroup group, State state) {
        this.group = group;
        if (state != null) {
            initialParameters = state.initialParameters.copy();
            selection = state.selection;
            baseline = state.baseline.copy();
            previous = state.previous == null ? null : state.previous.copy();
            modified = state.modified;
        } else {
            initialParameters = RuleDayParameterAdapter.capture(group);
            selection = group.hasAnyRules() ? WeekdaySelection.single(firstConfiguredDay())
                    : WeekdaySelection.initial();
            if (selection.mask != WeekdaySelection.ALL_DAYS) {
                RuleDayParameterAdapter.bind(group, parametersFor(firstConfiguredDay()));
            }
            baseline = RuleDayParameterAdapter.capture(group);
        }
    }

    /** @return 第一个有规则的星期；空配置使用星期一 */
    private int firstConfiguredDay() {
        for (int day = 1; day <= 7; day++) if (isConfigured(day)) return day;
        return 1;
    }

    /** @param weekday 需要读取的星期 @return 已保存参数副本，尚未编辑的日期使用初始参数 */
    private RuleDayParameters parametersFor(int weekday) {
        RuleDayParameters parameters = group.weekdayRules.get(weekday);
        return (parameters == null ? initialParameters : parameters).copy();
    }

    /** @param weekday 待显示的星期 @return 已保存的该日是否包含规则，不读取当前未保存输入 */
    public boolean isConfigured(int weekday) { return !parametersFor(weekday).types.isEmpty(); }

    /** @return 首次全选编辑或七天均有已保存规则；切换当前编辑日不改变执行范围 */
    public boolean isEveryDay() {
        if (selection.mask == WeekdaySelection.ALL_DAYS) return true;
        for (int day = 1; day <= 7; day++) if (!isConfigured(day)) return false;
        return true;
    }

    /** @return 当前编辑星期，首次空配置可能为七日全选 */
    public WeekdaySelection selection() { return selection; }

    /** @param weekday 需要编辑的星期 @return 切换成功；有未保存修改时保持原日并返回 false */
    public boolean select(int weekday) {
        WeekdaySelection next = selection.select(weekday);
        if (selection.sameAs(next)) return true;
        if (modified) return false;
        selection = next;
        baseline = parametersFor(weekday);
        RuleDayParameterAdapter.bind(group, baseline);
        return true;
    }

    /** 用户修改规则参数后标记当前星期草稿，程序回填由控件协调器过滤。 */
    public void onParametersChanged() { modified = true; }

    /** @return 当前星期是否有未保存参数 */
    public boolean hasPendingChanges() { return modified; }

    /** @return 是否有上一次成功保存的配置可复制 */
    public boolean canCopyPrevious() { return previous != null; }

    /** @return 是否成功复制上一份保存配置；复制结果仍需明确保存 */
    public boolean copyPrevious() {
        if (previous == null) return false;
        RuleDayParameterAdapter.bind(group, previous);
        modified = true;
        return true;
    }

    /** 恢复当前星期进入编辑或保存成功时的参数，不影响其他日期和复制来源。 */
    public void cancel() {
        RuleDayParameterAdapter.bind(group, baseline);
        modified = false;
    }

    /** @return 候选提交副本；首次全选初始化七天，后续只替换当前星期 */
    public RuleGroup prepareSave() {
        return withCurrentDay(RuleDayParameterAdapter.capture(group));
    }

    /** @return 只清除当前单日的候选副本；未保存输入和其他星期不参与清除 */
    public RuleGroup prepareClear() {
        if (selection.mask == WeekdaySelection.ALL_DAYS) {
            throw new IllegalStateException("Select one weekday before clearing");
        }
        RuleDayParameters empty = new RuleDayParameters();
        empty.passDurationMillis = baseline.passDurationMillis;
        return withCurrentDay(empty);
    }

    /** @param parameters 当前星期需要提交的参数 @return 与页面不共享集合的完整七日候选 */
    private RuleGroup withCurrentDay(RuleDayParameters parameters) {
        RuleGroup candidate = group.copy();
        RuleDayParameterAdapter.bind(candidate, parameters);
        RuleDayParameterAdapter.applyToDays(candidate, selection.mask, parameters, initialParameters);
        return candidate;
    }

    /** @param saved 已成功完成后台事务的候选配置；只有成功后才更新绿色标记和复制来源 */
    public void onSaved(RuleGroup saved) {
        acceptSaved(saved, true);
    }

    /** @param saved 清除当天后成功提交的候选；保留上一份确认应用的规则供继续复制 */
    public void onCleared(RuleGroup saved) {
        acceptSaved(saved, false);
    }

    /** @param saved 成功提交的候选 @param remember 是否把本次参数记作上一份应用配置 */
    private void acceptSaved(RuleGroup saved, boolean remember) {
        group.weekdayRules.clear();
        saved.weekdayRules.forEach((day, parameters) -> group.weekdayRules.put(day, parameters.copy()));
        if (selection.mask == WeekdaySelection.ALL_DAYS) selection = WeekdaySelection.single(1);
        baseline = parametersFor(Integer.numberOfTrailingZeros(selection.mask) + 1);
        if (remember) previous = baseline.copy();
        RuleDayParameterAdapter.bind(group, baseline);
        modified = false;
    }

    /** @return 不与本次编辑共享可变参数的页面重建快照 */
    public State snapshot() { return new State(selection, initialParameters, baseline, previous, modified); }
}
