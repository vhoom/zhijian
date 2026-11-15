package github.vhoom.zhijian.ui.component.navigation;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.widget.ViewFlipper;

import github.vhoom.zhijian.R;
import github.vhoom.zhijian.util.config.AppConfig;

/**
 * 横向拖动时两页跟手移动，松手后归位；纵向滚动与页面内点击仍由子视图处理。
 *
 * 维护日期：2026年11月08日
 */
public final class SwipePager extends ViewFlipper {
    private static final float PREVIEW_ALPHA = 0.94f;
    private static final float EDGE_RESISTANCE = 0.18f;

    /**
     * 页面切换完成后的索引通知接口。
     */
    public interface PageListener {
        /**
         * 接收页索引变化并同步分段选择状态。
         *
         * @param index 从零开始的条目或页面索引
         */
        void onPageShown(int index);
    }

    private final float dragFactor;
    private final float flipThreshold;
    private final int touchSlop;
    private final int settleDuration;
    private float downX;
    private float downY;
    private float dragOffset;
    private int neighbor = -1;
    private boolean takingDrag;
    private boolean canceledGesture;
    private PageListener pageListener;
    private ValueAnimator settlement;

    /**
     * 创建 SwipePager 实例，并初始化本次操作依赖或不可变字段。
     *
     * @param context 访问系统服务、资源或持久化文件的上下文
     * @param attrs XML 中传入的组件属性
     */
    public SwipePager(Context context, AttributeSet attrs) {
        super(context, attrs);
        AppConfig config = new AppConfig(context);
        dragFactor = config.swipeDragFactor();
        flipThreshold = config.swipeFlipThreshold();
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        settleDuration = getResources().getInteger(R.integer.page_settle_duration_ms);
        setMeasureAllChildren(true);
    }

    /**
     * 注册当前页面改变后的通知回调。
     *
     * @param listener 状态发生变化后执行的监听回调
     */
    public void setPageListener(PageListener listener) { pageListener = listener; }

