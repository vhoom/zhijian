package github.vhoom.zhijian.data.db.entity.runtime;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * 历史事实保留原因快照，删除规则不会删除复盘记录。
 *
 * 维护日期：2026年11月08日
 */
@Entity(tableName = "interception_record", indices = {
        @Index(value = {"occurredAtMs", "id"}),
        @Index(value = {"packageName", "occurredAtMs", "id"})})
public class InterceptionEntity {
    @PrimaryKey @NonNull public String id = "";
    @NonNull public String packageName = "";
    @NonNull public String policyId = "";
    public long policyRevision;
    @NonNull public String kind = "";
    @NonNull public String controlLevel = "";
    @NonNull public String reason = "";
    @NonNull public String challenge = "";
    @NonNull public String outcome = "";
    public long occurredAtMs;
    public long finishedAtMs;
}
