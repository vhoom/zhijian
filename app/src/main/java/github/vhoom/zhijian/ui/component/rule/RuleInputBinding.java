package github.vhoom.zhijian.ui.component.rule;

import android.os.Bundle;
import android.widget.EditText;

import github.vhoom.zhijian.data.model.policy.RuleInputLimits;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * 原始输入与数值草稿的适配器；校验只在保存时执行，恢复不触发用户编辑通知。
 *
 * 维护日期：2026年11月13日
 */
public final class RuleInputBinding {
    /** 校验失败的位置与通用提示分类，不携带真实范围。 */
    public static final class Issue {
        public final EditText field;
        public final RuleInputLimits.Status status;

        /** @param field 原输入框 @param status 无效或过大分类 */
        private Issue(EditText field, RuleInputLimits.Status status) {
            this.field = field;
            this.status = status;
        }
    }

    private final EditText field;
    private final NumberRow number;
    private final Supplier<RuleInputLimits.Range> range;
    private final BooleanSupplier required;

    /**
     * 保存输入控件及实时约束读取器。
     * @param field 当前原始输入框
     * @param number 负责数值回填与文本监听的绑定器
     * @param range 根据规则及当前时段读取约束的工厂
     * @param required 当前规则是否启用或要求填写
     */
    public RuleInputBinding(EditText field, NumberRow number, Supplier<RuleInputLimits.Range> range,
            BooleanSupplier required) {
        this.field = field;
        this.number = number;
        this.range = range;
        this.required = required;
    }

    /** @return 保存时的首个输入错误；合法时为 null */
    public Issue validate() {
        RuleInputLimits.Status status = range.get().checkText(field.getText().toString(), required.getAsBoolean());
        return status == RuleInputLimits.Status.VALID ? null : new Issue(field, status);
    }

    /** @param field 规则组名称输入框 @return 名称错误或 null */
    public static Issue validateName(EditText field) {
        RuleInputLimits.Status status = RuleInputLimits.checkName(field.getText().toString());
        return status == RuleInputLimits.Status.VALID ? null : new Issue(field, status);
    }

    /** @param state 系统状态容器 @param key 当前参数的稳定键 */
    public void save(Bundle state, String key) { state.putString(key, field.getText().toString()); }

    /** @param state 系统重建状态 @param key 当前参数的稳定键 */
    public void restore(Bundle state, String key) {
        if (state != null && state.containsKey(key)) number.applyText(state.getString(key, ""));
    }
}
