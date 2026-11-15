package github.vhoom.zhijian.data.store.settings;

import android.content.Context;
import android.content.SharedPreferences;

import github.vhoom.zhijian.data.model.interception.InterceptionMode;
import github.vhoom.zhijian.data.model.policy.ControlLevel;
import github.vhoom.zhijian.data.model.settings.SecondsRange;
import github.vhoom.zhijian.data.model.settings.ThemeMode;
import github.vhoom.zhijian.data.model.settings.WordChallengeSettings;
import github.vhoom.zhijian.data.store.ZhijianStorage;
import github.vhoom.zhijian.util.config.AppConfig;

import java.util.concurrent.CompletableFuture;

/**
 * 跨页面、跨启动保留的全局设置。
 * 分工：AppConfig 是 config.xml 里的出厂值，这里只存用户在设置页改过的覆盖值。
 *
 * 维护日期：2026年11月14日
 */
public final class GlobalSettings {

    private static final String FILE = "global_settings";
    private static final String KEY_THEME = "theme_mode";
    private static final String KEY_ROW_LIMIT = "usage_row_limit";
    private static final String KEY_CALCULATED_ROWS = "usage_calculated_rows";
    private static final String KEY_SHOW_SETUP_GUIDE = "show_setup_guide";
    private static final String KEY_SHOW_DAILY_POEM = "show_daily_poem";
    private static final String KEY_DAILY_RESET_MINUTE = "daily_reset_minute";

    public static final int DEFAULT_DAILY_RESET_MINUTE = 0;
    public static final long MIN_INTERACTION_MILLIS = SecondsRange.MIN_MILLIS;
    public static final long MAX_INTERACTION_MILLIS = SecondsRange.MAX_MILLIS;

    /** 0 = 未设置额度：没有真实规则组时，剩余时间与进度条都不出现。 */
    public static final int NO_QUOTA = 0;

    /** 覆盖位的缺省：不能拿 0 当「没设过」，否则用户真想要 0 的时候没法区分。 */
    private static final int UNSET = -1;

    private final Context context;
    private final SharedPreferences sp;
    private final AppConfig config;
    private final ZhijianStorage storage;
    private final InteractionSettingsStore interactions;

    /**
     * 创建 GlobalSettings 实例，并初始化本次操作依赖或不可变字段。
     *
     * @param context 访问系统服务、资源或持久化文件的上下文
     * @param config 从资源读取的出厂运行参数
     */
    public GlobalSettings(Context context, AppConfig config) {
        this.context = context;
        this.sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        this.config = config;
        this.storage = ZhijianStorage.get(context);
        this.interactions = new InteractionSettingsStore(context, config);
    }

    /**
     * 配色模式单独开一个静态读法：主题基类要在窗口资源被建出来之前拿到它才能改写资源配置，
     * 那时候连 SharedPreferences 都不该顺手建在 Activity 上。
     *
     * @param context 访问系统服务、资源或持久化文件的上下文
     */
    public static ThemeMode readThemeMode(Context context) {
        SharedPreferences sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        return ThemeMode.of(readInt(sp, KEY_THEME, ThemeMode.SYSTEM.ordinal()));
    }

    /**
     * 读取持久化配色模式。
     */
    public ThemeMode themeMode() {
        return readThemeMode(context);
    }

    /**
     * 保存配色模式，供页面重建时选择资源。
     *
     * @param mode 界面配色模式或设置文件的访问模式
     */
    public void setThemeMode(ThemeMode mode) {
        sp.edit().putInt(KEY_THEME, mode.ordinal()).apply();
    }

    /**
     * 读取当前已提交的全局管控档位。
     */
    public ControlLevel level() {
        return storage.snapshot().level();
    }

    /**
     * 事务更新全局管控档位，提交成功后发布新的策略快照。
     *
     * @param level 全局管控档位
     */
    public CompletableFuture<Void> setLevel(ControlLevel level) {
        return storage.setLevel(level);
    }

    /**
     * 读取全局每日用量额度，单位为毫秒。
     */
    public long dailyQuotaMillis() {
        return storage.snapshot().dailyQuotaMillis();
    }

    /**
     * 提交全局每日额度，单位为毫秒。
     *
     * @param durationMillis 配置或记录的时长，单位为毫秒
     */
    public CompletableFuture<Void> setDailyQuotaMillis(long durationMillis) {
        return storage.setDailyQuota(durationMillis);
    }

    /** 首页用量榜的实际条数：手动覆盖优先，否则使用最近一次实测计算的数字。 */
    public int usageRowLimit() {
        int saved = readInt(sp, KEY_ROW_LIMIT, UNSET);
        return saved <= 0 ? Math.max(1, readInt(sp, KEY_CALCULATED_ROWS, config.usageRowLimit())) : saved;
    }

    /** @return 是否存在用户明确输入的正整数覆盖值 */
    public boolean hasUsageRowLimitOverride() { return readInt(sp, KEY_ROW_LIMIT, UNSET) > 0; }

