package github.vhoom.zhijian.domain.usage;

/**
 * 一次规则统计的固定起点；同次开机使用单调时间投影，不随复查、版本或强度变化重建。
 *
 * 维护日期：2026年11月15日
 */
public final class UsageTrackingStart {
    public final String id;
    public final String bootId;
    public final long wallMs;
    public final long elapsedMs;

    /**
     * @param id 本次安装内独立的统计身份，用于隔离恢复的旧周期
     * @param bootId 首次生效时的启动身份
     * @param wallMs 首次生效的墙钟毫秒
     * @param elapsedMs 同一时刻的单调毫秒
     */
    public UsageTrackingStart(String id, String bootId, long wallMs, long elapsedMs) {
        if (id == null || id.isEmpty() || bootId == null || bootId.isEmpty()
                || wallMs < 0L || elapsedMs < 0L) {
            throw new IllegalArgumentException("Invalid usage tracking start");
        }
        this.id = id;
        this.bootId = bootId;
        this.wallMs = wallMs;
        this.elapsedMs = elapsedMs;
    }

    /**
     * @param currentBootId 当前启动身份
     * @param nowWallMs 系统事件查询的墙钟终点
     * @param nowElapsedMs 同一终点的单调毫秒
     * @return 投影不早于已保存墙钟起点；墙钟回拨至起点前时将本次区间收为空
     */
    public long floorMillis(String currentBootId, long nowWallMs, long nowElapsedMs) {
        if (!bootId.equals(currentBootId)) return Math.min(nowWallMs, wallMs);
        long sinceStart = Math.max(0L, nowElapsedMs - elapsedMs);
        return Math.min(nowWallMs, Math.max(wallMs, nowWallMs - sinceStart));
    }

    /**
     * @param eventBootId 已保存事实的启动身份
     * @param eventWallMs 已保存事实的墙钟毫秒
     * @param eventElapsedMs 已保存事实的单调毫秒
     * @return 事实是否发生于当前统计起点之后，同次开机优先使用单调时间
     */
    public boolean includes(String eventBootId, long eventWallMs, long eventElapsedMs) {
        return bootId.equals(eventBootId) ? eventElapsedMs >= elapsedMs : eventWallMs >= wallMs;
    }
}
