package github.vhoom.zhijian.ui.component.usage;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.DatePickerDialog;
import android.content.Context;
import android.os.Bundle;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import github.vhoom.zhijian.R;
import github.vhoom.zhijian.ui.component.dialog.AppDialogs;
import github.vhoom.zhijian.domain.usage.UsageCalendar;
import github.vhoom.zhijian.domain.usage.UsageDateSnap;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 星期标题固定，日期条显示相邻日期段并跟手滑动，释放后轻微吸附到星期对应位置。
 *
 * 维护日期：2026年11月14日
 */
public final class UsageDateSelector extends LinearLayout {
    /** 日期选择的观察者，由首页负责异步读取归档。 */
    public interface Listener {
        /** @param date 新选择的本地日期 */
        void onDateSelected(LocalDate date);
    }

    private static final int DAYS_PER_WEEK = 7;
    private final TextView[] dates = new TextView[DAYS_PER_WEEK * 3];
    private final UsageCalendar calendar = new UsageCalendar(LocalDate.now(), null);
    private final int touchSlop;
    private final int minimumVelocity;
    private final int maximumVelocity;
    private final FrameLayout numberViewport;
    private final LinearLayout numberTrack;
    private Listener listener;
    private float downX;
    private float downY;
    private boolean swiping;
    private boolean verticalGesture;
    private DatePickerDialog datePicker;
    private VelocityTracker velocityTracker;
    private ValueAnimator settlement;
    private int stripWidth;
    private float downOffset;
    private float dragOffset;

