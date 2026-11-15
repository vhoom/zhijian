package github.vhoom.zhijian.ui.interception.page.support;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;

import github.vhoom.zhijian.R;

/**
 * 数独完成展示：数字键退场、终盘上移、语句与操作出现；暂停时直接收束到最终状态。
 *
 * 维护日期：2026年11月14日
 */
public final class SudokuCompletionPresentation {
    private static final long RETIRE_DURATION_MS = 180L;
    private static final long MOVE_DURATION_MS = 260L;
    private static final long REVEAL_DURATION_MS = 200L;
    private final SudokuPanelLayout panel;
    private final View keys;
    private final TextView message;
    private final ChallengeCompletionAction action;
    private ValueAnimator movement;
    private boolean presenting;
    private boolean shown;

    /** @param panel 棋盘与文案布局 @param keys 九宫格数字键区 @param message 本局完成语句 @param action 已绑定状态机事件的继续按钮 */
    public SudokuCompletionPresentation(SudokuPanelLayout panel, View keys, TextView message,
            ChallengeCompletionAction action) {
        this.panel = panel;
        this.keys = keys;
        this.message = message;
        this.action = action;
    }

    /** @param animate 新完成时播放退场，系统重建时直接显示最终状态 */
    public void show(boolean animate) {
        if (shown || presenting) return;
        presenting = true;
        if (!animate || !ValueAnimator.areAnimatorsEnabled() || !keys.isAttachedToWindow() || !keys.isLaidOut()) {
            finish();
            return;
        }
        keys.animate().alpha(0f).translationY(keys.getResources().getDimension(R.dimen.space_xs))
                .setDuration(RETIRE_DURATION_MS).setInterpolator(new AccelerateInterpolator())
                .withEndAction(this::moveBoard).start();
    }

    /** 数字键退场后预留最终按钮高度，将终盘平滑移动到完成文案上方。 */
    private void moveBoard() {
        if (!presenting) return;
        action.prepare();
        panel.beginCompletion();
        movement = ValueAnimator.ofFloat(0f, 1f);
        movement.setDuration(MOVE_DURATION_MS);
        movement.setInterpolator(new DecelerateInterpolator());
        movement.addUpdateListener(animation -> panel.setCompletionProgress((float) animation.getAnimatedValue()));
        movement.addListener(new AnimatorListenerAdapter() {
            /** @param animation 已结束的终盘位移动画 */
            @Override public void onAnimationEnd(Animator animation) {
                movement = null;
                if (presenting) reveal();
            }
        });
        movement.start();
    }

    /** 数盘就位后淡入完成语句；继续按钮同步浮出，动画结束前保持禁用。 */
    private void reveal() {
        message.setAlpha(0f);
        message.setVisibility(View.VISIBLE);
        action.show(true);
        message.animate().alpha(1f).setDuration(REVEAL_DURATION_MS)
                .setInterpolator(new DecelerateInterpolator()).withEndAction(this::finish).start();
    }

    /** 统一提交可恢复的最终展示，取消所有剩余回调且不触发导航。 */
    private void finish() {
        if (!presenting) return;
        presenting = false;
        shown = true;
        keys.animate().cancel();
        message.animate().cancel();
        if (movement != null) {
            movement.removeAllListeners();
            movement.removeAllUpdateListeners();
            movement.cancel();
            movement = null;
        }
        action.prepare();
        panel.beginCompletion();
        panel.setCompletionProgress(1f);
        keys.setAlpha(0f);
        keys.setTranslationY(0f);
        keys.setVisibility(View.INVISIBLE);
        message.setAlpha(1f);
        message.setVisibility(View.VISIBLE);
        action.show(false);
    }

    /** 后台不保留动画回调；已经完成时保留继续操作，但不自动推进流程。 */
    public void pause() {
        if (presenting) finish();
        action.finishAnimation();
    }
}
