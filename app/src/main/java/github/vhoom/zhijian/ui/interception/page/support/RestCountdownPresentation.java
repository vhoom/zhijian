package github.vhoom.zhijian.ui.interception.page.support;

import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.widget.TextView;
import github.vhoom.zhijian.R;

/**
 * 休息状态文本适配器，不控制可见性、流程或休息时钟。
 *
 * 维护日期：2026年11月08日
 */
public final class RestCountdownPresentation {
    private final TextView view;

    /**
     * @param view 隐藏的休息状态文本
     */
    public RestCountdownPresentation(TextView view) { this.view = view; }

    /**
     * @param remainingMs 统一时钟计算的剩余毫秒，显示向上取整秒数
     */
    public void update(long remainingMs) {
        long seconds = remainingMs <= 0 ? 0 : (remainingMs - 1L) / 1000L + 1L;
        String text = view.getContext().getString(R.string.interception_rest_seconds, seconds);
        SpannableString styled = new SpannableString(text);
        int end = text.indexOf(' ');
        float scaledDensity = view.getResources().getDisplayMetrics().scaledDensity;
        styled.setSpan(new AbsoluteSizeSpan(Math.round(20f * scaledDensity)), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        styled.setSpan(new StyleSpan(Typeface.BOLD), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        styled.setSpan(new ForegroundColorSpan(view.getContext().getColor(R.color.ink)), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        view.setText(styled);
        view.setContentDescription(text);
    }
}
