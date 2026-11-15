package github.vhoom.zhijian.ui.component.input;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;

import github.vhoom.zhijian.R;

/**
 * 输入错误的统一展示适配器：只使用输入控件自己的下方文字，不使用原生错误图标。
 *
 * 维护日期：2026年11月13日
 */
public final class InputError {
    /** 静态适配器不创建实例。 */
    private InputError() { }

    /** @param field 编辑框或时间选择控件；独立未挂载的测试控件允许没有提示容器 */
    public static void bind(TextView field) {
        if (field.getTag(R.id.input_error_binding) != null) return;
        ViewParent parent = field.getParent();
        while (parent instanceof View && !(parent instanceof InputFieldLayout)) parent = parent.getParent();
        if (!(parent instanceof InputFieldLayout)) return;
        TextView error = ((InputFieldLayout) parent).errorCaption();
        field.setTag(R.id.input_error_binding, error);
        field.addTextChangedListener(new TextWatcher() {
            /** @param text 原文本 @param start 替换起点 @param count 替换长度 @param after 新长度 */
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            /** @param text 当前文本 @param start 替换起点 @param before 原长度 @param count 新长度 */
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { }
            /** @param text 当前完整输入；编辑和程序回填均清除旧提示 */
            @Override public void afterTextChanged(Editable text) { clear(field); }
        });
    }

    /** @param field 错误所属控件 @param message 可读提示，不改变输入正文 */
    public static void show(TextView field, CharSequence message) {
        bind(field);
        TextView error = caption(field);
        if (error == null) throw new IllegalStateException("Input must have its own error caption");
        error.setText(message);
        error.setVisibility(View.VISIBLE);
    }

    /** @param field 清除该控件下方的旧错误，不影响其他输入 */
    public static void clear(TextView field) {
        TextView error = caption(field);
        if (error != null) {
            error.setVisibility(View.GONE);
            error.setText("");
        }
    }

    /** @param root 当前表单或规则区域，清除其下方提示而不改变草稿 */
    public static void clearAll(View root) {
        if (root instanceof InputFieldLayout) {
            TextView error = ((InputFieldLayout) root).errorCaption();
            error.setVisibility(View.GONE);
            error.setText("");
        } else if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int index = 0; index < group.getChildCount(); index++) clearAll(group.getChildAt(index));
        }
    }

    /** @param field 已绑定的输入控件 @return 自己的提示文字，独立无容器控件返回 null */
    private static TextView caption(TextView field) {
        Object value = field.getTag(R.id.input_error_binding);
        return value instanceof TextView ? (TextView) value : null;
    }
}
