package github.vhoom.zhijian.domain.interception;

import github.vhoom.zhijian.data.model.policy.RuleDayParameterAdapter;
import github.vhoom.zhijian.data.model.policy.RuleDayParameters;
import github.vhoom.zhijian.data.model.policy.RuleGroup;
import github.vhoom.zhijian.data.model.policy.RuleWindow;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;

/**
 * 按设备本地星期解析规则；跨午夜窗口保留开始星期及该日放行参数。
 *
 * 维护日期：2026年11月12日
 */
public final class WeekdayRuleSchedule {
    /** 调度策略不持有时钟、页面或存储实例。 */
    private WeekdayRuleSchedule() { }

    /**
     * 获取当前全天、连续规则及带开始星期约束的全部时段。
     * @param source 已提交的完整星期配置
     * @param nowMs 当前墙钟时间戳，单位为毫秒
     * @param zoneId 当前设备时区标识
     * @return 无星期子配置的本次运行策略，修改不影响存储快照
     */
    public static RuleGroup resolve(RuleGroup source, long nowMs, String zoneId) {
        if (source.weekdayRules.isEmpty()) return source.copy();
        int today = Instant.ofEpochMilli(nowMs).atZone(ZoneId.of(zoneId)).getDayOfWeek().getValue();
        RuleGroup resolved = RuleDayParameterAdapter.withParameters(source,
                RuleDayParameterAdapter.forDay(source, today));
        resolved.windows.clear();
        for (Map.Entry<Integer, RuleDayParameters> day : source.weekdayRules.entrySet()) {
            for (RuleWindow window : day.getValue().windows) {
                if (!day.getValue().types.contains(window.type)) continue;
                RuleWindow scheduled = new RuleWindow("weekday:" + day.getKey() + ":" + window.id,
                        window.type, window.startMinute, window.endMinute, window.limitDurationMillis);
                scheduled.weekday = day.getKey();
                scheduled.limitCount = window.limitCount;
                scheduled.interruptionPauseMillis = window.interruptionPauseMillis;
                scheduled.scheduledPassDurationMillis = day.getValue().passDurationMillis;
                resolved.windows.add(scheduled);
                resolved.types.add(window.type);
            }
        }
        return resolved;
    }

    /**
     * 星期切换后重新判断旧待处理会话；跨午夜窗口按其原开始日继续生效。
     * @param policy 本次运行的单日策略
     * @param decision 原待处理会话中的决定
     * @param startedMs 原会话建立的墙钟时间戳
     * @param nowMs 当前墙钟时间戳
     * @param zoneId 设备本地时区标识
     * @return 原决定是否仍属于当前规则日或当前活动窗口
     */
    public static boolean stillApplies(RuleGroup policy, InterceptionDecision decision,
            long startedMs, long nowMs, String zoneId) {
        if (decision.kind == InterceptionDecision.Kind.INTERRUPT) return true;
        if (decision.trigger == InterceptionDecision.Trigger.WINDOW_BAN
                || decision.trigger == InterceptionDecision.Trigger.WINDOW_LIMIT
                || decision.trigger == InterceptionDecision.Trigger.WINDOW_COUNT) {
            for (RuleWindow window : policy.windows) {
                if (window.id.equals(decision.windowId)
                        && RuleWindowSchedule.activeInterval(window, nowMs, zoneId) != null) return true;
            }
            return false;
        }
        if (decision.trigger == InterceptionDecision.Trigger.ROLLING_COUNT) {
            return policy.has(github.vhoom.zhijian.data.model.policy.RuleType.ROLLING_COUNT);
        }
        ZoneId zone = ZoneId.of(zoneId);
        return Instant.ofEpochMilli(startedMs).atZone(zone).toLocalDate()
                .equals(Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate());
    }
}
