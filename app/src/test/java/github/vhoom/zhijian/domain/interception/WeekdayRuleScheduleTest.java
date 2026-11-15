package github.vhoom.zhijian.domain.interception;

import github.vhoom.zhijian.data.model.policy.ControlLevel;
import github.vhoom.zhijian.data.model.policy.RuleDayParameterAdapter;
import github.vhoom.zhijian.data.model.policy.RuleDayParameters;
import github.vhoom.zhijian.data.model.policy.RuleGroup;
import github.vhoom.zhijian.data.model.policy.RuleType;
import github.vhoom.zhijian.data.model.policy.RuleWindow;
import github.vhoom.zhijian.domain.interception.count.CountRuleEvaluator;
import github.vhoom.zhijian.domain.usage.OpenCountSnapshot;

import org.junit.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

/**
 * 覆盖逐日额度、跨午夜开始日、时区与旧待处理会话的星期边界。
 *
 * 维护日期：2026年11月13日
 */
public class WeekdayRuleScheduleTest {
    private static final String ZONE = "Asia/Shanghai";

    /** 跨午夜限次使用原开始日的整数额度和放行参数，结束毫秒立即解除。 */
    @Test
    public void overnightCountKeepsItsStartingDaysQuotaAndPassDuration() {
        RuleGroup group = base();
        RuleDayParameters initial = RuleDayParameterAdapter.capture(group);
        RuleDayParameters monday = initial.copy();
        monday.types.add(RuleType.WINDOW_COUNT);
        monday.passDurationMillis = 420_000L;
        RuleWindow window = new RuleWindow("night-count", RuleType.WINDOW_COUNT, 1320, 240, 0L);
        window.limitCount = 1;
        monday.windows.add(window);
        RuleDayParameterAdapter.applyToDays(group, 1, monday, initial);
        long nowMs = at("2026-11-03T04:00") - 250L;
        UsageEvidence evidence = new UsageEvidence(true, nowMs, ZONE, 0L, 0L, 0L, 0L, Collections.emptyMap())
                .withCountSnapshots(Collections.singletonMap("window_count:weekday:1:night-count",
                        new OpenCountSnapshot(Arrays.asList(nowMs - 1_000L, nowMs))));
        InterceptionEvaluation blocked = RuleEngine.evaluate("app.example", "group:1", 1L, group,
                ControlLevel.REPAIR, evidence, 180_000L);
        assertEquals("weekday:1:night-count", blocked.decision.windowId);
        assertEquals(1L, blocked.decision.limitValue);
        assertEquals(420_000L, blocked.decision.passDurationMs);
        assertEquals(at("2026-11-03T04:00"), blocked.decision.resetAtMs);
        assertEquals(250L, blocked.recheckAfterMs);
        RuleGroup resolved = WeekdayRuleSchedule.resolve(group, nowMs, ZONE);
        assertFalse(WeekdayRuleSchedule.stillApplies(resolved, blocked.decision,
                nowMs, nowMs + 250L, ZONE));
        assertNull(evaluate(group, nowMs + 250L, 0L, ZONE).decision);
    }

    /** 午夜后采用新星期的滚动时长和次数，窗口内昨日记录仍然消耗额度。 */
    @Test
    public void rollingCountUsesCurrentWeekdayWithoutDiscardingRecentOpenings() {
        RuleGroup group = base();
        RuleDayParameters initial = RuleDayParameterAdapter.capture(group);
        RuleDayParameters monday = initial.copy();
        monday.types.add(RuleType.ROLLING_COUNT);
        monday.frequencyWindowMillis = 6_000L;
        monday.frequencyCount = 1;
        RuleDayParameterAdapter.applyToDays(group, 1, monday, initial);
        RuleDayParameters tuesday = monday.copy();
        tuesday.frequencyWindowMillis = 12_000L;
        tuesday.frequencyCount = 2;
        RuleDayParameterAdapter.applyToDays(group, 2, tuesday, initial);
        long midnight = at("2026-11-03T00:00");
        long nowMs = midnight + 250L;
        long oldest = midnight - 6_750L;
        UsageEvidence evidence = new UsageEvidence(true, nowMs, ZONE, 0L, 0L, 0L, 0L, Collections.emptyMap())
                .withCountSnapshots(Collections.singletonMap("rolling_count",
                        new OpenCountSnapshot(Arrays.asList(oldest, midnight - 6_000L, midnight - 250L))));
        InterceptionDecision blocked = RuleEngine.evaluate("app.example", "group:1", 1L, group,
                ControlLevel.REPAIR, evidence, 180_000L).decision;
        assertEquals(InterceptionDecision.Trigger.ROLLING_COUNT, blocked.trigger);
        assertEquals(2L, blocked.limitValue);
        assertEquals(3L, blocked.currentValue);
        assertEquals(oldest + 12_000L, blocked.resetAtMs);
        assertEquals(nowMs - 12_000L + 1L,
                CountRuleEvaluator.queries(group, nowMs, ZONE, 0).get(0).startMs);
    }


