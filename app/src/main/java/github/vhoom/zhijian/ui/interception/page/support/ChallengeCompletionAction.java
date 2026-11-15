package github.vhoom.zhijian.ui.interception.page.support;

import android.animation.ValueAnimator;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.LinearLayout;

import github.vhoom.zhijian.R;

/**
 * 挑战完成操作的展示适配器：保留退出按钮，仅新增并浮出继续操作。
 *
 * 维护日期：2026年11月14日
 */
public final class ChallengeCompletionAction {
    private static final long REVEAL_DURATION_MS = 200L;
    private final LinearLayout actions;
    private final Button button;
    private boolean revealing;

    /**
     * 创建尚未加入页面的操作，真实导航仍由页面事件交给状态机。
     * @param actions 当前页面已有的底部操作容器
     * @param appearance 当前会话的文案与主题适配器
     * @param continueId 新增按钮的独立标识
     * @param onContinue 新的一次点击应报告的继续操作
     */
    public ChallengeCompletionAction(LinearLayout actions, PageAppearance appearance,
            int continueId, Runnable onContinue) {
        this.actions = actions;
        button = new Button(actions.getContext());
        button.setId(continueId);
        appearance.bindCompletedChallengeAction(button);
        button.setEnabled(false);
        button.setOnClickListener(view -> {
            if (!revealing && button.isShown()) onContinue.run();
        });
    }

    /**
     * 在退出按钮后新增操作；恢复页面时直接显示，不重新播放完成动画。
     * @param animate 是否由本次挑战完成触发按钮浮出
     */
    public void show(boolean animate) {
        boolean reveal = button.getParent() == null || button.getVisibility() != View.VISIBLE;
        attachButton();
        button.setVisibility(View.VISIBLE);
        finishAnimation();
        if (!reveal || !animate || !ValueAnimator.areAnimatorsEnabled()
                || !actions.isAttachedToWindow() || !actions.isLaidOut()) return;
        revealing = true;
        button.setEnabled(false);
        button.setTranslationY(actions.getResources().getDimension(R.dimen.space_md));
        button.animate().translationY(0f)
                .setDuration(REVEAL_DURATION_MS)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(this::finishAnimation)
                .start();
    }

    /** 预留继续按钮的最终高度，保持隐藏和禁用，供完成内容计算可用空间。 */
    public void prepare() {
        if (button.getParent() != null) return;
        attachButton();
        button.setVisibility(View.INVISIBLE);
        button.setEnabled(false);
    }

    /** 将继续按钮接入布局；预留空间和正式显示共用同一组尺寸。 */
    private void attachButton() {
        if (button.getParent() != null) return;
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = actions.getResources().getDimensionPixelSize(R.dimen.space_xs);
        actions.addView(button, params);
    }

    /** @param labelRes 仍有后续页面时使用的操作文字资源 */
    public void setLabel(int labelRes) { button.setText(labelRes); }

    /** 停止浮出动画并保留完整操作，完成手势和动画期间均不能触发继续。 */
    public void finishAnimation() {
        button.animate().cancel();
        revealing = false;
        button.setAlpha(1f);
        button.setTranslationY(0f);
        button.setEnabled(button.getParent() != null && button.getVisibility() == View.VISIBLE);
    }
}
