package github.vhoom.zhijian.data.db.entity.policy;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;
import androidx.room.ColumnInfo;

/**
 * 策略主体及版本信息；具体条件、应用与时段分别存放在关联表。
 *
 * 维护日期：2026年11月08日
 */
@Entity(tableName = "policy")
public class PolicyEntity {
    @PrimaryKey @NonNull public String id = "";
    @NonNull public String scope = "group";
    @NonNull public String name = "";
    public long groupId;
    public long creationOrder;
    public long revision;
    public boolean enabled = true;
    public long passDurationMs;
    @NonNull public String controlLevel = "repair";
    /** 七天完整参数；全局规则和首次编辑草稿使用空文本。 */
    @NonNull @ColumnInfo(defaultValue = "''") public String weekdayRulesJson = "";
}
