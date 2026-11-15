package github.vhoom.zhijian.ui.component.usage;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.ScrollView;

/**
 * 按首页实测预算限制榜单高度，超出部分内部滚动并按方向交还父容器手势。
 *
 * 维护日期：2026年11月09日
 */
public final class UsageListScrollView extends ScrollView {
    /** 尚未取得实测预算时保持自然高度。 */
    private int maximumHeight;
    private final int touchSlop;
    private float downX;
    private float downY;
    private float previousY;

    /** @param context 控件上下文 @param attrs XML 中传入的布局属性 */
    public UsageListScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    /** @param height 首页能够容纳的完整应用行与分隔线总高度，单位为像素 */
    public void setMaximumHeight(int height) {
        if (height <= 0) throw new IllegalArgumentException("Positive list height required");
        if (maximumHeight == height) return;
        maximumHeight = height;
        requestLayout();
    }

    /** @param widthMeasureSpec 父容器宽度约束 @param heightMeasureSpec 父容器高度约束 */
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (maximumHeight == 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }
        int height = maximumHeight;
        if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            height = Math.min(height, MeasureSpec.getSize(heightMeasureSpec));
        }
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST));
    }

    /**
     * 榜单可继续纵向滚动时保留手势，横向翻页或滚动到边界时交还父容器。
     *
     * @param event 平台分发的当前触摸事件
     * @return 内部滚动容器是否处理本次事件
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            downX = event.getX();
            downY = event.getY();
            previousY = downY;
        }
        boolean handled = super.dispatchTouchEvent(event);
        if (getParent() != null) {
            boolean keepGesture = canScrollVertically(-1) || canScrollVertically(1);
            float dx = Math.abs(event.getX() - downX);
            float dy = Math.abs(event.getY() - downY);
            if (action == MotionEvent.ACTION_MOVE && Math.max(dx, dy) > touchSlop) {
                int direction = event.getY() < previousY ? 1 : -1;
                keepGesture = dy > dx && canScrollVertically(direction);
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) keepGesture = false;
            getParent().requestDisallowInterceptTouchEvent(keepGesture);
        }
        previousY = event.getY();
        return handled;
    }
}
