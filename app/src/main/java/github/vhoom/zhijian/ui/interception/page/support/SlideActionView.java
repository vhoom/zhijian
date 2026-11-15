package github.vhoom.zhijian.ui.interception.page.support;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.LinearInterpolator;
import android.widget.Button;

import github.vhoom.zhijian.R;

/**
 * 滑至有效行程的 90% 并松手才执行，允许在控件外松手；空闲箭头提示方向，保留键盘和无障碍激活。
 *
 * 维护日期：2026年11月11日
 */
public final class SlideActionView extends Button {
    /** 留出末端 10% 的容差，降低必须精确拖至边缘的操作负担。 */
    private static final float COMPLETION_THRESHOLD = 0.90f;
    /** 三个箭头依次轻微前移，使用不透明颜色提示滑动方向。 */
    private static final long HINT_DURATION_MS = 1400L;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrow = new Path();
    private final ValueAnimator hintAnimation;
    private final float thumbSize;
    private final float inset;
    private final int touchSlop;
    private final int thumbColor;
    private final int arrowColor;
    private final float arrowStrokeWidth;
    private int pointerId = MotionEvent.INVALID_POINTER_ID;
    private float downX;
    private float progress;
    private float hintPhase;
    private boolean dragging;

    /** @param context 布局工具使用的主题上下文，默认创建描边轨道对应的滑块 */
    public SlideActionView(Context context) { this(context, false); }

    /** @param context 页面主题上下文 @param filled 当前轨道是否采用主操作表面 */
    public SlideActionView(Context context, boolean filled) {
        super(context, null, 0, filled
                ? R.style.Widget_App_Button_InterceptionPrimary : R.style.Widget_App_Button_InterceptionSecondary);
        thumbSize = getResources().getDimension(R.dimen.wuwei_slider_thumb);
        inset = getResources().getDimension(R.dimen.space_xs);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        thumbColor = context.getColor(R.color.primary);
        arrowColor = context.getColor(R.color.on_primary);
        arrowStrokeWidth = getResources().getDimension(R.dimen.divider_height) * 2f;
        hintAnimation = ValueAnimator.ofFloat(0f, 1f);
        hintAnimation.setDuration(HINT_DURATION_MS);
        hintAnimation.setRepeatCount(ValueAnimator.INFINITE);
        hintAnimation.setInterpolator(new LinearInterpolator());
        hintAnimation.addUpdateListener(this::onHintFrame);
    }

    /** @return 当前是否隐藏引导文字；语义文本仍供读屏读取 */
    public boolean isDragging() { return dragging; }

    /** @return 滑块从起点到终点的有效水平距离 */
    private float travel() { return Math.max(0f, getWidth() - 2f * inset - thumbSize); }

    /** @return 根据界面方向选择的前进方向 */
    private int direction() { return getLayoutDirection() == LAYOUT_DIRECTION_RTL ? -1 : 1; }

    /** @return 滑块当前左边缘坐标 */
    private float thumbLeft() {
        return inset + travel() * (direction() == 1 ? progress : 1f - progress);
    }

    /** @param canvas 轨道由背景绘制；拖动期间只绘制滑块，不绘制引导文字 */
    @Override protected void onDraw(Canvas canvas) {
        if (!dragging) super.onDraw(canvas);
        float left = thumbLeft();
        float top = (getHeight() - thumbSize) / 2f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(thumbColor);
        canvas.drawRoundRect(left, top, left + thumbSize, top + thumbSize,
                thumbSize / 2f, thumbSize / 2f, paint);
        drawHintArrows(canvas, left);
    }

    /** @param canvas 当前画布 @param left 圆形滑块的左边缘，箭头始终留在圆内 */
    private void drawHintArrows(Canvas canvas, float left) {
        float centerX = left + thumbSize / 2f;
        float centerY = getHeight() / 2f;
        float unit = thumbSize / 10f;
        boolean animated = hintAnimation.isRunning();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(arrowStrokeWidth);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(arrowColor);
        for (int index = 0; index < 3; index++) {
            float wave = animated ? (float) Math.max(0d,
                    Math.sin((hintPhase - index * 0.16f) * Math.PI * 2d)) : 0f;
            float pulse = wave * wave;
            float arrowX = centerX + direction() * ((index - 1) * unit * 1.5f + pulse * unit * 0.4f);
            arrow.reset();
            arrow.moveTo(arrowX - direction() * unit / 2f, centerY - unit);
            arrow.lineTo(arrowX + direction() * unit / 2f, centerY);
            arrow.lineTo(arrowX - direction() * unit / 2f, centerY + unit);
            canvas.drawPath(arrow, paint);
        }
    }

