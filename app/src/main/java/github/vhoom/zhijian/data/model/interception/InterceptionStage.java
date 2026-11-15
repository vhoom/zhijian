package github.vhoom.zhijian.data.model.interception;

/**
 * 当前独立页面标识；挑战完成态保留原布局等待点击，COMPLETE 为任务结束状态。
 *
 * 维护日期：2026年11月14日
 */
public enum InterceptionStage {
    INITIAL, WORD_TRANSLATION, HOLD, SUDOKU, HOLD_COMPLETED, WORD_COMPLETED, SUDOKU_COMPLETED,
    FINAL_CONFIRM, LOCKED, COMPLETE;

    /** @return 是否已完成挑战且仍等待主动继续，可作为系统重建的稳定阶段 */
    public boolean isCompletedChallenge() {
        return this == HOLD_COMPLETED || this == WORD_COMPLETED || this == SUDOKU_COMPLETED;
    }

    /** @return 当前挑战对应的等待点击阶段；其他阶段不允许转换 */
    public InterceptionStage completedChallenge() {
        if (this == HOLD) return HOLD_COMPLETED;
        if (this == WORD_TRANSLATION) return WORD_COMPLETED;
        if (this == SUDOKU) return SUDOKU_COMPLETED;
        throw new IllegalStateException("Stage is not a challenge: " + this);
    }
}
