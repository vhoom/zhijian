package github.vhoom.zhijian.data.store.settings;

import android.content.Context;
import android.content.SharedPreferences;

import github.vhoom.zhijian.data.model.interception.InterceptionChallenge;
import github.vhoom.zhijian.data.model.interception.InterceptionMode;
import github.vhoom.zhijian.data.model.settings.InteractionSettings;
import github.vhoom.zhijian.data.model.settings.InterceptionOptions;
import github.vhoom.zhijian.data.model.settings.ChallengeSelectionPolicy;
import github.vhoom.zhijian.data.model.settings.SecondsRange;
import github.vhoom.zhijian.data.model.settings.WordChallengeSettings;
import github.vhoom.zhijian.data.model.settings.SudokuDifficulty;
import github.vhoom.zhijian.data.model.word.BuiltInWordCatalog;
import github.vhoom.zhijian.util.config.AppConfig;
import github.vhoom.zhijian.data.store.ZhijianStorage;

import java.util.EnumSet;

/**
 * 模式独立的流程开关与修心挑战参数；禁用与提醒共用当前强度配置。
 *
 * 维护日期：2026年11月14日
 */
public final class InteractionSettingsStore {
    private final SharedPreferences preferences;
    private final AppConfig config;
    private final Context applicationContext;

    /**
     * 打开交互设置，不依赖数据库、页面或全局设置实例。
     * @param context 设置文件和资源上下文
     * @param config 交互参数出厂值
     */
    public InteractionSettingsStore(Context context, AppConfig config) {
        this.preferences = context.getSharedPreferences("global_settings", Context.MODE_PRIVATE);
        this.config = config;
        this.applicationContext = context.getApplicationContext();
    }

    /**
     * 读取当前模式的不可变配置，非交互模式始终没有挑战。
     * @param mode 配置所属模式
     * @return 经过校验的独立快照
     */
    public InteractionSettings read(InterceptionMode mode) {
        synchronized (InteractionSettingsStore.class) {
            return new InteractionSettings(enabledChallenges(mode), holdRange(mode), wordSettings(mode), readOptions(mode), sudokuDifficulty());
        }
    }

    /** @param mode 选项所属模式 @return 类型损坏时使用该模式默认值的不可变开关快照 */
    public InterceptionOptions readOptions(InterceptionMode mode) {
        synchronized (InteractionSettingsStore.class) {
            InterceptionOptions defaults = InterceptionOptions.defaultsFor(mode);
            return new InterceptionOptions(mode != InterceptionMode.SEAL
                    && readBoolean(mode.name() + ".temporary_pass.enabled", defaults.temporaryPassEnabled),
                    mode == InterceptionMode.REPAIR
                    && readBoolean(mode.name() + ".obstacle_pages.enabled", defaults.obstaclePagesEnabled),
                    mode != InterceptionMode.WUWEI
                    || readBoolean(mode.name() + ".dynamic_buttons.enabled", defaults.dynamicButtonsEnabled),
                    mode != InterceptionMode.REPAIR
                    || readBoolean(mode.name() + ".confirmation.enabled", defaults.confirmationEnabled));
        }
    }

    /** @param mode 无为或修心 @param enabled 是否允许之后新会话签发临时放行；不撤销已有期限 */
    public void setTemporaryPassEnabled(InterceptionMode mode, boolean enabled) {
        if (mode != InterceptionMode.WUWEI && mode != InterceptionMode.REPAIR) {
            throw new IllegalArgumentException("Mode cannot issue temporary usage");
        }
        synchronized (InteractionSettingsStore.class) {
            preferences.edit().putBoolean(mode.name() + ".temporary_pass.enabled", enabled).apply();
        }
    }

    /** @param enabled 无为是否使用随机样式；修心首屏与反问方案保持独立 */
    public void setDynamicButtonsEnabled(boolean enabled) {
        synchronized (InteractionSettingsStore.class) {
            preferences.edit().putBoolean(InterceptionMode.WUWEI.name() + ".dynamic_buttons.enabled", enabled).apply();
        }
    }

    /** @param enabled 修心挑战后是否进入反问页；阻碍页面关闭时保留该设置 */
    public void setConfirmationEnabled(boolean enabled) {
        synchronized (InteractionSettingsStore.class) {
            preferences.edit().putBoolean(InterceptionMode.REPAIR.name() + ".confirmation.enabled", enabled).apply();
        }
    }

