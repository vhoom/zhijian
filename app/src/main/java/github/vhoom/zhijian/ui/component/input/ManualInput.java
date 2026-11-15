package github.vhoom.zhijian.ui.component.input;

import android.text.InputFilter;
import android.text.method.TextKeyListener;
import android.widget.EditText;

/**
 * 手动输入配置工厂：数字键盘仅提供输入便利，粘贴原文交由保存校验解释。
 *
 * 维护日期：2026年11月13日
 */
public final class ManualInput {
    /** 工厂通过静态方法配置已有控件。 */
    private ManualInput() { }

    /** @param field 用户可编辑的数字框 @param inputType 数字或带小数的键盘类型 */
    public static void configure(EditText field, int inputType) {
        field.setFilters(new InputFilter[0]);
        field.setKeyListener(TextKeyListener.getInstance(false, TextKeyListener.Capitalize.NONE));
        field.setRawInputType(inputType);
        InputError.bind(field);
    }
}
