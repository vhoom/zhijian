package github.vhoom.zhijian.ui.component.rule;

import android.app.Activity;
import android.app.Dialog;
import android.app.TimePickerDialog;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import github.vhoom.zhijian.R;
import github.vhoom.zhijian.ui.component.input.InputError;
import github.vhoom.zhijian.ui.component.dialog.AppDialogs;
import github.vhoom.zhijian.data.model.policy.RuleGroup;
import github.vhoom.zhijian.data.model.policy.RuleGroupValidator;
import github.vhoom.zhijian.data.model.policy.RuleInputLimits;
import github.vhoom.zhijian.data.model.policy.RuleWindowValidation;
import github.vhoom.zhijian.data.model.policy.RuleType;
import github.vhoom.zhijian.data.model.policy.RuleWindow;
import github.vhoom.zhijian.data.store.RuleGroupStore;
import github.vhoom.zhijian.util.time.DecimalDuration;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 每条规则的开关和参数在同一张卡内，规则组与单应用编辑共用。只修改传入的草稿。
 *
 * 维护日期：2026年11月14日
 */
public final class RuleConditionsEditor {
    /** 配置页的统一展示顺序，独立于规则类型枚举及运行判定优先级。 */
    private static final List<RuleType> DISPLAY_ORDER = Collections.unmodifiableList(Arrays.asList(
            RuleType.DAILY_LIMIT,
            RuleType.DAILY_COUNT,
            RuleType.ROLLING_COUNT,
            RuleType.CONTINUOUS,
            RuleType.WINDOW_LIMIT,
            RuleType.WINDOW_COUNT,
            RuleType.WINDOW_CONTINUOUS,
            RuleType.WINDOW_BAN));

    private final Activity activity;
    private final RuleGroup draft;
    private final Runnable changed;
    private final Map<RuleType, View> cards = new EnumMap<>(RuleType.class);
    private final Map<RuleType, LinearLayout> bodies = new EnumMap<>(RuleType.class);
    private final Map<RuleType, EditText> fields = new EnumMap<>(RuleType.class);
    private final Map<RuleType, Map<String, View>> windowRows = new EnumMap<>(RuleType.class);
    /** 控件键包含类型与时段身份，原始文本不依赖重复的 Android 资源 ID。 */
    private final Map<String, RuleInputBinding> manualInputs = new LinkedHashMap<>();
    private EditText interruptionPauseField;
    private EditText frequencyCountField;
    private Dialog timeDialog;

    /**
     * 创建 RuleConditionsEditor 实例，并初始化本次操作依赖或不可变字段。
     *
     * @param activity 执行本次界面操作的 Activity
     * @param container 负责承载动态编辑组件的容器
     * @param draft 尚未提交的可编辑规则草稿
     * @param changed 规则草稿变化后通知所在页面的回调
     */
    public RuleConditionsEditor(Activity activity, LinearLayout container,
            RuleGroup draft, Runnable changed) {
        this.activity = activity;
        this.draft = draft;
        this.changed = changed;
        container.removeAllViews();
        for (RuleType type : DISPLAY_ORDER) {
            View card = activity.getLayoutInflater().inflate(R.layout.item_rule_condition, container, false);
            // 动态行有重复资源 id；草稿单独保存，避免系统按 id 把一个输入值恢复到其他行。
            card.setSaveFromParentEnabled(false);
            card.setTag(type);
            cards.put(type, card);
            ((TextView) card.findViewById(R.id.condition_title)).setText(type.nameRes);
            ((TextView) card.findViewById(R.id.condition_hint)).setText(hint(type));
            LinearLayout body = card.findViewById(R.id.condition_body);
            configureParameterPanel(body, type);
            bodies.put(type, body);
            Switch toggle = card.findViewById(R.id.type_check);
            toggle.setContentDescription(activity.getString(type.nameRes));
            toggle.setChecked(draft.has(type));
            card.findViewById(R.id.condition_header).setOnClickListener(v -> toggle.setChecked(!toggle.isChecked()));
            if (type.isWindow()) {
                windowRows.put(type, new HashMap<>());
                renderWindows(type);
            } else {
                buildParameter(type, body);
            }
            toggle.setOnCheckedChangeListener((button, checked) -> {
                if (checked) {
                    draft.types.add(type);
                    if (type.isWindow() && draft.windowsFor(type).isEmpty()) addWindow(type);
                } else {
                    draft.types.remove(type);
                }
                updateCard(type);
                onChanged();
            });
            updateCard(type);
            container.addView(card);
        }
    }

