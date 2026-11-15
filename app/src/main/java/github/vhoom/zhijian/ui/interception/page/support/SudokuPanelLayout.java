package github.vhoom.zhijian.ui.interception.page.support;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import github.vhoom.zhijian.R;

/**
 * 按可用宽高等比缩放棋盘与键区；完成后为终盘和语句留位，全程不依赖滚动。
 *
 * 维护日期：2026年11月14日
 */
public final class SudokuPanelLayout extends ViewGroup {
    private final int gap;
    private final int keypadWidth;
    private final int rightOffset;
    private final int completionGap;
    private boolean touching;
    private boolean completed;
    private boolean completionLaidOut;
    private float completionProgress;
    private float startX, startY, startScale;
    private float completionScale;

    /** @param context 主题上下文 @param attrs 布局属性 */
    public SudokuPanelLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        gap = getResources().getDimensionPixelSize(R.dimen.space_xs);
        keypadWidth = getResources().getDimensionPixelSize(R.dimen.sudoku_keypad_width);
        rightOffset = getResources().getDimensionPixelSize(R.dimen.sudoku_keypad_right_offset);
        completionGap = getResources().getDimensionPixelSize(R.dimen.space_md);
    }

    /** @param widthSpec 父级提供的宽度 @param heightSpec 底部操作之外的可用高度 */
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        if (getChildCount() != 3) throw new IllegalStateException("Sudoku panel requires a board, keypad and completion message");
        View board = getChildAt(0);
        View keys = getChildAt(1);
        int unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        board.measure(unspecified, unspecified);
        keys.measure(MeasureSpec.makeMeasureSpec(Math.min(board.getMeasuredWidth(), keypadWidth), MeasureSpec.EXACTLY), unspecified);
        int contentHeight = board.getMeasuredHeight() + gap + keys.getMeasuredHeight();
        if (completed) {
            View message = getChildAt(2);
            message.measure(MeasureSpec.makeMeasureSpec(board.getMeasuredWidth(), MeasureSpec.EXACTLY), unspecified);
            contentHeight = board.getMeasuredHeight() + completionGap + message.getMeasuredHeight();
        }
        setMeasuredDimension(resolveSize(board.getMeasuredWidth(), widthSpec),
                resolveSize(contentHeight, heightSpec));
    }

    /** @param changed 边界是否改变 @param left 左边界 @param top 上边界 @param right 右边界 @param bottom 下边界 */
    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        View board = getChildAt(0);
        View keys = getChildAt(1);
        int boardWidth = board.getMeasuredWidth();
        int boardHeight = board.getMeasuredHeight();
        int keysWidth = keys.getMeasuredWidth();
        int keysHeight = keys.getMeasuredHeight();
        int width = right - left;
        int height = bottom - top;
        if (completed) {
            View message = getChildAt(2);
            completionScale = fit(width, height, boardWidth,
                    boardHeight + completionGap + message.getMeasuredHeight());
            float boardLeft = (width - boardWidth * completionScale) / 2f;
            place(board, boardLeft, 0f, completionScale);
            place(message, boardLeft, (boardHeight + completionGap) * completionScale, completionScale);
            completionLaidOut = true;
            applyCompletionProgress();
            return;
        }
        float stackedScale = fit(width, height, boardWidth, boardHeight + gap + keysHeight);
        float sideScale = fit(width, height, boardWidth + gap + keysWidth, Math.max(boardHeight, keysHeight));
        boolean sideBySide = width > height && sideScale > stackedScale;
        float scale = sideBySide ? sideScale : stackedScale;
        int contentWidth = sideBySide ? boardWidth + gap + keysWidth : boardWidth;
        int contentHeight = sideBySide ? Math.max(boardHeight, keysHeight) : boardHeight + gap + keysHeight;
        float originX = (width - contentWidth * scale) / 2f;
        float originY = (height - contentHeight * scale) / 2f;
        place(board, originX, originY, scale);
        float centeredKeys = (width - keysWidth * scale) / 2f;
        float keysLeft = centeredKeys + Math.min(rightOffset * scale, centeredKeys);
        place(keys, sideBySide ? originX + (boardWidth + gap) * scale : keysLeft,
                sideBySide ? originY + (contentHeight - keysHeight) * scale / 2f
                        : originY + (boardHeight + gap) * scale, scale);
    }

    /** 冻结动画起点并为终盘、完成语句排版；继续按钮须同时预留最终高度。 */
    public void beginCompletion() {
        if (completed) return;
        View board = getChildAt(0);
        startX = board.getX();
        startY = board.getY();
        startScale = board.getScaleX();
        completed = true;
        getChildAt(1).setVisibility(INVISIBLE);
        getChildAt(2).setVisibility(INVISIBLE);
        requestLayout();
    }

    /** @param progress 终盘上移动画的进度，0 保持原位置，1 使用完成布局 */
    public void setCompletionProgress(float progress) {
        completionProgress = Math.max(0f, Math.min(1f, progress));
        applyCompletionProgress();
    }

    /** 只更新绘制变换；动画帧不重复测量或布局，尺寸变化时由下一次布局更新终点。 */
    private void applyCompletionProgress() {
        if (!completionLaidOut) return;
        View board = getChildAt(0);
        float remaining = 1f - completionProgress;
        board.setTranslationX((startX - board.getLeft()) * remaining);
        board.setTranslationY((startY - board.getTop()) * remaining);
        float scale = completionScale + (startScale - completionScale) * remaining;
        board.setScaleX(scale);
        board.setScaleY(scale);
    }

    /** @param width 可用宽度 @param height 可用高度 @param naturalWidth 内容原宽 @param naturalHeight 内容原高 @return 完整显示内容且不超过原尺寸的比例 */
    private static float fit(int width, int height, int naturalWidth, int naturalHeight) {
        return Math.max(0f, Math.min(1f, Math.min(width / (float) Math.max(1, naturalWidth),
                height / (float) Math.max(1, naturalHeight))));
    }

    /** @param child 棋盘或键区 @param left 缩放后的左边界 @param top 缩放后的上边界 @param scale 等比缩放比例 */
    private static void place(View child, float left, float top, float scale) {
        int x = Math.round(left);
        int y = Math.round(top);
        child.setPivotX(0f);
        child.setPivotY(0f);
        child.setScaleX(scale);
        child.setScaleY(scale);
        child.layout(x, y, x + child.getMeasuredWidth(), y + child.getMeasuredHeight());
    }

    /** @return 是否正在操作棋盘或数字键区，供 Debug 翻页避让 */
    public boolean isTouching() { return touching; }

    /** @param event 原生触摸事件 @return 原生格子或按钮是否消费事件 */
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) touching = true;
        boolean handled = super.dispatchTouchEvent(event);
        if (!handled || event.getActionMasked() == MotionEvent.ACTION_UP
                || event.getActionMasked() == MotionEvent.ACTION_CANCEL) touching = false;
        return handled;
    }

    /** @param attrs 子控件 XML 属性 @return 标准布局参数 */
    @Override public LayoutParams generateLayoutParams(AttributeSet attrs) { return new LayoutParams(getContext(), attrs); }

    /** @return 未显式配置子控件时的布局参数 */
    @Override protected LayoutParams generateDefaultLayoutParams() {
        return new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
    }
}
