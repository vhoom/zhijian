package github.vhoom.zhijian.data.db.dao;

import github.vhoom.zhijian.data.db.entity.runtime.InterceptionEntity;
import github.vhoom.zhijian.data.db.entity.runtime.PassGrantEntity;
import github.vhoom.zhijian.data.db.entity.runtime.RuntimeCheckpointEntity;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

/**
 * 拦截事实、临时放行和运行检查点的持久化接口。
 *
 * 维护日期：2026年11月15日
 */
@Dao
public interface RuntimeDao {
    /**
     * 拦截记录必须只写一次；重复 id 表示调用方幂等逻辑失效。
     *
     * @param record 待转换、比较或持久化的领域记录
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    void insertInterception(InterceptionEntity record);

    /**
     * 读取或还原对应的拦截审计记录。
     *
     * @param id 当前记录、规则或视图的稳定标识
     */
    @Query("SELECT * FROM interception_record WHERE id = :id")
    InterceptionEntity interception(String id);

    /**
     * 使用时间与 id 组成稳定游标，倒序读取下一页拦截记录。
     *
     * @param before 变更前的文本数量或待比较的旧记录
     * @param beforeId 上一页末条记录的稳定 ID，用于时间相同时分页
     * @param limit 当前额度阈值或查询允许的最大条目数
     */
    @Query("SELECT * FROM interception_record WHERE (occurredAtMs, id) < (:before, :beforeId) "
            + "ORDER BY occurredAtMs DESC, id DESC LIMIT :limit")
    List<InterceptionEntity> interceptions(long before, String beforeId, int limit);

    /**
     * 同一应用只保留最新临时放行。
     *
     * @param grant 本次可选临时放行，没有签发时为 null
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void putGrant(PassGrantEntity grant);

    /**
     * 读取或还原对应的临时放行记录。
     *
     * @param pkg 需要查询或绑定的应用包名
     */
    @Query("SELECT * FROM pass_grant WHERE packageName = :pkg")
    PassGrantEntity grant(String pkg);

    /**
     * 读取数据库内全部临时放行记录。
     */
    @Query("SELECT * FROM pass_grant")
    List<PassGrantEntity> grants();

    /**
     * 撤销指定应用当前的临时放行。
     *
     * @param pkg 需要查询或绑定的应用包名
     */
    @Query("DELETE FROM pass_grant WHERE packageName = :pkg")
    void removeGrant(String pkg);

    /**
     * 策略删除后撤销其签发的全部放行；同一策略的参数修改保留原期限。
     *
     * @param policyId 数据库中的稳定策略主键
     */
    @Query("DELETE FROM pass_grant WHERE policyId = :policyId")
    void removeGrantsForPolicy(String policyId);

    /**
     * 撤销全部临时放行，使新配置立即重新生效。
     */
    @Query("DELETE FROM pass_grant")
    void removeAllGrants();

    /**
     * 同一运行键只保留最新 sequence 的检查点。
     *
     * @param checkpoint 需要幂等持久化的运行检查点
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void putCheckpoint(RuntimeCheckpointEntity checkpoint);

    /**
     * 读取或还原对应的运行检查点记录。
     *
     * @param key 稳定标识或待读取的持久化设置键
     */
    @Query("SELECT * FROM runtime_checkpoint WHERE `key` = :key")
    RuntimeCheckpointEntity checkpoint(String key);

    /**
     * 读取全部运行恢复检查点。
     */
    @Query("SELECT * FROM runtime_checkpoint")
    List<RuntimeCheckpointEntity> checkpoints();

    /** 安装身份变化时，在同一事务内撤销应用与连续周期的恢复检查点，保留其他检查点及历史审计。 */
    @Query("DELETE FROM runtime_checkpoint WHERE `key` GLOB 'interruption-resume:*' "
            + "OR `key` GLOB 'continuous-resume:*'")
    void removeInterruptionResumeCheckpoints();
}
