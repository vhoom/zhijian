package github.vhoom.zhijian.data.db.entity.runtime;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * 单应用当前有效的临时放行凭据；同一包名最多保留一条。
 *
 * 维护日期：2026年11月08日
 */
@Entity(tableName = "pass_grant")
public class PassGrantEntity {
    @PrimaryKey @NonNull public String packageName = "";
    @NonNull public String policyId = "";
    public long policyRevision;
    @NonNull public String bootId = "";
    public long issuedAtMs;
    public long durationMs;
    public long deadlineElapsedMs;
}
