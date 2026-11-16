package github.vhoom.zhijian.data.store.interception;

import android.content.Context;
import android.content.SharedPreferences;

import github.vhoom.zhijian.data.model.policy.ControlLevel;
import github.vhoom.zhijian.data.model.policy.RuleGroup;
import github.vhoom.zhijian.data.model.runtime.PendingInterception;
import github.vhoom.zhijian.data.store.PolicySnapshot;
import github.vhoom.zhijian.domain.interception.InterceptionDecision;
import github.vhoom.zhijian.domain.interception.ReminderSchedule;
import github.vhoom.zhijian.domain.interception.RuleWindowSchedule;
import github.vhoom.zhijian.domain.interception.WeekdayRuleSchedule;
import github.vhoom.zhijian.data.model.policy.RuleWindow;

import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 每个目标应用只保存一个未完成会话；后台同步落盘后才允许启动拦截页面。
 *
 * 维护日期：2026年11月16日
 */
public final class PendingInterceptionStore {
    private static final String REST_PREFIX = "reminder-rest:";
    private static final String BLOCK_PREFIX = "restriction-block:";
    private static final Object LOCK = new Object();
    private final SharedPreferences preferences;
    private final Context context;

    /**
     * 使用应用私有存储，避免依赖 Activity 生命周期。
     *
     * @param context 访问应用存储的上下文
     */
    public PendingInterceptionStore(Context context) {
        this.context = context.getApplicationContext();
        preferences = context.getSharedPreferences("pending_interceptions", Context.MODE_PRIVATE);
    }

    /**
     * 还原完整决定；损坏记录不传入页面。
     *
     * @param packageName 目标应用包名
     */
    public PendingInterception read(String packageName) {
        synchronized (LOCK) {
            String value = preferences.getString(packageName, null);
            if (value == null) return null;
            try {
                JSONObject row = new JSONObject(value);
                InterceptionDecision decision = new InterceptionDecision(row.getString("package"),
                        row.getString("policy"), row.getLong("revision"),
                        InterceptionDecision.Kind.valueOf(row.getString("kind")),
                        InterceptionDecision.Trigger.valueOf(row.getString("trigger")),
                        ControlLevel.valueOf(row.getString("level")), row.getString("window"),
                        row.getLong("current"), row.getLong("limit"), row.getLong("reset"),
                        row.getLong("pass"), row.getLong("pause"));
                return new PendingInterception(decision, row.getString("session"),
                        row.getString("boot"), row.getLong("wall"), row.getLong("elapsed"),
                        row.getLong("generation"), row.getLong("occurred"),
                        row.getString("cycleKey"), row.getLong("cycleFloor"),
                        row.optLong("acknowledgedUsage", -1L), row.getLong("cycleCeiling"));
            } catch (JSONException | IllegalArgumentException invalid) {
                preferences.edit().remove(packageName).apply();
                return null;
            }
        }
    }

    /**
     * 在后台持久化开始事实；保存失败时禁止把仅存在于内存的会话当作已保存。
     *
     * @param pending 待保存的完整会话
     */
    public void save(PendingInterception pending) {
        synchronized (LOCK) {
            saveAt(pending.decision.packageName, pending);
            if (pending.decision.kind == InterceptionDecision.Kind.BLOCK) {
                saveBlock(pending);
            }
        }
    }

    /** @param pending 已触发禁用；独立保存不会新建或覆盖展示页面 */
    public void saveBlock(PendingInterception pending) {
        if (pending.decision.kind != InterceptionDecision.Kind.BLOCK) {
            throw new IllegalArgumentException("Only blocks create ban restrictions");
        }
        saveAt(blockKey(pending), pending);
    }

    /** @param pending 已触发的休息事实，独立保存防止禁用或临时放行覆盖 */
    public void saveRest(PendingInterception pending) {
        if (pending.decision.kind != InterceptionDecision.Kind.INTERRUPT) {
            throw new IllegalArgumentException("Only reminders create rest obligations");
        }
        saveAt(restKey(pending), pending);
    }