    /** @param animation 当前动画帧；系统关闭动画时立即回到静态方向提示 */
    private void onHintFrame(ValueAnimator animation) {
        if (!ValueAnimator.areAnimatorsEnabled()) {
            stopHintAnimation();
            return;
        }
        hintPhase = (float) animation.getAnimatedValue();
        invalidate();
    }

    /** 只在可见、可操作且没有触摸占用时播放提示，避免后台持续刷新。 */
    private void updateHintAnimation() {
        // Button 构造期间也可能回调状态方法，此时动画尚未初始化。
        if (hintAnimation == null) return;
        boolean active = isAttachedToWindow() && isShown() && getWindowVisibility() == VISIBLE
                && hasWindowFocus() && isEnabled() && !isPressed() && travel() > 0f
                && pointerId == MotionEvent.INVALID_POINTER_ID && ValueAnimator.areAnimatorsEnabled();
        if (active) {
            if (!hintAnimation.isStarted()) hintAnimation.start();
        } else {
            stopHintAnimation();
        }
    }

    /** 停止提示并清空波浪相位，使按住、隐藏和系统禁用动画时保持静态箭头。 */
    private void stopHintAnimation() {
        if (hintAnimation != null && hintAnimation.isStarted()) hintAnimation.cancel();
        hintPhase = 0f;
        invalidate();
    }

    /**
     * 只接受从滑块开始的单指拖动；松手点可在控件外，仍按拖动方向上的位置检查门槛。
     * 轨道点按、取消及多指操作不会执行路由。
     * @param event 当前手势事件
     * @return 是否消费事件，禁用时不消费
     */
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                cancelDrag();
                float top = (getHeight() - thumbSize) / 2f;
                if (event.getX() >= thumbLeft() && event.getX() <= thumbLeft() + thumbSize
                        && event.getY() >= top && event.getY() <= top + thumbSize && travel() > 0f) {
                    pointerId = event.getPointerId(0);
                    downX = event.getX();
                    setPressed(true);
                    requestFocus();
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                }
                break;
            case MotionEvent.ACTION_MOVE:
                int index = event.findPointerIndex(pointerId);
                if (index >= 0) {
                    float distance = direction() * (event.getX(index) - downX);
                    if (Math.abs(distance) > touchSlop) dragging = true;
                    if (dragging) progress = Math.max(0f, Math.min(1f, distance / travel()));
                    invalidate();
                }
                break;
            case MotionEvent.ACTION_UP:
                float completionX = downX + direction() * travel() * COMPLETION_THRESHOLD;
                boolean complete = pointerId == event.getPointerId(0) && dragging && travel() > 0f
                        && direction() * (event.getX() - completionX) >= 0f;
                cancelDrag();
                if (complete) performClick();
                break;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_POINTER_UP:
                cancelDrag();
                break;
            default:
                break;
        }
        return true;
    }

    /** 触摸完成或键盘、无障碍激活统一使用原按钮事件出口。 */
    @Override public boolean performClick() { return super.performClick(); }

    /** 复位未完成手势并恢复居中的引导文字，供页面暂停时调用。 */
    public void cancelDrag() {
        pointerId = MotionEvent.INVALID_POINTER_ID;
        dragging = false;
        progress = 0f;
        setPressed(false);
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        invalidate();
    }

    /** @param pressed 是否被触摸或键盘按住；按住时停波浪，释放后恢复空闲提示 */
    @Override public void setPressed(boolean pressed) {
        super.setPressed(pressed);
        updateHintAnimation();
    }

    /** @param enabled 是否允许操作；禁用时撤销仍在进行的手势 */
    @Override public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        if (!enabled) cancelDrag();
        else updateHintAnimation();
    }

    /** @param hasWindowFocus 失去窗口焦点时不能保留半途手势 */
    @Override public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (!hasWindowFocus) cancelDrag();
        else updateHintAnimation();
    }

    /** 控件附加到窗口后根据可见性启动方向提示。 */
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateHintAnimation();
    }

    /** @param changedView 可见性发生变化的控件或祖先 @param visibility 新可见性 */
    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility != VISIBLE) cancelDrag();
        else updateHintAnimation();
    }

    /** @param visibility 当前窗口可见性，隐藏时停止手势和提示动画 */
    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility != VISIBLE) cancelDrag();
        else updateHintAnimation();
    }

    /** 控件移除时释放手势占用和动画帧回调。 */
    @Override protected void onDetachedFromWindow() {
        cancelDrag();
        stopHintAnimation();
        super.onDetachedFromWindow();
    }

    /** @param width 新宽度 @param height 新高度 @param oldWidth 原宽度 @param oldHeight 原高度 */
    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        cancelDrag();
    }
}
