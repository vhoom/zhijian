package github.vhoom.zhijian.data.db.entity.usage;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;

/**
 * 按本地日期、时区和包名聚合的每日使用量，作为长周期查询的快速索引。
 *
 * 维护日期：2026年11月08日
 */
@Entity(tableName = "usage_daily", primaryKeys = {"localDate", "zoneId", "packageName"},
        indices = @Index(value = {"packageName", "localDate"}))
public class DailyUsageEntity {
    @NonNull public String localDate = "";
    @NonNull public String zoneId = "UTC";
    @NonNull public String packageName = "";
    public long foregroundMs;
    public long openCount;
}
