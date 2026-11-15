package github.vhoom.zhijian.data.store;

import android.content.Context;

import github.vhoom.zhijian.data.db.entity.usage.DailyUsageEntity;
import github.vhoom.zhijian.data.db.dao.UsageDao;
import github.vhoom.zhijian.data.db.entity.usage.UsageSessionEntity;
import github.vhoom.zhijian.data.model.usage.HistoryCursor;
import github.vhoom.zhijian.data.model.runtime.RuntimeCheckpoint;
import github.vhoom.zhijian.data.model.usage.UsageDay;
import github.vhoom.zhijian.data.model.usage.UsageSession;

import org.json.JSONObject;

import java.io.Writer;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 长期明细、幂等结算和分页查询。调用方按事件边界/检查点提交累计值。
 *
 * 维护日期：2026年11月14日
 */
public final class UsageHistoryStore {
    private static final int MAX_BATCH = 500;
    private final ZhijianStorage storage;

    /**
     * 创建 UsageHistoryStore 实例，并初始化本次操作依赖或不可变字段。
     *
     * @param context 访问系统服务、资源或持久化文件的上下文
     */
    public UsageHistoryStore(Context context) { this(ZhijianStorage.get(context)); }
    /**
     * 创建 UsageHistoryStore 实例，并初始化本次操作依赖或不可变字段。
     *
     * @param storage 进程共享或测试隔离的存储入口
     */
    UsageHistoryStore(ZhijianStorage storage) { this.storage = storage; }

    /**
     * 幂等保存单条使用会话，并同步更新每日聚合。
     *
     * @param session 当前使用会话明细
     */
    public CompletableFuture<Void> record(UsageSession session) {
        return recordBatch(Collections.singletonList(session), null);
    }

    /**
     * 明细、日汇总和可选运行检查点在同一事务提交，崩溃重试不会重复累计。
     *
     * @param sessions 需要批量处理的使用会话集合
     * @param checkpoint 需要幂等持久化的运行检查点
     */
    public CompletableFuture<Void> recordBatch(List<UsageSession> sessions, RuntimeCheckpoint checkpoint) {
        if (sessions.size() > MAX_BATCH) throw new IllegalArgumentException("Batch exceeds 500 sessions");
        List<UsageSession> records = new ArrayList<>(sessions);
        return storage.submit(db -> {
            db.runInTransaction(() -> {
                boolean advanceCheckpoint = checkpoint != null
                        && RuntimeStateStore.isNewCheckpoint(db.runtime(), checkpoint);
                Map<String, DailyUsageEntity> changes = new HashMap<>();
                for (UsageSession record : records) updateSession(db.usage(), record, changes);
                for (DailyUsageEntity delta : changes.values()) applyDelta(db.usage(), delta);
                if (advanceCheckpoint) db.runtime().putCheckpoint(HistoryCodec.entity(checkpoint));
            });
            if (checkpoint != null) storage.refreshRuntimeOnWriter();
            return null;
        });
    }

    /**
     * 根据复合游标读取下一页历史，避免相同时间戳导致重复或遗漏。
     *
     * @param packageName 需要查询或管控的应用包名
     * @param cursor 由时间和 ID 组成的历史分页游标
     * @param limit 当前额度阈值或查询允许的最大条目数
     */
    public CompletableFuture<List<UsageSession>> page(String packageName, HistoryCursor cursor, int limit) {
        checkLimit(limit);
        return storage.read(db -> {
            List<UsageSessionEntity> rows = packageName == null
                    ? db.usage().page(cursor.beforeTimeMs, cursor.beforeId, limit)
                    : db.usage().pageForApp(packageName, cursor.beforeTimeMs, cursor.beforeId, limit);
            List<UsageSession> records = new ArrayList<>();
            for (UsageSessionEntity row : rows) records.add(HistoryCodec.session(row));
            return Collections.unmodifiableList(records);
        });
    }

    /**
     * 以末条记录的时间和 id 构造下一页游标。
     *
     * @param last 上一页或上一批的末条使用会话
     */
    public static HistoryCursor after(UsageSession last) {
        return new HistoryCursor(last.startedAtMs, last.id);
    }

    /**
     * 读取或还原对应的每日聚合记录。
     *
     * @param fromDate 查询日期区间的开始日期
     * @param toDate 查询日期区间的结束日期
     */
    public CompletableFuture<List<UsageDay>> daily(String fromDate, String toDate) {
        LocalDate from = LocalDate.parse(fromDate);
        LocalDate to = LocalDate.parse(toDate);
        long days = ChronoUnit.DAYS.between(from, to);
        if (days < 0 || days > 366) throw new IllegalArgumentException("Query at most one year at a time");
        return storage.read(db -> {
            List<UsageDay> result = new ArrayList<>();
            for (DailyUsageEntity row : db.usage().dailyRange(fromDate, toDate)) {
                result.add(new UsageDay(row.localDate, row.zoneId, row.packageName, row.foregroundMs, row.openCount));
            }
            return Collections.unmodifiableList(result);
        });
    }