    /** @param context 主题和资源上下文 @param attrs 布局属性 */
    public UsageDateSelector(Context context, AttributeSet attrs) {
        super(context, attrs);
        ViewConfiguration configuration = ViewConfiguration.get(context);
        touchSlop = configuration.getScaledTouchSlop();
        minimumVelocity = configuration.getScaledMinimumFlingVelocity();
        maximumVelocity = configuration.getScaledMaximumFlingVelocity();
        setOrientation(VERTICAL);
        setLayoutDirection(LAYOUT_DIRECTION_LTR);
        setPadding(0, getResources().getDimensionPixelSize(R.dimen.space_sm), 0, 0);
        setFocusable(true);
        setContentDescription(context.getString(R.string.usage_dates_description));
        LinearLayout weekdays = row();
        String[] labels = getResources().getStringArray(R.array.usage_weekdays);
        for (int i = 0; i < DAYS_PER_WEEK; i++) {
            TextView heading = new TextView(context);
            heading.setGravity(Gravity.CENTER);
            heading.setText(labels[i]);
            heading.setTextAppearance(R.style.TextAppearance_App_Caption);
            heading.setTextColor(context.getColor(R.color.mute));
            heading.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            heading.setMinHeight(getResources().getDimensionPixelSize(R.dimen.usage_weekday_height));
            weekdays.addView(heading, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        }
        numberViewport = new FrameLayout(context);
        numberViewport.setClipChildren(true);
        addView(numberViewport, new LayoutParams(LayoutParams.MATCH_PARENT,
                getResources().getDimensionPixelSize(R.dimen.control_height)));
        numberTrack = new LinearLayout(context);
        numberTrack.setOrientation(HORIZONTAL);
        numberViewport.addView(numberTrack, new FrameLayout.LayoutParams(1, LayoutParams.MATCH_PARENT));
        LinearLayout numbers = null;
        for (int i = 0; i < dates.length; i++) {
            if (i % DAYS_PER_WEEK == 0) {
                numbers = new LinearLayout(context);
                numbers.setOrientation(HORIZONTAL);
                numberTrack.addView(numbers, new LayoutParams(1, LayoutParams.MATCH_PARENT));
            }
            TextView date = new TextView(context);
            date.setGravity(Gravity.CENTER);
            date.setTextAppearance(R.style.TextAppearance_App_BodyMd);
            date.setBackgroundResource(R.drawable.bg_usage_date);
            date.setOnClickListener(view -> select((LocalDate) view.getTag()));
            date.setOnLongClickListener(view -> { showDatePicker(); return true; });
            numbers.addView(date, cellParams(R.dimen.control_height));
            dates[i] = date;
        }
        render();
    }

    /** @param listener 日期变化接收者；null 可解除引用 */
    public void setListener(Listener listener) { this.listener = listener; }

    /** @return 选择的日期，页面重建时由协调层保存 */
    public LocalDate selectedDate() { return calendar.selected(); }

    /** @param date 系统重建前保存的日期，未来日期恢复时回退到今天 */
    public void restoreDate(LocalDate date) {
        stopSettlement();
        calendar.updateToday(LocalDate.now());
        calendar.select(calendar.canSelect(date) ? date : LocalDate.now());
        render();
        resetTrack();
    }

    /** @return 跨日后实际选择的日期，历史选择不会因实时刷新跳回今天 */
    public LocalDate refreshToday() {
        LocalDate week = calendar.weekStart();
        calendar.updateToday(LocalDate.now());
        if (!week.equals(calendar.weekStart())) {
            stopSettlement();
            resetTrack();
        }
        render();
        return calendar.selected();
    }

    /** @param date 点击或日期弹窗确认的候选日期 */
    private void select(LocalDate date) {
        calendar.updateToday(LocalDate.now());
        if (!calendar.select(date)) return;
        stopSettlement();
        render();
        resetTrack();
        if (listener != null) listener.onDateSelected(calendar.selected());
    }

    /** @param direction -1 为前一周，1 为后一周 @return 是否实际翻页 */
    private boolean moveWeek(int direction) {
        if (direction > 0 && !canAdvance()) return false;
        settleTo(direction);
        return true;
    }

    /** 按真实日期渲染今天、选中、过去和不可选未来四种状态。 */
    private void render() {
        LocalDate today = LocalDate.now();
        for (int i = 0; i < dates.length; i++) {
            LocalDate value = calendar.dateAt(i % DAYS_PER_WEEK).plusWeeks(i / DAYS_PER_WEEK - 1L);
            TextView cell = dates[i];
            boolean enabled = calendar.canSelect(value);
            boolean selected = value.equals(calendar.selected());
            cell.setTag(value);
            cell.setText(value.equals(today) ? getContext().getString(R.string.usage_today_short)
                    : Integer.toString(value.getDayOfMonth()));
            cell.setEnabled(enabled);
            cell.setSelected(selected);
            cell.setTextColor(getContext().getColor(!enabled ? R.color.disabled_text
                    : selected ? R.color.level_selected_text : R.color.ink));
            String dateLabel = value.format(DateTimeFormatter.ofPattern("yyyy年M月d日"));
            cell.setContentDescription(getContext().getString(!enabled ? R.string.usage_date_future
                    : selected ? R.string.usage_date_selected : R.string.usage_date_select, dateLabel));
        }
    }

    /** @return 已添加到组件中的等宽行容器 */
    private LinearLayout row() {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        addView(row, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        return row;
    }

    /** @param heightRes 行高资源 @return 七等分列参数 */
    private LayoutParams cellParams(int heightRes) {
        return new LayoutParams(0, getResources().getDimensionPixelSize(heightRes), 1f);
    }

    /** 长按日期可跳转到远期历史，不必逐周滑过多年记录。 */
    private void showDatePicker() {
        if (datePicker != null) datePicker.dismiss();
        LocalDate selected = calendar.selected();
        DatePickerDialog dialog = new DatePickerDialog(getContext(), R.style.Theme_Zhijian_Dialog, (picker, year, month, day) ->
                select(LocalDate.of(year, month + 1, day)), selected.getYear(), selected.getMonthValue() - 1,
                selected.getDayOfMonth());
        dialog.getDatePicker().setMaxDate(LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault())
                .toInstant().toEpochMilli() - 1L);
        datePicker = dialog;
        dialog.setOnDismissListener(ignored -> { if (datePicker == dialog) datePicker = null; });
        AppDialogs.show(dialog);
    }

    /** 页面销毁或主题重建时释放日期弹窗，避免持有旧 Activity 的窗口。 */
    @Override protected void onDetachedFromWindow() {
        stopSettlement();
        resetTrack();
        recycleVelocity();
        if (datePicker != null) datePicker.dismiss();
        super.onDetachedFromWindow();
    }

    /**
     * 日期范围内的水平手势由组件处理，纵向手势继续交给首页滚动容器。
     * @param event 当前触摸事件
     * @return 子控件或日期翻页是否消费事件
     */
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            stopSettlement();
            recycleVelocity();
            velocityTracker = VelocityTracker.obtain();
            downX = event.getX();
            downY = event.getY();
            swiping = false;
            verticalGesture = false;
            downOffset = numberTrack.getTranslationX() + stripWidth;
            disallowParent(true);
        } else if (action == MotionEvent.ACTION_MOVE && !swiping) {
            float dx = Math.abs(event.getX() - downX);
            float dy = Math.abs(event.getY() - downY);
            if (dy > touchSlop && dy > dx) {
                verticalGesture = true;
                disallowParent(false);
            }
        }
        if (velocityTracker != null) velocityTracker.addMovement(event);
        boolean handled = super.dispatchTouchEvent(event);
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            disallowParent(false);
            recycleVelocity();
        }
        return handled;
    }

    /** @param event 子控件触摸事件 @return 只接管超过阈值的水平手势 */
    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE && !verticalGesture) {
            float dx = Math.abs(event.getX() - downX);
            float dy = Math.abs(event.getY() - downY);
            swiping |= dx > touchSlop && dx > dy;
        }
        return swiping;
    }

    /** @param event 已由日期组件接管的事件 @return 是否消费当前手势 */
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE && swiping) {
            renderDrag(downOffset + event.getX() - downX);
        } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            if (swiping) {
                renderDrag(downOffset + event.getX() - downX);
                float velocity = 0f;
                if (velocityTracker != null) {
                    velocityTracker.computeCurrentVelocity(1000, maximumVelocity);
                    velocity = velocityTracker.getXVelocity();
                }
                settleTo(UsageDateSnap.direction(dragOffset, stripWidth, velocity, minimumVelocity, canAdvance()));
            } else {
                performClick();
                if (downOffset != 0f) settleTo(0);
            }
        } else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            settleTo(0);
        }
        return true;
    }

    /** @param offset 手指对应的日期带偏移，边缘使用轻阻尼而不是切换整个首页 */
    private void renderDrag(float offset) {
        float bounded = Math.max(-stripWidth, Math.min(stripWidth, offset));
        if (bounded < 0f && !canAdvance()) bounded *= 0.18f;
        dragOffset = bounded;
        numberTrack.setTranslationX(-stripWidth + bounded);
    }

    /** @return 下一日期段是否仍包含今天或过去的日期 */
    private boolean canAdvance() { return !calendar.weekStart().plusWeeks(1).isAfter(LocalDate.now()); }

    /** @param direction 释放后进入的相邻日期段，0 表示原位吸附 */
    private void settleTo(int direction) {
        stopSettlement();
        float start = numberTrack.getTranslationX();
        float target = -stripWidth - direction * stripWidth;
        if (stripWidth == 0 || !ValueAnimator.areAnimatorsEnabled() || start == target) {
            finishSettlement(direction);
            return;
        }
        ValueAnimator animation = ValueAnimator.ofFloat(start, target);
        settlement = animation;
        animation.setDuration(getResources().getInteger(R.integer.usage_date_snap_duration_ms));
        animation.setInterpolator(new DecelerateInterpolator());
        animation.addUpdateListener(value -> numberTrack.setTranslationX((float) value.getAnimatedValue()));
        animation.addListener(new AnimatorListenerAdapter() {
            /** @param animator 到达吸附位置的动画，取消或旧动画不得改变选择日期 */
            @Override public void onAnimationEnd(Animator animator) {
                if (settlement != animation) return;
                settlement = null;
                finishSettlement(direction);
            }
        });
        animation.start();
    }

    /** @param direction 已完成的日期段吸附方向，完成后统一更新选择并发布数据读取通知 */
    private void finishSettlement(int direction) {
        boolean changed = direction != 0 && calendar.moveWeek(direction);
        render();
        resetTrack();
        if (changed && listener != null) listener.onDateSelected(calendar.selected());
    }

    /** 取消旧吸附但保留当前位置，下一次拖动从手指看到的位置继续。 */
    private void stopSettlement() {
        ValueAnimator old = settlement;
        settlement = null;
        if (old != null) old.cancel();
    }

    /** 把当前日期段放回可视位置，相邻段在两侧待下一次拖动进入。 */
    private void resetTrack() {
        dragOffset = 0f;
        numberTrack.setTranslationX(-stripWidth);
    }

    /** @param disallow 是否由日期组件持有水平手势 */
    private void disallowParent(boolean disallow) {
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(disallow);
    }

    /** 结束手势后释放速度采样，不跨页面持有 MotionEvent。 */
    private void recycleVelocity() {
        if (velocityTracker != null) velocityTracker.recycle();
        velocityTracker = null;
    }

    /** @param w 新宽 @param h 新高 @param oldw 原宽 @param oldh 原高 */
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        resizeStrip(w);
    }

    /** @param widthSpec 组件宽度约束 @param heightSpec 两行内容高度约束 */
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        resizeStrip(MeasureSpec.getSize(widthSpec));
        super.onMeasure(widthSpec, heightSpec);
    }

    /** @param w 日期区域宽度，首帧测量前即分配相邻段尺寸，避免出现一帧空白 */
    private void resizeStrip(int w) {
        if (w <= 0 || w == stripWidth) return;
        stopSettlement();
        stripWidth = w;
        android.view.ViewGroup.LayoutParams trackParams = numberTrack.getLayoutParams();
        trackParams.width = w * 3;
        for (int i = 0; i < numberTrack.getChildCount(); i++) {
            android.view.ViewGroup.LayoutParams params = numberTrack.getChildAt(i).getLayoutParams();
            params.width = w;
        }
        resetTrack();
    }

    /** @return 标准点击语义，日期跳转由各列或辅助功能动作负责 */
    @Override public boolean performClick() { return super.performClick(); }

    /** @param info 提供读屏用户可用的按周翻页操作 */
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
        if (!calendar.weekStart().plusWeeks(1).isAfter(LocalDate.now())) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
        }
        info.setScrollable(true);
    }

    /** @param action 平台辅助功能动作 @param arguments 平台附带参数 @return 动作是否成功 */
    @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) return moveWeek(-1);
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) return moveWeek(1);
        return super.performAccessibilityAction(action, arguments);
    }
}
