package github.vhoom.zhijian.data.db.entity.usage;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * 完整使用片段长期保留；进行中的片段用同一 id 和递增 sequence 覆盖检查点。
 *
 * 维护日期：2026年11月08日
 */
@Entity(tableName = "usage_session", indices = {
        @Index(value = {"startedAtMs", "id"}),
        @Index(value = {"packageName", "startedAtMs", "id"}),
        @Index("visitId")})
public class UsageSessionEntity {
    @PrimaryKey @NonNull public String id = "";
    @NonNull public String packageName = "";
    @NonNull public String appLabel = "";
    @NonNull public String visitId = "";
    @NonNull public String zoneId = "UTC";
    @NonNull public String source = "accessibility";
    @NonNull public String endReason = "ongoing";
    public long startedAtMs;
    public long endedAtMs;
    public long foregroundMs;
    public long sequence;
    public boolean countsAsOpen;
    public boolean complete;
}
