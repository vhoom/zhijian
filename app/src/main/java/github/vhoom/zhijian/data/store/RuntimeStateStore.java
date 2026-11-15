package github.vhoom.zhijian.data.store;

import android.content.Context;

import github.vhoom.zhijian.data.db.entity.runtime.InterceptionEntity;
import github.vhoom.zhijian.data.db.entity.runtime.RuntimeCheckpointEntity;
import github.vhoom.zhijian.data.db.dao.RuntimeDao;
import github.vhoom.zhijian.data.model.usage.HistoryCursor;
import github.vhoom.zhijian.data.model.runtime.InterceptionRecord;
import github.vhoom.zhijian.data.model.runtime.PassGrant;
import github.vhoom.zhijian.data.model.runtime.RuntimeCheckpoint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 持久化事实和期限。是否允许通过交互由拦截核心判定；本层验证引用与提交一致性。
 *
 * 维护日期：2026年11月14日
 */
public final class RuntimeStateStore {
    private final ZhijianStorage storage;

    /**
     * 创建 RuntimeStateStore 实例，并初始化本次操作依赖或不可变字段。
     *
     * @param context 访问系统服务、资源或持久化文件的上下文
     */
    public RuntimeStateStore(Context context) { this(ZhijianStorage.get(context)); }
    /**
     * 创建 RuntimeStateStore 实例，并初始化本次操作依赖或不可变字段。
     *
     * @param storage 进程共享或测试隔离的存储入口
     */
    RuntimeStateStore(ZhijianStorage storage) { this.storage = storage; }

    /**
     * 返回指定应用当前的临时放行；不存在时返回 null。
     *
     * @param pkg 需要查询或绑定的应用包名
     */
    public PassGrant grantFor(String pkg) {
        ZhijianStorage.State state = storage.state();
        return state.runtime.grantFor(pkg, state.policies.effectivePolicyId(pkg));
    }
    /** @param pkg 当前应用 @param bootId 启动身份 @param wall 当前墙钟 @param elapsed 当前单调时间
     * @return 同组本次启动仍有效的授权，或待清理的旧授权 */
    public PassGrant grantFor(String pkg, String bootId, long wall, long elapsed) {
        ZhijianStorage.State state = storage.state();
        return state.runtime.grantFor(pkg, state.policies.effectivePolicyId(pkg), bootId, wall, elapsed);
    }
    /**
     * 读取或还原对应的运行检查点记录。
     *
     * @param key 稳定标识或待读取的持久化设置键
     */
    public RuntimeCheckpoint checkpoint(String key) { return storage.state().runtime.checkpoint(key); }

    /**
     * 查询原子提交的完成事实，修复结果落盘后进程退出而未清理待办的情况。
     *
     * @param sessionId 待核对的拦截会话身份
     */
    public CompletableFuture<Boolean> wasAllowed(String sessionId) {
        return storage.read(db -> {
            InterceptionEntity record = db.runtime().interception(sessionId);
            return record != null && "allowed".equals(record.outcome);
        });
    }

    /**
     * 为每个应用生成独立的中断恢复检查点键。
     *
     * @param packageName 恢复连续计时的目标应用包名
     */
    public static String interruptionResumeKey(String packageName) {
        return "interruption-resume:" + packageName;
    }

    /** @param policyId 共用周期的稳定策略主键 @return 同组唯一的恢复检查点键 */
    public static String continuousResumeKey(String policyId) {
        return "continuous-resume:" + policyId;
    }

    /**
     * 原子记录中断后的恢复与连续使用起点；返回键和后台恢复同一会话时只提交一次。
     *
     * @param record 结果为 allowed 的中断拦截审计记录
     * @param bootId 当前设备启动身份
     * @param resumedElapsedMs 本次实际恢复使用的单调时间，单位为毫秒
     */
    public CompletableFuture<Void> recordInterruptionResume(InterceptionRecord record,
            String bootId, long resumedElapsedMs) {
        return recordInterruptionResume(record, bootId, resumedElapsedMs, true);
    }

