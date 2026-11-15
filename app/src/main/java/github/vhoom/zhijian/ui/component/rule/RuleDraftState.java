package github.vhoom.zhijian.ui.component.rule;

import android.os.Bundle;

import github.vhoom.zhijian.data.model.policy.RuleGroup;
import github.vhoom.zhijian.data.model.policy.RuleType;
import github.vhoom.zhijian.data.model.policy.RuleWindow;
import github.vhoom.zhijian.data.model.policy.RuleDayParameterAdapter;

import java.util.ArrayList;

/**
 * 两种编辑页共用的草稿状态；旋转、主题切换时保留每个时段的稳定 id 和未保存内容。
 *
 * 维护日期：2026年11月12日
 */
public final class RuleDraftState {
    /**
     * 工具类不允许实例化，调用方直接使用静态方法。
     */
    private RuleDraftState() { }

    /**
     * 把完整规则草稿写入页面状态，保留每个时段的身份和额度。
     *
     * @param destination 由调用方提供的输出文档或字符流
     * @param key 稳定标识或待读取的持久化设置键
     * @param group 待读取、校验或编辑的规则组
     */
    public static void write(Bundle destination, String key, RuleGroup group) {
        Bundle draft = new Bundle();
        draft.putLong("id", group.id);
        draft.putString("name", group.name);
        draft.putBoolean("enabled", group.enabled);
        draft.putLong("daily_limit_millis", group.dailyLimitMillis);
        draft.putInt("count", group.count);
        draft.putLong("frequency_window_millis", group.frequencyWindowMillis);
        draft.putInt("frequency_count", group.frequencyCount);
        draft.putLong("continuous_limit_millis", group.continuousLimitMillis);
        draft.putLong("interruption_pause_millis", group.interruptionPauseMillis);
        draft.putLong("pass_duration_millis", group.passDurationMillis);
        draft.putStringArrayList("apps", new ArrayList<>(group.apps));
        ArrayList<String> types = new ArrayList<>();
        for (RuleType type : group.types) types.add(type.name());
        draft.putStringArrayList("types", types);
        ArrayList<Bundle> windows = new ArrayList<>();
        for (RuleWindow window : group.windows) {
            Bundle row = new Bundle();
            row.putString("id", window.id);
            row.putString("type", window.type.name());
            row.putInt("start", window.startMinute);
            row.putInt("end", window.endMinute);
            row.putLong("limit_duration_millis", window.limitDurationMillis);
            row.putInt("limit_count", window.limitCount);
            row.putLong("pause_duration_millis", window.interruptionPauseMillis);
            windows.add(row);
        }
        draft.putParcelableArrayList("windows", windows);
        ArrayList<Bundle> weekdays = new ArrayList<>();
        for (Integer weekday : group.weekdayRules.keySet()) {
            Bundle row = new Bundle();
            row.putInt("weekday", weekday);
            write(row, "parameters", RuleDayParameterAdapter.withParameters(group,
                    group.weekdayRules.get(weekday)));
            weekdays.add(row);
        }
        draft.putParcelableArrayList("weekdays", weekdays);
        destination.putBundle(key, draft);
    }

    /**
     * 从页面保存状态恢复规则草稿，避免旋转丢失输入。
     *
     * @param source 需要复制的原始草稿、记录或数据来源
     * @param key 稳定标识或待读取的持久化设置键
     */
    public static RuleGroup read(Bundle source, String key) {
        if (source == null || source.getBundle(key) == null) return null;
        Bundle draft = source.getBundle(key);
        RuleGroup group = new RuleGroup(draft.getLong("id"), draft.getString("name", ""));
        group.enabled = draft.getBoolean("enabled", true);
        group.dailyLimitMillis = draft.getLong("daily_limit_millis");
        group.count = draft.getInt("count");
        group.frequencyWindowMillis = draft.getLong("frequency_window_millis");
        group.frequencyCount = draft.getInt("frequency_count");
        group.continuousLimitMillis = draft.getLong("continuous_limit_millis");
        group.interruptionPauseMillis = draft.getLong("interruption_pause_millis",
                RuleGroup.DEFAULT_INTERRUPTION_PAUSE_MILLIS);
        group.passDurationMillis = draft.getLong("pass_duration_millis");
        ArrayList<String> apps = draft.getStringArrayList("apps");
        if (apps != null) group.apps.addAll(apps);
        ArrayList<String> types = draft.getStringArrayList("types");
        if (types != null) for (String type : types) group.types.add(RuleType.valueOf(type));
        ArrayList<Bundle> windows = draft.getParcelableArrayList("windows");
        if (windows != null) for (Bundle row : windows) {
            RuleWindow restored = new RuleWindow(row.getString("id"), RuleType.valueOf(row.getString("type")),
                    row.getInt("start"), row.getInt("end"),
                    row.getLong("limit_duration_millis"));
            restored.interruptionPauseMillis = row.getLong("pause_duration_millis", RuleGroup.DEFAULT_INTERRUPTION_PAUSE_MILLIS);
            restored.limitCount = row.getInt("limit_count");
            group.windows.add(restored);
        }
        ArrayList<Bundle> weekdays = draft.getParcelableArrayList("weekdays");
        if (weekdays != null) for (Bundle row : weekdays) {
            RuleGroup parameters = read(row, "parameters");
            if (parameters != null) group.weekdayRules.put(row.getInt("weekday"),
                    RuleDayParameterAdapter.capture(parameters));
        }
        return group;
    }
}
