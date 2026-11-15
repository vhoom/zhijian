package github.vhoom.zhijian.domain.usage;

/**
 * 每日归档策略：按应用当天累计判断门槛，重复采集不能累加或缩短已保存的用量。
 *
 * 维护日期：2026年11月08日
 */
public final class DailyUsagePolicy {
    public static final long MIN_RECORDED_MILLIS = 60_000L;

    /** 工具类不持有运行状态。 */
    private DailyUsagePolicy() { }

    /** @param millis 同一应用当天累计前台毫秒数 @return 是否达到一分钟归档门槛 */
    public static boolean shouldRecord(long millis) {
        return millis >= MIN_RECORDED_MILLIS;
    }

    /**
     * 系统事件过期或迟到查询不能擦掉已归档历史，同一累计快照不重复相加。
     * @param saved 已保存累计值
     * @param observed 本次系统查询累计值
     * @return 单调不减的累计值
     */
    public static long merge(long saved, long observed) {
        if (saved < 0L || observed < 0L) throw new IllegalArgumentException("Negative usage");
        return Math.max(saved, observed);
    }
}