    /**
     * 流式导出已结束的不可变片段。分批读取，无长读事务；Writer 由调用方关闭。
     *
     * @param destination 由调用方提供的输出文档或字符流
     */
    public CompletableFuture<Long> exportCompletedSessions(Writer destination) {
        return storage.read(db -> {
            long watermark = db.usage().exportWatermark();
            destination.write("{\"format\":\"zhijian.usage\",\"version\":2,\"completedOnly\":true}\n");
            long after = 0;
            long count = 0;
            while (true) {
                List<UsageSessionEntity> rows = db.usage().exportPage(after, watermark, MAX_BATCH);
                if (rows.isEmpty()) break;
                for (UsageSessionEntity row : rows) {
                    JSONObject json = new JSONObject();
                    json.put("recordType", "session");
                    json.put("id", row.id);
                    json.put("packageName", row.packageName);
                    json.put("appLabel", row.appLabel);
                    json.put("visitId", row.visitId);
                    json.put("zoneId", row.zoneId);
                    json.put("source", row.source);
                    json.put("startedAtMs", row.startedAtMs);
                    json.put("endedAtMs", row.endedAtMs);
                    json.put("foregroundMs", row.foregroundMs);
                    json.put("countsAsOpen", row.countsAsOpen);
                    json.put("endReason", row.endReason);
                    json.put("sequence", row.sequence);
                    destination.write(json.toString());
                    destination.write('\n');
                    count++;
                }
                after = db.usage().rowId(rows.get(rows.size() - 1).id);
            }
            count += UsageArchiveStore.exportOnReader(db.usageArchive(), destination);
            destination.flush();
            return count;
        });
    }

    /**
     * 幂等更新单条明细，并计算本次提交需要变更的每日用量。
     *
     * @param dao 负责当前数据表查询与写入的 DAO
     * @param record 待转换、比较或持久化的领域记录
     * @param changes 本次事务按日期和应用累计的变更量
     */
    private static void updateSession(UsageDao dao, UsageSession record,
            Map<String, DailyUsageEntity> changes) {
        UsageSessionEntity old = dao.session(record.id);
        if (old != null) {
            if (record.sequence < old.sequence) return;
            if (record.sequence == old.sequence) {
                if (!sameCheckpoint(old, record)) throw new IllegalArgumentException("Sequence reused with different usage");
                return;
            }
            if (old.complete) throw new IllegalArgumentException("A completed session is immutable");
            if (!sameIdentity(old, record) || record.endedAtMs < old.endedAtMs
                    || record.foregroundMs < old.foregroundMs) {
                throw new IllegalArgumentException("Usage checkpoints must advance the same session");
            }
            accumulate(changes, UsageBuckets.split(HistoryCodec.session(old)), -1);
        }
        if (record.countsAsOpen && dao.alreadyCountedOpen(record.visitId, record.id)) {
            throw new IllegalArgumentException("An app visit can count as an open only once");
        }
        dao.putSession(HistoryCodec.entity(record));
        accumulate(changes, UsageBuckets.split(record), 1);
    }

    /**
     * 检查重试记录是否仍属于同一应用访问和时间片段。
     *
     * @param old 数据库中已存在、需要比较的旧记录
     * @param record 待转换、比较或持久化的领域记录
     */
    private static boolean sameIdentity(UsageSessionEntity old, UsageSession record) {
        return old.packageName.equals(record.packageName) && old.visitId.equals(record.visitId)
                && old.zoneId.equals(record.zoneId) && old.source.equals(record.source)
                && old.startedAtMs == record.startedAtMs && old.countsAsOpen == record.countsAsOpen;
    }

    /**
     * 检查累计值与序号是否重复，避免重复结算。
     *
     * @param old 数据库中已存在、需要比较的旧记录
     * @param record 待转换、比较或持久化的领域记录
     */
    private static boolean sameCheckpoint(UsageSessionEntity old, UsageSession record) {
        return sameIdentity(old, record) && old.appLabel.equals(record.appLabel)
                && old.endedAtMs == record.endedAtMs && old.foregroundMs == record.foregroundMs
                && old.complete == record.complete && old.endReason.equals(record.endReason);
    }

    /**
     * 按当前记录累加用量，保留原有聚合的身份信息。
     *
     * @param changes 本次事务按日期和应用累计的变更量
     * @param days 按本地日期拆分的用量集合
     * @param sign 累计变更方向，正一表示增加、负一表示撤销
     */
    private static void accumulate(Map<String, DailyUsageEntity> changes, List<UsageDay> days, int sign) {
        for (UsageDay day : days) {
            String key = day.localDate + '|' + day.zoneId + '|' + day.packageName;
            DailyUsageEntity delta = changes.get(key);
            if (delta == null) {
                delta = new DailyUsageEntity();
                delta.localDate = day.localDate;
                delta.zoneId = day.zoneId;
                delta.packageName = day.packageName;
                changes.put(key, delta);
            }
            delta.foregroundMs = Math.addExact(delta.foregroundMs, sign * day.foregroundMs);
            delta.openCount = Math.addExact(delta.openCount, sign * day.openCount);
        }
    }

    /**
     * 把本次变更量合并到每日聚合中。
     *
     * @param dao 负责当前数据表查询与写入的 DAO
     * @param delta 本次提交需要合并到每日聚合的用量变更
     */
    private static void applyDelta(UsageDao dao, DailyUsageEntity delta) {
        if (delta.foregroundMs == 0 && delta.openCount == 0) return;
        DailyUsageEntity old = dao.daily(delta.localDate, delta.zoneId, delta.packageName);
        if (old != null) {
            delta.foregroundMs = Math.addExact(delta.foregroundMs, old.foregroundMs);
            delta.openCount = Math.addExact(delta.openCount, old.openCount);
        }
        if (delta.foregroundMs < 0 || delta.openCount < 0) throw new IllegalStateException("Invalid daily aggregate");
        dao.putDaily(delta);
    }

    /**
     * 校验分页大小，避免无界查询加载全部历史。
     *
     * @param limit 当前额度阈值或查询允许的最大条目数
     */
    static void checkLimit(int limit) {
        if (limit < 1 || limit > MAX_BATCH) throw new IllegalArgumentException("Page size must be 1..500");
    }
}