    /** @param enabled 修心是否展示挑战及其后续选择；关闭时仍保留挑战开关及参数 */
    public void setObstaclePagesEnabled(boolean enabled) {
        synchronized (InteractionSettingsStore.class) {
            preferences.edit().putBoolean(InterceptionMode.REPAIR.name() + ".obstacle_pages.enabled", enabled).apply();
        }
    }

    /**
     * 读取经模式策略规范的独立开关。
     * @param mode 开关所属模式
     * @param challenge 独立挑战类型
     * @return 是否启用
     */
    public boolean isEnabled(InterceptionMode mode, InterceptionChallenge challenge) {
        synchronized (InteractionSettingsStore.class) {
            return enabledChallenges(mode).contains(challenge);
        }
    }

    /**
     * 读取修心独立挑战开关；默认只开启按压，已有选择优先，集合为空时回退按压。
     * @param mode 配置所属模式
     * @return 无循环依赖的有效开关集合
     */
    private EnumSet<InterceptionChallenge> enabledChallenges(InterceptionMode mode) {
        EnumSet<InterceptionChallenge> enabled = EnumSet.noneOf(InterceptionChallenge.class);
        if (mode == InterceptionMode.REPAIR) {
            for (InterceptionChallenge challenge : InterceptionChallenge.values()) {
                boolean fallback = challenge == InterceptionChallenge.BUTTON_HOLD;
                boolean selected;
                try { selected = preferences.getBoolean(enabledKey(mode, challenge), fallback); }
                catch (ClassCastException invalidType) { selected = fallback; }
                if (selected) enabled.add(challenge);
            }
        }
        return ChallengeSelectionPolicy.normalize(mode, enabled);
    }

    /**
     * 原子保存修心开关，最后一种挑战不能关闭；不修改挑战参数。
     * @param mode 开关所属模式
     * @param challenge 独立挑战类型
     * @param enabled 是否启用该选项
     */
    public void setEnabled(InterceptionMode mode, InterceptionChallenge challenge, boolean enabled) {
        requireInteractive(mode);
        synchronized (InteractionSettingsStore.class) {
            EnumSet<InterceptionChallenge> selected = ChallengeSelectionPolicy.change(
                    mode, enabledChallenges(mode), challenge, enabled);
            SharedPreferences.Editor editor = preferences.edit();
            for (InterceptionChallenge option : InterceptionChallenge.values()) {
                editor.putBoolean(enabledKey(mode, option), selected.contains(option));
            }
            editor.apply();
        }
    }

    /**
     * 生成模式与挑战共同组成的稳定键。
     * @param mode 开关所属模式
     * @param challenge 独立挑战类型
     * @return 不与其他模式共用的键
     */
    private String enabledKey(InterceptionMode mode, InterceptionChallenge challenge) {
        return mode.name() + ".challenge." + challenge.name() + ".enabled";
    }

    /** @return 修心数独新局的难度；损坏设置回退简单，不改写已有棋盘 */
    public SudokuDifficulty sudokuDifficulty() {
        synchronized (InteractionSettingsStore.class) {
            return SudokuDifficulty.fromName(readString("sudoku_difficulty", SudokuDifficulty.EASY.name()));
        }
    }

    /** @param difficulty 用户选择的新局难度 */
    public void setSudokuDifficulty(SudokuDifficulty difficulty) {
        java.util.Objects.requireNonNull(difficulty);
        synchronized (InteractionSettingsStore.class) {
            preferences.edit().putString("sudoku_difficulty", difficulty.name()).apply();
        }
    }

    /**
     * 读取修心长按区间。
     * @param mode 时长所属模式
     * @return 合法长按区间
     */
    public SecondsRange holdRange(InterceptionMode mode) {
        synchronized (InteractionSettingsStore.class) {
            long defaultMin = config.buttonFrictionMinSeconds() * 1000L;
            long defaultMax = config.buttonFrictionMaxSeconds() * 1000L;
            long min = readLong("hold_min_millis", defaultMin);
            long max = readLong("hold_max_millis", defaultMax);
            return validRange(min, max) ? new SecondsRange(min, max)
                    : new SecondsRange(defaultMin, defaultMax);
        }
    }

