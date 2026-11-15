package github.vhoom.zhijian.data.store;

import android.content.Context;

import github.vhoom.zhijian.data.db.dao.UsageArchiveDao;
import github.vhoom.zhijian.data.db.entity.usage.UsageArchiveAppEntity;
import github.vhoom.zhijian.data.db.entity.usage.UsageArchiveDayEntity;
import github.vhoom.zhijian.data.model.AppInfo;
import github.vhoom.zhijian.data.model.usage.ArchivedUsage;
import github.vhoom.zhijian.domain.usage.DailyUsagePolicy;

import org.json.JSONObject;

import java.io.Writer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 每日快照持久化代理：写事务统一执行门槛和幂等合并，历史读取不依赖当前系统权限。
 *
 * 维护日期：2026年11月14日
 */
public final class UsageArchiveStore {
    private final ZhijianStorage storage;

    /** @param context 用于取得进程级后台存储 */
    public UsageArchiveStore(Context context) { this(ZhijianStorage.get(context)); }

    /** @param storage 测试可注入独立数据库，避免触碰真实历史 */
    UsageArchiveStore(ZhijianStorage storage) { this.storage = storage; }

    /**
     * @param days 系统近期查询得到的不可变累计快照
     * @return 所有日期提交成功后的完成信号
     */
    public CompletableFuture<Void> save(List<ArchivedUsage> days) {
        List<ArchivedUsage> snapshots = new ArrayList<>(days);
        return storage.submit(db -> {
            db.runInTransaction(() -> {
                UsageArchiveDao dao = db.usageArchive();
                for (ArchivedUsage snapshot : snapshots) {
                    if (!snapshot.available) continue;
                    String date = snapshot.date.toString();
                    UsageArchiveDayEntity day = dao.day(date);
                    boolean existing = day != null;
                    if (!existing) {
                        day = new UsageArchiveDayEntity();
                        day.localDate = date;
                    }
                    day.screenMs = DailyUsagePolicy.merge(day.screenMs, snapshot.screenMillis);
                    if (snapshot.observedAtMillis >= day.observedAtMs) day.zoneId = snapshot.zoneId;
                    day.observedAtMs = Math.max(day.observedAtMs, snapshot.observedAtMillis);
                    if (existing) dao.updateDay(day);
                    else dao.insertDay(day);
                    Map<String, UsageArchiveAppEntity> previous = new HashMap<>();
                    for (UsageArchiveAppEntity row : dao.apps(date)) previous.put(row.packageName, row);
                    for (ArchivedUsage.Entry entry : snapshot.entries) {
                        if (!DailyUsagePolicy.shouldRecord(entry.millis)) continue;
                        UsageArchiveAppEntity row = previous.get(entry.app.packageName);
                        if (row == null) {
                            row = new UsageArchiveAppEntity();
                            row.localDate = date;
                            row.packageName = entry.app.packageName;
                        }
                        row.foregroundMs = DailyUsagePolicy.merge(row.foregroundMs, entry.millis);
                        if (row.appLabel.isEmpty() || !entry.app.label.equals(entry.app.packageName)) {
                            row.appLabel = entry.app.label;
                        }
                        dao.putApp(row);
                        previous.put(row.packageName, row);
                    }
                }
            });
            return null;
        });
    }

    /** @param date ISO 本地日期 @return 同一数据库读取事务中的日期与全部应用快照 */
    public CompletableFuture<ArchivedUsage> load(LocalDate date) {
        return storage.read(db -> db.runInTransaction(() -> {
            UsageArchiveDayEntity day = db.usageArchive().day(date.toString());
            if (day == null) return ArchivedUsage.missing(date);
            List<ArchivedUsage.Entry> entries = new ArrayList<>();
            for (UsageArchiveAppEntity row : db.usageArchive().apps(date.toString())) {
                entries.add(new ArchivedUsage.Entry(new AppInfo(row.packageName, row.appLabel), row.foregroundMs));
            }
            return new ArchivedUsage(date, day.zoneId, day.screenMs, day.observedAtMs, true, entries);
        }));
    }

    /**
     * 在现有历史导出的后台读线程追加每日应用记录；分页，不加载全部历史。
     * @param dao 同次导出使用的归档 DAO
     * @param destination 调用者持有的 JSONL 流
     * @return 追加的应用日期记录数
     * @throws Exception 查询、编码或写入失败
     */
    static long exportOnReader(UsageArchiveDao dao, Writer destination) throws Exception {
        long watermark = dao.exportWatermark();
        long after = 0L;
        long count = 0L;
        while (true) {
            List<UsageArchiveAppEntity> rows = dao.exportPage(after, watermark, 500);
            if (rows.isEmpty()) break;
            for (UsageArchiveAppEntity row : rows) {
                UsageArchiveDayEntity day = dao.day(row.localDate);
                JSONObject json = new JSONObject();
                json.put("recordType", "daily_archive");
                json.put("localDate", row.localDate);
                json.put("zoneId", day.zoneId);
                json.put("packageName", row.packageName);
                json.put("appLabel", row.appLabel);
                json.put("foregroundMs", row.foregroundMs);
                json.put("screenMs", day.screenMs);
                json.put("observedAtMs", day.observedAtMs);
                destination.write(json.toString());
                destination.write('\n');
                count++;
            }
            UsageArchiveAppEntity last = rows.get(rows.size() - 1);
            after = dao.rowId(last.localDate, last.packageName);
        }
        return count;
    }
}
