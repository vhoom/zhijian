package github.vhoom.zhijian.data.model.settings;

/**
 * 表示一个包含首尾值的毫秒区间，并按 0.1 秒粒度稳定选择时长。
 *
 * 维护日期：2026年11月08日
 */
public final class SecondsRange {
    private static final long STEP_MILLIS = 100L;
    public static final long MIN_MILLIS = STEP_MILLIS;
    public static final long MAX_MILLIS = 3_600_000L;

    public final long minMillis;
    public final long maxMillis;

    /**
     * 创建合法的时长区间。
     *
     * @param minMillis 最短时长，必须是 0.1 秒的整数倍
     * @param maxMillis 最长时长，必须不小于最短时长
     */
    public SecondsRange(long minMillis, long maxMillis) {
        if (!isValid(minMillis, maxMillis)) {
            throw new IllegalArgumentException("Invalid seconds range");
        }
        this.minMillis = minMillis;
        this.maxMillis = maxMillis;
    }

    /**
     * 页面、配置模型和存储共用按压区间边界，防止恢复时绕过一小时上限。
     * @param minMillis 下界毫秒数
     * @param maxMillis 上界毫秒数
     * @return 区间位于允许范围且具有 0.1 秒精度时返回 true
     */
    public static boolean isValid(long minMillis, long maxMillis) {
        return minMillis >= MIN_MILLIS && maxMillis <= MAX_MILLIS && minMillis <= maxMillis
                && minMillis % STEP_MILLIS == 0 && maxMillis % STEP_MILLIS == 0;
    }

    /**
     * 使用稳定种子选择区间内的毫秒值，确保同一次拦截重建页面后时长不变。
     *
     * @param seed 标识同一次拦截的稳定种子
     * @return 以 0.1 秒为粒度选中的毫秒数
     */
    public long valueFor(String seed) {
        if (seed == null) throw new IllegalArgumentException("Seed is required");
        long size = (maxMillis - minMillis) / STEP_MILLIS + 1;
        return minMillis + Math.floorMod((long) seed.hashCode(), size) * STEP_MILLIS;
    }
}
