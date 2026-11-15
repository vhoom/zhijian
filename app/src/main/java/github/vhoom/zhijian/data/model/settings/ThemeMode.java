package github.vhoom.zhijian.data.model.settings;

import android.content.res.Configuration;

import github.vhoom.zhijian.R;

/**
 * 界面配色模式。声明顺序即设置页选项的顺序。
 * 跟随系统不需要改写任何东西：资源限定符自己就会挑 values-night；另两档靠运行时覆盖 uiMode 实现。
 *
 * 维护日期：2026年11月08日
 */
public enum ThemeMode {

    SYSTEM(R.string.theme_mode_system, 0),
    LIGHT(R.string.theme_mode_light, Configuration.UI_MODE_NIGHT_NO),
    DARK(R.string.theme_mode_dark, Configuration.UI_MODE_NIGHT_YES);

    public final int nameRes;
    /** 0 是「不改写」的哨兵值，只有另两档带真正的夜间位。 */
    public final int nightBits;

    /**
     * 创建 ThemeMode 实例，并初始化本次操作依赖或不可变字段。
     *
     * @param nameRes 界面显示名称对应的字符串资源 ID
     * @param nightBits Configuration.uiMode 中需要应用的夜间位
     */
    ThemeMode(int nameRes, int nightBits) {
        this.nameRes = nameRes;
        this.nightBits = nightBits;
    }

    /**
     * 从输入值构造当前类型；枚举序号非法时使用规定的缺省值。
     *
     * @param ordinal 从持久化文件读取的枚举序号
     */
    public static ThemeMode of(int ordinal) {
        return ordinal >= 0 && ordinal < values().length ? values()[ordinal] : SYSTEM;
    }
}
