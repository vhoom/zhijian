package github.vhoom.zhijian.data.model.usage;

import github.vhoom.zhijian.data.model.AppInfo;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 某个本地日期的不可变归档快照，包含全部达到门槛的应用，不受首页榜单条数影响。
 *
 * 维护日期：2026年11月08日
 */
public final class ArchivedUsage {
    public final LocalDate date;
    public final String zoneId;
    public final long screenMillis;
    public final long observedAtMillis;
    public final boolean available;
    public final List<Entry> entries;

    /** 保存应用名称快照，卸载或包不可见时仍可展示历史。 */
    public static final class Entry {
        public final AppInfo app;
        public final long millis;

        /** @param app 应用包名和当时名称 @param millis 当天累计前台毫秒数 */
        public Entry(AppInfo app, long millis) {
            if (millis < 0L) throw new IllegalArgumentException("Negative usage");
            this.app = app;
            this.millis = millis;
        }
    }

    /**
     * @param date 统计使用的本地日期
     * @param zoneId 采集时设备时区
     * @param screenMillis 独立于应用榜单的屏幕累计时长
     * @param observedAtMillis 本次查询的墙钟时间
     * @param available 是否已有真实采集记录
     * @param entries 当天应用累计值，不代表增量
     */
    public ArchivedUsage(LocalDate date, String zoneId, long screenMillis, long observedAtMillis,
            boolean available, List<Entry> entries) {
        ZoneId.of(zoneId);
        if (screenMillis < 0L || observedAtMillis < 0L) throw new IllegalArgumentException("Negative time");
        this.date = date;
        this.zoneId = zoneId;
        this.screenMillis = screenMillis;
        this.observedAtMillis = observedAtMillis;
        this.available = available;
        this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
    }

    /** @param date 请求的日期 @return 缺少记录的快照，区别于有记录但没有满一分钟的应用 */
    public static ArchivedUsage missing(LocalDate date) {
        return new ArchivedUsage(date, "UTC", 0L, 0L, false, Collections.emptyList());
    }
}
