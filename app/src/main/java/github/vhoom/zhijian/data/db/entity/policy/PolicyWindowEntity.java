package github.vhoom.zhijian.data.db.entity.policy;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.ColumnInfo;

/**
 * 策略中的独立时间窗口；windowId 在编辑、持久化和运行计量间保持稳定。
 *
 * 维护日期：2026年11月13日
 */
@Entity(tableName = "policy_window", primaryKeys = {"policyId", "windowId"},
        foreignKeys = @ForeignKey(entity = PolicyEntity.class, parentColumns = "id",
                childColumns = "policyId", onDelete = ForeignKey.CASCADE),
        indices = @Index(value = {"policyId", "type", "position"}))
public class PolicyWindowEntity {
    @NonNull public String policyId = "";
    @NonNull public String windowId = "";
    @NonNull public String type = "";
    public int position;
    public int startMinute;
    public int endMinute;
    public long durationMs;
    /** 时段限次独立保存整数次数，时长规则保持零。 */
    @ColumnInfo(defaultValue = "0") public int limitCount;
    /** 每段连续提醒独立保存休息时长，默认一分钟。 */
    @ColumnInfo(defaultValue = "60000") public long interruptionPauseMs = 60_000L;
}
