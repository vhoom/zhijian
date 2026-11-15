package github.vhoom.zhijian.ui.component.settings;

import android.graphics.Rect;
import android.view.View;
import android.widget.EditText;
import github.vhoom.zhijian.ui.component.input.InputError;
import github.vhoom.zhijian.R;

/**
 * 底部参数弹窗的校验适配器，错误只显示在所属输入下方，复用明暗语义色。
 *
 * 维护日期：2026年11月13日
 */
public final class SettingsValidation {
    /** 提示工具不创建实例。 */
    private SettingsValidation() { }

    /**
     * 显示本表单的错误并聚焦第一处无效输入，不改变草稿。
     * @param content 当前表单根视图
     * @param field 第一处无效输入
     * @param message 可读的校验说明
     */
    public static void show(View content, EditText field, CharSequence message) {
        InputError.show(field, message);
        field.requestFocus();
        View caption = (View) field.getTag(R.id.input_error_binding);
        View column = (View) caption.getParent();
        column.post(() -> {
            if (content.isAttachedToWindow() && field.hasFocus() && caption.isShown()) {
                column.requestRectangleOnScreen(new Rect(0, 0, column.getWidth(), column.getHeight()), true);
            }
        });
    }

    /**
     * 输入改变后清除上一轮提示，避免已修正的值仍显示旧错误。
     * @param content 当前表单根视图
     */
    public static void clear(View content) {
        InputError.clearAll(content);
    }
}