    /**
     * 区分主动恢复和系统证据已完成的休息，后者保留新周期中已产生的使用量。
     * @param record 完成审计 @param bootId 当前启动身份 @param resumedElapsedMs 恢复单调时间
     * @param resetCycle 是否需要写入主动恢复检查点
     * @return 原子提交结果
     */
    public CompletableFuture<Void> recordInterruptionResume(InterceptionRecord record,
            String bootId, long resumedElapsedMs, boolean resetCycle) {
        return storage.submit(db -> {
            db.runInTransaction(() -> {
                InterceptionEntity old = db.runtime().interception(record.id);
                if (old != null) {
                    if (!sameInterruptionResume(old, record)) {
                        throw new IllegalArgumentException("Conflicting interception id");
                    }
                    return;
                }
                PolicySnapshot policies = storage.snapshot();
                if (!"allowed".equals(record.outcome) || !"interrupt".equals(record.kind)
                        || !policies.level().code.equals(record.controlLevel)
                        || !policies.effectivePolicyId(record.packageName).equals(record.policyId)
                        || policies.revisionOf(record.policyId) != record.policyRevision) {
                    throw new IllegalArgumentException("Resume refers to a different or stale policy");
                }
                if (resetCycle) {
                    saveResumeCheckpoint(db.runtime(), interruptionResumeKey(record.packageName),
                            record, bootId, resumedElapsedMs);
                    saveResumeCheckpoint(db.runtime(), continuousResumeKey(record.policyId),
                            record, bootId, resumedElapsedMs);
                }
                if (resetCycle) db.runtime().removeGrant(record.packageName);
                db.runtime().insertInterception(HistoryCodec.entity(record));
            });
            storage.refreshRuntimeOnWriter();
            return null;
        });
    }

    /** @param dao 事务内运行表入口 @param key 目标范围键 @param record 恢复事实
     * @param bootId 启动身份 @param elapsed 恢复单调时间 */
    private static void saveResumeCheckpoint(RuntimeDao dao, String key, InterceptionRecord record,
            String bootId, long elapsed) {
        RuntimeCheckpointEntity previous = dao.checkpoint(key);
        dao.putCheckpoint(HistoryCodec.entity(new RuntimeCheckpoint(key, record.id,
                record.packageName, bootId, "interruption-resumed",
                previous == null ? 1L : Math.addExact(previous.sequence, 1L),
                record.finishedAtMs, elapsed, elapsed)));
    }

    /**
     * 在同一事务写入拦截审计和可选临时放行，重试保持幂等。
     *
     * @param record 待转换、比较或持久化的领域记录
     * @param grant 本次可选临时放行，没有签发时为 null
     */
    public CompletableFuture<Void> recordInterception(InterceptionRecord record, PassGrant grant) {
        return storage.submit(db -> {
            db.runInTransaction(() -> {
                InterceptionEntity old = db.runtime().interception(record.id);
                if (old != null) {
                    if (!sameInterception(old, record)) throw new IllegalArgumentException("Conflicting interception id");
                    return; // 重试不能覆盖另一个更新的通行证，也不能再次发放。
                }
                if (grant != null) {
                    PolicySnapshot policies = storage.snapshot();
                    if (!"allowed".equals(record.outcome) || !record.packageName.equals(grant.packageName)
                            || "seal".equals(record.controlLevel)
                            || !policies.level().code.equals(record.controlLevel)
                            || !record.policyId.equals(grant.policyId) || record.policyRevision != grant.policyRevision
                            || !policies.effectivePolicyId(grant.packageName).equals(grant.policyId)
                            || policies.revisionOf(grant.policyId) != grant.policyRevision) {
                        throw new IllegalArgumentException("Grant refers to a different or stale policy");
                    }
                    db.runtime().putGrant(HistoryCodec.entity(grant));
                }
                db.runtime().insertInterception(HistoryCodec.entity(record));
            });
            storage.refreshRuntimeOnWriter();
            return null;
        });
    }

