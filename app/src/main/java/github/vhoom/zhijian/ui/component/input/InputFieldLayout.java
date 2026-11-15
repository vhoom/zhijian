package github.vhoom.zhijian.ui.component.input;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 输入控件和下方提示的共用容器；提示按控件宽度换行，不扩大紧凑输入区域。
 *
 * 维护日期：2026年11月13日
 */
public final class InputFieldLayout extends LinearLayout {
    /** @param context 当前主题上下文 */
    public InputFieldLayout(Context context) { this(context, null); }

    /** @param context 当前主题上下文 @param attrs XML 容器属性 */
    public InputFieldLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
    }

    /**
     * 返回直接位于输入区域下方的提示，绑定依赖所属容器，不要求各提示使用同一个 ID。
     * @return 此容器第二个直接子视图；XML 固定为输入区域和一条提示，结构错误时拒绝错误绑定
     */
    public TextView errorCaption() {
        if (getChildCount() != 2 || !(getChildAt(1) instanceof TextView)) {
            throw new IllegalStateException("Input field must contain its control and error caption");
        }
        return (TextView) getChildAt(1);
    }

    /**
     * 由第一项输入控件决定宽度，再以此宽度测量可换行的下方错误。
     * @param widthMeasureSpec 父容器允许的宽度
     * @param heightMeasureSpec 父容器允许的高度
     */
    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (getChildCount() == 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }
        View control = getChildAt(0);
        measureChildWithMargins(control, widthMeasureSpec, 0, heightMeasureSpec, 0);
        LayoutParams margins = (LayoutParams) control.getLayoutParams();
        int desired = control.getMeasuredWidth() + margins.leftMargin + margins.rightMargin
                + getPaddingLeft() + getPaddingRight();
        int width = resolveSize(Math.max(getSuggestedMinimumWidth(), desired), widthMeasureSpec);
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightMeasureSpec);
    }
}
