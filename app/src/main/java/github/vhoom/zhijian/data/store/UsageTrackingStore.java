package github.vhoom.zhijian.data.store;

import github.vhoom.zhijian.data.db.dao.PolicyDao;
import github.vhoom.zhijian.data.db.entity.StorageMetaEntity;
import github.vhoom.zhijian.data.model.policy.RuleGroup;
import github.vhoom.zhijian.domain.usage.UsageTrackingStart;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 与规则同一事务保存首次生效起点；安装身份变化才隔离旧统计，强度和修订不改变起点。
 *
 * 维护日期：2026年11月15日
 */
final class UsageTrackingStore {
    private static final String KEY = "usage_tracking_starts";

    /** 起点存储由现有写入队列和配置事务调度。 */
    private UsageTrackingStore() { }

    /**
     * @param dao 同一事务的策略 DAO
     * @param installationId 当前 no-backup 安装身份
     * @return 已保存起点是否来自另一安装；没有记录表示尚未建立起点
     */
    static boolean belongsToAnotherInstallation(PolicyDao dao, String installationId) {
        JSONObject root = read(dao);
        return root != null && !installationId.equals(string(root, "installation"));
    }

    /**
     * @param dao 配置事务持有的 DAO
     * @param candidate 已通过变更校验的候选规则
     * @param installationId 本次安装身份
     * @param bootId 同一生效边界的启动身份
     * @param wallMs 生效边界的墙钟毫秒
     * @param elapsedMs 生效边界的单调毫秒
     * @return 待随事务成功发布的不可变起点；已启用策略始终保留原值
     */
    static Map<String, UsageTrackingStart> synchronize(PolicyDao dao, PolicySnapshot candidate,
            String installationId, String bootId, long wallMs, long elapsedMs) {
        try {
            JSONObject old = read(dao);
            boolean sameInstallation = old != null && installationId.equals(old.getString("installation"));
            JSONObject rows = sameInstallation ? old.getJSONObject("starts") : new JSONObject();
            JSONObject nextRows = new JSONObject();
            Map<String, UsageTrackingStart> starts = new HashMap<>();
            for (Map.Entry<String, RuleGroup> entry : candidate.configuredPolicies().entrySet()) {
                String key = entry.getKey();
                RuleGroup policy = entry.getValue();
                UsageTrackingStart start = rows.has(key) ? decode(rows.getJSONObject(key)) : null;
                if (start == null && policy.enabled && policy.hasAnyRules()) {
                    start = new UsageTrackingStart(installationId + ':' + UUID.randomUUID(), bootId, wallMs, elapsedMs);
                }
                if (start != null) {
                    starts.put(key, start);
                    nextRows.put(key, new JSONObject().put("id", start.id).put("boot", start.bootId)
                            .put("wall", start.wallMs).put("elapsed", start.elapsedMs));
                }
            }
            dao.putMeta(new StorageMetaEntity(KEY, new JSONObject().put("installation", installationId)
                    .put("starts", nextRows).toString()));
            return Collections.unmodifiableMap(starts);
        } catch (JSONException | IllegalArgumentException failure) {
            throw new IllegalStateException("Invalid usage tracking starts", failure);
        }
    }

    /** @param dao 策略 DAO @return 完整当前格式，缺失时为空，损坏时阻止配置提交 */
    private static JSONObject read(PolicyDao dao) {
        String value = dao.meta(KEY);
        if (value == null) return null;
        try { return new JSONObject(value); }
        catch (JSONException failure) { throw new IllegalStateException("Invalid usage tracking metadata", failure); }
    }

    /** @param row 当前格式的起点对象 @return 已验证的不可变起点 @throws JSONException 字段缺失 */
    private static UsageTrackingStart decode(JSONObject row) throws JSONException {
        return new UsageTrackingStart(row.getString("id"), row.getString("boot"), row.getLong("wall"), row.getLong("elapsed"));
    }

    /** @param row 元数据对象 @param key 必需字段 @return 当前格式的字符串，缺失时报告损坏 */
    private static String string(JSONObject row, String key) {
        try { return row.getString(key); }
        catch (JSONException failure) { throw new IllegalStateException("Invalid usage tracking metadata", failure); }
    }
}