    /**
     * 在后台撤销指定应用的放行并刷新运行快照。
     *
     * @param packageName 需要查询或管控的应用包名
     */
    public CompletableFuture<Void> revokeGrant(String packageName) {
        return storage.submit(db -> {
            db.runtime().removeGrant(packageName);
            storage.refreshRuntimeOnWriter();
            return null;
        });
    }

    /**
     * 只保存更新的运行检查点，重复提交不覆盖已有状态。
     *
     * @param checkpoint 需要幂等持久化的运行检查点
     */
    public CompletableFuture<Void> saveCheckpoint(RuntimeCheckpoint checkpoint) {
        return storage.submit(db -> {
            db.runInTransaction(() -> {
                if (isNewCheckpoint(db.runtime(), checkpoint)) {
                    db.runtime().putCheckpoint(HistoryCodec.entity(checkpoint));
                }
            });
            storage.refreshRuntimeOnWriter();
            return null;
        });
    }

    /**
     * 根据复合游标读取下一页历史，避免相同时间戳导致重复或遗漏。
     *
     * @param cursor 由时间和 ID 组成的历史分页游标
     * @param limit 当前额度阈值或查询允许的最大条目数
     */
    public CompletableFuture<List<InterceptionRecord>> page(HistoryCursor cursor, int limit) {
        UsageHistoryStore.checkLimit(limit);
        return storage.read(db -> {
            List<InterceptionRecord> records = new ArrayList<>();
            for (InterceptionEntity row : db.runtime().interceptions(cursor.beforeTimeMs, cursor.beforeId, limit)) {
                records.add(HistoryCodec.interception(row));
            }
            return Collections.unmodifiableList(records);
        });
    }

    /**
     * 比较运行序号，判断本次检查点能否推进数据库状态。
     *
     * @param dao 负责当前数据表查询与写入的 DAO
     * @param checkpoint 需要幂等持久化的运行检查点
     */
    static boolean isNewCheckpoint(RuntimeDao dao, RuntimeCheckpoint checkpoint) {
        RuntimeCheckpointEntity old = dao.checkpoint(checkpoint.key);
        if (old == null || checkpoint.sequence > old.sequence) return true;
        if (checkpoint.sequence == old.sequence && (!old.sessionId.equals(checkpoint.sessionId)
                || !old.packageName.equals(checkpoint.packageName) || !old.bootId.equals(checkpoint.bootId)
                || !old.phase.equals(checkpoint.phase) || old.recordedAtMs != checkpoint.recordedAtMs
                || old.recordedElapsedMs != checkpoint.recordedElapsedMs
                || old.continuousStartedElapsedMs != checkpoint.continuousStartedElapsedMs)) {
            throw new IllegalArgumentException("Sequence reused with a different runtime checkpoint");
        }
        return false;
    }

    /**
     * 比较审计记录身份和结果，避免同一会话重复写入。
     *
     * @param old 数据库中已存在、需要比较的旧记录
     * @param record 待转换、比较或持久化的领域记录
     */
    private static boolean sameInterception(InterceptionEntity old, InterceptionRecord record) {
        return old.packageName.equals(record.packageName) && old.policyId.equals(record.policyId)
                && old.policyRevision == record.policyRevision && old.kind.equals(record.kind)
                && old.controlLevel.equals(record.controlLevel) && old.reason.equals(record.reason)
                && old.challenge.equals(record.challenge) && old.outcome.equals(record.outcome)
                && old.occurredAtMs == record.occurredAtMs && old.finishedAtMs == record.finishedAtMs;
    }

    /**
     * 同一中断可从按钮、返回键或后台恢复重复完成；保留首次结果与起点，不再次计时。
     *
     * @param old 首次已提交的中断结果
     * @param record 另一个恢复入口提交的结果
     */
    private static boolean sameInterruptionResume(InterceptionEntity old, InterceptionRecord record) {
        return "interrupt".equals(old.kind) && "interrupt".equals(record.kind)
                && "allowed".equals(old.outcome) && "allowed".equals(record.outcome)
                && old.policyId.equals(record.policyId)
                && old.policyRevision == record.policyRevision && old.controlLevel.equals(record.controlLevel)
                && old.reason.equals(record.reason);
    }
}