    /**
     * 保存无为或修心直接继续后的用量起点并清当前展示；调用方先校验冻结的放行开关。
     * @param presentation 用户实际操作的页面身份，过期页面不得更新新的提醒
     * @param usedMs 该连续周期已经确认的真实用量
     */
    public void acknowledgeReminder(PendingInterception presentation, long usedMs) {
        if (usedMs < 0L || presentation.decision.kind != InterceptionDecision.Kind.INTERRUPT
                || presentation.decision.level == ControlLevel.SEAL) {
            throw new IllegalArgumentException("Only interactive reminders can be acknowledged");
        }
        synchronized (LOCK) {
            PendingInterception current = read(presentation.decision.packageName);
            if (current == null || !current.sessionId.equals(presentation.sessionId)) return;
            SharedPreferences.Editor editor = preferences.edit();
            String key = restKey(current);
            PendingInterception rest = read(key);
            if (rest != null && rest.occurredAtMs == current.occurredAtMs) {
                try {
                    JSONObject row = new JSONObject(preferences.getString(key, "{}"));
                    row.put("acknowledgedUsage", usedMs);
                    editor.putString(key, row.toString());
                } catch (JSONException invalid) {
                    throw new IllegalStateException("Could not acknowledge reminder", invalid);
                }
            }
            for (String candidate : preferences.getAll().keySet()) {
                if (candidate.startsWith(REST_PREFIX) || candidate.startsWith(BLOCK_PREFIX)) continue;
                PendingInterception shown = read(candidate);
                if (shown != null && shown.sessionId.equals(current.sessionId)) editor.remove(candidate);
            }
            if (!editor.commit()) throw new IllegalStateException("Could not save reminder acknowledgement");
        }
    }

    /** @param pending 提醒或其展示 @return 同策略、触发类型及窗口共用的休息键 */
    private static String restKey(PendingInterception pending) {
        return REST_PREFIX + pending.decision.policyId + ":" + pending.decision.trigger.code
                + ":" + pending.decision.windowId;
    }

    /** @param pending 原提醒或其展示 @return 该规则最新的休息事实，已解除时为空 */
    public PendingInterception restFor(PendingInterception pending) { return read(restKey(pending)); }

    /** @param key 事实或展示记录键 @param pending 完整待处理事实 */
    private void saveAt(String key, PendingInterception pending) {
        synchronized (LOCK) {
            try {
                InterceptionDecision d = pending.decision;
                int variant = copyVariantForSession(pending.sessionId);
                if (variant < 0) variant = key.startsWith(REST_PREFIX) || key.startsWith(BLOCK_PREFIX)
                        ? 0 : InterceptionCopyStore.next(context);
                JSONObject row = new JSONObject().put("package", d.packageName).put("policy", d.policyId)
                        .put("revision", d.policyRevision).put("kind", d.kind.name())
                        .put("trigger", d.trigger.name()).put("level", d.level.name())
                        .put("window", d.windowId).put("current", d.currentValue)
                        .put("limit", d.limitValue).put("reset", d.resetAtMs)
                        .put("pass", d.passDurationMs).put("pause", d.minimumInterruptionPauseDurationMs)
                        .put("session", pending.sessionId).put("boot", pending.bootId)
                        .put("wall", pending.startedAtMs).put("elapsed", pending.startedElapsedMs)
                        .put("generation", pending.cycleGeneration).put("occurred", pending.occurredAtMs)
                        .put("cycleKey", pending.continuousKey).put("cycleFloor", pending.continuousFloorMs)
                        .put("acknowledgedUsage", pending.acknowledgedUsageMs)
                        .put("cycleCeiling", pending.continuousCeilingMs)
                        .put("copyVariant", variant);
                if (!preferences.edit().putString(key, row.toString()).commit()) {
                    throw new IllegalStateException("Could not save pending interception");
                }
            } catch (JSONException invalid) {
                throw new IllegalArgumentException("Invalid pending interception", invalid);
            }
        }
    }

