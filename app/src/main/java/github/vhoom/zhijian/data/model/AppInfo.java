package github.vhoom.zhijian.data.model;

/**
 * 一个可启动应用的不可变包名和显示名称，来自系统读取或已保存的本地清单。
 *
 * 维护日期：2026年11月10日
 */
public final class AppInfo {

    public final String packageName;
    public final String label;

    /**
     * 创建 AppInfo 实例，并初始化本次操作依赖或不可变字段。
     *
     * @param packageName 需要查询或管控的应用包名
     * @param label 界面显示的可读名称
     */
    public AppInfo(String packageName, String label) {
        this.packageName = packageName;
        this.label = label;
    }
}