    /**
     * 按开关状态展开或收起当前规则的参数区域。
     *
     * @param type 当前规则条件的类型
     */
    private void updateCard(RuleType type) {
        boolean selected = draft.has(type);
        bodies.get(type).setVisibility(selected ? View.VISIBLE : View.GONE);
    }

    /**
     * 多项组合使用浅灰面板，单项参数保持透明且不增加容器内边距。
     * @param body 当前规则的展开参数容器
     * @param type 决定当前规则是否包含多个参数或时段操作
     */
    private void configureParameterPanel(LinearLayout body, RuleType type) {
        boolean grouped = type == RuleType.CONTINUOUS || type == RuleType.ROLLING_COUNT || type.isWindow();
        int padding = grouped ? activity.getResources().getDimensionPixelSize(R.dimen.rule_window_padding) : 0;
        if (grouped) body.setBackgroundResource(R.drawable.bg_rule_panel);
        else body.setBackground(null);
        body.setPadding(padding, padding, padding, padding);
        LinearLayout.LayoutParams size = (LinearLayout.LayoutParams) body.getLayoutParams();
        size.topMargin = activity.getResources().getDimensionPixelSize(R.dimen.space_sm);
        body.setLayoutParams(size);
    }

    /**
     * 为当前规则条件创建对应参数输入组件。
     *
     * @param type 当前规则条件的类型
     * @param body 当前条件卡片的参数容器
     */
    private void buildParameter(RuleType type, LinearLayout body) {
        if (type == RuleType.CONTINUOUS) {
            buildContinuousParameters(body);
            return;
        }
        if (type == RuleType.ROLLING_COUNT) {
            buildFrequencyParameters(body);
            return;
        }
        View row = activity.getLayoutInflater().inflate(R.layout.item_rule_value, body, false);
        int label = type == RuleType.DAILY_COUNT ? R.string.rule_value_count
                : R.string.rule_value_daily;
        ((TextView) row.findViewById(R.id.param_label)).setText(label);
        ((TextView) row.findViewById(R.id.param_unit)).setText(type == RuleType.DAILY_COUNT
                ? R.string.rule_unit_times : R.string.rule_unit_minutes);
        EditText field = row.findViewById(R.id.param_field);
        configureCompactField(field);
        field.setContentDescription(activity.getString(type.nameRes));
        fields.put(type, field);
        NumberRow number;
        if (type == RuleType.DAILY_COUNT) {
            number = RuleParameterFactory.bindCount(field, draft.count, value -> {
                draft.count = Math.toIntExact(value);
                onChanged();
            });
        } else {
            number = RuleParameterFactory.bindDuration(field, draft.dailyLimitMillis, value -> {
                draft.dailyLimitMillis = value;
                onChanged();
            });
            if (draft.has(type) && draft.dailyLimitMillis == 0L) number.apply(0L);
        }
        bindManualInput(type.name() + ":value", field, number,
                () -> type == RuleType.DAILY_COUNT ? RuleInputLimits.count() : RuleInputLimits.usage(type, 0L), type);
        body.addView(row);
    }

