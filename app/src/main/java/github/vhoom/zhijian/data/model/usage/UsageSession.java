package github.vhoom.zhijian.data.model.usage;

import java.time.ZoneId;
import java.util.Objects;

/**
 * 一段有效使用的累计检查点。id 标识片段，sequence 在同一片段内递增。
 *
 * 维护日期：2026年11月08日
 */
public final class UsageSession {
    public final String id;
    public final String packageName;
    public final String appLabel;
    public final String visitId;
    public final String zoneId;
    public final String source;
    public final String endReason;
    public final long startedAtMs;
    public final long endedAtMs;
    public final long foregroundMs;
    public final long sequence;
    public final boolean countsAsOpen;
    public final boolean complete;

    /**
     * 创建 UsageSession 实例，并初始化本次操作依赖或不可变字段。
     *
     * @param builder 已经收集完整会话字段的构建器
     */
    private UsageSession(Builder builder) {
        id = requireText(builder.id);
        packageName = requireText(builder.packageName);
        appLabel = Objects.requireNonNull(builder.appLabel);
        visitId = requireText(builder.visitId);
        zoneId = ZoneId.of(builder.zoneId).getId();
        source = requireText(builder.source);
        endReason = requireText(builder.endReason);
        startedAtMs = builder.startedAtMs;
        endedAtMs = builder.endedAtMs;
        foregroundMs = builder.foregroundMs;
        sequence = builder.sequence;
        countsAsOpen = builder.countsAsOpen;
        complete = builder.complete;
        if (startedAtMs < 0 || endedAtMs < startedAtMs || foregroundMs < 0 || sequence < 0
                || (endedAtMs == startedAtMs && foregroundMs != 0)) {
            throw new IllegalArgumentException("Invalid usage checkpoint");
        }
        // 校时应结束旧片段；该上限也防止异常数据一次生成无界的日汇总。
        if (endedAtMs - startedAtMs > 366L * 86_400_000L) {
            throw new IllegalArgumentException("Split usage sessions at calendar boundaries");
        }
    }

    /**
     * 创建使用会话构建器，并保留明细的稳定身份。
     *
     * @param id 当前记录、规则或视图的稳定标识
     * @param packageName 需要查询或管控的应用包名
     * @param startedAtMs 会话开始的墙钟时间戳，单位为毫秒
     */
    public static Builder builder(String id, String packageName, long startedAtMs) {
        return new Builder(id, packageName, startedAtMs);
    }

    /**
     * 校验标识文本非空，拒绝无法持久化的记录。
     *
     * @param value 本次需要读取、转换或写入的值
     */
    private static String requireText(String value) {
        if (value == null || value.isEmpty()) throw new IllegalArgumentException("Missing record identifier");
        return value;
    }

    /**
     * 逐项收集使用明细字段，并在构建时统一校验。
     */
    public static final class Builder {
        private final String id;
        private final String packageName;
        private final long startedAtMs;
        private String appLabel = "";
        private String visitId;
        private String zoneId = "UTC";
        private String source = "accessibility";
        private String endReason = "ongoing";
        private long endedAtMs;
        private long foregroundMs;
        private long sequence;
        private boolean countsAsOpen;
        private boolean complete;

        /**
         * 创建 Builder 实例，并初始化本次操作依赖或不可变字段。
         *
         * @param id 当前记录、规则或视图的稳定标识
         * @param packageName 需要查询或管控的应用包名
         * @param startedAtMs 会话开始的墙钟时间戳，单位为毫秒
         */
        private Builder(String id, String packageName, long startedAtMs) {
            this.id = id;
            this.packageName = packageName;
            this.startedAtMs = startedAtMs;
            this.endedAtMs = startedAtMs;
            this.visitId = id;
        }

        /**
         * 设置会话对应的应用显示名称。
         *
         * @param label 界面显示的可读名称
         */
        public Builder label(String label) { appLabel = label; return this; }
        /**
         * 设置同一次应用访问共享的访问标识。
         *
         * @param id 当前记录、规则或视图的稳定标识
         * @param newOpen 该时间片段是否计作一次新打开
         */
        public Builder visit(String id, boolean newOpen) { visitId = id; countsAsOpen = newOpen; return this; }
        /**
         * 设置记录归属的时区。
         *
         * @param zone 日期归属或时间换算使用的时区
         */
        public Builder zone(String zone) { zoneId = zone; return this; }
        /**
         * 设置明细的采集来源标识。
         *
         * @param value 本次需要读取、转换或写入的值
         */
        public Builder source(String value) { source = value; return this; }
        /**
         * 读取或还原对应的运行检查点记录。
         *
         * @param endMs 区间结束时间戳，单位为毫秒
         * @param usedMs 本次结算累计的前台时长，单位为毫秒
         * @param nextSequence 下一次结算使用的递增序号
         */
        public Builder checkpoint(long endMs, long usedMs, long nextSequence) {
            endedAtMs = endMs;
            foregroundMs = usedMs;
            sequence = nextSequence;
            return this;
        }
        /**
         * 设置会话结束时刻、累计用量和结算序号。
         *
         * @param reason 记录结束或触发的原因
         */
        public Builder finished(String reason) { complete = true; endReason = reason; return this; }
        /**
         * 构造经过完整校验的不可变使用会话。
         */
        public UsageSession build() { return new UsageSession(this); }
    }
}
