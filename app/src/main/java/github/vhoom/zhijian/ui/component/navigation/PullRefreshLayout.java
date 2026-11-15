package github.vhoom.zhijian.ui.component.navigation;

import android.animation.ValueAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.TextView;

import github.vhoom.zhijian.R;
import github.vhoom.zhijian.util.config.AppConfig;

/**
 * 首页的下拉刷新容器。只有一个可滚动内容：内容停在顶部时向下拖，把内容整块顶出来，
 * 提示头部悬在那条被顶出来的缝里，松手过阈值即触发刷新。
 *
 * 维护日期：2026年11月08日
 */
public final class PullRefreshLayout extends FrameLayout {

    /**
     * 下拉刷新超过阈值后执行的数据加载回调。
     */
    public interface OnRefresh {
        /**
         * 接收下拉刷新完成手势，启动数据加载。
         */
        void onRefresh();
    }

    /** 手指位移换算成内容位移的阻尼，取 1 的话下拉跟手得像在直接撕纸，一点分量都没有。 */
    private final float damping;

    /** 最多顶出几倍头部高度，再多就只是把内容推出屏幕。 */
    private final int maxPullRatio;

    private final int settleDurationMs;

    private final TextView hint;
    private final int headerHeight;
    private final int touchSlop;

    /** 头部当前显示的提示，用来挡掉拖动过程中重复的 setText。 */
    private int shownTextRes = R.string.refresh_pull;

    private float downY;
    private float downX;
    private boolean dragging;
    private boolean refreshing;

    /** 当前把内容顶出去的距离，同时也是松手时判断是否触发刷新的依据。 */
    private int pull;

    private OnRefresh onRefresh;

