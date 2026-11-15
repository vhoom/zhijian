package github.vhoom.zhijian.ui.interception.page.support;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;

/**
 * 已提交页面之间的不透明位移过渡；动画不持有流程回调，也不决定挑战是否完成。
 *
 * 维护日期：2026年11月11日
 */
public final class PageTransition {
    private AnimatorSet animation;
    private ViewGroup container;
    private View outgoing;
    private View incoming;

    /** @return 是否正在过渡，期间不接受新页面操作以免完成手势误触 */
    public boolean isRunning() { return animation != null; }

    /**
     * 旧页面已从布局移除，新阶段已同步装配；先移出旧视图，再显示并移入新页面。
     * @param host 新页面容器
     * @param previous 刚移除的旧页面
     * @param next 已装配的新页面
     */
    public void start(ViewGroup host, View previous, View next) {
        cancel();
        if (!ValueAnimator.areAnimatorsEnabled() || !host.isAttachedToWindow()
                || previous == null || previous.getWidth() == 0) return;
        container = host;
        outgoing = previous;
        incoming = next;
        host.getOverlay().add(previous);
        float shift = 8f * host.getResources().getDisplayMetrics().density;
        next.setAlpha(1f);
        next.setVisibility(View.INVISIBLE);
        next.setTranslationY(shift);
        AnimatorSet leave = new AnimatorSet();
        leave.playTogether(ObjectAnimator.ofFloat(previous, View.TRANSLATION_Y, 0f, -shift));
        leave.setDuration(160L);
        AnimatorSet enter = new AnimatorSet();
        enter.playTogether(ObjectAnimator.ofFloat(next, View.TRANSLATION_Y, shift, 0f));
        enter.setDuration(200L);
        enter.addListener(new AnimatorListenerAdapter() {
            /** @param started 已开始的新页位移动画；移除旧画面后再显示不透明的新页 */
            @Override public void onAnimationStart(Animator started) {
                host.getOverlay().remove(previous);
                next.setVisibility(View.VISIBLE);
            }
        });
        animation = new AnimatorSet();
        animation.setInterpolator(new DecelerateInterpolator());
        animation.playSequentially(leave, enter);
        animation.addListener(new AnimatorListenerAdapter() {
            /** @param finished 已结束的视觉动画；最终页面始终完整可见 */
            @Override public void onAnimationEnd(Animator finished) { cancel(); }
        });
        animation.start();
    }

    /** 暂停、重建或新请求时立即移除旧画面，保持已经完成的最终状态。 */
    public void cancel() {
        if (animation != null) {
            animation.removeAllListeners();
            animation.cancel();
            animation = null;
        }
        if (outgoing != null) {
            container.getOverlay().remove(outgoing);
            outgoing.setAlpha(1f);
            outgoing.setTranslationY(0f);
        }
        if (incoming != null) {
            incoming.setAlpha(1f);
            incoming.setVisibility(View.VISIBLE);
            incoming.setTranslationY(0f);
        }
        outgoing = null;
        incoming = null;
        container = null;
    }
}
