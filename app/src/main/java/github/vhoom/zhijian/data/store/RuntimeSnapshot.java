package github.vhoom.zhijian.data.store;

import github.vhoom.zhijian.data.db.entity.runtime.PassGrantEntity;
import github.vhoom.zhijian.data.db.entity.runtime.RuntimeCheckpointEntity;
import github.vhoom.zhijian.data.db.dao.RuntimeDao;
import github.vhoom.zhijian.data.model.runtime.PassGrant;
import github.vhoom.zhijian.data.model.runtime.RuntimeCheckpoint;

import java.util.HashMap;
import java.util.Map;

/**
 * 内存中的运行状态快照；数据库提交完成后整体替换，读取方不会看到半次更新。
 *
 * 维护日期：2026年11月08日
 */
public final class RuntimeSnapshot {
    private final Map<String, PassGrant> grants = new HashMap<>();
    private final Map<String, RuntimeCheckpoint> checkpoints = new HashMap<>();

    /**
     * 创建 RuntimeSnapshot 实例，并初始化本次操作依赖或不可变字段。
     *
     * @param dao 负责当前数据表查询与写入的 DAO
     */
    private RuntimeSnapshot(RuntimeDao dao) {
        for (PassGrantEntity row : dao.grants()) grants.put(row.packageName, HistoryCodec.grant(row));
        for (RuntimeCheckpointEntity row : dao.checkpoints()) checkpoints.put(row.key, HistoryCodec.checkpoint(row));
    }

    /**
     * 从已提交数据库数据组装当前模块的不可变内存快照。
     *
     * @param dao 负责当前数据表查询与写入的 DAO
     */
    static RuntimeSnapshot load(RuntimeDao dao) { return new RuntimeSnapshot(dao); }

    /**
     * 返回应用当前的临时放行；没有放行时返回 null。
     *
     * @param pkg 需要查询或绑定的应用包名
     */
    public PassGrant grantFor(String pkg) { return grants.get(pkg); }

    /** @param pkg 当前应用 @param policyId 当前生效策略 @return 同组共享最长有效期限，不扩大到独立覆盖 */
    public PassGrant grantFor(String pkg, String policyId) {
        PassGrant selected = null;
        for (PassGrant grant : grants.values()) {
            if (grant.covers(pkg, policyId) && (selected == null
                    || grant.deadlineElapsedMs > selected.deadlineElapsedMs)) selected = grant;
        }
        return selected;
    }

    /** @param pkg 当前应用 @param policyId 生效策略 @param bootId 启动身份 @param wall 当前墙钟
     * @param elapsed 当前单调时间 @return 优先有效授权，旧启动长期限不能遮住本次新授权 */
    public PassGrant grantFor(String pkg, String policyId, String bootId, long wall, long elapsed) {
        PassGrant selected = null;
        for (PassGrant grant : grants.values()) {
            if (grant.covers(pkg, policyId) && grant.remainingMs(bootId, wall, elapsed) > 0L
                    && (selected == null || grant.remainingMs(bootId, wall, elapsed)
                    > selected.remainingMs(bootId, wall, elapsed))) selected = grant;
        }
        return selected == null ? grantFor(pkg, policyId) : selected;
    }

    /**
     * 返回指定运行键的恢复检查点；首次运行时返回 null。
     *
     * @param key 稳定标识或待读取的持久化设置键
     */
    public RuntimeCheckpoint checkpoint(String key) { return checkpoints.get(key); }
}
