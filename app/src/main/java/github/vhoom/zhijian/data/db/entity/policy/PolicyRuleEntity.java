package github.vhoom.zhijian.data.db.entity.policy;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.ForeignKey;

/**
 * 某项策略类型的启用状态和数值参数。
 *
 * 维护日期：2026年11月08日
 */
@Entity(tableName = "policy_rule", primaryKeys = {"policyId", "type"},
        foreignKeys = @ForeignKey(entity = PolicyEntity.class, parentColumns = "id",
                childColumns = "policyId", onDelete = ForeignKey.CASCADE))
public class PolicyRuleEntity {
    @NonNull public String policyId = "";
    @NonNull public String type = "";
    public boolean selected;
    public long durationMs;
    /** 同一规则类型的第二个时长参数；连续提醒用它保存“我将休息”。 */
    public long secondaryDurationMs;
    public int limitCount;
}
