package github.vhoom.zhijian.data.db.dao;

import github.vhoom.zhijian.data.db.entity.policy.PolicyAppEntity;

import github.vhoom.zhijian.data.db.entity.StorageMetaEntity;
import github.vhoom.zhijian.data.db.entity.policy.PolicyEntity;
import github.vhoom.zhijian.data.db.entity.policy.PolicyRuleEntity;
import github.vhoom.zhijian.data.db.entity.policy.PolicyWindowEntity;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

/**
 * 已提交策略快照的底层读写接口；组合写入由存储层在事务内协调。
 *
 * 维护日期：2026年11月08日
 */
@Dao
public interface PolicyDao {
    /** 按创建顺序读取全部策略主体。 */
    @Query("SELECT * FROM policy ORDER BY creationOrder, id")
    List<PolicyEntity> policies();

    /**
     * 按稳定字符串 id 读取单条策略。
     *
     * @param id 当前记录、规则或视图的稳定标识
     */
    @Query("SELECT * FROM policy WHERE id = :id")
    PolicyEntity policy(String id);

    /** 读取全部规则参数，交由编码层按 policyId 组装。 */
    @Query("SELECT * FROM policy_rule ORDER BY policyId, type")
    List<PolicyRuleEntity> rules();

    /** 读取全部应用绑定，position 保留用户选择顺序。 */
    @Query("SELECT * FROM policy_app ORDER BY policyId, position")
    List<PolicyAppEntity> apps();

    /** 读取全部时间窗口，按策略、类型和显示顺序稳定排列。 */
    @Query("SELECT * FROM policy_window ORDER BY policyId, type, position")
    List<PolicyWindowEntity> windows();

    /**
     * 插入一条策略主体记录。
     *
     * @param policy 待判断或持久化的策略
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insertPolicy(PolicyEntity policy);

    /**
     * 更新策略主体，关联记录由同一事务的调用方维护。
     *
     * @param policy 待判断或持久化的策略
     */
    @Update
    void updatePolicy(PolicyEntity policy);

    /**
     * 批量写入策略条件及其参数。
     *
     * @param rules 策略选择的规则条件集合
     */
    @Insert
    void insertRules(List<PolicyRuleEntity> rules);

    /**
     * 批量写入策略与应用包名的绑定关系。
     *
     * @param apps 应用列表或策略绑定的应用集合
     */
    @Insert
    void insertApps(List<PolicyAppEntity> apps);

    /**
     * 批量写入策略时段和各时段独立额度。
     *
     * @param windows 按规则类型分类的时间窗口集合
     */
    @Insert
    void insertWindows(List<PolicyWindowEntity> windows);

    /**
     * 删除指定策略的时段关联。
     *
     * @param id 当前记录、规则或视图的稳定标识
     */
    @Query("DELETE FROM policy_window WHERE policyId = :id")
    void removeWindows(String id);

    /**
     * 删除指定策略的条件关联。
     *
     * @param id 当前记录、规则或视图的稳定标识
     */
    @Query("DELETE FROM policy_rule WHERE policyId = :id")
    void removeRules(String id);

    /**
     * 删除指定策略绑定的应用列表。
     *
     * @param id 当前记录、规则或视图的稳定标识
     */
    @Query("DELETE FROM policy_app WHERE policyId = :id")
    void removeApps(String id);

    /**
     * 删除指定策略及其关联数据。
     *
     * @param id 当前记录、规则或视图的稳定标识
     */
    @Query("DELETE FROM policy WHERE id = :id")
    void removePolicy(String id);

    /** 为新规则组分配只增不复用的创建顺序。 */
    @Query("SELECT COALESCE(MAX(creationOrder), 0) + 1 FROM policy")
    long nextCreationOrder();

    /**
     * 读取默认组移除记录等内部元数据。
     *
     * @param key 稳定标识或待读取的持久化设置键
     */
    @Query("SELECT value FROM storage_meta WHERE `key` = :key")
    String meta(String key);

    /**
     * 原子覆盖指定元数据键。
     *
     * @param value 本次需要读取、转换或写入的值
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void putMeta(StorageMetaEntity value);
}
