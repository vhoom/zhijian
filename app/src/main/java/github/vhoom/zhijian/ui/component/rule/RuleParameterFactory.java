package github.vhoom.zhijian.ui.component.rule;

import android.text.InputType;
import android.widget.EditText;

import github.vhoom.zhijian.util.time.DecimalDuration;
import github.vhoom.zhijian.ui.component.input.InputError;
import github.vhoom.zhijian.ui.component.input.ManualInput;

/**
 * 次数与时长参数的绑定工厂；输入类型和回填行为集中维护，范围错误由保存入口提示。
 *
 * 维护日期：2026年11月13日
 */
public final class RuleParameterFactory {
    /** 工厂不持有页面或草稿。 */
    private RuleParameterFactory() { }

    /**
     * 绑定整数次数；编辑时只同步草稿并清除旧错误，保存时由模型校验范围。
     *
     * @param field 现有紧凑输入框
     * @param current 当前草稿值
     * @param writer 草稿写入及变更通知
     * @return 可抑制通知恢复原始文本的绑定器
     */
    public static NumberRow bindCount(EditText field, int current, NumberRow.Writer writer) {
        ManualInput.configure(field, InputType.TYPE_CLASS_NUMBER);
        field.setHint(null);
        NumberRow number = new NumberRow(field, value -> {
            writer.write(value);
            InputError.clear(field);
        });
        number.bind();
        if (current > 0) number.apply(current);
        return number;
    }

    /**
     * 绑定以分钟输入的精确毫秒参数。
     *
     * @param field 现有紧凑输入框
     * @param current 当前毫秒值
     * @param writer 草稿写入及变更通知
     * @return 可抑制通知恢复原始文本的绑定器
     */
    public static NumberRow bindDuration(EditText field, long current, NumberRow.Writer writer) {
        ManualInput.configure(field, InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        NumberRow number = NumberRow.duration(field, DecimalDuration.MINUTE_MILLIS, value -> {
            writer.write(value);
            InputError.clear(field);
        });
        number.bind();
        if (current > 0L) number.apply(current);
        return number;
    }
}
