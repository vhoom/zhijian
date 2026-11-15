package github.vhoom.zhijian.ui.component.apps;

import android.animation.ValueAnimator;
import android.graphics.Rect;
import android.graphics.drawable.RotateDrawable;
import android.view.TouchDelegate;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.ImageButton;

import github.vhoom.zhijian.R;

/**
 * 应用清单的紧凑图标按钮：数量行代理完整触控范围，读取期间防重复点击并旋转图标。
 *
 * 维护日期：2026年11月15日
 */
public final class AppListRefreshButton {
    private static final long ROTATION_DURATION_MS = 900L;
    private final ImageButton button;
    private final ViewGroup touchHost;
    private final View.OnLayoutChangeListener touchLayoutListener;
    private final RotateDrawable icon = new RotateDrawable();
    private ValueAnimator animation;
    private boolean loading;
    private boolean active;

    /**
     * 绑定真实读取动作和透明触摸代理，读取时仅旋转循环箭头。
     * @param button 位于至少 48dp 宽高数量行内、已应用共用样式的紧凑图标按钮
     * @param refresh 用户主动点击后执行的应用清单刷新
     */
    public AppListRefreshButton(ImageButton button, Runnable refresh) {
        this.button = button;
        touchHost = (ViewGroup) button.getParent();
        touchLayoutListener = (view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> updateTouchTarget();
        touchHost.addOnLayoutChangeListener(touchLayoutListener);
        icon.setDrawable(button.getDrawable());
        icon.setFromDegrees(0f);
        icon.setToDegrees(360f);
        button.setImageDrawable(icon);
        button.setOnClickListener(view -> refresh.run());
        updateTouchTarget();
    }

    /** 数量行将图标附近至少 48dp 的横向触摸交给按钮，左右布局同步镜像。 */
    private void updateTouchTarget() {
        if (!button.isLaidOut()) return;
        Rect bounds = new Rect();
        button.getHitRect(bounds);
        int minimum = button.getResources().getDimensionPixelSize(R.dimen.control_height);
        if (button.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) {
            bounds.left = Math.min(bounds.left, bounds.right - minimum);
        } else {
            bounds.right = Math.max(bounds.right, bounds.left + minimum);
        }
        touchHost.setTouchDelegate(new TouchDelegate(bounds, button));
    }

    /**
     * 同步读取状态、共用禁用颜色和读屏说明，系统关闭动画时保持静态图标。
     * @param loading 是否正在读取或保存应用清单
     */
    public void setLoading(boolean loading) {
        this.loading = loading;
        button.setEnabled(!loading);
        String description = button.getContext().getString(loading
                ? R.string.apps_refresh_loading : R.string.apps_refresh);
        button.setContentDescription(description);
        updateAnimation();
    }

    /**
     * 页面隐藏或离开前台时停止动画，后台读取仍可完成保存。
     * @param active 当前管控页是否处于可见前台
     */
    public void setActive(boolean active) {
        this.active = active;
        updateAnimation();
    }

    /** 根据读取状态和页面可见性启停循环箭头，避免隐藏页面持续绘制。 */
    private void updateAnimation() {
        if (!loading || !active || !ValueAnimator.areAnimatorsEnabled()) {
            stopAnimation();
            return;
        }
        if (animation != null) return;
        animation = ValueAnimator.ofInt(0, 10_000);
        animation.setDuration(ROTATION_DURATION_MS);
        animation.setInterpolator(new LinearInterpolator());
        animation.setRepeatCount(ValueAnimator.INFINITE);
        animation.addUpdateListener(value -> icon.setLevel((Integer) value.getAnimatedValue()));
        animation.start();
    }

    /** 释放动画监听并恢复箭头方向，不改变正在执行的后台读取。 */
    private void stopAnimation() {
        if (animation != null) {
            animation.cancel();
            animation.removeAllUpdateListeners();
            animation = null;
        }
        icon.setLevel(0);
    }

    /** 页面销毁时释放动画、布局监听、触摸代理及点击监听。 */
    public void close() {
        stopAnimation();
        touchHost.removeOnLayoutChangeListener(touchLayoutListener);
        touchHost.setTouchDelegate(null);
        button.setOnClickListener(null);
    }
}