    /**
     * 原子保存当前模式的长按区间，固定值使用相同上下限。
     * @param mode 时长所属模式
     * @param range 校验通过的长按区间
     */
    public void setHoldRange(InterceptionMode mode, SecondsRange range) {
        requireInteractive(mode);
        if (range == null || !validRange(range.minMillis, range.maxMillis)) {
            throw new IllegalArgumentException("Invalid hold range");
        }
        synchronized (InteractionSettingsStore.class) {
            preferences.edit().putLong("hold_min_millis", range.minMillis)
                    .putLong("hold_max_millis", range.maxMillis).apply();
        }
    }

    /**
     * 读取本模式单词配置，损坏类型与超界值恢复为出厂参数。
     * @param mode 题量和停留时长所属模式
     * @return 合法单词配置
     */
    public WordChallengeSettings wordSettings(InterceptionMode mode) {
        synchronized (InteractionSettingsStore.class) {
            int count = readInt("word_question_count", config.wordChallengeQuestionCount());
            long dwell = readLong("word_min_dwell_millis",
                    config.wordChallengeMinDwellSeconds() * 1000L);
            BuiltInWordCatalog catalog = BuiltInWordCatalog.fromId(readString("word_catalog", ""));
            return WordChallengeSettings.isValid(count, dwell) ? new WordChallengeSettings(count, dwell, catalog)
                    : new WordChallengeSettings(config.wordChallengeQuestionCount(),
                            config.wordChallengeMinDwellSeconds() * 1000L, catalog);
        }
    }

    /**
     * 原子保存本模式的题量与每题停留时长。
     * @param mode 设置所属模式
     * @param settings 合法单词配置
     */
    public void setWordSettings(InterceptionMode mode, WordChallengeSettings settings) {
        requireInteractive(mode);
        if (settings == null) throw new IllegalArgumentException("Word settings required");
        int size = ZhijianStorage.get(applicationContext).wordCatalog(settings.catalog).size();
        if (!WordChallengeSettings.isValidQuestionCount(settings.questionCount, size)) {
            throw new IllegalArgumentException("Question count exceeds word catalog");
        }
        synchronized (InteractionSettingsStore.class) {
            preferences.edit().putInt("word_question_count", settings.questionCount)
                    .putLong("word_min_dwell_millis", settings.minDwellMillis)
                    .putString("word_catalog", settings.catalog.id).apply();
        }
    }

    /**
     * 拒绝向没有交互配置的模式写入设置。
     * @param mode 要写入的模式
     */
    private static void requireInteractive(InterceptionMode mode) {
        if (mode != InterceptionMode.REPAIR) {
            throw new IllegalArgumentException("Mode has no challenges");
        }
    }

    /**
     * 校验长按边界与 0.1 秒精度。
     * @param min 区间最小毫秒数
     * @param max 区间最大毫秒数
     * @return 区间是否有效
     */
    private static boolean validRange(long min, long max) {
        return SecondsRange.isValid(min, max);
    }

    /**
     * 安全读取毫秒值，类型错误回退。
     * @param key 持久化键
     * @param fallback 缺省值
     * @return 保存值或缺省值
     */
    private long readLong(String key, long fallback) {
        try { return preferences.getLong(key, fallback); }
        catch (ClassCastException invalidType) { return fallback; }
    }

    /** @param key 持久化开关键 @param fallback 模式出厂值 @return 合法布尔值或出厂值 */
    private boolean readBoolean(String key, boolean fallback) {
        try { return preferences.getBoolean(key, fallback); }
        catch (ClassCastException invalidType) { return fallback; }
    }

    /**
     * 安全读取整数，类型错误回退。
     * @param key 持久化键
     * @param fallback 缺省值
     * @return 保存值或缺省值
     */
    private int readInt(String key, int fallback) {
        try { return preferences.getInt(key, fallback); }
        catch (ClassCastException invalidType) { return fallback; }
    }

    /**
     * 安全读取词库标识，缺失或类型损坏时使用缺省值。
     * @param key 持久化键
     * @param fallback 缺省标识
     * @return 保存的标识或缺省值
     */
    private String readString(String key, String fallback) {
        try { return preferences.getString(key, fallback); }
        catch (ClassCastException invalidType) { return fallback; }
    }
}
