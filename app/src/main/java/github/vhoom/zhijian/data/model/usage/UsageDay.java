package github.vhoom.zhijian.data.model.usage;

/**
 * 一个应用在某个本地日期及时区下的累计使用量与打开次数。
 *
 * 维护日期：2026年11月08日
 */
public final class UsageDay {
    public final String localDate;
    public final String zoneId;
    public final String packageName;
    public final long foregroundMs;
    public final long openCount;

    /**
     * 创建 UsageDay 实例，并初始化本次操作依赖或不可变字段。
     *
     * @param localDate 每日聚合对应的本地日期
     * @param zoneId 记录或时间计算使用的时区标识
     * @param packageName 需要查询或管控的应用包名
     * @param foregroundMs 累计前台用量，单位为毫秒
     * @param openCount 需要累计的应用打开次数
     */
    public UsageDay(String localDate, String zoneId, String packageName, long foregroundMs, long openCount) {
        this.localDate = localDate;
        this.zoneId = zoneId;
        this.packageName = packageName;
        this.foregroundMs = foregroundMs;
        this.openCount = openCount;
    }
}
