package github.vhoom.zhijian.domain.interception;

import github.vhoom.zhijian.data.model.policy.ControlLevel;
import github.vhoom.zhijian.data.model.policy.RuleDayParameterAdapter;
import github.vhoom.zhijian.data.model.policy.RuleDayParameters;
import github.vhoom.zhijian.data.model.policy.RuleGroup;
import github.vhoom.zhijian.data.model.policy.RuleType;
import github.vhoom.zhijian.data.model.policy.RuleWindow;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

/**
 * 时段提醒在午夜前后及修改配置后使用原开始星期的放行参数。
 * 维护日期：2026年11月08日
 */
public class ReminderPassDurationTest {
    private static final String ZONE = "Asia/Shanghai";

    /** 星期一窗口在星期二触发时，展示不能覆盖引擎选出的七分钟放行。 */
    @Test public void overnightReminderKeepsStartingWeekdayPass() {
        RuleGroup source = overnight(420_000L);
        long now = at("2026-11-03T02:00");
        RuleGroup policy = WeekdayRuleSchedule.resolve(source, now, ZONE);
        UsageEvidence usage = new UsageEvidence(true, now, ZONE, 0L, 0L, 0L, 0L, Collections.emptyMap())
                .withWindowContinuousMillis(Collections.singletonMap("weekday:1:night", 60_000L));
        InterceptionDecision decision = RuleEngine.evaluate("app.a", "group:1", 1L, policy,
                ControlLevel.REPAIR, usage, 180_000L).decision;
        assertEquals(420_000L, decision.passDurationMs);
        assertEquals(420_000L, ReminderSchedule.passDurationMillis(policy, decision, 180_000L));
    }

    /** 窗口结束后的未完成休息读取原星期最新参数，而不是新星期的缺省值。 */
    @Test public void pendingReminderUsesLatestStartingDayParametersOutsideWindow() {
        RuleGroup policy = WeekdayRuleSchedule.resolve(overnight(600_000L), at("2026-11-03T06:00"), ZONE);
        assertEquals(600_000L, ReminderSchedule.passDurationMillis(policy, reminder(), 180_000L));
    }

    /** @param passMs 星期一放行时长 @return 星期一晚十点至次日四点的提醒配置 */
    private static RuleGroup overnight(long passMs) {
        RuleGroup group = new RuleGroup(1L, "夜间");
        group.passDurationMillis = 180_000L;
        RuleDayParameters baseline = RuleDayParameterAdapter.capture(group);
        RuleDayParameters monday = baseline.copy();
        monday.types.add(RuleType.WINDOW_CONTINUOUS);
        monday.passDurationMillis = passMs;
        RuleWindow window = new RuleWindow("night", RuleType.WINDOW_CONTINUOUS, 1320, 240, 60_000L);
        window.interruptionPauseMillis = 300_000L;
        monday.windows.add(window);
        RuleDayParameterAdapter.applyToDays(group, 1, monday, baseline);
        return group;
    }

    /** @return 原星期一时段的未完成提醒身份 */
    private static InterceptionDecision reminder() {
        return new InterceptionDecision("app.a", "group:1", 1L, InterceptionDecision.Kind.INTERRUPT,
                InterceptionDecision.Trigger.WINDOW_CONTINUOUS, ControlLevel.REPAIR,
                "weekday:1:night", 60_000L, 60_000L, 0L, 420_000L, 300_000L);
    }

    /** @param local 上海本地日期时间 @return 对应墙钟毫秒 */
    private static long at(String local) {
        return LocalDateTime.parse(local).atZone(ZoneId.of(ZONE)).toInstant().toEpochMilli();
    }
}