    /**
     * 只清理指定旧会话，避免完成回调删除更新的拦截。
     *
     * @param packageName 目标应用包名
     * @param sessionId 已完成或失效的会话身份
     */
    public void clear(String packageName, String sessionId) {
        clearSession(sessionId, true);
    }

    /** @param sessionId 已被临时放行或新禁用替换的页面身份，保留未完成休息 */
    public void clearPresentation(String sessionId) { clearSession(sessionId, false); }

    /**
     * 安装身份变化时同步撤销恢复的展示、休息和禁用事实，不通过启动标识或时间猜测来源。
     * 仅由后台初始化在新安装身份提交前调用；失败必须阻止发布新状态，重复调用可安全重试。
     *
     * @throws IllegalStateException 清理未同步落盘，调用方不得提交新安装身份
     */
    public void clearForNewInstallation() {
        synchronized (LOCK) {
            if (!preferences.edit().clear().commit()) {
                throw new IllegalStateException("Could not clear restored pending interceptions");
            }
        }
    }

    /**
     * 按整份已提交配置清理已关闭或删除的提醒，包含已失去实际目标的旧组。
     * @param policies 已提交配置，不从当前目标的有效策略推断原规则是否存在
     * @param zoneId 解析原触发星期使用的设备时区
     */
    public void clearCancelledReminders(PolicySnapshot policies, String zoneId) {
        synchronized (LOCK) {
            Map<String, RuleGroup> configured = policies.configuredPolicies();
            SharedPreferences.Editor editor = preferences.edit();
            boolean changed = false;
            for (String key : preferences.getAll().keySet()) {
                if (key.startsWith(BLOCK_PREFIX)) continue;
                PendingInterception value = read(key);
                if (value == null || value.decision.kind != InterceptionDecision.Kind.INTERRUPT) continue;
                RuleGroup source = configured.get(value.decision.policyId);
                if (source == null || !ReminderSchedule.stillConfigured(
                        source, value.decision, value.occurredAtMs, zoneId)) {
                    editor.remove(key);
                    changed = true;
                }
            }
            if (changed && !editor.commit()) throw new IllegalStateException("Could not clear cancelled reminders");
        }
    }

    /**
     * 时段发生身份改变后，旧提醒仅保留休息，使用量最迟统计到配置生效时刻。
     * @param policies 新提交快照 @param wallMs 配置生效墙钟 @param zoneId 设备时区
     */
    public void closeChangedWindowUsage(PolicySnapshot policies, long wallMs, String zoneId) {
        synchronized (LOCK) {
            Map<String, RuleGroup> configured = policies.configuredPolicies();
            for (String key : new ArrayList<>(preferences.getAll().keySet())) {
                PendingInterception value = read(key);
                if (value == null || value.decision.trigger != InterceptionDecision.Trigger.WINDOW_CONTINUOUS) continue;
                RuleGroup source = configured.get(value.decision.policyId);
                if (source == null) continue;
                RuleWindow window = ReminderSchedule.window(WeekdayRuleSchedule.resolve(source,
                        value.occurredAtMs, zoneId), value.decision.windowId);
                RuleWindowSchedule.Interval interval = RuleWindowSchedule.activeInterval(window, value.occurredAtMs, zoneId);
                if (interval == null || !value.continuousKey.equals(ReminderSchedule.cycleKey(
                        value.decision.policyId, window, value.occurredAtMs, zoneId))) {
                    saveAt(key, value.closeUsageAt(wallMs));
                }
            }
        }
    }

