package github.vhoom.zhijian.data.db.entity.usage;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * 每日归档的存在标记和屏幕累计值；独立于旧会话派生表，不随规则删除。
 *
 * 维护日期：2026年11月08日
 */
@Entity(tableName = "usage_archive_day")
public class UsageArchiveDayEntity {
    @PrimaryKey @NonNull public String localDate = "";
    @NonNull public String zoneId = "UTC";
    public long screenMs;
    public long observedAtMs;
}