    /** @param rows 当前首页实测得到的默认数，供设置页直接显示同一个数字 */
    public void setCalculatedUsageRows(int rows) {
        if (rows <= 0) throw new IllegalArgumentException("Positive row count required");
        if (readInt(sp, KEY_CALCULATED_ROWS, UNSET) != rows) {
            sp.edit().putInt(KEY_CALCULATED_ROWS, rows).apply();
        }
    }

    /**
     * 保存首页用量显示条数；非正值删除手动覆盖，恢复实测计算的默认数。
     *
     * @param rows 需要展示或处理的列表行集合
     */
    public void setUsageRowLimit(int rows) {
        // 计算结果和手动覆盖分开保存，布局变化不能覆盖用户手动输入。
        SharedPreferences.Editor editor = sp.edit();
        if (rows <= 0) editor.remove(KEY_ROW_LIMIT);
        else editor.putInt(KEY_ROW_LIMIT, rows);
        editor.apply();
    }

    /**
     * 读取是否在首页显示系统配置指导卡片。
     */
    public boolean showSetupGuide() {
        try {
            return sp.getBoolean(KEY_SHOW_SETUP_GUIDE, true);
        } catch (ClassCastException invalidType) {
            return true;
        }
    }

    /**
     * 保存首页指导卡片的可见性。
     *
     * @param visible 卡片是否应当可见
     */
    public void setShowSetupGuide(boolean visible) {
        sp.edit().putBoolean(KEY_SHOW_SETUP_GUIDE, visible).apply();
    }

    /** @return 首页是否显示每日古诗；未设置或旧偏好类型损坏时默认显示 */
    public boolean showDailyPoem() {
        try {
            return sp.getBoolean(KEY_SHOW_DAILY_POEM, true);
        } catch (ClassCastException invalidType) {
            return true;
        }
    }

    /** @param visible 首页每日古诗是否应当可见；与配置引导开关独立保存 */
    public void setShowDailyPoem(boolean visible) {
        sp.edit().putBoolean(KEY_SHOW_DAILY_POEM, visible).apply();
    }

    /** 返回全天限时、限次规则每天恢复时对应的分钟数。 */
    public int dailyResetMinute() {
        return readDailyResetMinute(context);
    }

    /** @param context 设置读取上下文 @return 每日恢复分钟；不初始化其他存储依赖 */
    public static int readDailyResetMinute(Context context) {
        int saved = readInt(context.getSharedPreferences(FILE, Context.MODE_PRIVATE),
                KEY_DAILY_RESET_MINUTE, DEFAULT_DAILY_RESET_MINUTE);
        return validResetMinute(saved) ? saved : DEFAULT_DAILY_RESET_MINUTE;
    }

    /**
     * 保存全天限时、限次规则的每日恢复时间。
     *
     * @param minute 一天内对应的分钟值
     */
    public void setDailyResetMinute(int minute) {
        sp.edit().putInt(KEY_DAILY_RESET_MINUTE, normalizeResetMinute(minute)).apply();
    }

    /** 返回按钮长按摩擦使用的随机秒数区间。 */
    public SecondsRange buttonFrictionRange() {
        return interactions.holdRange(InterceptionMode.REPAIR);
    }

    /**
     * 保存按钮长按摩擦使用的随机秒数区间。
     *
     * @param range 经过校验的时长区间
     */
    public void setButtonFrictionRange(SecondsRange range) {
        interactions.setHoldRange(InterceptionMode.REPAIR, range);
    }

    /** 返回独立于长按时长的单词摩擦题量与停留时间。 */
    public WordChallengeSettings wordChallengeSettings() {
        return interactions.wordSettings(InterceptionMode.REPAIR);
    }

    /**
     * 原子保存单词摩擦的题量和每题最少停留时间。
     *
     * @param settings 经过边界校验的全局或挑战设置
     */
    public void setWordChallengeSettings(WordChallengeSettings settings) {
        interactions.setWordSettings(InterceptionMode.REPAIR, settings);
    }

    /**
     * 读取整数设置；损坏文件中的类型错误回落到缺省值。
     *
     * @param preferences 全局设置的持久化文件
     * @param key 要读取的设置键
     * @param fallback 缺失或类型不匹配时使用的合法值
     * @return 持久化整数或缺省值
     */
    private static int readInt(SharedPreferences preferences, String key, int fallback) {
        try {
            return preferences.getInt(key, fallback);
        } catch (ClassCastException invalidType) {
            return fallback;
        }
    }

    /**
     * 校验一天内的分钟值，拒绝静默保存无法解释的恢复时间。
     *
     * @param minute 一天内对应的分钟值
     */
    private static int normalizeResetMinute(int minute) {
        if (!validResetMinute(minute)) {
            throw new IllegalArgumentException("Invalid daily reset minute");
        }
        return minute;
    }

    /**
     * 判断分钟值能否表示一天内的恢复时间。
     *
     * @param minute 一天内对应的分钟值
     */
    private static boolean validResetMinute(int minute) {
        return minute >= 0 && minute < 24 * 60;
    }
}
