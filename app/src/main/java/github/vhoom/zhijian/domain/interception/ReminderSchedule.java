package github.vhoom.zhijian.domain.interception;

import github.vhoom.zhijian.data.model.policy.RuleDayParameterAdapter;
import github.vhoom.zhijian.data.model.policy.RuleGroup;
import github.vhoom.zhijian.data.model.policy.RuleType;
import github.vhoom.zhijian.data.model.policy.RuleWindow;
import java.time.Instant;
import java.time.ZoneId;

/**
 * 提醒范围策略：时段参数接管触发，已触发的休息不随时段结束取消。
 *
 * 维护日期：2026年11月16日
 */
public final class ReminderSchedule {
    /** 提醒策略不持有系统时钟或存储。 */
    private ReminderSchedule() { }

    /** @param policy 已解析的星期规则 @param nowMs 当前墙钟 @param zoneId 设备时区 @return 优先提醒时段 */
    public static RuleWindow activeWindow(RuleGroup policy, long nowMs, String zoneId) {
        if (!policy.has(RuleType.WINDOW_CONTINUOUS)) return null;
        RuleWindow selected = null;
        for (RuleWindow window : policy.windowsFor(RuleType.WINDOW_CONTINUOUS)) {
            if (RuleWindowSchedule.activeInterval(window, nowMs, zoneId) != null
                    && (selected == null || window.limitDurationMillis < selected.limitDurationMillis)) selected = window;
        }
        return selected;
    }

    /** @param policy 已解析的窗口集合 @param id 原窗口运行标识 @return 对应提醒窗口，已删除时为空 */
    public static RuleWindow window(RuleGroup policy, String id) {
        if (!policy.has(RuleType.WINDOW_CONTINUOUS)) return null;
        for (RuleWindow window : policy.windowsFor(RuleType.WINDOW_CONTINUOUS)) {
            if (window.id.equals(id)) return window;
        }
        return null;
    }

    /**
     * 时段提醒沿用开始星期的最新放行配置，全天提醒使用当前星期参数。
     * @param policy 已解析的当前星期及全部时段配置
     * @param decision 原提醒身份
     * @param fallbackMs 当前星期的有效放行时长
     * @return 对应提醒的放行时长
     */
    public static long passDurationMillis(RuleGroup policy, InterceptionDecision decision, long fallbackMs) {
        RuleWindow window = decision.trigger == InterceptionDecision.Trigger.WINDOW_CONTINUOUS
                ? window(policy, decision.windowId) : null;
        return window != null && window.scheduledPassDurationMillis > 0L
                ? window.scheduledPassDurationMillis : fallbackMs;
    }

    /** @param source 最新完整配置 @return 任意星期是否配置全天提醒 */
    public static boolean hasAllDay(RuleGroup source) {
        for (int day = 1; day <= 7; day++) {
            if (RuleDayParameterAdapter.forDay(source, day).types.contains(RuleType.CONTINUOUS)) return true;
        }
        return false;
    }

    /** @param source 最新完整配置 @param decision 原提醒 @param occurredMs 原触发时刻 @param zoneId 时区
     * @return 规则仍被配置；只关闭或删除原规则才取消原休息 */
    public static boolean stillConfigured(RuleGroup source, InterceptionDecision decision, long occurredMs, String zoneId) {
        if (!source.enabled) return false;
        if (decision.trigger == InterceptionDecision.Trigger.WINDOW_CONTINUOUS) {
            return window(WeekdayRuleSchedule.resolve(source, occurredMs, zoneId), decision.windowId) != null;
        }
        int day = Instant.ofEpochMilli(occurredMs).atZone(ZoneId.of(zoneId)).getDayOfWeek().getValue();
        return RuleDayParameterAdapter.forDay(source, day).types.contains(RuleType.CONTINUOUS);
    }

    /** @param policyId 策略身份 @param window 提醒窗口 @param occurredMs 本次窗口内的时刻 @param zoneId 时区
     * @return 每次实际窗口发生独立的计时身份，跨午夜保持同一个身份 */
    public static String cycleKey(String policyId, RuleWindow window, long occurredMs, String zoneId) {
        RuleWindowSchedule.Interval interval = RuleWindowSchedule.activeInterval(window, occurredMs, zoneId);
        if (interval == null) throw new IllegalArgumentException("Reminder must start in its window");
        return policyId + ":window:" + window.id + ":" + interval.startMs + ":" + interval.endMs;
    }
}
