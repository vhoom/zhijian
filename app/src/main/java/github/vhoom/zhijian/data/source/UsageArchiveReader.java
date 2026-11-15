package github.vhoom.zhijian.data.source;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.pm.PackageManager;

import github.vhoom.zhijian.data.model.AppInfo;
import github.vhoom.zhijian.data.model.usage.ArchivedUsage;
import github.vhoom.zhijian.domain.usage.DailyUsagePolicy;
import github.vhoom.zhijian.domain.usage.UsageTimeline;
import github.vhoom.zhijian.util.permission.PermissionStatusReader;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统事件适配器：一次查询拆分近期本地日期，按真实日历边界处理跨日和夏令时。
 *
 * 维护日期：2026年11月08日
 */
public final class UsageArchiveReader {
    private final Context context;
    private final UsageStatsManager manager;
    private final PackageManager packages;

    /** @param context 应用上下文，用于系统统计和当时应用名称查询 */
    public UsageArchiveReader(Context context) {
        this.context = context.getApplicationContext();
        manager = context.getSystemService(UsageStatsManager.class);
        packages = context.getPackageManager();
    }

    /**
     * @param days 含今天的回查天数，仅补录系统仍保留的事件，不承诺历史事件完整
     * @param nowMillis 本次查询固定的墙钟边界
     * @return 有实际用量证据的日期以及今日快照，权限缺失时不产生空归档
     */
    public List<ArchivedUsage> readRecent(int days, long nowMillis) {
        if (days < 1 || days > 7) throw new IllegalArgumentException("Query one to seven days");
        if (manager == null || !PermissionStatusReader.hasUsageAccess(context)) return Collections.emptyList();
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate();
        LocalDate first = today.minusDays(days - 1L);
        // 多读取一天用于重放跨午夜时已在前台的应用，计量仍只裁剪到请求日期内。
        long queryStart = first.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        UsageEvents events = manager.queryEvents(queryStart, nowMillis);
        if (events == null) return Collections.emptyList();
        UsageTimeline timeline = UsageEventTimeline.read(events, context.getPackageName(), null, 0L);
        Map<String, String> labels = new HashMap<>();
        List<ArchivedUsage> result = new ArrayList<>();
        for (LocalDate date = first; !date.isAfter(today); date = date.plusDays(1)) {
            long begin = date.atStartOfDay(zone).toInstant().toEpochMilli();
            long end = Math.min(nowMillis, date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli());
            Map<String, Long> totals = timeline.totals(begin, end);
            long screen = timeline.screenMillis(begin, end);
            List<ArchivedUsage.Entry> entries = new ArrayList<>();
            boolean evidence = screen > 0L;
            for (Map.Entry<String, Long> total : totals.entrySet()) {
                evidence |= total.getValue() > 0L;
                if (!DailyUsagePolicy.shouldRecord(total.getValue())) continue;
                String pkg = total.getKey();
                entries.add(new ArchivedUsage.Entry(new AppInfo(pkg,
                        labels.computeIfAbsent(pkg, this::labelOf)), total.getValue()));
            }
            if (evidence || date.equals(today)) {
                result.add(new ArchivedUsage(date, zone.getId(), screen, nowMillis, true, entries));
            }
        }
        return result;
    }

    /** @param pkg 实际事件中的应用包名 @return 可读取的名称，不可见或已卸载时保留包名 */
    private String labelOf(String pkg) {
        try { return packages.getApplicationLabel(packages.getApplicationInfo(pkg, 0)).toString(); }
        catch (PackageManager.NameNotFoundException unavailable) { return pkg; }
    }
}