    /** @param body 全天频率限次卡片的参数容器；始终只装配一条窗口与次数配置 */
    private void buildFrequencyParameters(LinearLayout body) {
        View row = activity.getLayoutInflater().inflate(R.layout.view_rule_frequency_parameters, body, false);
        EditText duration = row.findViewById(R.id.frequency_window_field);
        frequencyCountField = row.findViewById(R.id.frequency_count_field);
        configureCompactField(duration);
        configureCompactField(frequencyCountField);
        duration.setContentDescription(activity.getString(R.string.rule_frequency_window_description));
        frequencyCountField.setContentDescription(activity.getString(R.string.rule_frequency_count_description));
        fields.put(RuleType.ROLLING_COUNT, duration);
        NumberRow durationInput = RuleParameterFactory.bindDuration(duration, draft.frequencyWindowMillis, value -> {
            draft.frequencyWindowMillis = value;
            onChanged();
        });
        NumberRow countInput = RuleParameterFactory.bindCount(frequencyCountField, draft.frequencyCount, value -> {
            draft.frequencyCount = Math.toIntExact(value);
            onChanged();
        });
        bindManualInput(RuleType.ROLLING_COUNT.name() + ":value", duration, durationInput,
                () -> RuleInputLimits.usage(RuleType.ROLLING_COUNT, 0L), RuleType.ROLLING_COUNT);
        bindManualInput(RuleType.ROLLING_COUNT.name() + ":count", frequencyCountField, countInput,
                RuleInputLimits::count, RuleType.ROLLING_COUNT);
        body.addView(row);
    }

    /**
     * 将全天连续使用与休息参数装配到同一行，分别绑定原有草稿字段。
     *
     * @param body 连续提醒卡片承载参数的容器
     */
    private void buildContinuousParameters(LinearLayout body) {
        View parameters = activity.getLayoutInflater().inflate(R.layout.view_rule_continuous_parameters, body, false);
        View usage = parameters.findViewById(R.id.window_limit_row);
        configureCompactParameter(usage, R.string.rule_value_continuous);
        EditText field = usage.findViewById(R.id.param_field);
        field.setContentDescription(activity.getString(R.string.rule_type_continuous));
        fields.put(RuleType.CONTINUOUS, field);
        NumberRow number = RuleParameterFactory.bindDuration(field, draft.continuousLimitMillis, value -> {
            draft.continuousLimitMillis = value;
            onChanged();
        });
        bindManualInput(RuleType.CONTINUOUS.name() + ":value", field, number,
                () -> RuleInputLimits.usage(RuleType.CONTINUOUS, 0L), RuleType.CONTINUOUS);
        bindInterruptionPause(parameters.findViewById(R.id.window_pause_row));
        body.addView(parameters);
    }

    /**
     * 使用短标签与按内容收窄的数字框展示单个使用或休息参数。
     * @param row 使用或休息参数行
     * @param label 简短标签的字符串资源
     */
    private void configureCompactParameter(View row, int label) {
        ((TextView) row.findViewById(R.id.param_label)).setText(label);
        configureCompactField(row.findViewById(R.id.param_field));
    }

    /**
     * 绑定内容尺寸与透明触控区域，数字框保持独立编辑。
     * @param field 时间显示框或使用休息输入框
     */
    private void configureCompactField(TextView field) {
        CompactRuleField.bind(field);
    }

    /**
     * 为全天连续提醒绑定独立的休息分钟参数。
     * @param row 横向组合中的休息参数行
     */
    private void bindInterruptionPause(View row) {
        configureCompactParameter(row, R.string.rule_value_interruption_pause);
        interruptionPauseField = row.findViewById(R.id.param_field);
        interruptionPauseField.setContentDescription(activity.getString(
                R.string.rule_value_interruption_pause));
        NumberRow number = RuleParameterFactory.bindDuration(interruptionPauseField,
                draft.interruptionPauseMillis, value -> {
            draft.interruptionPauseMillis = value;
            onChanged();
        });
        bindManualInput(RuleType.CONTINUOUS.name() + ":rest", interruptionPauseField, number,
                RuleInputLimits::rest, RuleType.CONTINUOUS);
    }