    /** 相同用量在星期一和星期二使用各自额度及放行参数。 */
    @Test public void dailyLimitsAndPassDurationUseCurrentWeekday() {
        RuleGroup group = base();
        RuleDayParameters initialParameters = RuleDayParameterAdapter.capture(group);
        RuleDayParameters monday = initialParameters.copy();
        monday.types.add(RuleType.DAILY_LIMIT);
        monday.dailyLimitMillis = 60_000;
        monday.passDurationMillis = 360_000;
        RuleDayParameterAdapter.applyToDays(group, 1, monday, initialParameters);
        RuleDayParameters tuesday = monday.copy();
        tuesday.dailyLimitMillis = 120_000;
        RuleDayParameterAdapter.applyToDays(group, 2, tuesday, initialParameters);
        InterceptionEvaluation blocked = evaluate(group, at("2026-11-02T10:00"), 90_000, ZONE);
        assertEquals(InterceptionDecision.Trigger.DAILY_LIMIT, blocked.decision.trigger);
        assertEquals(360_000, blocked.decision.passDurationMs);
        assertNull(evaluate(group, at("2026-11-03T10:00"), 90_000, ZONE).decision);
        assertNull(evaluate(group, at("2026-11-04T10:00"), 900_000, ZONE).decision);
    }

    /** 星期一夜间时段延续到星期二凌晨，不会错误重复到星期三凌晨。 */
    @Test public void overnightWindowBelongsToItsStartingWeekday() {
        RuleGroup group = overnightMonday();
        assertNotNull(evaluate(group, at("2026-11-02T23:00"), 0, ZONE).decision);
        InterceptionDecision tuesday = evaluate(group, at("2026-11-03T02:00"), 0, ZONE).decision;
        assertNotNull(tuesday);
        assertEquals("weekday:1:night", tuesday.windowId);
        assertEquals(420_000, tuesday.passDurationMs);
        assertNull(evaluate(group, at("2026-11-03T23:00"), 0, ZONE).decision);
        assertNull(evaluate(group, at("2026-11-04T02:00"), 0, ZONE).decision);
    }

    /** 下一次开始可跨越多个未配置星期，精确指向下一周对应开始时刻。 */
    @Test public void nextWindowStartSkipsOtherWeekdays() {
        RuleGroup resolved = WeekdayRuleSchedule.resolve(overnightMonday(), at("2026-11-03T10:00"), ZONE);
        assertEquals(at("2026-11-09T22:00") - at("2026-11-03T10:00"),
                RuleWindowSchedule.millisUntilNextStart(resolved.windows.get(0), at("2026-11-03T10:00"), ZONE));
    }

    /** 星期选择使用传入设备时区，UTC 的星期日和上海的星期一可得到不同规则。 */
    @Test public void weekdayIsResolvedInDeviceTimeZone() {
        RuleGroup group = base();
        RuleDayParameters initialParameters = RuleDayParameterAdapter.capture(group);
        RuleDayParameters monday = initialParameters.copy();
        monday.types.add(RuleType.DAILY_LIMIT);
        monday.dailyLimitMillis = 60_000;
        RuleDayParameterAdapter.applyToDays(group, 1, monday, initialParameters);
        long instant = java.time.Instant.parse("2026-11-01T23:00:00Z").toEpochMilli();
        assertNotNull(evaluate(group, instant, 90_000, ZONE).decision);
        assertNull(evaluate(group, instant, 90_000, "UTC").decision);
    }

    /** 全天会话不沿用到另一星期；跨午夜窗口的原会话仍可继续。 */
    @Test public void pendingWindowSurvivesMidnightOnlyWhileOriginalWindowIsActive() {
        RuleGroup group = overnightMonday();
        long started = at("2026-11-02T23:00");
        InterceptionDecision decision = evaluate(group, started, 0, ZONE).decision;
        RuleGroup resolved = WeekdayRuleSchedule.resolve(group, at("2026-11-03T02:00"), ZONE);
        assertTrue(WeekdayRuleSchedule.stillApplies(resolved, decision, started, at("2026-11-03T02:00"), ZONE));
        assertFalse(WeekdayRuleSchedule.stillApplies(resolved, decision, started, at("2026-11-03T04:00"), ZONE));
    }

    /** 全局参数在未配置星期时每天生效。 */
    @Test public void globalDailyRuleRemainsActiveWithoutWeekdayOverrides() {
        RuleGroup group = base();
        group.types.add(RuleType.DAILY_LIMIT);
        group.dailyLimitMillis = 60_000;
        assertNotNull(evaluate(group, at("2026-11-03T10:00"), 90_000, ZONE).decision);
    }

    /** @return 带默认放行时长及成员的空规则组 */
    private static RuleGroup base() {
        RuleGroup group = new RuleGroup(1, "工作");
        group.apps.add("app.example");
        group.passDurationMillis = 180_000;
        return group;
    }

    /** @return 仅星期一晚十点到次日四点启用的时段策略 */
    private static RuleGroup overnightMonday() {
        RuleGroup group = base();
        RuleDayParameters initialParameters = RuleDayParameterAdapter.capture(group);
        RuleDayParameters monday = initialParameters.copy();
        monday.types.add(RuleType.WINDOW_BAN);
        monday.passDurationMillis = 420_000;
        monday.windows.add(new RuleWindow("night", RuleType.WINDOW_BAN, 1320, 240, 0));
        RuleDayParameterAdapter.applyToDays(group, 1, monday, initialParameters);
        return group;
    }

    /** @param local 上海本地日期时间 @return 对应墙钟毫秒 */
    private static long at(String local) {
        return LocalDateTime.parse(local).atZone(ZoneId.of(ZONE)).toInstant().toEpochMilli();
    }

    /** @param group 星期策略 @param now 当前墙钟毫秒 @param used 当日用量毫秒 @param zone 设备时区 @return 判定结果 */
    private static InterceptionEvaluation evaluate(RuleGroup group, long now, long used, String zone) {
        UsageEvidence evidence = new UsageEvidence(true, now, zone, used, 0, 0, 0, Collections.emptyMap());
        return RuleEngine.evaluate("app.example", "group:1", 1, group, ControlLevel.REPAIR, evidence, 180_000);
    }
}
