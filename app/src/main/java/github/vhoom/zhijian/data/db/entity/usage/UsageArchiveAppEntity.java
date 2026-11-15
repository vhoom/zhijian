package github.vhoom.zhijian.data.db.entity.usage;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;

/**
 * 同一日期和包名只保存一份累计快照；没有自动删除或随应用卸载删除的关系。
 *
 * 维护日期：2026年11月08日
 */
@Entity(tableName = "usage_archive_app", primaryKeys = {"localDate", "packageName"},
        foreignKeys = @ForeignKey(entity = UsageArchiveDayEntity.class,
                parentColumns = "localDate", childColumns = "localDate"),
        indices = @Index(value = {"packageName", "localDate"}))
public class UsageArchiveAppEntity {
    @NonNull public String localDate = "";
    @NonNull public String packageName = "";
    @NonNull public String appLabel = "";
    public long foregroundMs;
}
