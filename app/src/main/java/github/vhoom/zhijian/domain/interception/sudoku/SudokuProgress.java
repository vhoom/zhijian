package github.vhoom.zhijian.domain.interception.sudoku;

import github.vhoom.zhijian.data.model.settings.SudokuDifficulty;

/**
 * 与拦截身份独立的一局进度；版本用于拒绝旧页面回写，完成身份用于同会话恢复。
 *
 * 维护日期：2026年11月14日
 */
public final class SudokuProgress {
    public final String attemptId;
    public final long revision;
    public final SudokuBoard board;
    public final String completedSession;
    public final SudokuDifficulty difficulty;
    public final String entryId;

    /** @param attemptId 本局独立身份 @param revision 已提交版本 @param board 不可变棋盘 @param completedSession 完成本局的拦截身份，未完成为空 */
    public SudokuProgress(String attemptId, long revision, SudokuBoard board, String completedSession) {
        this(attemptId, revision, board, completedSession, SudokuDifficulty.EASY);
    }

    /** @param attemptId 本局身份 @param revision 已提交版本 @param board 固定题面和填写 @param completedSession 完成会话 @param difficulty 本局创建时的难度 */
    public SudokuProgress(String attemptId, long revision, SudokuBoard board, String completedSession,
            SudokuDifficulty difficulty) {
        this(attemptId, revision, board, completedSession, difficulty, "");
    }

    /** @param attemptId 本局身份 @param revision 已保存版本 @param board 棋盘 @param completedSession 完成会话 @param difficulty 本局难度 @param entryId 创建本局的进入标识 */
    public SudokuProgress(String attemptId, long revision, SudokuBoard board, String completedSession,
            SudokuDifficulty difficulty, String entryId) {
        if (attemptId == null || attemptId.isEmpty() || revision < 0 || board == null
                || completedSession == null || difficulty == null || entryId == null
                || board.isComplete() != !completedSession.isEmpty()) {
            throw new IllegalArgumentException("Invalid Sudoku progress");
        }
        this.attemptId = attemptId;
        this.revision = revision;
        this.board = board;
        this.completedSession = completedSession;
        this.difficulty = difficulty;
        this.entryId = entryId;
    }

    /** @param sessionId 当前拦截身份 @return 未完成跨会话恢复，已完成只供原会话恢复 */
    public boolean resumesIn(String sessionId) {
        return completedSession.isEmpty() || completedSession.equals(sessionId);
    }

    /** @return 成功填写过即算开始，之后清空也不重新出题 */
    public boolean hasStarted() { return revision > 0 || board.filledCount() > 0; }

    /** @param sessionId 拦截身份 @param requestedEntryId 本次进入标识 @return 未开始只在原次进入恢复，已开始跨进入保留 */
    public boolean resumesIn(String sessionId, String requestedEntryId) {
        return board.isComplete() ? completedSession.equals(sessionId) : hasStarted() || entryId.equals(requestedEntryId);
    }

    /** @param index 目标格子 @param digit 数字或清除 @param sessionId 当前拦截身份 @return 下一版本候选，尚未提交存储 */
    public SudokuProgress edit(int index, int digit, String sessionId) {
        if (board.isComplete()) throw new IllegalStateException("Sudoku already completed");
        SudokuBoard next = board.withDigit(index, digit);
        return new SudokuProgress(attemptId, revision + 1, next, next.isComplete() ? sessionId : "", difficulty, entryId);
    }
}
