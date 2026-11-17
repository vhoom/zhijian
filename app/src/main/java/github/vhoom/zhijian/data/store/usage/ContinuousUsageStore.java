package github.vhoom.zhijian.data.store.usage;

import android.content.Context;
import android.content.SharedPreferences;
import github.vhoom.zhijian.domain.usage.ContinuousUsageCycle;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 按生效策略持久化连续周期，独立于页面和进程生命周期。
 *
 * 维护日期：2026年11月16日
 */
public final class ContinuousUsageStore {
    private final SharedPreferences preferences;

    /** @param context 应用私有存储上下文 */
    public ContinuousUsageStore(Context context) {
        preferences = context.getSharedPreferences("continuous_usage_cycles", Context.MODE_PRIVATE);
    }

    /** @param policyId 稳定策略主键 @return 已处理边界，不存在时为空 */
    public ContinuousUsageCycle read(String policyId) {
        return read(policyId, null);
    }

    /** @param policyId 周期键 @param trackingId 本次统计身份，null 表示只读当前已保存记录
     * @return 身份相同的检查点；恢复自另一安装或旧统计起点时为空 */
    public ContinuousUsageCycle read(String policyId, String trackingId) {
        String value = preferences.getString(policyId, null);
        if (value == null) return null;
        try {
            JSONObject row = new JSONObject(value);
            if (trackingId != null && !trackingId.equals(row.optString("tracking", ""))) return null;
            if (!row.has("foreground") || !row.has("activity")) {
                throw new JSONException("Missing foreground identity");
            }
            return new ContinuousUsageCycle(row.getString("boot"), row.getLong("wall"),
                    row.getLong("elapsed"), row.getLong("used"), row.getLong("rest"),
                    row.isNull("foreground") ? null : row.getString("foreground"),
                    row.isNull("activity") ? null : row.getString("activity"), row.getString("resume"),
                    row.getLong("generation"), row.optLong("requiredRest", 0L));
        } catch (JSONException | IllegalArgumentException failure) {
            throw new IllegalStateException("Invalid continuous usage state: " + policyId, failure);
        }
    }

    /** @param policyId 稳定策略主键 @param cycle 本次已完整处理的周期 */
    public void save(String policyId, ContinuousUsageCycle cycle) {
        save(policyId, cycle, "");
    }

    /** @param policyId 周期键 @param cycle 已结算周期 @param trackingId 固定统计身份，强度变化不更换 */
    public void save(String policyId, ContinuousUsageCycle cycle, String trackingId) {
        try {
            JSONObject row = new JSONObject().put("boot", cycle.bootId).put("wall", cycle.wallMs)
                    .put("tracking", trackingId)
                    .put("elapsed", cycle.elapsedMs).put("used", cycle.usedMs).put("rest", cycle.restMs)
                    .put("foreground", cycle.foregroundPackage == null ? JSONObject.NULL : cycle.foregroundPackage)
                    .put("activity", cycle.foregroundClass == null ? JSONObject.NULL : cycle.foregroundClass)
                    .put("resume", cycle.resumeId).put("generation", cycle.generation)
                    .put("requiredRest", cycle.requiredRestMs);
            if (!preferences.edit().putString(policyId, row.toString()).commit()) {
                throw new IllegalStateException("Could not persist continuous usage state");
            }
        } catch (JSONException failure) {
            throw new IllegalArgumentException("Invalid continuous usage state", failure);
        }
    }
}