    /**
     * 创建带稳定身份的新时段并加入当前草稿。
     *
     * @param type 当前规则条件的类型
     */
    private void addWindow(RuleType type) {
        draft.windows.add(RuleWindow.create(type, RuleGroupStore.DEFAULT_START, RuleGroupStore.DEFAULT_END));
        renderWindows(type);
    }

    /**
     * 按条件类型分别渲染时段列表。
     *
     * @param type 当前规则条件的类型
     */
    private void renderWindows(RuleType type) {
        String prefix = type.name() + ":";
        Bundle previousText = new Bundle();
        for (Map.Entry<String, RuleInputBinding> input : manualInputs.entrySet()) {
            if (input.getKey().startsWith(prefix)) input.getValue().save(previousText, input.getKey());
        }
        manualInputs.keySet().removeIf(key -> key.startsWith(prefix));
        LinearLayout body = bodies.get(type);
        body.removeAllViews();
        windowRows.get(type).clear();
        List<RuleWindow> windows = draft.windowsFor(type);
        for (int i = 0; i < windows.size(); i++) {
            RuleWindow window = windows.get(i);
            View row = activity.getLayoutInflater().inflate(R.layout.item_rule_window, body, false);
            configureWindowParameterLayout(row, type);
            if (i > 0) {
                LinearLayout.LayoutParams size = (LinearLayout.LayoutParams) row.getLayoutParams();
                size.topMargin = activity.getResources().getDimensionPixelSize(R.dimen.space_md);
                row.setLayoutParams(size);
            }
            windowRows.get(type).putIfAbsent(window.id, row);
            bindTimes(row, window);
            row.findViewById(R.id.tv_start).setOnClickListener(v -> pickTime(row, window, true));
            row.findViewById(R.id.tv_end).setOnClickListener(v -> pickTime(row, window, false));
            View remove = row.findViewById(R.id.window_remove);
            remove.setContentDescription(activity.getString(R.string.rule_remove_window,
                    activity.getString(type.nameRes), i + 1));
            remove.setOnClickListener(v -> {
                draft.windows.remove(window);
                renderWindows(type);
                onChanged();
            });
            View limit = row.findViewById(R.id.window_limit_row);
            row.findViewById(R.id.window_parameters).setVisibility(
                    type == RuleType.WINDOW_CONTINUOUS ? View.VISIBLE : View.GONE);
            limit.setVisibility(type != RuleType.WINDOW_BAN ? View.VISIBLE : View.GONE);
            if (type != RuleType.WINDOW_BAN) {
                configureCompactParameter(limit, type.isCount()
                        ? R.string.rule_value_window_count : R.string.rule_value_continuous);
                EditText field = limit.findViewById(R.id.param_field);
                field.setContentDescription(activity.getString(type.isCount()
                        ? R.string.rule_window_count_description : type.isReminder()
                        ? R.string.rule_window_continuous_description : R.string.rule_window_limit_description, i + 1));
                if (type.isCount()) {
                    ((TextView) limit.findViewById(R.id.param_unit)).setText(R.string.rule_unit_times);
                    NumberRow number = RuleParameterFactory.bindCount(field, window.limitCount, value -> {
                        window.limitCount = Math.toIntExact(value);
                        onChanged();
                    });
                    bindManualInput(windowKey(window, "count"), field, number, RuleInputLimits::count, type);
                } else {
                    NumberRow number = RuleParameterFactory.bindDuration(field, window.limitDurationMillis, value -> {
                        window.limitDurationMillis = value;
                        onChanged();
                    });
                    bindManualInput(windowKey(window, "value"), field, number,
                            () -> RuleInputLimits.usage(type, draft.has(type) && RuleWindowValidation.hasValidEndpoints(window)
                                    ? RuleWindowValidation.durationMinutes(window) * DecimalDuration.MINUTE_MILLIS : 0L), type);
                }
            }
            View pause = row.findViewById(R.id.window_pause_row);
            pause.setVisibility(type == RuleType.WINDOW_CONTINUOUS ? View.VISIBLE : View.GONE);
            if (type == RuleType.WINDOW_CONTINUOUS) bindWindowPause(pause, window, i + 1);
            body.addView(row);
        }
        Button add = new Button(activity, null, 0, R.style.Widget_App_Button_RuleAddWindow);
        add.setId(R.id.btn_add_window);
        add.setText(R.string.rule_add_window);
        add.setContentDescription(activity.getString(R.string.rule_add_window_description, activity.getString(type.nameRes)));
        LinearLayout.LayoutParams addSize = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        addSize.topMargin = activity.getResources().getDimensionPixelSize(R.dimen.space_xs);
        add.setLayoutParams(addSize);
        add.setOnClickListener(v -> {
            addWindow(type);
            onChanged();
        });
        body.addView(add);
        for (Map.Entry<String, RuleInputBinding> input : manualInputs.entrySet()) {
            if (input.getKey().startsWith(prefix)) input.getValue().restore(previousText, input.getKey());
        }
    }

