package github.vhoom.zhijian.data.db.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;
import androidx.room.Upsert;

import github.vhoom.zhijian.data.db.entity.usage.UsageArchiveAppEntity;
import github.vhoom.zhijian.data.db.entity.usage.UsageArchiveDayEntity;

import java.util.List;

/**
 * 每日归档 DAO；仅有查询、插入和更新，不提供历史淘汰入口。
 *
 * 维护日期：2026年11月08日
 */
@Dao
public interface UsageArchiveDao {
    /** @param date ISO 本地日期 @return 已保存的屏幕累计值和查询时间 */
    @Query("SELECT * FROM usage_archive_day WHERE localDate = :date")
    UsageArchiveDayEntity day(String date);

    /** @param date ISO 本地日期 @return 全部应用，按时长及包名稳定排序 */
    @Query("SELECT * FROM usage_archive_app WHERE localDate = :date ORDER BY foregroundMs DESC, packageName")
    List<UsageArchiveAppEntity> apps(String date);

    /** @param row 新日期；忽略重复日期，避免 REPLACE 删除外键父记录 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    void insertDay(UsageArchiveDayEntity row);

    /** @param row 已在事务内校验的日期累计值 */
    @Update
    void updateDay(UsageArchiveDayEntity row);

    /** @param row 已在存储入口校验一分钟门槛的累计值 */
    @Upsert
    void putApp(UsageArchiveAppEntity row);

    /** @return 导出固定行号水位，后续新增应用留到下一次导出 */
    @Query("SELECT COALESCE(MAX(rowid), 0) FROM usage_archive_app")
    long exportWatermark();

    /** @param after 上页末行号 @param watermark 导出行号上限 @param limit 页大小 @return 下一页 */
    @Query("SELECT * FROM usage_archive_app WHERE rowid > :after AND rowid <= :watermark ORDER BY rowid LIMIT :limit")
    List<UsageArchiveAppEntity> exportPage(long after, long watermark, int limit);

    /** @param date 日期 @param pkg 包名 @return 当前记录的稳定分页行号 */
    @Query("SELECT rowid FROM usage_archive_app WHERE localDate = :date AND packageName = :pkg")
    long rowId(String date, String pkg);
}
