package github.vhoom.zhijian.domain.interception.sudoku;

import java.util.Random;
import github.vhoom.zhijian.data.model.settings.SudokuDifficulty;

/**
 * 分级题目工厂；各难度使用独立题面，再作保留规则和唯一解的数字置换与转置。
 *
 * 维护日期：2026年11月14日
 */
public final class SudokuPuzzles {
    private static final String EASY = "534678000" + "672195000" + "198342060"
            + "859060003" + "426803001" + "713020006"
            + "961000280" + "287419005" + "345080079";
    private static final String MEDIUM = "530070000600195000098000060800060003400803001700020006060000280000419005000080079";
    private static final String HARD = "100007090" + "030020008" + "009600500"
            + "005300900" + "010080002" + "600004000"
            + "300000010" + "040000007" + "007000300";

    /** 工厂只提供静态入口。 */
    private SudokuPuzzles() { }

    /** @param seed 稳定题目种子 @return 一局尚未填写的简单数独 */
    public static SudokuBoard create(String seed) {
        return create(seed, SudokuDifficulty.EASY);
    }

    /** @param seed 稳定题目种子 @param difficulty 新局难度 @return 尚未填写的独立棋盘 */
    public static SudokuBoard create(String seed, SudokuDifficulty difficulty) {
        String sourceClues = difficulty == SudokuDifficulty.HARD ? HARD
                : difficulty == SudokuDifficulty.MEDIUM ? MEDIUM : EASY;
        Random random = new Random(seed.hashCode());
        int[] digits = {1, 2, 3, 4, 5, 6, 7, 8, 9};
        for (int index = digits.length - 1; index > 0; index--) {
            int other = random.nextInt(index + 1);
            int swap = digits[index];
            digits[index] = digits[other];
            digits[other] = swap;
        }
        boolean transpose = random.nextBoolean();
        StringBuilder clues = new StringBuilder(SudokuBoard.CELL_COUNT);
        for (int index = 0; index < SudokuBoard.CELL_COUNT; index++) {
            int source = transpose ? index % 9 * 9 + index / 9 : index;
            int value = sourceClues.charAt(source) - '0';
            clues.append(value == 0 ? 0 : digits[value - 1]);
        }
        return new SudokuBoard(clues.toString(), clues.toString());
    }
}
