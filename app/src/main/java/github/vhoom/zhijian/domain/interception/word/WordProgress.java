package github.vhoom.zhijian.domain.interception.word;

import github.vhoom.zhijian.data.model.settings.WordChallengeSettings;

import java.util.List;

/**
 * 独立于拦截会话的不可变单词进度；答错也标记开始，退出不重置题目与最低停留时间。
 *
 * 维护日期：2026年11月14日
 */
public final class WordProgress {
    public final String attemptId;
    public final String entryId;
    public final long revision;
    public final WordChallengeSettings settings;
    public final WordQuizSession.State state;
    public final String bootId;
    public final boolean started;
    public final String completedSession;

    /**
     * @param attemptId 本局身份及出题种子 @param entryId 本次实际进入标识 @param revision 已提交版本
     * @param settings 本局固定配置 @param state 当前题号及单调解锁时刻 @param bootId 解锁时钟所属启动
     * @param started 是否已经有效选择过答案 @param completedSession 完成会话，未完成为空
     */
    public WordProgress(String attemptId, String entryId, long revision, WordChallengeSettings settings,
            WordQuizSession.State state, String bootId, boolean started, String completedSession) {
        if (attemptId == null || attemptId.isEmpty() || entryId == null || revision < 0 || settings == null
                || state == null || state.questionIndex < 0 || state.questionIndex > settings.questionCount
                || state.unlockAtElapsedMs < 0 || bootId == null || completedSession == null
                || state.questionIndex > 0 && !started
                || (state.questionIndex == settings.questionCount) != !completedSession.isEmpty()) {
            throw new IllegalArgumentException("Invalid word progress");
        }
        this.attemptId = attemptId;
        this.entryId = entryId;
        this.revision = revision;
        this.settings = settings;
        this.state = state;
        this.bootId = bootId;
        this.started = started;
        this.completedSession = completedSession;
    }

    /** @param entries 本局词库 @param settings 本局配置 @param seed 独立种子 @param entryId 进入标识 @param bootId 启动身份 @param now 当前单调毫秒 @return 尚未答题的新局 */
    public static WordProgress start(List<WordEntry> entries, WordChallengeSettings settings, String seed,
            String entryId, String bootId, long now) {
        WordQuizSession quiz = WordQuizSession.start(entries, settings, seed, now);
        return new WordProgress(seed, entryId, 0, settings, quiz.snapshot(), bootId, false, "");
    }

    /** @return 是否已完成本局全部题目 */
    public boolean isComplete() { return !completedSession.isEmpty(); }

    /** @param sessionId 拦截身份 @param requestedEntry 本次进入标识 @return 已作答跨进入恢复，未作答只保留原次进入，完成态只恢复原会话 */
    public boolean resumesIn(String sessionId, String requestedEntry) {
        return isComplete() ? completedSession.equals(sessionId) : started || entryId.equals(requestedEntry);
    }

    /** @param entries 本局固定词库 @return 独立可展示会话；完成态重现最后一题，修改不会影响已提交快照 */
    public WordQuizSession quiz(List<WordEntry> entries) {
        WordQuizSession.State display = isComplete()
                ? new WordQuizSession.State(settings.questionCount - 1, state.unlockAtElapsedMs) : state;
        return WordQuizSession.restore(entries, settings, attemptId, display, state.unlockAtElapsedMs);
    }

    /** @param answered 接受本次答案后的领域状态 @param sessionId 当前拦截身份 @return 下一版本；调用方只在非锁定答案后调用 */
    public WordProgress answered(WordQuizSession.State answered, String sessionId) {
        if (isComplete()) throw new IllegalStateException("Word quiz already completed");
        return new WordProgress(attemptId, entryId, revision + 1, settings, answered, bootId, true,
                answered.questionIndex == settings.questionCount ? sessionId : "");
    }

    /** @param currentBoot 当前启动身份 @param now 当前单调毫秒 @return 重启后为当前未完成题重新建立停留期限，不改变题号与已答状态 */
    public WordProgress onBoot(String currentBoot, long now) {
        if (bootId.equals(currentBoot)) return this;
        long unlock = isComplete() ? now : Math.addExact(now, settings.minDwellMillis);
        return new WordProgress(attemptId, entryId, revision + 1, settings,
                new WordQuizSession.State(state.questionIndex, unlock), currentBoot, started, completedSession);
    }

    /** @return 完成退出后的记录；下一次即使复用拦截身份也会新建挑战 */
    public WordProgress retired() {
        if (!isComplete()) return this;
        return new WordProgress(attemptId, entryId, revision + 1, settings, state, bootId,
                started, "finished:" + completedSession);
    }
}
