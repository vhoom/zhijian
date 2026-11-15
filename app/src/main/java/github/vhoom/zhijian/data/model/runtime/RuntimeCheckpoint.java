package github.vhoom.zhijian.data.model.runtime;

import java.util.Objects;

/**
 * 同一个 key 的 sequence 跨服务恢复继续递增；长按触摸进度不落盘。
 *
 * 维护日期：2026年11月08日
 */
public final class RuntimeCheckpoint {
    public final String key;
    public final String sessionId;
    public final String packageName;
    public final String bootId;
    public final String phase;
    public final long sequence;
    public final long recordedAtMs;
    public final long recordedElapsedMs;
    public final long continuousStartedElapsedMs;

    /**
     * 创建 RuntimeCheckpoint 实例，并初始化本次操作依赖或不可变字段。
     *
     * @param key 稳定标识或待读取的持久化设置键
     * @param sessionId 本次拦截或使用会话的稳定标识
     * @param packageName 需要查询或管控的应用包名
     * @param bootId 本次系统启动的稳定身份
     * @param phase 运行检查点对应的阶段标识
     * @param sequence 累计状态的单调递增序号
     * @param recordedAtMs 检查点写入的墙钟时间戳，单位为毫秒
     * @param recordedElapsedMs 检查点写入的系统启动后单调计时值
     * @param continuousStartedElapsedMs 本次连续使用开始的单调计时值
     */
    public RuntimeCheckpoint(String key, String sessionId, String packageName, String bootId,
            String phase, long sequence, long recordedAtMs, long recordedElapsedMs,
            long continuousStartedElapsedMs) {
        this.key = Objects.requireNonNull(key);
        this.sessionId = Objects.requireNonNull(sessionId);
        this.packageName = Objects.requireNonNull(packageName);
        this.bootId = Objects.requireNonNull(bootId);
        this.phase = Objects.requireNonNull(phase);
        this.sequence = sequence;
        this.recordedAtMs = recordedAtMs;
        this.recordedElapsedMs = recordedElapsedMs;
        this.continuousStartedElapsedMs = continuousStartedElapsedMs;
        if (key.isEmpty() || bootId.isEmpty() || sequence < 0 || recordedAtMs < 0
                || continuousStartedElapsedMs < 0 || recordedElapsedMs < continuousStartedElapsedMs) {
            throw new IllegalArgumentException("Invalid runtime checkpoint");
        }
    }
}