    /**
     * 创建 PullRefreshLayout 实例，并初始化本次操作依赖或不可变字段。
     *
     * @param context 访问系统服务、资源或持久化文件的上下文
     * @param attrs XML 中传入的组件属性
     */
    public PullRefreshLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        AppConfig config = new AppConfig(context);
        damping = config.pullDamping();
        maxPullRatio = config.pullMaxHeaderRatio();
        settleDurationMs = config.pullSettleDurationMs();
        headerHeight = getResources().getDimensionPixelSize(R.dimen.refresh_header_height);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        hint = new TextView(context);
        hint.setTextAppearance(R.style.TextAppearance_App_BodySm);
        hint.setTextColor(context.getColor(R.color.mute));
        hint.setGravity(Gravity.CENTER);
        // 初始文案直接 setText：shownTextRes 已经预置成同一个值，走 showHint 会被当成没变化而跳过。
        hint.setText(R.string.refresh_pull);
        hint.setTranslationY(-headerHeight);
        hint.setVisibility(INVISIBLE);
        hint.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setClipChildren(true);
        addView(hint, new LayoutParams(LayoutParams.MATCH_PARENT, headerHeight));
    }

    /**
     * 注册下拉超过阈值后执行的刷新回调。
     *
     * @param onRefresh 超过下拉阈值后执行的刷新回调
     */
    public void setOnRefresh(OnRefresh onRefresh) {
        this.onRefresh = onRefresh;
    }

    /** 数据落地后收回头部；不在刷新中就什么都不做，定时刷新也会走到这里。 */
    public void finishRefresh() {
        if (!refreshing) return;
        refreshing = false;
        showHint(R.string.refresh_pull);
        settle(0);
    }

    /**
     * 根据拖动方向、阈值与内容位置判断是否接管手势。
     *
     * @param event 系统事件或当前状态机收到的交互事件
     */
    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downY = event.getY();
                downX = event.getX();
                dragging = false;
                return false;
            case MotionEvent.ACTION_MOVE:
                float distance = event.getY() - downY;
                if (!refreshing && isContentAtTop() && distance > touchSlop
                        && distance > Math.abs(event.getX() - downX)) {
                    content().animate().cancel();
                    hint.animate().cancel();
                    dragging = true;
                    return true;
                }
                return false;
            default:
                return false;
        }
    }

    /**
     * 处理已经接管的拖动、释放和取消事件。
     *
     * @param event 系统事件或当前状态机收到的交互事件
     */
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!dragging) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // 拦截成功后父容器会补发一个 DOWN，这里只认下它，起点仍是拦截阶段记的那个 downY。
                return true;
            case MotionEvent.ACTION_MOVE:
                pull(event.getY() - downY);
                return true;
            case MotionEvent.ACTION_UP:
                dragging = false;
                if (pull >= headerHeight) startRefresh();
                else settle(0);
                return true;
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                settle(0);
                return true;
            default:
                return false;
        }
    }

    /**
     * 进入刷新状态并通知数据加载方。
     */
    private void startRefresh() {
        refreshing = true;
        showHint(R.string.refresh_loading);
        settle(headerHeight);
        if (onRefresh != null) onRefresh.onRefresh();
    }

    /**
     * 按阻尼系数计算下拉偏移，并在跨过阈值时更新提示。
     *
     * @param dragDistance 手指相对起点的拖动距离，单位为像素
     */
    private void pull(float dragDistance) {
        int next = clamp((int) (dragDistance * damping));
        if (next == pull) return;
        pull = next;
        content().setTranslationY(pull);
        hint.setTranslationY(pull - headerHeight);
        hint.setVisibility(pull > 0 ? VISIBLE : INVISIBLE);
        showHint(pull >= headerHeight ? R.string.refresh_release : R.string.refresh_pull);
    }

    /**
     * 文本变化会触发一次测量与布局，拖动过程中只在跨过阈值那一刻换一次。
     *
     * @param textRes 提示文本对应的字符串资源 ID
     */
    private void showHint(int textRes) {
        if (shownTextRes == textRes) return;
        shownTextRes = textRes;
        hint.setText(textRes);
    }

    /**
     * 把内容和提示头部平滑移动到指定偏移。
     *
     * @param target 本次需要切换到的档位、页索引或像素偏移
     */
    private void settle(int target) {
        pull = target;
        content().animate().cancel();
        hint.animate().cancel();
        if (target > 0) hint.setVisibility(VISIBLE);
        if (!ValueAnimator.areAnimatorsEnabled() || settleDurationMs == 0) {
            content().setTranslationY(target);
            hint.setTranslationY(target - headerHeight);
            hint.setVisibility(target == 0 ? INVISIBLE : VISIBLE);
            return;
        }
        content().animate().translationY(target).setDuration(settleDurationMs).start();
        hint.animate().translationY(target - headerHeight).setDuration(settleDurationMs)
                .withEndAction(() -> {
                    if (target == 0 && pull == 0 && !dragging && !refreshing) hint.setVisibility(INVISIBLE);
                }).start();
    }

    /** 页面销毁时停止动画并恢复空闲状态，旧回调不能留下刷新提示。 */
    @Override protected void onDetachedFromWindow() {
        hint.animate().cancel();
        if (getChildCount() > 1) {
            content().animate().cancel();
            content().setTranslationY(0f);
        }
        pull = 0;
        dragging = false;
        refreshing = false;
        hint.setTranslationY(-headerHeight);
        hint.setVisibility(INVISIBLE);
        super.onDetachedFromWindow();
    }

    /**
     * 把偏移限制在允许的拖动范围内。
     *
     * @param value 本次需要读取、转换或写入的值
     */
    private int clamp(int value) {
        return Math.max(0, Math.min(value, headerHeight * maxPullRatio));
    }

    /**
     * 判断内容是否已滚动到顶部。
     */
    private boolean isContentAtTop() {
        return content().getScrollY() == 0;
    }

    /** 提示头部占 index 0，被包裹的内容永远是第二个孩子。 */
    private View content() {
        return getChildAt(1);
    }
}