    /**
     * 将时段限时的额度移动到起止时间之后，复用同一个输入框和草稿绑定。
     * @param row 当前时段的完整编辑行
     * @param type 决定是否采用单行展示的规则类型
     */
    private void configureWindowParameterLayout(View row, RuleType type) {
        if (type != RuleType.WINDOW_LIMIT && type != RuleType.WINDOW_COUNT) return;
        LinearLayout parameters = row.findViewById(R.id.window_parameters);
        View limit = parameters.findViewById(R.id.window_limit_row);
        parameters.removeView(limit);
        LinearLayout timeRow = row.findViewById(R.id.window_time_row);
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        size.setMarginStart(activity.getResources().getDimensionPixelSize(R.dimen.space_xs));
        timeRow.addView(limit, timeRow.indexOfChild(row.findViewById(R.id.window_action_spacer)), size);
    }

    /** @param row 独立休息参数行 @param window 当前窗口草稿 @param position 窗口序号 */
    private void bindWindowPause(View row, RuleWindow window, int position) {
        configureCompactParameter(row, R.string.rule_value_interruption_pause);
        EditText field = row.findViewById(R.id.param_field);
        field.setContentDescription(activity.getString(R.string.rule_window_pause_description, position));
        NumberRow number = RuleParameterFactory.bindDuration(field, window.interruptionPauseMillis, value -> {
            window.interruptionPauseMillis = value;
            onChanged();
        });
        bindManualInput(windowKey(window, "rest"), field, number, RuleInputLimits::rest, window.type);
    }

    /** @param window 当前时段 @param role 参数角色 @return 不依赖资源 ID 的稳定输入键 */
    private static String windowKey(RuleWindow window, String role) {
        return window.type.name() + ":" + window.id + ":" + role;
    }

    /** @param key 稳定控件键 @param field 原输入框 @param number 数值绑定器
     * @param range 约束工厂 @param type 决定必填状态的规则类型 */
    private void bindManualInput(String key, EditText field, NumberRow number,
            Supplier<RuleInputLimits.Range> range, RuleType type) {
        manualInputs.put(key, new RuleInputBinding(field, number, range, () -> draft.has(type)));
    }

    /** @return 保存时第一个原始文本错误；合法时为 null */
    public RuleInputBinding.Issue validateManualInputs() {
        for (RuleInputBinding input : manualInputs.values()) {
            RuleInputBinding.Issue issue = input.validate();
            if (issue != null) return issue;
        }
        return null;
    }

    /** @param state 接收当前星期原始文本的系统状态容器 */
    public void saveManualInputs(Bundle state) {
        Bundle text = new Bundle();
        for (Map.Entry<String, RuleInputBinding> input : manualInputs.entrySet()) {
            input.getValue().save(text, input.getKey());
        }
        state.putBundle("rule_manual_inputs", text);
    }

