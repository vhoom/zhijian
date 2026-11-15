package github.vhoom.zhijian.ui.component.rule;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.ScrollView;

import github.vhoom.zhijian.R;

/**
 * 应用少时自然展开，超过高度上限后在子块内滚动。
 *
 * 维护日期：2026年11月08日
 */
public final class RuleAppListScrollView extends ScrollView {
    /** @param context 控件上下文 @param attrs 布局属性 */
    public RuleAppListScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
        // 使用平台方法，让 Android 10 起也按背景圆角裁切滚动内容。
        setClipToOutline(true);
    }

    /** @param widthMeasureSpec 父容器宽度约束 @param heightMeasureSpec 父容器高度约束 */
    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int maximum = getResources().getDimensionPixelSize(R.dimen.rule_app_list_max_height);
        if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            maximum = Math.min(maximum, MeasureSpec.getSize(heightMeasureSpec));
        }
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maximum, MeasureSpec.AT_MOST));
    }

    /** @param event 当前手势；列表可滚动时由子块完成本次手势，避免外页抢走拖动 */
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN && getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(canScrollVertically(-1) || canScrollVertically(1));
        }
        boolean handled = super.dispatchTouchEvent(event);
        if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) && getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(false);
        }
        return handled;
    }
}
