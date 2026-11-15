package github.vhoom.zhijian.ui.component.rule;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import github.vhoom.zhijian.R;
import github.vhoom.zhijian.data.model.policy.RuleDayParameterAdapter;
import github.vhoom.zhijian.data.model.policy.RuleDayParameters;
import github.vhoom.zhijian.data.model.policy.RuleGroup;
import github.vhoom.zhijian.data.model.policy.RuleGroupValidator;

import java.util.function.Consumer;

/**
 * 两个规则页共用的单日编辑控件，保存由页面现有的后台事务完成。
 *
 * 维护日期：2026年11月13日
 */
public final class WeekdayRuleEditor {
    private final Activity activity;
    private final RuleGroup group;
    private final Runnable refreshRules;
    private final Consumer<RuleGroupValidator.Result> showError;
    private final TextView[] choices = new TextView[7];
    private final View copyPrevious;
    private final View clearDay;
    private final TextView title;
    private final WeekdayRuleDraft draft;
    private boolean binding;

    /**
     * @param activity 控件所属页面
     * @param host 星期区域的独立容器
     * @param group 当前页面草稿
     * @param state 页面重建保存的状态，可为 null
     * @param refreshRules 重新绑定规则及放行控件的操作
     * @param onDraftChanged 用户复制规则后通知页面的操作，程序回填不触发
     * @param save 保存当前星期并保留页面的操作
     * @param clear 清除当前星期并保存的操作
     * @param showError 将校验错误定位到原规则控件的操作
     */
    public WeekdayRuleEditor(Activity activity, LinearLayout host, RuleGroup group, Bundle state,
            Runnable refreshRules, Runnable onDraftChanged, Runnable save, Runnable clear,
            Consumer<RuleGroupValidator.Result> showError) {
        this.activity = activity;
        this.group = group;
        this.refreshRules = refreshRules;
        this.showError = showError;
        draft = new WeekdayRuleDraft(group, readState(state));
        host.removeAllViews();
        activity.getLayoutInflater().inflate(R.layout.view_rule_weekdays, host, true);
        title = host.findViewById(R.id.weekday_title);
        copyPrevious = host.findViewById(R.id.weekday_copy_previous);
        copyPrevious.setOnClickListener(v -> {
            if (draft.copyPrevious()) {
                refresh();
                onDraftChanged.run();
            }
        });
        host.findViewById(R.id.weekday_save).setOnClickListener(v -> save.run());
        clearDay = host.findViewById(R.id.weekday_clear);
        clearDay.setOnClickListener(v -> {
            if (draft.selection().mask == WeekdaySelection.ALL_DAYS) return;
            clear.run();
        });
        LinearLayout row = host.findViewById(R.id.weekday_choices);
        int touchSize = activity.getResources().getDimensionPixelSize(R.dimen.control_height);
        // 视觉间隔由背景内缩提供，整行等分且每项保留 48dp 触控宽度。
        row.setMinimumWidth(touchSize * choices.length);
        String[] labels = activity.getResources().getStringArray(R.array.rule_weekday_labels);
        for (int index = 0; index < choices.length; index++) {
            final int day = index + 1;
            TextView choice = new TextView(activity, null, 0, R.style.Text_App_BarAction);
            choice.setText(labels[index]);
            choice.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                    activity.getResources().getDimension(R.dimen.text_body_sm));
            choice.setGravity(android.view.Gravity.CENTER);
            choice.setMinWidth(touchSize);
            choice.setMinHeight(touchSize);
            choice.setPadding(0, 0, 0, 0);
            choice.setBackgroundResource(R.drawable.bg_weekday_choice);
            choice.setClickable(true);
            choice.setFocusable(true);
            choice.setOnClickListener(v -> select(day));
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            row.addView(choice, size);
            choices[index] = choice;
        }
        render();
    }

    /** 页面完成协调器赋值后回填当前日，避免初始化输入回调进入旧协调器。 */
    public void bindCurrentDay() { refresh(); }

    /** @param state 页面状态 @param key 参数快照键 @return 独立参数副本，不存在时为 null */
    private static RuleDayParameters readParameters(Bundle state, String key) {
        RuleGroup saved = RuleDraftState.read(state, key);
        return saved == null ? null : RuleDayParameterAdapter.capture(saved);
    }

    /** @param state 页面保存状态 @return 单日草稿及上次保存的复制来源，首次进入为 null */
    private static WeekdayRuleDraft.State readState(Bundle state) {
        if (state == null || !state.containsKey("weekday_editor_mask")) return null;
        return new WeekdayRuleDraft.State(new WeekdaySelection(state.getInt("weekday_editor_mask")),
                readParameters(state, "weekday_editor_initial"),
                readParameters(state, "weekday_editor_baseline"),
                readParameters(state, "weekday_editor_previous"),
                state.getBoolean("weekday_editor_modified"));
    }

    /** @param day 需要单独编辑的星期一至星期日 */
    private void select(int day) {
        int previousMask = draft.selection().mask;
        if (previousMask == WeekdaySelection.single(day).mask) return;
        if (!draft.select(day)) {
            Toast.makeText(activity, R.string.rule_weekday_finish_editing, Toast.LENGTH_SHORT).show();
            return;
        }
        refresh();
        animateChoice(choices[day - 1], previousMask);
    }

    /** 回填和取消期间屏蔽输入监听，避免把程序绑定误认为用户修改。 */
    private void refresh() {
        binding = true;
        try {
            refreshRules.run();
        } finally {
            binding = false;
        }
        render();
    }

    /** @return 是否接收用户修改；程序回填返回 false，不应自动开启独立配置 */
    public boolean onParametersChanged() {
        if (binding) return false;
        draft.onParametersChanged();
        return true;
    }

    /** @return 当前星期是否存在未保存修改 */
    public boolean hasPendingChanges() { return draft.hasPendingChanges(); }

    /** @return 通过当前参数校验的候选配置；失败保留输入并定位错误 */
    public RuleGroup prepareSave() {
        RuleGroup candidate = draft.prepareSave();
        RuleGroupValidator.Result result = RuleGroupValidator.validateConfiguration(candidate);
        if (!result.isValid()) {
            showError.accept(result);
            return null;
        }
        return candidate;
    }

    /** @param saved 后台事务成功保存的配置；更新复制来源和取消基线 */
    public void onSaved(RuleGroup saved) {
        draft.onSaved(saved);
        refresh();
    }

    /** @return 只清除当前星期的独立候选；原输入在存储失败时继续保留 */
    public RuleGroup prepareClear() { return draft.prepareClear(); }

    /** @param saved 当天清除成功后提交的配置，其他星期及上一份应用配置保持不变 */
    public void onCleared(RuleGroup saved) {
        draft.onCleared(saved);
        refresh();
    }

    /**
     * 定位错误星期，有未保存输入时保留当前日，交由规则卡片展示通用错误。
     * @param weekday 完整七天校验失败的星期；0 表示当前草稿或全局参数
     * @return 错误是否属于当前展示的日配置
     */
    public boolean showValidationDay(int weekday) {
        if (weekday == 0 || draft.selection().contains(weekday)) return true;
        if (!draft.select(weekday)) return false;
        refresh();
        return true;
    }

    /** @param weekday 未能切换到的错误星期 @return 可见星期入口，不标记当前日的其他参数 */
    public View validationDayTarget(int weekday) {
        return weekday >= 1 && weekday <= choices.length ? choices[weekday - 1] : title;
    }

    /** 已保存星期使用绿色标记，当前编辑星期加浅绿底与边框，两种状态独立。 */
    private void render() {
        title.setText(draft.isEveryDay() ? R.string.rule_every_day : R.string.rule_weekdays_title);
        String[] labels = activity.getResources().getStringArray(R.array.rule_weekday_full_labels);
        for (int index = 0; index < choices.length; index++) {
            boolean selected = draft.selection().contains(index + 1);
            boolean configured = draft.isConfigured(index + 1);
            TextView choice = choices[index];
            choice.setSelected(selected);
            choice.setActivated(configured);
            choice.setTextColor(activity.getColor(configured ? R.color.rule_primary : R.color.rule_text_secondary));
            String status = activity.getString(configured
                    ? R.string.rule_weekday_configured : R.string.rule_weekday_empty);
            choice.setContentDescription(activity.getString(selected
                    ? R.string.rule_weekday_selected_description : R.string.rule_weekday_unselected_description,
                    labels[index], status));
        }
        copyPrevious.setEnabled(draft.canCopyPrevious());
        copyPrevious.setAlpha(draft.canCopyPrevious() ? 1f : 0.45f);
        boolean canClear = draft.selection().mask != WeekdaySelection.ALL_DAYS;
        clearDay.setEnabled(canClear);
        clearDay.setAlpha(canClear ? 1f : 0.45f);
    }

    /** @param choice 当前点击的日期控件 @param previousMask 切换前的选择，用于确定左右移动方向 */
    private void animateChoice(TextView choice, int previousMask) {
        choice.animate().cancel();
        choice.setTranslationX(0);
        choice.setAlpha(1f);
        if (!ValueAnimator.areAnimatorsEnabled()) return;
        int previousDay = Integer.numberOfTrailingZeros(previousMask);
        int selectedDay = Integer.numberOfTrailingZeros(draft.selection().mask);
        float direction = selectedDay >= previousDay ? 1f : -1f;
        choice.setTranslationX(activity.getResources().getDisplayMetrics().density * 4f * direction);
        choice.setAlpha(0.7f);
        choice.animate().translationX(0).alpha(1f).setDuration(180)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    /** @param state 接收当前星期、未保存参数、取消基线及上一份保存配置的页面状态 */
    public void saveState(Bundle state) {
        WeekdayRuleDraft.State saved = draft.snapshot();
        state.putInt("weekday_editor_mask", saved.selection.mask);
        state.putBoolean("weekday_editor_modified", saved.modified);
        RuleDraftState.write(state, "weekday_editor_initial",
                RuleDayParameterAdapter.withParameters(group, saved.initialParameters));
        RuleDraftState.write(state, "weekday_editor_baseline",
                RuleDayParameterAdapter.withParameters(group, saved.baseline));
        if (saved.previous != null) RuleDraftState.write(state, "weekday_editor_previous",
                RuleDayParameterAdapter.withParameters(group, saved.previous));
    }

    /** 页面退出或重绑时取消全部日期控件动画。 */
    public void close() {
        for (TextView choice : choices) choice.animate().cancel();
    }
}
