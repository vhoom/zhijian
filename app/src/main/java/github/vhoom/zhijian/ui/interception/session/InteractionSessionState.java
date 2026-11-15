package github.vhoom.zhijian.ui.interception.session;

import android.os.Bundle;

import github.vhoom.zhijian.data.model.interception.InterceptionChallenge;
import github.vhoom.zhijian.data.model.settings.InteractionSettings;
import github.vhoom.zhijian.data.model.settings.InterceptionOptions;
import github.vhoom.zhijian.data.model.settings.SecondsRange;
import github.vhoom.zhijian.data.model.settings.WordChallengeSettings;
import github.vhoom.zhijian.data.model.settings.SudokuDifficulty;
import github.vhoom.zhijian.data.model.word.BuiltInWordCatalog;

import java.util.ArrayList;
import java.util.EnumSet;

/**
 * 会话交互配置的系统重建快照；设置修改只影响之后新建的会话。
 *
 * 维护日期：2026年11月14日
 */
public final class InteractionSessionState {
    private static final String PREFIX = "interception.interaction.";

    /** 工具类禁止实例化。 */
    private InteractionSessionState() { }

    /**
     * 保存当前模式的配置副本与选中挑战。
     * @param state 系统重建容器
     * @param settings 本会话不可变配置
     * @param challenge 当前选中的挑战，无挑战的模式可为空
     */
    public static void save(Bundle state, InteractionSettings settings, InterceptionChallenge challenge) {
        ArrayList<String> enabled = new ArrayList<>();
        for (InterceptionChallenge value : settings.enabledChallenges) enabled.add(value.name());
        state.putStringArrayList(PREFIX + "enabled", enabled);
        state.putLong(PREFIX + "min", settings.holdRange.minMillis);
        state.putLong(PREFIX + "max", settings.holdRange.maxMillis);
        state.putInt(PREFIX + "count", settings.word.questionCount);
        state.putLong(PREFIX + "dwell", settings.word.minDwellMillis);
        state.putString(PREFIX + "catalog", settings.word.catalog.id);
        state.putString(PREFIX + "challenge", challenge == null ? "" : challenge.name());
        state.putBoolean(PREFIX + "temporaryPass", settings.options.temporaryPassEnabled);
        state.putBoolean(PREFIX + "obstaclePages", settings.options.obstaclePagesEnabled);
        state.putBoolean(PREFIX + "dynamicButtons", settings.options.dynamicButtonsEnabled);
        state.putBoolean(PREFIX + "confirmation", settings.options.confirmationEnabled);
        state.putString(PREFIX + "sudokuDifficulty", settings.sudokuDifficulty.name());
    }

    /**
     * 恢复原配置，缺失或损坏状态使用当前合法配置。
     * @param state 原会话的系统状态
     * @param fallback 缺失时的合法配置
     * @return 还原的不可变配置
     */
    public static InteractionSettings restore(Bundle state, InteractionSettings fallback) {
        if (!state.containsKey(PREFIX + "enabled")) return fallback;
        try {
            if (!(state.get(PREFIX + "temporaryPass") instanceof Boolean)
                    || !(state.get(PREFIX + "obstaclePages") instanceof Boolean)
                    || !(state.get(PREFIX + "dynamicButtons") instanceof Boolean)
                    || !(state.get(PREFIX + "confirmation") instanceof Boolean)) return fallback;
            EnumSet<InterceptionChallenge> enabled = EnumSet.noneOf(InterceptionChallenge.class);
            for (String value : state.getStringArrayList(PREFIX + "enabled")) enabled.add(InterceptionChallenge.valueOf(value));
            return new InteractionSettings(enabled, new SecondsRange(state.getLong(PREFIX + "min"),
                    state.getLong(PREFIX + "max")), new WordChallengeSettings(state.getInt(PREFIX + "count"),
                    state.getLong(PREFIX + "dwell"), BuiltInWordCatalog.fromId(
                            state.getString(PREFIX + "catalog", BuiltInWordCatalog.JUNIOR_HIGH.id))),
                    new InterceptionOptions(state.getBoolean(PREFIX + "temporaryPass"),
                            state.getBoolean(PREFIX + "obstaclePages"), state.getBoolean(PREFIX + "dynamicButtons"),
                            state.getBoolean(PREFIX + "confirmation")),
                    SudokuDifficulty.fromName(state.getString(PREFIX + "sudokuDifficulty")));
        } catch (RuntimeException invalidState) { return fallback; }
    }

    /**
     * 恢复原挑战选择；空选择使用配置兜底，不因单词开关变化切换有效挑战。
     * @param state 原会话系统状态
     * @param settings 本会话恢复配置
     * @param sessionId 稳定选择种子
     * @return 原挑战或回退选择
     */
    public static InterceptionChallenge restoreChallenge(Bundle state, InteractionSettings settings, String sessionId) {
        if (!settings.options.obstaclePagesEnabled) return null;
        if (settings.enabledChallenges.isEmpty()) return InterceptionChallenge.BUTTON_HOLD;
        if (!state.containsKey(PREFIX + "challenge")) return settings.selectChallenge(sessionId);
        String value = state.getString(PREFIX + "challenge", "");
        if (value.isEmpty()) return settings.selectChallenge(sessionId);
        try { return InterceptionChallenge.valueOf(value); }
        catch (IllegalArgumentException invalidState) { return settings.selectChallenge(sessionId); }
    }
}
