package github.vhoom.zhijian.data.db.entity.runtime;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * 运行引擎的可恢复检查点，保存墙钟和单调时钟两套时间口径。
 *
 * 维护日期：2026年11月08日
 */
@Entity(tableName = "runtime_checkpoint")
public class RuntimeCheckpointEntity {
    @PrimaryKey @NonNull public String key = "";
    @NonNull public String sessionId = "";
    @NonNull public String packageName = "";
    @NonNull public String bootId = "";
    @NonNull public String phase = "";
    public long sequence;
    public long recordedAtMs;
    public long recordedElapsedMs;
    public long continuousStartedElapsedMs;
}
