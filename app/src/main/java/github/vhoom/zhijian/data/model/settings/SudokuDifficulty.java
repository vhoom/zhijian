package github.vhoom.zhijian.data.model.settings;

/**
 * 数独新局的难度选项；存储名称与界面文字分离，进行中的题面不随设置变化。
 *
 * 维护日期：2026年11月14日
 */
public enum SudokuDifficulty {
    EASY, MEDIUM, HARD;

    /** @param name 保存的枚举名称 @return 有效难度，缺失或损坏时使用简单 */
    public static SudokuDifficulty fromName(String name) {
        try { return name == null ? EASY : valueOf(name); }
        catch (IllegalArgumentException invalid) { return EASY; }
    }
}
