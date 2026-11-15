package github.vhoom.zhijian.data.db.entity.policy;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;

/**
 * 策略与受管应用的有序关联；删除策略时由外键级联清理。
 *
 * 维护日期：2026年11月08日
 */
@Entity(tableName = "policy_app", primaryKeys = {"policyId", "packageName"},
        indices = @Index("packageName"),
        foreignKeys = @ForeignKey(entity = PolicyEntity.class, parentColumns = "id",
                childColumns = "policyId", onDelete = ForeignKey.CASCADE))
public class PolicyAppEntity {
    @NonNull public String policyId = "";
    @NonNull public String packageName = "";
    public int position;
}
