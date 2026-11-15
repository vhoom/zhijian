package github.vhoom.zhijian.domain.interception;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import github.vhoom.zhijian.domain.usage.OpenCountSnapshot;

/**
 * 一次规则判定使用的只读用量证据；Android 统计读取细节不会泄漏到规则模块。
 *
 * 维护日期：2026年11月12日
 */
public final class UsageEvidence {
    public final boolean available;
    public final long nowMs;
    public final String zoneId;
    public final long appForegroundMs;
    public final long screenForegroundMs;
    public final long openCount;
    public final long continuousMs;
    private final Map<String, Long> windowForegroundMs;
    private final Map<String, Long> windowContinuousMs;
    private final Map<String, OpenCountSnapshot> countSnapshots;

    /**
     * 创建 UsageEvidence 实例，并初始化本次操作依赖或不可变字段。
     *
     * @param available 是否已经授权且成功取得用量证据
     * @param nowMs 当前墙钟时间戳，单位为毫秒
     * @param zoneId 记录或时间计算使用的时区标识
     * @param appForegroundMs 当前应用或其生效规则组累计前台时长，单位为毫秒
     * @param screenForegroundMs 屏幕累计交互时长，单位为毫秒
     * @param openCount 当前应用或其生效规则组累计打开次数
     * @param continuousMs 本次连续前台时长，单位为毫秒
     * @param windowForegroundMs 各时段累计前台时长的映射
     */
    public UsageEvidence(boolean available, long nowMs, String zoneId, long appForegroundMs,
            long screenForegroundMs, long openCount, long continuousMs,
            Map<String, Long> windowForegroundMs) {
        this(available, nowMs, zoneId, appForegroundMs, screenForegroundMs, openCount, continuousMs,
                windowForegroundMs, Collections.emptyMap());
    }

    /** @param available 用量证据是否可用 @param nowMs 当前墙钟 @param zoneId 时区
     * @param appForegroundMs 全天用量 @param screenForegroundMs 屏幕用量 @param openCount 打开次数
     * @param continuousMs 全天连续用量 @param windowForegroundMs 时段累计用量
     * @param windowContinuousMs 各时段独立连续用量 */
    public UsageEvidence(boolean available, long nowMs, String zoneId, long appForegroundMs,
            long screenForegroundMs, long openCount, long continuousMs,
            Map<String, Long> windowForegroundMs, Map<String, Long> windowContinuousMs) {
        this(available, nowMs, zoneId, appForegroundMs, screenForegroundMs, openCount, continuousMs,
                windowForegroundMs, windowContinuousMs, Collections.emptyMap());
    }

    /**
     * 合并各统计模块的不可变证据，副本方法统一保留所有统计结果。
     * @param available 系统统计是否可用 @param nowMs 当前墙钟 @param zoneId 设备时区
     * @param appForegroundMs 全天用量 @param screenForegroundMs 亮屏用量 @param openCount 全天打开次数
     * @param continuousMs 全天连续用量 @param windowForegroundMs 时段累计用量
     * @param windowContinuousMs 时段连续用量 @param countSnapshots 各限次范围的真实打开快照
     */
    private UsageEvidence(boolean available, long nowMs, String zoneId, long appForegroundMs,
            long screenForegroundMs, long openCount, long continuousMs,
            Map<String, Long> windowForegroundMs, Map<String, Long> windowContinuousMs,
            Map<String, OpenCountSnapshot> countSnapshots) {
        if (nowMs < 0 || zoneId == null || zoneId.isEmpty() || appForegroundMs < 0
                || screenForegroundMs < 0 || openCount < 0 || continuousMs < 0) {
            throw new IllegalArgumentException("Invalid usage evidence");
        }
        this.available = available;
        this.nowMs = nowMs;
        this.zoneId = zoneId;
        this.appForegroundMs = appForegroundMs;
        this.screenForegroundMs = screenForegroundMs;
        this.openCount = openCount;
        this.continuousMs = continuousMs;
        this.windowForegroundMs = Collections.unmodifiableMap(new HashMap<>(windowForegroundMs));
        this.windowContinuousMs = Collections.unmodifiableMap(new HashMap<>(windowContinuousMs));
        this.countSnapshots = Collections.unmodifiableMap(new HashMap<>(countSnapshots));
    }

    /**
     * 读取指定时段已经累计的前台毫秒数。
     *
     * @param windowId 当前时段的稳定标识
     */
    public long windowForegroundMs(String windowId) {
        return windowForegroundMs.getOrDefault(windowId, 0L);
    }

    /** @param value 同组周期的累计使用量 @return 保留其它证据的只读副本 */
    public UsageEvidence withContinuousMillis(long value) {
        return new UsageEvidence(available, nowMs, zoneId, appForegroundMs,
                screenForegroundMs, openCount, value, windowForegroundMs, windowContinuousMs, countSnapshots);
    }

    /** @param windowId 提醒窗口身份 @return 该次窗口内的连续用量 */
    public long windowContinuousMs(String windowId) { return windowContinuousMs.getOrDefault(windowId, 0L); }

    /** @param values 已保存的独立窗口周期用量 @return 保留全部其他证据的快照 */
    public UsageEvidence withWindowContinuousMillis(Map<String, Long> values) {
        return new UsageEvidence(available, nowMs, zoneId, appForegroundMs, screenForegroundMs,
                openCount, continuousMs, windowForegroundMs, values, countSnapshots);
    }

    /** @param windowId 单个提醒窗口 @param value 该窗口用于提醒判定的用量 @return 保留其他窗口和禁用证据的副本 */
    public UsageEvidence withWindowContinuousMillis(String windowId, long value) {
        Map<String, Long> values = new HashMap<>(windowContinuousMs);
        values.put(windowId, value);
        return withWindowContinuousMillis(values);
    }

    /** @param key 限次查询给出的稳定统计键 @return 对应范围的只读打开快照 */
    public OpenCountSnapshot countSnapshot(String key) {
        return countSnapshots.getOrDefault(key, OpenCountSnapshot.empty());
    }

    /** @param values 同次系统查询得到的限次快照 @return 保留所有用量和休息证据的副本 */
    public UsageEvidence withCountSnapshots(Map<String, OpenCountSnapshot> values) {
        return new UsageEvidence(available, nowMs, zoneId, appForegroundMs, screenForegroundMs,
                openCount, continuousMs, windowForegroundMs, windowContinuousMs, values);
    }
}
