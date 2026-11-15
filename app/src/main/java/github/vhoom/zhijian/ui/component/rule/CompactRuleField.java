package github.vhoom.zhijian.ui.component.rule;

import android.graphics.Rect;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.TouchDelegate;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.EditText;
import android.widget.TextView;

import github.vhoom.zhijian.R;
import github.vhoom.zhijian.ui.component.input.InputError;

/**
 * 数字框保留紧凑矩形的最小尺寸，名称保留整行宽度，外层代理完整触控范围。
 * 只观察界面内容，不参与数字解析、草稿保存或规则执行。
 *
 * 维护日期：2026年11月13日
 */
public final class CompactRuleField {
    private final TextView field;
    private final FrameLayout target;

    /**
     * 初始化单个数字框的尺寸观察器。
     * @param field 已放入透明触控容器的时间或时长数字框
     */
    private CompactRuleField(TextView field) {
        this.field = field;
        target = (FrameLayout) field.getParent();
    }

    /**
     * 每个数字框只绑定一次，后续回填和用户编辑自动更新可见宽度。
     * @param field 需要共用触控范围的数字框或整行名称输入框
     */
    public static void bind(TextView field) {
        InputError.bind(field);
        if (field.getTag(R.id.rule_compact_field_binding) != null) return;
        CompactRuleField binding = new CompactRuleField(field);
        field.setTag(R.id.rule_compact_field_binding, binding);
        if (field instanceof EditText) RuleInputFocus.bind((EditText) field);
        field.addTextChangedListener(new TextWatcher() {
            /**
             * 文本变化前无需调整布局。
             * @param text 原文本
             * @param start 替换起点
             * @param count 原字符数量
             * @param after 新字符数量
             */
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }

            /**
             * 等待文本编辑结束再测量。
             * @param text 当前文本
             * @param start 替换起点
             * @param before 原字符数量
             * @param count 新字符数量
             */
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { }

            /**
             * 用户输入和程序回填都只更新显示尺寸。
             * @param text 编辑结束后的文本
             */
            @Override public void afterTextChanged(Editable text) {
                binding.resize();
            }
        });
        // 布局和字体变化后同步输入宽度，错误文字由外层独立测量。
        field.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> binding.resize());
        binding.target.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> binding.updateTouchTarget());
        binding.resize();
    }

    /** 按参考最小宽度和当前文本调整边框，外层保留点击范围。 */
    private void resize() {
        // 整行名称由父容器决定宽度，仅复用触摸代理及完成动作。
        if (field.getLayoutParams().width == ViewGroup.LayoutParams.MATCH_PARENT) return;
        CharSequence text = field.getText().length() > 0 ? field.getText() : field.getHint();
        int width = Math.max(field.getMinWidth(),
                textWidth(text) + field.getCompoundPaddingStart() + field.getCompoundPaddingEnd());
        ViewGroup.LayoutParams fieldSize = field.getLayoutParams();
        if (fieldSize.width != width) {
            fieldSize.width = width;
            field.setLayoutParams(fieldSize);
        }
        int minimum = field.getResources().getDimensionPixelSize(R.dimen.control_height);
        int reference = Math.max(textWidth("00:00"), textWidth("9999"))
                + field.getPaddingStart() + field.getPaddingEnd();
        ViewGroup.LayoutParams targetSize = target.getLayoutParams();
        int targetWidth = Math.max(minimum, Math.max(reference, width));
        if (targetSize.width != targetWidth) {
            targetSize.width = targetWidth;
            target.setLayoutParams(targetSize);
        }
    }

    /**
     * 测量最长一行，跨午夜的“次日”标记保留自然的两行高度。
     * @param text 当前文本、空值提示或列宽参考文本
     * @return 向上取整的最长行像素宽度
     */
    private int textWidth(CharSequence text) {
        float width = 0;
        if (text != null) {
            for (String line : text.toString().split("\n", -1)) {
                width = Math.max(width, field.getPaint().measureText(line));
            }
        }
        return (int) Math.ceil(width);
    }

    /** 将透明容器内的触摸交给原生数字框，保留点击、选中和时间选择器。 */
    private void updateTouchTarget() {
        target.setTouchDelegate(new TouchDelegate(new Rect(0, 0, target.getWidth(), target.getHeight()), field));
    }
}
