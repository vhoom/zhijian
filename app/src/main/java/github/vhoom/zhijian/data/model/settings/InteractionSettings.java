package github.vhoom.zhijian.data.model.settings;

import github.vhoom.zhijian.data.model.interception.InterceptionChallenge;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * 一种模式的不可变交互配置快照；会话创建后不跟随设置变化。
 *
 * 维护日期：2026年11月14日
 */
public final class InteractionSettings {
    public final Set<InterceptionChallenge> enabledChallenges;
    public final SecondsRange holdRange;
    public final WordChallengeSettings word;
    public final InterceptionOptions options;
    public final SudokuDifficulty sudokuDifficulty;

    /**
     * 保存独立挑战配置并复制启用集合，避免调用方修改会话数据。
     * @param enabledChallenges 本模式启用的挑战
     * @param holdRange 本模式长按时长区间
     * @param word 本模式单词题量与停留时间
     * @param options 本次会话的放行与阻碍页面开关
     */
    public InteractionSettings(Set<InterceptionChallenge> enabledChallenges, SecondsRange holdRange,
            WordChallengeSettings word, InterceptionOptions options) {
        this(enabledChallenges, holdRange, word, options, SudokuDifficulty.EASY);
    }

    /** @param enabledChallenges 开启的挑战 @param holdRange 按压区间 @param word 单词配置 @param options 流程开关 @param sudokuDifficulty 数独新局难度 */
    public InteractionSettings(Set<InterceptionChallenge> enabledChallenges, SecondsRange holdRange,
            WordChallengeSettings word, InterceptionOptions options, SudokuDifficulty sudokuDifficulty) {
        this.enabledChallenges = Collections.unmodifiableSet(enabledChallenges.isEmpty()
                ? EnumSet.noneOf(InterceptionChallenge.class) : EnumSet.copyOf(enabledChallenges));
        this.holdRange = holdRange;
        this.word = word;
        this.options = options;
        this.sudokuDifficulty = java.util.Objects.requireNonNull(sudokuDifficulty);
    }

    /**
     * 阻碍页面关闭时不选挑战；开启时从启用集合稳定选择，空配置回退按压。
     * @param sessionId 同一拦截会话的稳定标识
     * @return 选中的挑战；阻碍页面关闭时为空
     */
    public InterceptionChallenge selectChallenge(String sessionId) {
        if (!options.obstaclePagesEnabled) return null;
        InterceptionChallenge[] available = enabledChallenges.toArray(new InterceptionChallenge[0]);
        return available.length == 0 ? InterceptionChallenge.BUTTON_HOLD
                : available[Math.floorMod(sessionId.hashCode(), available.length)];
    }
}
