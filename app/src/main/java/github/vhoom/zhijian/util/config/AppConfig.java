package github.vhoom.zhijian.util.config;

import android.content.Context;
import android.content.res.Resources;

import github.vhoom.zhijian.R;

/**
 * 运行参数的类型化入口，值全部来自 res/values/config.xml。
 *
 * 维护日期：2026年11月08日
 */
public final class AppConfig {

    private final int usageRefreshIntervalMs;
    private final int usageRowLimit;
    private final int appIconCacheSize;
    private final int defaultPassMinutes;
    private final int buttonFrictionMinSeconds;
    private final int buttonFrictionMaxSeconds;
    private final int wordChallengeQuestionCount;
    private final int wordChallengeMinDwellSeconds;
    private final int pullSettleDurationMs;
    private final int pullMaxHeaderRatio;
    private final float pullDamping;
    private final float swipeDragFactor;
    private final float swipeFlipThreshold;

    /**
     * 从资源读取本次进程使用的出厂配置。
     *
     * @param context 访问系统服务、资源或持久化文件的上下文
     */
    public AppConfig(Context context) {
        Resources res = context.getResources();
        usageRefreshIntervalMs = res.getInteger(R.integer.usage_refresh_interval_ms);
        usageRowLimit = res.getInteger(R.integer.usage_row_limit);
        appIconCacheSize = res.getInteger(R.integer.app_icon_cache_size);
        defaultPassMinutes = res.getInteger(R.integer.default_pass_minutes);
        buttonFrictionMinSeconds = res.getInteger(R.integer.button_friction_min_seconds);
        buttonFrictionMaxSeconds = res.getInteger(R.integer.button_friction_max_seconds);
        wordChallengeQuestionCount = res.getInteger(R.integer.word_challenge_question_count);
        wordChallengeMinDwellSeconds = res.getInteger(
                R.integer.word_challenge_min_dwell_seconds);
        pullSettleDurationMs = res.getInteger(R.integer.pull_settle_duration_ms);
        pullMaxHeaderRatio = res.getInteger(R.integer.pull_max_header_ratio);
        // 资源里没有 float 类型，系数在 config.xml 里以字符串维护，写错就当场崩在启动阶段。
        pullDamping = Float.parseFloat(res.getString(R.string.pull_damping));
        swipeDragFactor = Float.parseFloat(res.getString(R.string.swipe_drag_factor));
        swipeFlipThreshold = Float.parseFloat(res.getString(R.string.swipe_flip_threshold));
        validate();
    }

    /** 启动阶段检查基础资源的取值范围，避免非法缓存、刷新间隔和手势参数进入页面。 */
    private void validate() {
        if (usageRefreshIntervalMs <= 0 || usageRowLimit <= 0 || appIconCacheSize <= 0
                || defaultPassMinutes <= 0 || pullSettleDurationMs < 0 || pullMaxHeaderRatio <= 0
                || !Float.isFinite(pullDamping) || pullDamping <= 0
                || !Float.isFinite(swipeDragFactor) || swipeDragFactor <= 0
                || !Float.isFinite(swipeFlipThreshold) || swipeFlipThreshold <= 0 || swipeFlipThreshold > 1) {
            throw new IllegalArgumentException("Invalid application configuration resources");
        }
    }

    /**
     * 返回用量刷新间隔，单位为毫秒。
     */
    public int usageRefreshIntervalMs() {
        return usageRefreshIntervalMs;
    }

    /**
     * 返回首页用量列表显示的最大行数。
     */
    public int usageRowLimit() {
        return usageRowLimit;
    }

    /**
     * 返回应用图标缓存允许保留的条目数。
     */
    public int appIconCacheSize() {
        return appIconCacheSize;
    }

    /**
     * 返回全局策略的缺省临时放行分钟数。
     */
    public int defaultPassMinutes() {
        return defaultPassMinutes;
    }

    /** 返回按钮长按摩擦出厂区间的最小秒数。 */
    public int buttonFrictionMinSeconds() {
        return buttonFrictionMinSeconds;
    }

    /** 返回按钮长按摩擦出厂区间的最大秒数。 */
    public int buttonFrictionMaxSeconds() {
        return buttonFrictionMaxSeconds;
    }



    /** 返回单词摩擦的出厂题量。 */
    public int wordChallengeQuestionCount() {
        return wordChallengeQuestionCount;
    }

    /** 返回单词摩擦每题的出厂最少停留秒数。 */
    public int wordChallengeMinDwellSeconds() {
        return wordChallengeMinDwellSeconds;
    }

    /**
     * 返回下拉刷新回弹动画时长，单位为毫秒。
     */
    public int pullSettleDurationMs() {
        return pullSettleDurationMs;
    }

    /**
     * 返回下拉距离相对提示头部高度的最大倍数。
     */
    public int pullMaxHeaderRatio() {
        return pullMaxHeaderRatio;
    }

    /**
     * 返回下拉手势距离换算为内容偏移的阻尼系数。
     */
    public float pullDamping() {
        return pullDamping;
    }

    /**
     * 返回横向拖动距离的缩放系数。
     */
    public float swipeDragFactor() {
        return swipeDragFactor;
    }

    /**
     * 返回触发翻页需要超过的页面宽度比例。
     */
    public float swipeFlipThreshold() {
        return swipeFlipThreshold;
    }
}