    /** @param state 当前页面重建状态，首次进入时为 null */
    public void restoreManualInputs(Bundle state) {
        Bundle text = state == null ? null : state.getBundle("rule_manual_inputs");
        if (text == null) return;
        for (Map.Entry<String, RuleInputBinding> input : manualInputs.entrySet()) {
            input.getValue().restore(text, input.getKey());
        }
    }

    /**
     * 绑定时段开始、结束时间的显示与选择动作。
     *
     * @param row 待绑定或转换的数据库或列表行
     * @param window 带稳定标识和独立额度的时间窗口
     */
    private void bindTimes(View row, RuleWindow window) {
        TextView start = row.findViewById(R.id.tv_start);
        TextView end = row.findViewById(R.id.tv_end);
        configureCompactField(start);
        configureCompactField(end);
        start.setText(time(window.startMinute));
        end.setText(window.endMinute < window.startMinute
                ? activity.getString(R.string.rule_time_next_day_display, time(window.endMinute)) : time(window.endMinute));
        start.setContentDescription(activity.getString(R.string.rule_start_time_a11y, start.getText()));
        end.setContentDescription(activity.getString(R.string.rule_end_time_a11y, end.getText()));
    }

    /**
     * 打开时间选择器并更新对应时段端点。
     *
     * @param row 待绑定或转换的数据库或列表行
     * @param window 带稳定标识和独立额度的时间窗口
     * @param start 变化起点或区间开始值
     */
    private void pickTime(View row, RuleWindow window, boolean start) {
        dismissTimeDialog();
        int value = start ? window.startMinute : window.endMinute;
        timeDialog = new TimePickerDialog(activity, R.style.Theme_Zhijian_Dialog, (view, hour, minute) -> {
            if (start) window.startMinute = hour * 60 + minute;
            else window.endMinute = hour * 60 + minute;
            bindTimes(row, window);
            onChanged();
        }, value / 60, value % 60, true);
        AppDialogs.show(timeDialog);
    }

    /**
     * 清除上一轮草稿校验留下的错误提示。
     */
    public void clearErrors() {
        for (View card : cards.values()) {
            card.findViewById(R.id.condition_error).setVisibility(View.GONE);
            InputError.clearAll(card);
        }
    }

    /**
     * 把校验错误显示在对应条件或时段输入位置。
     *
     * @param result 异步规则检查或模型校验的结果
     * @return 需要定位的可见输入或规则操作
     */
    public View showError(RuleGroupValidator.Result result) {
        return showError(result, true);
    }

    /**
     * 定位当前星期的错误；其他星期或收起的参数使用卡片提示，保留当前草稿。
     * @param result 包含类型、参数角色及时段身份的保存校验结果
     * @param currentDay 错误是否属于当前展示的星期
     * @return 可见的修改入口；输入缺失时回退到对应规则开关
     */
    public View showError(RuleGroupValidator.Result result, boolean currentDay) {
        if (result == null || result.isValid()) return null;
        RuleType type = result.type == null ? RuleType.DAILY_LIMIT : result.type;
        int message = RuleValidationFeedback.message(result.tooLarge);
        if (!currentDay || result.type == null || !draft.has(type)) {
            return showCardError(type, message);
        }
        if (result.windowId != null) {
            Map<String, View> rows = windowRows.get(type);
            View row = rows == null ? null : rows.get(result.windowId);
            if (row == null) return showCardError(type, message);
            EditText field = null;
            if (result.field == RuleGroupValidator.Field.REST) {
                View pause = row.findViewById(R.id.window_pause_row);
                if (pause != null) field = pause.findViewById(R.id.param_field);
            } else if (result.field == RuleGroupValidator.Field.COUNT
                    || result.error == RuleGroupValidator.Error.PARAM_REQUIRED
                    || result.error == RuleGroupValidator.Error.WINDOW_LIMIT_EXCEEDS
                    || result.error == RuleGroupValidator.Error.VALUE_TOO_LARGE) {
                field = row.findViewById(R.id.param_field);
            }
            if (field != null) return RuleValidationFeedback.markInputError(field, message);
            TextView time = timeErrorTarget(row, type, result);
            return time == null ? showCardError(type, message)
                    : RuleValidationFeedback.markInputError(time, message);
        }
        if (result.error == RuleGroupValidator.Error.WINDOW_REQUIRED) {
            showCardError(type, message);
            View add = bodies.get(type).findViewById(R.id.btn_add_window);
            return add == null ? cards.get(type).findViewById(R.id.type_check) : add;
        }
        EditText field = result.field == RuleGroupValidator.Field.REST ? interruptionPauseField
                : result.field == RuleGroupValidator.Field.COUNT && type == RuleType.ROLLING_COUNT
                ? frequencyCountField : fields.get(type);
        if (field != null) return RuleValidationFeedback.markInputError(field, message);
        return showCardError(type, message);
    }

