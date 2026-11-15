package github.vhoom.zhijian.ui.component.rule;

import android.app.Activity;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewParent;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;

import github.vhoom.zhijian.R;
import github.vhoom.zhijian.data.model.policy.RuleInputLimits;
import github.vhoom.zhijian.data.model.policy.RuleType;
import github.vhoom.zhijian.ui.component.input.InputError;

import java.util.function.Supplier;

/**
 * 规则输入错误的就地反馈与焦点协调器；错误位于控件下方，不弹校验对话框。
 *
 * 维护日期：2026年11月13日
 */
public final class RuleValidationFeedback {
    private final Activity activity;
    private boolean closed;
    /** 每次定位递增，过期的布局回调不能恢复旧字段的焦点。 */
    private long focusGeneration;

    /** @param activity 编辑页面 */
    public RuleValidationFeedback(Activity activity) { this.activity = activity; }

    /** @param tooLarge 是否超过数值上界 @return 共用错误资源 */
    public static int message(boolean tooLarge) {
        return tooLarge ? R.string.rule_value_too_large : R.string.rule_value_invalid;
    }

    /** @param issue 原始输入校验结果 */
    public void show(RuleInputBinding.Issue issue) {
        boolean tooLarge = issue.status == RuleInputLimits.Status.TOO_LARGE;
        show(tooLarge, () -> markInputError(issue.field, message(tooLarge)));
    }

    /**
     * 标记原输入；收起规则使用自己的卡片提示，不改变启用状态。
     * @param field 错误所属输入或时间控件
     * @param message 共用错误资源
     * @return 当前可见的修改入口
     */
    public static View markInputError(TextView field, int message) {
        InputError.show(field, field.getContext().getString(message));
        if (field.isShown()) return field;
        View ancestor = field;
        while (ancestor != null) {
            if (ancestor.getTag() instanceof RuleType) {
                TextView caption = ancestor.findViewById(R.id.condition_error);
                if (caption != null) {
                    caption.setText(message);
                    caption.setVisibility(View.VISIBLE);
                }
                return ancestor.findViewById(R.id.type_check);
            }
            ViewParent parent = ancestor.getParent();
            ancestor = parent instanceof View ? (View) parent : null;
        }
        return null;
    }

    /**
     * 立即展示所属控件提示并定位；其他星期由调用方返回可见的修改入口。
     * @param tooLarge 校验分类，供原有调用入口保持一致
     * @param locate 标记错误后返回输入或规则操作控件
     */
    public void show(boolean tooLarge, Supplier<View> locate) {
        if (!active()) return;
        long generation = ++focusGeneration;
        View target = locate.get();
        if (target == null || !target.isAttachedToWindow() || !target.isShown()) return;
        target.requestFocus();
        target.post(() -> {
            if (!active() || generation != focusGeneration
                    || !target.isAttachedToWindow() || !target.isShown()) return;
            View area = target;
            if (target.getTag(R.id.input_error_binding) instanceof View) {
                area = (View) ((View) target.getTag(R.id.input_error_binding)).getParent();
            }
            area.requestRectangleOnScreen(new Rect(0, 0, area.getWidth(), area.getHeight()), true);
            if (target instanceof EditText) {
                EditText field = (EditText) target;
                field.setSelection(field.length());
                InputMethodManager keyboard = activity.getSystemService(InputMethodManager.class);
                if (keyboard != null) keyboard.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT);
            }
        });
    }

    /** @return 页面仍允许展示和定位输入 */
    private boolean active() { return !closed && !activity.isFinishing() && !activity.isDestroyed(); }

    /** 页面销毁或退出时停止所有待执行的焦点恢复。 */
    public void close() { closed = true; focusGeneration++; }
}
