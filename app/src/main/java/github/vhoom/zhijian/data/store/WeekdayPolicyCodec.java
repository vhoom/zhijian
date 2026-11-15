package github.vhoom.zhijian.data.store;

import github.vhoom.zhijian.data.model.policy.CountLimit;
import github.vhoom.zhijian.data.model.policy.RuleDayParameterAdapter;
import github.vhoom.zhijian.data.model.policy.RuleDayParameters;
import github.vhoom.zhijian.data.model.policy.RuleGroup;
import github.vhoom.zhijian.data.model.policy.RuleGroupValidator;
import github.vhoom.zhijian.data.model.policy.RuleType;
import github.vhoom.zhijian.data.model.policy.RuleWindow;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 七天完整配置的版本化编码；规则键与毫秒精度复用现有持久化约定。
 *
 * 维护日期：2026年11月13日
 */
final class WeekdayPolicyCodec {
    /** 不创建持有数据的编码器实例。 */
    private WeekdayPolicyCodec() { }

    /** @param days 七天独立参数；全局配置和首次编辑草稿为空映射 @return 可事务保存的版本化文本 */
    static String encode(Map<Integer, RuleDayParameters> days) {
        if (days.isEmpty()) return "";
        if (days.size() != 7) throw new IllegalArgumentException("Incomplete weekday policy");
        try {
            JSONObject entries = new JSONObject();
            for (int day = 1; day <= 7; day++) entries.put(Integer.toString(day), writeDay(days.get(day)));
            return new JSONObject().put("version", 3).put("days", entries).toString();
        } catch (JSONException malformed) {
            throw new IllegalArgumentException("Cannot encode weekday policy", malformed);
        }
    }

    /** @param text 数据库保存的版本化文本 @return 独立参数映射；损坏配置抛错并保留原数据 */
    static Map<Integer, RuleDayParameters> decode(String text) {
        Map<Integer, RuleDayParameters> days = new LinkedHashMap<>();
        if (text.isEmpty()) return days;
        try {
            JSONObject root = new JSONObject(text);
            int version = root.getInt("version");
            if (version != 3) throw new IllegalArgumentException("Unknown weekday policy version");
            JSONObject entries = root.getJSONObject("days");
            if (entries.length() != 7) throw new IllegalArgumentException("Incomplete weekday policy");
            for (int day = 1; day <= 7; day++) {
                RuleDayParameters parameters = readDay(entries.getJSONObject(Integer.toString(day)));
                RuleGroup test = RuleDayParameterAdapter.withParameters(new RuleGroup(-1, ""), parameters);
                if (!RuleGroupValidator.validateStoredFields(test).isValid()) {
                    throw new IllegalArgumentException("Invalid weekday parameters: " + day);
                }
                days.put(day, parameters);
            }
            return days;
        } catch (JSONException malformed) {
            throw new IllegalArgumentException("Invalid stored weekday policy", malformed);
        }
    }

    /** @param day 当前星期完整参数 @return 使用稳定类型键的参数对象 @throws JSONException 编码对象失败 */
    private static JSONObject writeDay(RuleDayParameters day) throws JSONException {
        if (day == null) throw new IllegalArgumentException("Missing weekday parameters");
        JSONArray types = new JSONArray();
        for (RuleType type : day.types) types.put(PolicyCodec.typeKey(type));
        JSONArray windows = new JSONArray();
        for (RuleWindow window : day.windows) {
            windows.put(new JSONObject().put("id", window.id).put("type", PolicyCodec.typeKey(window.type))
                    .put("start", window.startMinute).put("end", window.endMinute)
                    .put("limit", window.limitDurationMillis).put("count", window.limitCount)
                    .put("pause", window.interruptionPauseMillis));
        }
        return new JSONObject().put("types", types).put("windows", windows)
                .put("daily", day.dailyLimitMillis).put("count", day.count)
                .put("frequencyWindow", day.frequencyWindowMillis).put("frequencyCount", day.frequencyCount)
                .put("continuous", day.continuousLimitMillis).put("pause", day.interruptionPauseMillis)
                .put("pass", day.passDurationMillis);
    }

    /** @param json 使用稳定规则键的参数对象
     * @return 不共享数组的单日参数 @throws JSONException 必需字段缺失 */
    private static RuleDayParameters readDay(JSONObject json) throws JSONException {
        RuleDayParameters day = new RuleDayParameters();
        day.dailyLimitMillis = json.getLong("daily");
        day.count = readStoredCount(json, "count");
        day.frequencyWindowMillis = json.getLong("frequencyWindow");
        day.frequencyCount = readStoredCount(json, "frequencyCount");
        day.continuousLimitMillis = json.getLong("continuous");
        day.interruptionPauseMillis = json.getLong("pause");
        day.passDurationMillis = json.getLong("pass");
        JSONArray types = json.getJSONArray("types");
        for (int index = 0; index < types.length(); index++) day.types.add(PolicyCodec.ruleType(types.getString(index)));
        JSONArray windows = json.getJSONArray("windows");
        for (int index = 0; index < windows.length(); index++) {
            JSONObject window = windows.getJSONObject(index);
            RuleWindow restored = new RuleWindow(window.getString("id"), PolicyCodec.ruleType(window.getString("type")),
                    window.getInt("start"), window.getInt("end"), window.getLong("limit"));
            restored.interruptionPauseMillis = window.getLong("pause");
            restored.limitCount = readStoredCount(window, "count");
            day.windows.add(restored);
        }
        return day;
    }

    /**
     * 严格读取整数次数，避免 JSON 的 getInt 将小数或超大整数截断为合法额度。
     *
     * @param json 包含次数的单日参数或时段对象
     * @param key 次数字段的稳定存储键
     * @return 0 至 99 的整数，零表示关闭条件或尚未填写
     * @throws JSONException 必需的次数字段缺失
     */
    private static int readStoredCount(JSONObject json, String key) throws JSONException {
        Object value = json.get(key);
        if (!(value instanceof Integer || value instanceof Long)
                || !CountLimit.isValidStored(((Number) value).longValue())) {
            throw new IllegalArgumentException("Invalid stored count: " + key);
        }
        return ((Number) value).intValue();
    }
}