    /**
     * 根据拖动方向、阈值与内容位置判断是否接管手势。
     *
     * @param event 系统事件或当前状态机收到的交互事件
     */
    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        if (settlement != null) return true;
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            beginGesture(event);
            return false;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            if (canceledGesture) return true;
            if (!takingDrag) takingDrag = isHorizontalDrag(event);
            if (takingDrag) renderDrag(event.getX() - downX);
            return takingDrag;
        }
        return false;
    }

    /**
     * 处理已经接管的拖动、释放和取消事件。
     *
     * @param event 系统事件或当前状态机收到的交互事件
     */
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (settlement != null) return true;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                beginGesture(event);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (canceledGesture) return true;
                if (!takingDrag) takingDrag = isHorizontalDrag(event);
                if (takingDrag) renderDrag(event.getX() - downX);
                return true;
            case MotionEvent.ACTION_UP:
                if (takingDrag) {
                    renderDrag(event.getX() - downX);
                    takingDrag = false;
                    int target = neighbor >= 0 && Math.abs(dragOffset) >= getWidth() * flipThreshold
                            ? neighbor : getDisplayedChild();
                    settleTo(target);
                } else if (!canceledGesture) {
                    performClick();
                }
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_CANCEL:
                takingDrag = false;
                canceledGesture = true;
                settleTo(getDisplayedChild());
                return true;
            default:
                return true;
        }
    }

    /**
     * 执行平台点击语义，保证辅助功能能够调用组件。
     */
    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    /**
     * 标题点击与恢复页面共用此入口；提交页码后，保存状态始终指向目标页。
     *
     * @param index 从零开始的条目或页面索引
     * @param animate 是否需要使用动画移动到目标状态
     */
    public void showPage(int index, boolean animate) {
        if (index < 0 || index >= getChildCount()) throw new IllegalArgumentException("Invalid page index");
        stopSettlement();
        takingDrag = false;
        if (index == getDisplayedChild() || !animate || getWidth() == 0) {
            setDisplayedChild(index);
            resetPages();
            notifyPage();
            return;
        }
        neighbor = index;
        View incoming = getChildAt(index);
        incoming.setVisibility(VISIBLE);
        incoming.setTranslationX(index > getDisplayedChild() ? getWidth() : -getWidth());
        incoming.setAlpha(PREVIEW_ALPHA);
        settleTo(index);
    }

    /**
     * 记录本次手势起点并停止未结束的翻页动画。
     *
     * @param event 系统事件或当前状态机收到的交互事件
     */
    private void beginGesture(MotionEvent event) {
        downX = event.getX();
        downY = event.getY();
        takingDrag = false;
        canceledGesture = false;
    }

    /**
     * 按方向和触摸阈值判断是否为横向翻页手势。
     *
     * @param event 系统事件或当前状态机收到的交互事件
     */
    private boolean isHorizontalDrag(MotionEvent event) {
        float dx = Math.abs(event.getX() - downX);
        float dy = Math.abs(event.getY() - downY);
        return event.getPointerCount() == 1 && dx > touchSlop * dragFactor && dx > dy * dragFactor;
    }

    /**
     * 把当前拖动偏移应用到参与切换的页面。
     *
     * @param offset 当前页面相对起点的偏移
     */
    private void renderDrag(float offset) {
        int width = getWidth();
        if (width == 0) return;
        int current = getDisplayedChild();
        int candidate = offset < 0 ? current + 1 : current - 1;
        neighbor = candidate >= 0 && candidate < getChildCount() ? candidate : -1;
        dragOffset = Math.max(-width, Math.min(width, offset));
        if (neighbor < 0) dragOffset *= EDGE_RESISTANCE;
        float progress = Math.abs(dragOffset) / width;
        for (int i = 0; i < getChildCount(); i++) {
            View page = getChildAt(i);
            page.setVisibility(i == current || i == neighbor ? VISIBLE : GONE);
            page.setImportantForAccessibility(i == current
                    ? IMPORTANT_FOR_ACCESSIBILITY_AUTO : IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            if (i == current) {
                page.setTranslationX(dragOffset);
                page.setAlpha(1f - (1f - PREVIEW_ALPHA) * progress);
            } else if (i == neighbor) {
                page.setTranslationX(dragOffset + (neighbor > current ? width : -width));
                page.setAlpha(PREVIEW_ALPHA + (1f - PREVIEW_ALPHA) * progress);
            }
        }
    }

    /**
     * 按拖动结果动画切换到目标页并通知标题同步。
     *
     * @param target 本次需要切换到的档位、页索引或像素偏移
     */
    private void settleTo(int target) {
        int previous = getDisplayedChild();
        boolean changed = target != previous;
        View outgoing = getChildAt(previous);
        View incoming = neighbor >= 0 ? getChildAt(neighbor) : null;
        float outgoingStart = outgoing.getTranslationX();
        float outgoingEnd = changed ? (target > previous ? -getWidth() : getWidth()) : 0f;
        float incomingStart = incoming == null ? 0f : incoming.getTranslationX();
        float incomingEnd = changed || neighbor < 0 ? 0f : (neighbor > previous ? getWidth() : -getWidth());
        float outgoingAlpha = outgoing.getAlpha();
        float incomingAlpha = incoming == null ? 1f : incoming.getAlpha();

        if (changed) {
            setDisplayedChild(target);
            outgoing.setVisibility(VISIBLE);
            outgoing.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            getChildAt(target).setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_AUTO);
            notifyPage();
        }
        if (!ValueAnimator.areAnimatorsEnabled() || outgoingStart == outgoingEnd) {
            resetPages();
            return;
        }

        ValueAnimator animation = ValueAnimator.ofFloat(0f, 1f);
        settlement = animation;
        animation.setDuration(settleDuration);
        animation.setInterpolator(new DecelerateInterpolator());
        animation.addUpdateListener(value -> {
            float fraction = (float) value.getAnimatedValue();
            outgoing.setTranslationX(outgoingStart + (outgoingEnd - outgoingStart) * fraction);
            outgoing.setAlpha(outgoingAlpha + ((changed ? PREVIEW_ALPHA : 1f) - outgoingAlpha) * fraction);
            if (incoming != null) {
                incoming.setTranslationX(incomingStart + (incomingEnd - incomingStart) * fraction);
                incoming.setAlpha(incomingAlpha + ((changed ? 1f : PREVIEW_ALPHA) - incomingAlpha) * fraction);
            }
        });
        animation.addListener(new AnimatorListenerAdapter() {
            /**
             * 动画结束后归一页面位置并发布当前页面。
             *
             * @param finished 已经结束的动画对象
             */
            @Override
            public void onAnimationEnd(Animator finished) {
                if (settlement != animation) return;
                settlement = null;
                resetPages();
            }
        });
        animation.start();
    }

    /**
     * 通知监听方当前显示的页面索引。
     */
    private void notifyPage() {
        if (pageListener != null) pageListener.onPageShown(getDisplayedChild());
    }

    /**
     * 恢复页面位移和可见性，避免保留上一轮动画状态。
     */
    private void resetPages() {
        neighbor = -1;
        dragOffset = 0f;
        for (int i = 0; i < getChildCount(); i++) {
            View page = getChildAt(i);
            boolean selected = i == getDisplayedChild();
            page.setTranslationX(0f);
            page.setAlpha(1f);
            page.setVisibility(selected ? VISIBLE : GONE);
            page.setImportantForAccessibility(selected
                    ? IMPORTANT_FOR_ACCESSIBILITY_AUTO : IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        }
    }

    /**
     * 停止尚未结束的页面切换动画。
     */
    private void stopSettlement() {
        if (settlement != null) {
            settlement.removeAllListeners();
            settlement.removeAllUpdateListeners();
            settlement.cancel();
            settlement = null;
        }
        resetPages();
    }

    /**
     * 容器尺寸变化后重算页面位置，避免旧宽度影响翻页。
     *
     * @param width 组件当前宽度，单位为像素
     * @param height 组件当前高度，单位为像素
     * @param oldWidth 尺寸变化前的宽度，单位为像素
     * @param oldHeight 尺寸变化前的高度，单位为像素
     */
    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (oldWidth != 0 && width != oldWidth) {
            takingDrag = false;
            stopSettlement();
        }
    }

    /**
     * 组件脱离窗口时取消仍在执行的动画。
     */
    @Override
    protected void onDetachedFromWindow() {
        takingDrag = false;
        stopSettlement();
        super.onDetachedFromWindow();
    }
}
