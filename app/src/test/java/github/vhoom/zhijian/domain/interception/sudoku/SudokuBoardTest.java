package github.vhoom.zhijian.domain.interception.sudoku;

import org.junit.Test;
import github.vhoom.zhijian.data.model.settings.SudokuDifficulty;
import static org.junit.Assert.*;

/**
 * 数独题面、独立解数校验与填写边界回归；不依赖 Android。
 *
 * 维护日期：2026年11月14日
 */
public class SudokuBoardTest {
    private static final String SOLVED = "534678912672195348198342567859761423426853791713924856961537284287419635345286179";

    /** 给定数字和越界输入不能改写题面。 */
    @Test public void givenDigitsAndInvalidInputsAreRejected() {
        SudokuBoard board = new SudokuBoard(SOLVED, SOLVED);
        assertThrows(IllegalArgumentException.class, () -> board.withDigit(0, 0));
        assertThrows(IllegalArgumentException.class, () -> board.withDigit(81, 1));
        assertThrows(IllegalArgumentException.class, () -> board.withDigit(0, 10));
        assertThrows(IllegalArgumentException.class, () -> new SudokuBoard(SOLVED, "0" + SOLVED.substring(1)));
        String conflictingClues = "3" + SOLVED.substring(1);
        assertThrows(IllegalArgumentException.class, () -> new SudokuBoard(conflictingClues, conflictingClues));
    }

    /** 行、列和宫的重复均提示；错误输入仍保留，清除可解除冲突。 */
    @Test public void conflictsRemainEditableAndClearable() {
        String empty = new String(new char[81]).replace('\0', '0');
        for (int other : new int[]{4, 36, 10}) {
            SudokuBoard board = new SudokuBoard(empty, empty).withDigit(0, 1).withDigit(other, 1);
            assertTrue(board.hasConflict(0));
            assertTrue(board.hasConflict(other));
            assertEquals(1, board.digit(other));
            assertFalse(board.withDigit(other, 0).hasConflicts());
        }
    }

    /** 仅填满不算完成，所有单元均满足规则后才完成。 */
    @Test public void completionRequiresFullValidGrid() {
        String clues = "0" + SOLVED.substring(1);
        SudokuBoard board = new SudokuBoard(clues, clues);
        assertFalse(board.isComplete());
        assertFalse(board.withDigit(0, 3).isComplete());
        assertTrue(board.withDigit(0, 5).isComplete());
    }

    /** 快照与原棋盘互不影响，恢复保留错误填写及空格。 */
    @Test public void immutableSnapshotRestoresExactEntries() {
        String clues = "00" + SOLVED.substring(2);
        SudokuBoard original = new SudokuBoard(clues, clues);
        SudokuBoard changed = original.withDigit(0, 3);
        SudokuBoard restored = new SudokuBoard(changed.clues(), changed.values());
        assertEquals(0, original.digit(0));
        assertEquals(1, restored.filledCount());
        assertEquals(2, restored.emptyCount());
        assertEquals(changed.values(), restored.values());
    }

    /** 独立回溯解数器验证题面唯一解，并验证相同种子稳定。 */
    @Test public void generatedPuzzlesHaveExactlyOneSolution() {
        java.util.Set<String> clues = new java.util.HashSet<>();
        int previousBlanks = 0;
        for (SudokuDifficulty difficulty : SudokuDifficulty.values()) {
            for (int seed = 0; seed < 6; seed++) {
                SudokuBoard board = SudokuPuzzles.create("puzzle-" + seed, difficulty);
                assertEquals(board.clues(), SudokuPuzzles.create("puzzle-" + seed, difficulty).clues());
                assertFalse(board.hasConflicts());
                assertEquals(difficulty.name(), 1, solutions(board.clues().toCharArray()));
                clues.add(board.clues());
                if (seed == 0) {
                    assertTrue(board.emptyCount() > previousBlanks);
                    previousBlanks = board.emptyCount();
                }
            }
        }
        assertTrue(clues.size() > 1);
    }

    /** @param board 独立求解数组 @return 最多两个解，优先最少候选的格子以限制困难题回归耗时 */
    private static int solutions(char[] board) {
        int index = -1;
        int candidates = 0;
        int minimum = 10;
        for (int cell = 0; cell < 81; cell++) {
            if (board[cell] != '0') continue;
            int mask = 0x3fe;
            for (int offset = 0; offset < 9; offset++) {
                int box = cell / 27 * 27 + cell % 9 / 3 * 3 + offset / 3 * 9 + offset % 3;
                mask &= ~(1 << (board[cell / 9 * 9 + offset] - '0'));
                mask &= ~(1 << (board[cell % 9 + offset * 9] - '0'));
                mask &= ~(1 << (board[box] - '0'));
            }
            int count = Integer.bitCount(mask);
            if (count == 0) return 0;
            if (count < minimum) {
                minimum = count;
                index = cell;
                candidates = mask;
            }
        }
        if (index < 0) return 1;
        int count = 0;
        for (int value = 1; value <= 9 && count < 2; value++) {
            if ((candidates & (1 << value)) != 0) {
                board[index] = (char) ('0' + value);
                count += solutions(board);
            }
        }
        board[index] = '0';
        return Math.min(2, count);
    }
}
