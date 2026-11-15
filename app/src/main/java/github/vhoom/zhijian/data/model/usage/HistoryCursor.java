package github.vhoom.zhijian.data.model.usage;

import java.util.Objects;

/**
 * 按时间与稳定 id 倒序翻页，相同时间戳也不会漏项或重复。
 *
 * 维护日期：2026年11月08日
 */
public final class HistoryCursor {
    public final long beforeTimeMs;
    public final String beforeId;

    /**
     * 创建 HistoryCursor 实例，并初始化本次操作依赖或不可变字段。
     *
     * @param beforeTimeMs 上一页末条记录的时间戳
     * @param beforeId 上一页末条记录的稳定 ID，用于时间相同时分页
     */
    public HistoryCursor(long beforeTimeMs, String beforeId) {
        this.beforeTimeMs = beforeTimeMs;
        this.beforeId = Objects.requireNonNull(beforeId);
    }

    /**
     * 创建历史分页的起始游标。
     */
    public static HistoryCursor first() { return new HistoryCursor(Long.MAX_VALUE, ""); }
}
