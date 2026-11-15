package github.vhoom.zhijian.data.store;

import github.vhoom.zhijian.data.db.entity.runtime.InterceptionEntity;
import github.vhoom.zhijian.data.db.entity.runtime.PassGrantEntity;
import github.vhoom.zhijian.data.db.entity.runtime.RuntimeCheckpointEntity;
import github.vhoom.zhijian.data.db.entity.usage.UsageSessionEntity;
import github.vhoom.zhijian.data.model.runtime.InterceptionRecord;
import github.vhoom.zhijian.data.model.runtime.PassGrant;
import github.vhoom.zhijian.data.model.runtime.RuntimeCheckpoint;
import github.vhoom.zhijian.data.model.usage.UsageSession;

/**
 * 历史与运行领域对象到 Room 行对象的集中转换，避免存储入口各自复制映射逻辑。
 *
 * 维护日期：2026年11月08日
 */
final class HistoryCodec {
    /**
     * 工具类不允许实例化，调用方直接使用静态方法。
     */
    private HistoryCodec() {}

    /**
     * 把领域记录转换为对应的 Room 实体，保留稳定标识与时间字段。
     *
     * @param record 待转换、比较或持久化的领域记录
     */
    static UsageSessionEntity entity(UsageSession record) {
        UsageSessionEntity row = new UsageSessionEntity();
        row.id = record.id;
        row.packageName = record.packageName;
        row.appLabel = record.appLabel;
        row.visitId = record.visitId;
        row.zoneId = record.zoneId;
        row.source = record.source;
        row.endReason = record.endReason;
        row.startedAtMs = record.startedAtMs;
        row.endedAtMs = record.endedAtMs;
        row.foregroundMs = record.foregroundMs;
        row.sequence = record.sequence;
        row.countsAsOpen = record.countsAsOpen;
        row.complete = record.complete;
        return row;
    }

    /**
     * 读取或还原对应的使用会话记录。
     *
     * @param row 待绑定或转换的数据库或列表行
     */
    static UsageSession session(UsageSessionEntity row) {
        UsageSession.Builder builder = UsageSession.builder(row.id, row.packageName, row.startedAtMs)
                .label(row.appLabel).visit(row.visitId, row.countsAsOpen).zone(row.zoneId).source(row.source)
                .checkpoint(row.endedAtMs, row.foregroundMs, row.sequence);
        if (row.complete) builder.finished(row.endReason);
        return builder.build();
    }

    /**
     * 把领域记录转换为对应的 Room 实体，保留稳定标识与时间字段。
     *
     * @param record 待转换、比较或持久化的领域记录
     */
    static PassGrantEntity entity(PassGrant record) {
        PassGrantEntity row = new PassGrantEntity();
        row.packageName = record.packageName;
        row.policyId = record.policyId;
        row.policyRevision = record.policyRevision;
        row.bootId = record.bootId;
        row.issuedAtMs = record.issuedAtMs;
        row.durationMs = record.durationMs;
        row.deadlineElapsedMs = record.deadlineElapsedMs;
        return row;
    }

    /**
     * 读取或还原对应的临时放行记录。
     *
     * @param row 待绑定或转换的数据库或列表行
     */
    static PassGrant grant(PassGrantEntity row) {
        return new PassGrant(row.packageName, row.policyId, row.policyRevision, row.bootId,
                row.issuedAtMs, row.durationMs, row.deadlineElapsedMs);
    }

    /**
     * 把领域记录转换为对应的 Room 实体，保留稳定标识与时间字段。
     *
     * @param record 待转换、比较或持久化的领域记录
     */
    static RuntimeCheckpointEntity entity(RuntimeCheckpoint record) {
        RuntimeCheckpointEntity row = new RuntimeCheckpointEntity();
        row.key = record.key;
        row.sessionId = record.sessionId;
        row.packageName = record.packageName;
        row.bootId = record.bootId;
        row.phase = record.phase;
        row.sequence = record.sequence;
        row.recordedAtMs = record.recordedAtMs;
        row.recordedElapsedMs = record.recordedElapsedMs;
        row.continuousStartedElapsedMs = record.continuousStartedElapsedMs;
        return row;
    }

    /**
     * 读取或还原对应的运行检查点记录。
     *
     * @param row 待绑定或转换的数据库或列表行
     */
    static RuntimeCheckpoint checkpoint(RuntimeCheckpointEntity row) {
        return new RuntimeCheckpoint(row.key, row.sessionId, row.packageName, row.bootId, row.phase,
                row.sequence, row.recordedAtMs, row.recordedElapsedMs, row.continuousStartedElapsedMs);
    }

    /**
     * 把领域记录转换为对应的 Room 实体，保留稳定标识与时间字段。
     *
     * @param record 待转换、比较或持久化的领域记录
     */
    static InterceptionEntity entity(InterceptionRecord record) {
        InterceptionEntity row = new InterceptionEntity();
        row.id = record.id;
        row.packageName = record.packageName;
        row.policyId = record.policyId;
        row.policyRevision = record.policyRevision;
        row.kind = record.kind;
        row.controlLevel = record.controlLevel;
        row.reason = record.reason;
        row.challenge = record.challenge;
        row.outcome = record.outcome;
        row.occurredAtMs = record.occurredAtMs;
        row.finishedAtMs = record.finishedAtMs;
        return row;
    }

    /**
     * 读取或还原对应的拦截审计记录。
     *
     * @param row 待绑定或转换的数据库或列表行
     */
    static InterceptionRecord interception(InterceptionEntity row) {
        return new InterceptionRecord(row.id, row.packageName, row.policyId, row.policyRevision, row.kind,
                row.controlLevel, row.reason, row.challenge, row.outcome, row.occurredAtMs, row.finishedAtMs);
    }
}
