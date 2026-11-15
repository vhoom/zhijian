package github.vhoom.zhijian.ui.component.settings;

import android.text.Editable;
import android.text.TextWatcher;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;

import github.vhoom.zhijian.R;
import github.vhoom.zhijian.ui.component.input.ManualInput;
import github.vhoom.zhijian.util.format.UnsignedInteger;
import github.vhoom.zhijian.util.time.DecimalDuration;

import java.util.function.LongFunction;
import java.util.function.LongSupplier;
import java.util.function.IntSupplier;
import java.util.function.ToLongFunction;

/**
 * 数字输入适配器：按钮按整数单位步进，手动时长输入保留一位小数。
 *
 * 维护日期：2026年11月13日
 */
public final class SettingsNumberStepper {
    /** 控件内部的动作角色标记，可跨实例复用且不依赖子视图排列顺序。 */
    private static final String DECREASE_TAG = "settings_step_decrease";
    private static final String INCREASE_TAG = "settings_step_increase";
    private final EditText field;
    private final View decrease;
    private final View increase;
    private final long minimum;
    private final LongSupplier maximum;
    private final long step;
    private final LongFunction<String> format;
    private final ToLongFunction<String> parse;
    private final Runnable changed;

    /**
     * 将数字策略绑定到输入框所在的步进控件，不接入持久化存储。
     *
     * @param field 可手动填写和粘贴数字的输入框
     * @param value 已保存的初始值
     * @param minimum 允许的最小值
     * @param maximum 当前允许的最大值供应器
     * @param step 每次按钮操作的增减值
     * @param format 将内部数值转换成输入文字的策略
     * @param parse 将输入文字转换成内部数值的策略
     * @param changed 可选的草稿变化通知
     */
    private SettingsNumberStepper(EditText field, long value, long minimum, LongSupplier maximum,
            long step, LongFunction<String> format, ToLongFunction<String> parse, Runnable changed) {
        this.field = field;
        this.minimum = minimum;
        this.maximum = maximum;
        this.step = step;
        this.format = format;
        this.parse = parse;
        this.changed = changed;
        View parent = (View) field.getParent();
        decrease = parent.findViewWithTag(DECREASE_TAG);
        increase = parent.findViewWithTag(INCREASE_TAG);
        CharSequence label = field.getContentDescription();
        decrease.setContentDescription(field.getContext().getString(R.string.settings_step_decrease, label));
        increase.setContentDescription(field.getContext().getString(R.string.settings_step_increase, label));
        field.setText(format.apply(value));
        decrease.setOnClickListener(view -> change(-step));
        increase.setOnClickListener(view -> change(step));
        field.addTextChangedListener(new TextWatcher() {
            /** 编辑前无需缓存草稿。
             * @param text 原输入
             * @param start 变更位置
             * @param count 被替换字数
             * @param after 新增字数
             */
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            /** 编辑时的更新集中在完整文字可用后执行。
             * @param text 当前输入
             * @param start 变更位置
             * @param before 被替换字数
             * @param count 新增字数
             */
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { }
            /** 刷新按钮边界与依赖当前草稿的摘要。
             * @param text 当前完整输入
             */
            @Override public void afterTextChanged(Editable text) { refresh(); }
        });
        refresh();
    }

    /**
     * 创建题量步进控件，每次增减一题。
     * @param field 题量输入框
     * @param value 当前题量
     * @param minimum 最小题量
     * @param maximum 最大题量
     * @param changed 可选的草稿变化通知
     */
    public static void bindCount(EditText field, int value, int minimum, int maximum, Runnable changed) {
        bindCount(field, value, minimum, () -> maximum, changed);
    }

    /**
     * 按当前选中词库的实际容量装配题量按钮，不修改超容量草稿。
     * @param field 题量输入框
     * @param value 当前题量
     * @param minimum 最小题量
     * @param maximum 实际词条容量供应器
     * @param changed 草稿变化通知
     * @return 可在切换词库后刷新边界的控件
     */
    public static SettingsNumberStepper bindCount(EditText field, int value, int minimum,
            IntSupplier maximum, Runnable changed) {
        ManualInput.configure(field, InputType.TYPE_CLASS_NUMBER);
        return new SettingsNumberStepper(field, value, minimum, () -> maximum.getAsInt(), 1L,
                Long::toString, UnsignedInteger::parse, changed);
    }

    /**
     * 创建时长步进控件，每次增减一秒，手动输入的小数部分保持不变。
     * @param field 秒数输入框
     * @param value 当前毫秒数
     * @param minimum 最小毫秒数
     * @param maximum 最大毫秒数
     * @param changed 可选的草稿变化通知
     */
    public static void bindDuration(EditText field, long value, long minimum, long maximum,
            Runnable changed) {
        ManualInput.configure(field, InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        new SettingsNumberStepper(field, value, minimum, () -> maximum, DecimalDuration.SECOND_MILLIS,
                millis -> DecimalDuration.format(millis, DecimalDuration.SECOND_MILLIS),
                text -> DecimalDuration.parseMillis(text, DecimalDuration.SECOND_MILLIS), changed);
    }

    /** 读取当前草稿，非法数字用负值标识，留给保存时显示具体错误。 */
    private long value() {
        try {
            return parse.applyAsLong(field.getText().toString());
        } catch (IllegalArgumentException invalid) {
            return -1L;
        }
    }

    /** 更新边界按钮的可用状态，并通知依赖草稿的展示。 */
    private void refresh() {
        long value = value();
        long maximum = this.maximum.getAsLong();
        boolean valid = value >= minimum && value <= maximum;
        decrease.setEnabled(valid && value > minimum);
        increase.setEnabled(valid && value < maximum);
        decrease.setAlpha(decrease.isEnabled() ? 1f : 0.35f);
        increase.setAlpha(increase.isEnabled() ? 1f : 0.35f);
        if (changed != null) changed.run();
    }

    /**
     * 在合法草稿上执行一次增减，达到边界时停止。
     * @param delta 本次增减的内部数值
     */
    private void change(long delta) {
        long value = value();
        long maximum = this.maximum.getAsLong();
        if (value < minimum || value > maximum) return;
        field.setText(format.apply(Math.max(minimum, Math.min(maximum, value + delta))));
    }

    /** 词库选择变化后刷新按钮和摘要，保留当前输入原文。 */
    public void refreshBounds() { refresh(); }
}
