package github.vhoom.zhijian.ui.interception.page;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import github.vhoom.zhijian.R;
import github.vhoom.zhijian.data.model.interception.InterceptionEvent;
import github.vhoom.zhijian.domain.interception.hold.HoldProgress;
import github.vhoom.zhijian.ui.interception.copy.InterceptionCopyCatalog.Page;
import github.vhoom.zhijian.ui.interception.copy.InterceptionCopyCatalog.State;
import github.vhoom.zhijian.ui.interception.copy.InterceptionCopyCatalog.Scenario;
import github.vhoom.zhijian.ui.interception.page.support.ChallengeCompletionAction;
import github.vhoom.zhijian.ui.interception.session.PageSession;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * 按压挑战页；关闭再次确认时保留当前控件，完成后新增底部继续操作。
 *
 * 维护日期：2026年11月11日
 */
public final class HoldPage extends BaseInterceptionPage {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable holdTick = this::updateHold;
    private final TextView title;
    private final Button hold, exit;
    private final ProgressBar progress;
    private final LinearLayout actions;
    private final HoldProgress holdProgress;
    private final ChallengeCompletionAction completionAction;
    private boolean completed;
    private State displayedState;
    private long displayedSeconds = -1L;

    /**
     * @param root 页面根
     * @param session 模式参数
     * @param events 事件出口
     * @param state 重建容器，完成阶段由 session 保存
     */
    @SuppressLint("ClickableViewAccessibility")
    public HoldPage(View root, PageSession session, Consumer<InterceptionEvent> events, Bundle state) {
        super(root, session, events);
        completed = session.stage.isCompletedChallenge();
        holdProgress = new HoldProgress(session.holdDurationMs);
        title = findViewById(R.id.interception_hold_title);
        hold = findViewById(R.id.interception_hold_button);
        progress = findViewById(R.id.interception_hold_progress);
        actions = findViewById(R.id.interception_hold_actions);
        exit = findViewById(R.id.interception_hold_exit);
        completionAction = new ChallengeCompletionAction(actions, appearance, R.id.interception_hold_continue,
                () -> dispatch(InterceptionEvent.CONTINUE));
        exit.setOnClickListener(v -> dispatch(InterceptionEvent.ABANDON));
        if (!completed) hold.setOnTouchListener(this::onHoldTouch);
    }

    /** 失去前台只取消未完成按压，完成阶段不清零。 */
    @Override public void pause() {
        main.removeCallbacks(holdTick);
        completionAction.finishAnimation();
        if (!completed) {
            holdProgress.reset();
            progress.setProgress(0);
        }
    }

    /** 显示原挑战控件；完成状态恢复后直接显示新增的继续操作。 */
    @Override public void render() {
        pause();
        if (!completed) displayedState = null;
        // 专属 XML 样式保持各状态的主色，完成后禁用输入也不变灰。
        hold.setMaxLines(1);
        // 单行挑战引导从共用字号中选择；资源先按系统字体缩放转换为像素。
        hold.setAutoSizeTextTypeUniformWithPresetSizes(new int[]{
                hold.getResources().getDimensionPixelSize(R.dimen.text_caption),
                hold.getResources().getDimensionPixelSize(R.dimen.text_body_sm),
                hold.getResources().getDimensionPixelSize(R.dimen.text_body_md)
        }, android.util.TypedValue.COMPLEX_UNIT_PX);
        if (displayedState == null) renderCopy(displaySeconds(session.holdDurationMs));
        if (completed) renderCompleted(false);
    }

    /** 在已经提交的完成阶段更新当前按压控件，保留页面和原退出操作。 */
    @Override public boolean completeChallenge() {
        if (!completed) {
            completed = true;
            renderCompleted(true);
        }
        return true;
    }

    /**
     * 保留满格进度和已完成的按压按钮，停止后续按压输入。
     * @param animate 是否浮出本次新增的继续操作
     */
    private void renderCompleted(boolean animate) {
        main.removeCallbacks(holdTick);
        hold.setOnTouchListener(null);
        hold.setPressed(false);
        hold.setEnabled(false);
        hold.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        hold.setText(R.string.interception_hold_completed);
        progress.setProgress(progress.getMax());
        progress.setContentDescription(context.getString(R.string.interception_hold_completed));
        completionAction.show(animate);
    }

    /**
     * @param seconds 当前按压剩余秒数；主句保持不变，仅更新按钮提示
     */
    private void renderCopy(long seconds) {
        State state = holdProgress.isHolding() ? State.ACTIVE : State.IDLE;
        if (state == displayedState && seconds == displayedSeconds) return;
        displayedState = state;
        displayedSeconds = seconds;
        Scenario selected = appearance.bindScenario(Page.HOLD, state, title, actions, exit, null);
        hold.setText(String.format(Locale.getDefault(), selected.interactionAction, seconds));
    }

    /**
     * @param view 按压按钮
     * @param event 触摸输入；松开、滑出或取消后逐渐加速倒退，再按住继续推进
     */
    private boolean onHoldTouch(View view, MotionEvent event) {
        if (completed) return true;
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            holdProgress.setHolding(true, SystemClock.elapsedRealtime());
            updateHold();
        } else if (action == MotionEvent.ACTION_MOVE
                && (event.getX() < 0 || event.getX() > view.getWidth() || event.getY() < 0 || event.getY() > view.getHeight())) {
            releaseHold();
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (action == MotionEvent.ACTION_UP) view.performClick();
            releaseHold();
        }
        return true;
    }

    /** 按单调时间刷新推进或倒退；完成仅报告事件，不直接放行。 */
    private void updateHold() {
        main.removeCallbacks(holdTick);
        if (completed) return;
        holdProgress.update(SystemClock.elapsedRealtime());
        progress.setProgress((int) Math.round(holdProgress.fraction() * progress.getMax()));
        if (holdProgress.isComplete()) {
            dispatch(InterceptionEvent.CHALLENGE_COMPLETED);
            return;
        }
        renderCopy(displaySeconds(holdProgress.remainingMs()));
        if (holdProgress.needsUpdate()) main.postDelayed(holdTick, 50L);
    }

    /** 松手后结算本次按压并开始加速倒退，不影响独立休息期限。 */
    private void releaseHold() {
        if (completed) return;
        holdProgress.setHolding(false, SystemClock.elapsedRealtime());
        updateHold();
    }

    /**
     * @param milliseconds 精确剩余时长 @return 展示用向上取整秒数
     */
    private static long displaySeconds(long milliseconds) { return Math.max(1L, (milliseconds + 999L) / 1000L); }
}
