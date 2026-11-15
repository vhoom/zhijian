package github.vhoom.zhijian.data.store.interception;

import android.content.Context;
import android.content.SharedPreferences;

import github.vhoom.zhijian.data.model.policy.ControlLevel;
import github.vhoom.zhijian.data.model.runtime.BlockContinuation;
import github.vhoom.zhijian.data.model.runtime.PendingInterception;
import github.vhoom.zhijian.domain.interception.InterceptionDecision;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongPredicate;

/**
 * 持久化一次应用访问的禁用忽略；后台前台观察和结果提交共用原子偏好快照。
 *
 * 维护日期：2026年11月14日
 */
public final class BlockContinuationStore {
    private static final Object LOCK = new Object();
    private static final String FOREGROUND = "foreground";
    private static final String PREFIX = "app:";
    private final SharedPreferences preferences;
    private final String selfPackage;

    /** @param context 私有偏好与 止间包名的上下文 */
    public BlockContinuationStore(Context context) {
        preferences = context.getSharedPreferences("block_continuations", Context.MODE_PRIVATE);
        selfPackage = context.getPackageName();
    }

    /**
     * 后台记录真实应用或桌面切换；拦截窗口不结束原访问，系统临时窗口由服务先过滤。
     * @param packageName 服务确认的前台包名，未知状态不推测离开
     */
    public void observeForeground(String packageName) {
        if (packageName == null || selfPackage.equals(packageName)) return;
        synchronized (LOCK) {
            SharedPreferences.Editor editor = preferences.edit();
            boolean changed = !packageName.equals(preferences.getString(FOREGROUND, ""));
            editor.putString(FOREGROUND, packageName);
            for (String key : preferences.getAll().keySet()) {
                if (key.startsWith(PREFIX) && !key.equals(PREFIX + packageName)) {
                    editor.remove(key);
                    changed = true;
                }
            }
            if (changed && !editor.commit()) throw new IllegalStateException("Could not end block continuation");
        }
    }

    /**
     * 在后台保存本次确认；原触发时间只用于审计，不作为当前访问的忽略边界。
     * @param pending 已核对会话身份的最新禁用页面
     * @param confirmed 用户实际确认的冻结规则，旧回调不得忽略后来换入的规则
     * @param confirmedAtMs 用户提交继续时的墙钟时间，不推迟到异步落盘完成
     * @return 是否保存成功；已切走、规则变化或确认之后的新展示均不创建忽略
     */
    public boolean ignore(PendingInterception pending, InterceptionDecision confirmed, long confirmedAtMs) {
        if (confirmed.level == ControlLevel.SEAL || confirmed.kind != InterceptionDecision.Kind.BLOCK) {
            throw new IllegalArgumentException("Only interactive blocks can be continued");
        }
        BlockContinuation continuation = new BlockContinuation(confirmed.packageName, confirmed.policyId,
                confirmed.policyRevision, confirmed.level, confirmed.trigger, confirmed.windowId, pending.bootId, confirmedAtMs);
        if (!continuation.matches(pending.decision) || pending.startedAtMs > confirmedAtMs) return false;
        synchronized (LOCK) {
            String foreground = preferences.getString(FOREGROUND, confirmed.packageName);
            if (!foreground.equals(confirmed.packageName)) return false;
            List<BlockContinuation> values = read(confirmed.packageName, pending.bootId);
            values.removeIf(value -> value.matches(confirmed));
            values.add(continuation);
            write(confirmed.packageName, values);
            return true;
        }
    }

    /**
     * 按各条确认边界复查访问，证据读取在偏好锁外进行，旧快照不清除并发写入的新确认。
     * @param packageName 当前目标应用
     * @param bootId 本次系统启动身份
     * @param reopenedSince 查询指定确认时间之后是否出现真实新访问的证据策略
     * @return 清理过期记录后重新读取的完整快照
     */
    public List<BlockContinuation> readForVisit(String packageName, String bootId, LongPredicate reopenedSince) {
        List<BlockContinuation> previous = read(packageName, bootId);
        List<BlockContinuation> expired = new ArrayList<>();
        for (BlockContinuation value : previous) {
            if (reopenedSince.test(value.startedAtMs)) expired.add(value);
        }
        if (!expired.isEmpty()) clearVisit(packageName, bootId, expired);
        return read(packageName, bootId);
    }

    /** @param packageName 当前应用 @param bootId 本次系统启动 @return 当前格式的忽略快照，损坏或跨启动记录不生效 */
    public List<BlockContinuation> read(String packageName, String bootId) {
        synchronized (LOCK) {
            List<BlockContinuation> result = new ArrayList<>();
            try {
                JSONArray rows = new JSONArray(preferences.getString(PREFIX + packageName, "[]"));
                for (int index = 0; index < rows.length(); index++) {
                    JSONObject row = rows.getJSONObject(index);
                    BlockContinuation value = new BlockContinuation(packageName, row.getString("policy"),
                            row.getLong("revision"), ControlLevel.valueOf(row.getString("level")),
                            InterceptionDecision.Trigger.valueOf(row.getString("trigger")),
                            row.getString("window"), row.getString("boot"), row.getLong("started"));
                    if (bootId.equals(value.bootId)) result.add(value);
                }
            } catch (JSONException | IllegalArgumentException | ClassCastException invalid) {
                result.clear();
            }
            return result;
        }
    }

    /** @param packageName 已经重新打开的应用，后台清除全部原访问的忽略 */
    public void clear(String packageName) {
        synchronized (LOCK) {
            if (!preferences.edit().remove(PREFIX + packageName).commit()) {
                throw new IllegalStateException("Could not clear block continuation");
            }
        }
    }

    /** @param packageName 被重新打开的应用 @param bootId 当前启动 @param previous 已复查过期的原访问快照 */
    public void clearVisit(String packageName, String bootId, List<BlockContinuation> previous) {
        synchronized (LOCK) {
            List<BlockContinuation> current = read(packageName, bootId);
            if (current.removeIf(value -> previous.stream().anyMatch(value::sameOccurrence))) {
                write(packageName, current);
            }
        }
    }

    /** @param packageName 当前目标 @param values 原子提交的完整忽略列表，空列表清除记录 */
    private void write(String packageName, List<BlockContinuation> values) {
        JSONArray rows = new JSONArray();
        try {
            for (BlockContinuation value : values) {
                rows.put(new JSONObject().put("policy", value.policyId).put("revision", value.policyRevision)
                        .put("level", value.level.name()).put("trigger", value.trigger.name()).put("window", value.windowId)
                        .put("boot", value.bootId).put("started", value.startedAtMs));
            }
        } catch (JSONException invalid) {
            throw new IllegalArgumentException("Invalid block continuation", invalid);
        }
        SharedPreferences.Editor editor = preferences.edit();
        if (values.isEmpty()) editor.remove(PREFIX + packageName);
        else editor.putString(PREFIX + packageName, rows.toString());
        if (!editor.commit()) throw new IllegalStateException("Could not save block continuation");
    }
}
