package github.vhoom.zhijian.domain.interception.sudoku;

/**
 * 不可变的九宫格；保护给定数字，允许保留错误填写，并按行、列、宫判断冲突。
 *
 * 维护日期：2026年11月14日
 */
public final class SudokuBoard {
    public static final int SIDE = 9;
    public static final int CELL_COUNT = SIDE * SIDE;
    private final String clues;
    private final String values;

    /** @param clues 81 位题面，0 为空格 @param values 包含给定数字的 81 位当前棋盘 */
    public SudokuBoard(String clues, String values) {
        validateDigits(clues);
        validateDigits(values);
        this.clues = clues;
        this.values = values;
        for (int index = 0; index < CELL_COUNT; index++) {
            if (isGiven(index) && clues.charAt(index) != values.charAt(index)) {
                throw new IllegalArgumentException("Changed Sudoku clue");
            }
            if (conflicts(clues, index)) throw new IllegalArgumentException("Conflicting Sudoku clues");
        }
    }

    /** @param digits 待校验的棋盘编码 */
    private static void validateDigits(String digits) {
        if (digits == null || !digits.matches("[0-9]{81}")) {
            throw new IllegalArgumentException("Invalid Sudoku board");
        }
    }

    /** @return 初始题面编码 */
    public String clues() { return clues; }

    /** @return 当前棋盘编码，可直接用于持久化 */
    public String values() { return values; }

    /** @param index 从 0 开始的格子编号 @return 当前数字，0 表示空白 */
    public int digit(int index) { return values.charAt(index) - '0'; }

    /** @param index 从 0 开始的格子编号 @return 是否为不可修改的给定数字 */
    public boolean isGiven(int index) { return clues.charAt(index) != '0'; }

    /** @param index 目标格子 @param digit 1 至 9，0 表示清除 @return 修改后的独立棋盘 */
    public SudokuBoard withDigit(int index, int digit) {
        if (index < 0 || index >= CELL_COUNT || digit < 0 || digit > 9 || isGiven(index)) {
            throw new IllegalArgumentException("Invalid Sudoku edit");
        }
        char[] next = values.toCharArray();
        next[index] = (char) ('0' + digit);
        return new SudokuBoard(clues, new String(next));
    }

    /** @param index 待检查格子 @return 是否与同行、同列或同宫的其他数字重复 */
    public boolean hasConflict(int index) {
        return conflicts(values, index);
    }

    /** @param digits 完整棋盘或给定题面 @param index 待检查位置 @return 同行、列或宫是否重复 */
    private static boolean conflicts(String digits, int index) {
        int value = digits.charAt(index) - '0';
        if (value == 0) return false;
        int row = index / SIDE;
        int column = index % SIDE;
        for (int other = 0; other < CELL_COUNT; other++) {
            if (other == index || digits.charAt(other) - '0' != value) continue;
            int otherRow = other / SIDE;
            int otherColumn = other % SIDE;
            if (row == otherRow || column == otherColumn
                    || row / 3 == otherRow / 3 && column / 3 == otherColumn / 3) return true;
        }
        return false;
    }

    /** @return 是否存在任意行、列或宫冲突 */
    public boolean hasConflicts() {
        for (int index = 0; index < CELL_COUNT; index++) if (hasConflict(index)) return true;
        return false;
    }

    /** @return 已填写的可编辑格子数量 */
    public int filledCount() {
        int count = 0;
        for (int index = 0; index < CELL_COUNT; index++) if (!isGiven(index) && digit(index) != 0) count++;
        return count;
    }

    /** @return 需要填写的格子总数 */
    public int emptyCount() {
        int count = 0;
        for (int index = 0; index < CELL_COUNT; index++) if (!isGiven(index)) count++;
        return count;
    }

    /** @return 全部填满且每行、每列、每宫满足规则时才完成 */
    public boolean isComplete() { return values.indexOf('0') < 0 && !hasConflicts(); }
}
