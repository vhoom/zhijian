package github.vhoom.zhijian.data.db.dao;

import github.vhoom.zhijian.data.db.entity.usage.DailyUsageEntity;

import github.vhoom.zhijian.data.db.entity.usage.UsageSessionEntity;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

/**
 * 使用会话、每日聚合与一致性导出所需的查询接口。
 *
 * 维护日期：2026年11月08日
 */
@Dao
public interface UsageDao {
    /**
     * 读取或还原对应的使用会话记录。
     *
     * @param id 当前记录、规则或视图的稳定标识
     */
    @Query("SELECT * FROM usage_session WHERE id = :id")
    UsageSessionEntity session(String id);

    /**
     * 写入或更新使用会话明细。
     *
     * @param session 当前使用会话明细
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void putSession(UsageSessionEntity session);

    /**
     * 读取或还原对应的每日聚合记录。
     *
     * @param date 需要查询或拆分的本地日期
     * @param zone 日期归属或时间换算使用的时区
     * @param pkg 需要查询或绑定的应用包名
     */
    @Query("SELECT * FROM usage_daily WHERE localDate = :date AND zoneId = :zone AND packageName = :pkg")
    DailyUsageEntity daily(String date, String zone, String pkg);

    /**
     * 写入日用量聚合记录。
     *
     * @param daily 需要处理的每日聚合记录
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void putDaily(DailyUsageEntity daily);

    /**
     * 查询日期范围内的每日聚合，结果顺序由 SQL 明确指定。
     *
     * @param from 查询区间的开始日期
     * @param to 查询区间的结束日期
     */
    @Query("SELECT * FROM usage_daily WHERE localDate >= :from AND localDate <= :to "
            + "ORDER BY localDate, packageName, zoneId")
    List<DailyUsageEntity> dailyRange(String from, String to);

    /**
     * 根据复合游标读取下一页历史，避免相同时间戳导致重复或遗漏。
     *
     * @param before 变更前的文本数量或待比较的旧记录
     * @param beforeId 上一页末条记录的稳定 ID，用于时间相同时分页
     * @param limit 当前额度阈值或查询允许的最大条目数
     */
    @Query("SELECT * FROM usage_session WHERE (startedAtMs, id) < (:before, :beforeId) "
            + "ORDER BY startedAtMs DESC, id DESC LIMIT :limit")
    List<UsageSessionEntity> page(long before, String beforeId, int limit);

    /**
     * 用时间与记录 ID 的复合游标读取指定应用的历史页。
     *
     * @param pkg 需要查询或绑定的应用包名
     * @param before 变更前的文本数量或待比较的旧记录
     * @param beforeId 上一页末条记录的稳定 ID，用于时间相同时分页
     * @param limit 当前额度阈值或查询允许的最大条目数
     */
    @Query("SELECT * FROM usage_session WHERE packageName = :pkg AND "
            + "(startedAtMs, id) < (:before, :beforeId) "
            + "ORDER BY startedAtMs DESC, id DESC LIMIT :limit")
    List<UsageSessionEntity> pageForApp(String pkg, long before, String beforeId, int limit);

    /**
     * 查询与指定区间相交的会话，用于补算或校验某段累计用量。
     *
     * @param pkg 需要查询或绑定的应用包名
     * @param start 变化起点或区间开始值
     * @param end 区间结束时刻或一天内结束分钟
     */
    @Query("SELECT * FROM usage_session WHERE packageName = :pkg AND startedAtMs < :end "
            + "AND endedAtMs > :start ORDER BY startedAtMs, id")
    List<UsageSessionEntity> overlapping(String pkg, long start, long end);

    /**
     * 同一 visit 只允许一个会话计作打开次数，防止重试重复累计。
     *
     * @param visit 同一次应用访问的标识
     * @param id 当前记录、规则或视图的稳定标识
     */
    @Query("SELECT EXISTS(SELECT 1 FROM usage_session WHERE visitId = :visit "
            + "AND countsAsOpen = 1 AND id != :id)")
    boolean alreadyCountedOpen(String visit, String id);

    /** 固定本次导出的最高 rowid，导出期间新增记录留到下一次。 */
    @Query("SELECT COALESCE(MAX(rowid), 0) FROM usage_session")
    long exportWatermark();

    /**
     * 在固定水位内分批读取已结束会话，避免长事务和大对象常驻内存。
     *
     * @param after 变更后的文本数量或导出起始行号
     * @param watermark 本次导出固定的最大行号，隔离后来新增记录
     * @param limit 当前额度阈值或查询允许的最大条目数
     */
    @Query("SELECT * FROM usage_session WHERE rowid > :after AND rowid <= :watermark "
            + "AND complete = 1 ORDER BY rowid LIMIT :limit")
    List<UsageSessionEntity> exportPage(long after, long watermark, int limit);

    /**
     * 读取明细记录的 SQLite 行号，供稳定的导出分页使用。
     *
     * @param id 当前记录、规则或视图的稳定标识
     */
    @Query("SELECT rowid FROM usage_session WHERE id = :id")
    long rowId(String id);
}