    /** @param type 错误归属的规则 @param message 通用提示资源 @return 可见规则开关 */
    private View showCardError(RuleType type, int message) {
        View card = cards.get(type);
        if (card == null) return null;
        showErrorText(card.findViewById(R.id.condition_error), message);
        return card.findViewById(R.id.type_check);
    }

    /**
     * 端点错误归属实际无效端点；相同起止时间提示结束框，时段冲突提示开始框。
     * @param row 错误时段的编辑行
     * @param type 当前时段规则类型
     * @param result 保存校验结果
     * @return 错误所属时间控件
     */
    private TextView timeErrorTarget(View row, RuleType type, RuleGroupValidator.Result result) {
        if (result.error == RuleGroupValidator.Error.WINDOW_INVALID) {
            for (RuleWindow window : draft.windowsFor(type)) {
                if (window.id.equals(result.windowId) && window.startMinute >= 0 && window.startMinute < 1440) {
                    return row.findViewById(R.id.tv_end);
                }
            }
        }
        return row.findViewById(R.id.tv_start);
    }

    /** 仅关闭时间选择器，不释放仍在编辑的原始输入绑定。 */
    private void dismissTimeDialog() {
        if (timeDialog != null) {
            timeDialog.dismiss();
            timeDialog = null;
        }
    }

    /**
     * 释放当前组件的观察、数据或弹窗状态。
     */
    public void close() {
        dismissTimeDialog();
        manualInputs.clear();
    }

    /**
     * 草稿参数变化后更新相关界面提示。
     */
    private void onChanged() {
        clearErrors();
        changed.run();
    }

    /**
     * 在指定输入位置显示校验失败的原因。
     *
     * @param view 当前接收点击或触摸事件的视图
     * @param message 需要记录或显示的诊断说明
     */
    private void showErrorText(TextView view, int message) {
        if (view == null) return;
        view.setText(message);
        view.setVisibility(View.VISIBLE);
        view.announceForAccessibility(view.getText());
    }

    /**
     * 把一天内的分钟值格式化为二十四小时制时间。
     *
     * @param minute 一天内对应的分钟值
     */
    private static String time(int minute) { return String.format(Locale.US, "%02d:%02d", minute / 60, minute % 60); }

    /**
     * 按条件类型返回适合当前参数的输入提示。
     *
     * @param type 当前规则条件的类型
     */
    private static int hint(RuleType type) {
        switch (type) {
            case DAILY_LIMIT: return R.string.rule_hint_daily;
            case DAILY_COUNT: return R.string.rule_hint_count;
            case WINDOW_LIMIT: return R.string.rule_hint_window_limit;
            case WINDOW_BAN: return R.string.rule_hint_window_ban;
            case WINDOW_CONTINUOUS: return R.string.rule_hint_window_continuous;
            case WINDOW_COUNT: return R.string.rule_hint_window_count;
            case ROLLING_COUNT: return R.string.rule_hint_frequency_count;
            default: return R.string.rule_hint_continuous;
        }
    }
}