    /** @param sessionId 原会话身份 @param includeRest 是否同时清理已完成或被删除的休息 */
    private void clearSession(String sessionId, boolean includeRest) {
        synchronized (LOCK) {
            SharedPreferences.Editor editor = preferences.edit();
            for (String key : preferences.getAll().keySet()) {
                if (key.startsWith(BLOCK_PREFIX)) continue;
                if (!includeRest && key.startsWith(REST_PREFIX)) continue;
                PendingInterception current = read(key);
                if (current != null && current.sessionId.equals(sessionId)) editor.remove(key);
            }
            // 同组可能从另一个应用恢复同一会话，仅清除此身份，不影响更新的会话。
            if (!editor.commit()) throw new IllegalStateException("Could not clear pending interception");
        }
    }

    /** @return 全部独立禁用和休息事实；展示记录不参与约束判定 */
    public List<PendingInterception> restrictions() {
        synchronized (LOCK) {
            List<PendingInterception> result = new ArrayList<>();
            for (String key : preferences.getAll().keySet()) {
                if (!key.startsWith(REST_PREFIX) && !key.startsWith(BLOCK_PREFIX)) continue;
                PendingInterception value = read(key);
                if (value != null) result.add(value);
            }
            return result;
        }
    }

    /** @param pending 已经复查解除的旧禁用；只清同一身份，避免删除并发新触发 */
    public void clearBlock(PendingInterception pending) {
        synchronized (LOCK) {
            String key = blockKey(pending);
            PendingInterception current = read(key);
            if (current != null && current.sessionId.equals(pending.sessionId)
                    && !preferences.edit().remove(key).commit()) {
                throw new IllegalStateException("Could not clear restriction");
            }
        }
    }

    /** @param pending 原禁用事实 @return 按策略和目标分开的键，改归属后的新限制不覆盖原组约束 */
    private static String blockKey(PendingInterception pending) {
        return BLOCK_PREFIX + pending.decision.policyId + ":" + pending.decision.packageName;
    }

    /** @param policyId 当前策略 @return 全部独立休息事实；展示记录不参与重复计数 */
    public List<PendingInterception> rests(String policyId) {
        synchronized (LOCK) {
            List<PendingInterception> result = new ArrayList<>();
            for (String key : preferences.getAll().keySet()) {
                if (!key.startsWith(REST_PREFIX)) continue;
                PendingInterception pending = read(key);
                if (pending != null && policyId.equals(pending.decision.policyId)) result.add(pending);
            }
            return result;
        }
    }

    /** @param policyId 同组策略身份 @return 此组尚未结束的中断，普通限制仍按应用处理 */
    public PendingInterception sharedInterruption(String policyId) {
        synchronized (LOCK) {
            PendingInterception latest = null;
            for (String key : preferences.getAll().keySet()) {
                if (key.startsWith(REST_PREFIX)) continue;
                PendingInterception value = read(key);
                if (value != null && value.decision.kind == InterceptionDecision.Kind.INTERRUPT
                        && policyId.equals(value.decision.policyId)
                        && (latest == null || value.startedAtMs > latest.startedAtMs)) latest = value;
            }
            return latest;
        }
    }

    /** @return 尚未清理的展示、禁用和休息会话身份，用于保留同一轮拦截的主句 */
    public Set<String> sessionIds() {
        synchronized (LOCK) {
            Set<String> result = new HashSet<>();
            for (String key : preferences.getAll().keySet()) {
                PendingInterception pending = read(key);
                if (pending != null) result.add(pending.sessionId);
            }
            return result;
        }
    }

    /** @param sessionId 稳定会话身份 @return 首次触发时分配的文案序号，未保存时为 -1 */
    public int copyVariantForSession(String sessionId) {
        synchronized (LOCK) {
            for (String key : preferences.getAll().keySet()) {
                try {
                    JSONObject row = new JSONObject(preferences.getString(key, "{}"));
                    int variant = row.optInt("copyVariant", -1);
                    if (sessionId.equals(row.optString("session")) && variant >= 0) return variant;
                } catch (JSONException ignored) { /* 损坏条目由正式读取入口清理。 */ }
            }
            return -1;
        }
    }
}
