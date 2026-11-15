package github.vhoom.zhijian.ui.interception.page.support;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import github.vhoom.zhijian.R;

/**
 * 将主句放在可见内容区中部略偏上的滚动容器；下方挑战内容过长时仍可完整滚动。
 *
 * 维护日期：2026年11月08日
 */
public final class HeadlineScrollView extends ScrollView {
    private final boolean fitChallenge;
    /** @param context 页面主题上下文 */
    public HeadlineScrollView(Context context) { this(context, null); }

    /** @param context 页面主题上下文 @param attrs 布局中的滚动容器属性 */
    public HeadlineScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
        TypedArray attributes = context.obtainStyledAttributes(attrs, R.styleable.HeadlineScrollView);
        try { fitChallenge = attributes.getBoolean(R.styleable.HeadlineScrollView_fitChallenge, false); }
        finally { attributes.recycle(); }
    }

    /**
     * 先取得真实视口和主句高度，再设置内容顶部留白；内容总高度由 ScrollView 正常测量。
     * 直接子容器首项是主句；挑战页按完整内容留位，其他页面仅按主句定位。
     * @param widthMeasureSpec 父容器提供的宽度约束
     * @param heightMeasureSpec 父容器提供的高度约束
     */
    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED || getChildCount() == 0) return;
        View child = getChildAt(0);
        if (!(child instanceof ViewGroup)) return;
        ViewGroup content = (ViewGroup) child;
        if (content.getChildCount() == 0 || !(content.getChildAt(0) instanceof TextView)) return;
        View headline = content.getChildAt(0);
        int offset = getResources().getDimensionPixelSize(R.dimen.interception_headline_offset);
        int viewport = getMeasuredHeight() - getPaddingTop() - getPaddingBottom();
        // 长主句从顶部安全留白处开始，避免上移后被裁切。
        int top = Math.max(offset, (viewport - headline.getMeasuredHeight()) / 2 - offset);
        if (fitChallenge) {
            int height = content.getPaddingBottom();
            for (int index = 0; index < content.getChildCount(); index++) {
                View item = content.getChildAt(index);
                if (item.getVisibility() == GONE) continue;
                height += item.getMeasuredHeight();
                ViewGroup.LayoutParams params = item.getLayoutParams();
                if (params instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
                    height += margins.topMargin + margins.bottomMargin;
                }
            }
            top = Math.max(offset, Math.min(top, (viewport - height) / 2));
        }
        if (content.getPaddingTop() != top) {
            content.setPadding(content.getPaddingLeft(), top, content.getPaddingRight(), content.getPaddingBottom());
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }
}
