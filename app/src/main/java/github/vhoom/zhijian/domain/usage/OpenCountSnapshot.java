package github.vhoom.zhijian.domain.usage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一次查询中真实打开时刻的只读快照，统一计算次数和滚动额度的释放时刻。
 *
 * 维护日期：2026年11月12日
 */
public final class OpenCountSnapshot {
    private static final OpenCountSnapshot EMPTY = new OpenCountSnapshot(Collections.emptyList());
    private final List<Long> openingTimes;

    /**
     * 隔离源集合并验证时间顺序，避免调用方改动本次统计。
     * @param openingTimes 按墙钟时间升序排列的真实打开时刻
     */
    public OpenCountSnapshot(List<Long> openingTimes) {
        List<Long> copy = new ArrayList<>(openingTimes);
        long previous = -1L;
        for (Long time : copy) {
            if (time == null || time < 0L || time < previous) {
                throw new IllegalArgumentException("Invalid opening order");
            }
            previous = time;
        }
        this.openingTimes = Collections.unmodifiableList(copy);
    }

    /** @return 没有打开记录的共用不可变快照 */
    public static OpenCountSnapshot empty() { return EMPTY; }

    /** @return 本次统计范围内的真实打开次数 */
    public long count() { return openingTimes.size(); }

    /**
     * 计算当前超限访问恢复到额度内的时刻；反复打开产生的新记录也参与计算。
     * @param limitCount 允许的最大次数
     * @param windowMillis 每条打开记录在滚动窗口中保留的毫秒数
     * @return 足够多的旧记录过期时刻；当前没有超限时返回零
     */
    public long resetAtMs(int limitCount, long windowMillis) {
        if (limitCount <= 0 || windowMillis <= 0L) {
            throw new IllegalArgumentException("Invalid rolling count limit");
        }
        if (openingTimes.size() <= limitCount) return 0L;
        long opening = openingTimes.get(openingTimes.size() - limitCount - 1);
        return windowMillis > Long.MAX_VALUE - opening ? Long.MAX_VALUE : opening + windowMillis;
    }
}
