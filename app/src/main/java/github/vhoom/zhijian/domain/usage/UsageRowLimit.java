package github.vhoom.zhijian.domain.usage;

/**
 * 首页手动条数以已扫描可管控应用数为上界；未知或空容量不能生成可保存值。
 *
 * 维护日期：2026年11月13日
 */
public final class UsageRowLimit {
    /** 手动设置至少保留一行；恢复默认由独立操作完成。 */
    public static final int MIN_ROWS = 1;

    /** 约束工具不创建实例。 */
    private UsageRowLimit() { }

    /** @param rows 原文解析得到的条数 @param scannedApps 最近成功扫描的应用数 @return 可保存 */
    public static boolean isValid(long rows, int scannedApps) {
        return scannedApps >= MIN_ROWS && rows >= MIN_ROWS && rows <= scannedApps;
    }
}
