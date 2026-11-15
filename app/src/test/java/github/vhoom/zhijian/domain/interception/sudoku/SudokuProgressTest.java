package github.vhoom.zhijian.domain.interception.sudoku;

import org.junit.Test;
import github.vhoom.zhijian.data.model.settings.SudokuDifficulty;
import static org.junit.Assert.*;

/**
 * 挑战尝试身份不依赖应用或拦截身份，完成边界单独记录。
 *
 * 维护日期：2026年11月14日
 */
public class SudokuProgressTest {
    /** 未开始只恢复原次进入；填写后即使全部清空也跨应用保留。 */
    @Test public void unfinishedAttemptResumesAcrossSessions() {
        SudokuProgress progress = new SudokuProgress("attempt", 0, SudokuPuzzles.create("stable"), "");
        assertTrue(progress.resumesIn("app-a"));
        assertTrue(progress.resumesIn("app-b"));
        assertTrue(progress.resumesIn("app-a", ""));
        assertFalse(progress.resumesIn("app-a", "next-entry"));
        int index = progress.board.clues().indexOf('0');
        SudokuProgress cleared = progress.edit(index, 3, "app-a").edit(index, 0, "app-a");
        assertEquals(0, cleared.board.filledCount());
        assertTrue(cleared.hasStarted());
        assertTrue(cleared.resumesIn("app-b", "next-entry"));
    }

    /** 完成局可恢复原页，下一会话换题，且完成后禁止再次编辑。 */
    @Test public void completedAttemptOnlyResumesItsCompletingSession() {
        String solution = "534678912672195348198342567859761423426853791713924856961537284287419635345286179";
        String clues = "0" + solution.substring(1);
        SudokuProgress before = new SudokuProgress("attempt", 2, new SudokuBoard(clues, clues), "", SudokuDifficulty.HARD);
        SudokuProgress after = before.edit(0, 5, "session-a");
        assertEquals(3, after.revision);
        assertEquals(SudokuDifficulty.HARD, after.difficulty);
        assertFalse(before.board.isComplete());
        assertTrue(after.resumesIn("session-a"));
        assertFalse(after.resumesIn("session-b"));
        assertThrows(IllegalStateException.class, () -> after.edit(0, 0, "session-a"));
    }
}
