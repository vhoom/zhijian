package github.vhoom.zhijian.domain.interception;

import github.vhoom.zhijian.data.model.policy.ControlLevel;
import github.vhoom.zhijian.data.model.policy.RuleGroup;
import github.vhoom.zhijian.data.model.policy.RuleType;
import github.vhoom.zhijian.data.model.policy.RuleWindow;

import org.junit.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * 覆盖 RuleEngine 的边界输入、状态变化与回归行为。
 *
 * 维护日期：2026年11月16日
 */
public class RuleEngineTest {
    private static final String ZONE = "Asia/Shanghai";
    private static final long PASS = 600_000L;

    /** 规则只剩亚秒额度时应在阈值复查，不能固定多放行一秒。 */
    @Test
    public void remainingFractionOfSecondKeepsThresholdDeadline() {
        RuleGroup policy = policy(RuleType.CONTINUOUS);
        policy.continuousLimitMillis = 6_000L;
        policy.interruptionPauseMillis = 6_000L;
        InterceptionEvaluation result = evaluate(policy,
                evidence(at(10, 0), 0, 0, 0, 5_750L, null));
        assertNull(result.decision);
        assertEquals(250L, result.recheckAfterMs);
    }

    /** 时段即将在亚秒后生效时也要保留准确边界。 */
    @Test public void upcomingBanKeepsFractionalSecondStartDeadline() {
        RuleGroup policy = policy(RuleType.WINDOW_BAN);
        policy.windows.add(new RuleWindow("morning", RuleType.WINDOW_BAN, 540, 600, 0));
        InterceptionEvaluation result = evaluate(policy, evidence(at(9, 0) - 250L, 0, 0, 0, 0, null));
        assertNull(result.decision);
        assertEquals(250L, result.recheckAfterMs);
    }

    /** 每日额度和时段额度同时接近阈值时，检查较早的共享额度期限。 */
    @Test public void combinedLimitsUseEarliestSharedDeadline() {
        RuleGroup policy = policy(RuleType.DAILY_LIMIT, RuleType.WINDOW_LIMIT, RuleType.CONTINUOUS);
        policy.dailyLimitMillis = 12_000L;
        policy.continuousLimitMillis = 18_000L;
        policy.windows.add(new RuleWindow("morning", RuleType.WINDOW_LIMIT, 540, 660, 6_000L));
        Map<String, Long> windows = new HashMap<>();
        windows.put("morning", 5_800L);
        InterceptionEvaluation result = evaluate(policy,
                evidence(at(10, 0), 11_500L, 0, 0, 17_000L, windows));
        assertNull(result.decision);
        assertEquals(200L, result.recheckAfterMs);
    }

    /**
     * 验证连续使用超限生成打断决定及配置的休息时长。
     */
    @Test
    public void continuousRuleCreatesInterruptDecision() {
        RuleGroup policy = policy(RuleType.CONTINUOUS);
        policy.continuousLimitMillis = 5 * 60_000L;
        policy.interruptionPauseMillis = 3 * 60_000L;

        InterceptionEvaluation result = evaluate(policy, evidence(at(10, 0), 0, 0, 0, 300_000, null));

        assertEquals(InterceptionDecision.Kind.INTERRUPT, result.decision.kind);
        assertEquals(InterceptionDecision.Trigger.CONTINUOUS, result.decision.trigger);
        assertEquals(3 * 60_000L, result.decision.minimumInterruptionPauseDurationMs);
    }

    /** 三档的单应用全天提醒保留原配置，由周期统一应用五分钟非使用边界。 */
    @Test public void allLevelsKeepConfiguredRestForContinuousReminder() {
        RuleGroup policy = policy(RuleType.CONTINUOUS);
        policy.continuousLimitMillis = 60_000L;
        for (ControlLevel level : ControlLevel.values()) {
            for (long restMs : new long[]{60_000L, 120_000L, 600_000L}) {
                policy.interruptionPauseMillis = restMs;
                InterceptionDecision decision = RuleEngine.evaluate("app.test", "app:app.test", 2L,
                        policy, level, evidence(at(10, 0), 0L, 0L, 0L, 60_000L, null), PASS).decision;
                assertEquals(InterceptionDecision.Trigger.CONTINUOUS, decision.trigger);
                assertEquals(level, decision.level);
                assertEquals(restMs, decision.minimumInterruptionPauseDurationMs);
            }
        }
    }

    /** 单应用的活动窗口和结束补查采用同一休息要求，不能通过窗口边界提前完成休息。 */
    @Test public void singleApplicationWindowRemindersUseTheSameRestRequirement() {
        RuleGroup policy = policy(RuleType.WINDOW_CONTINUOUS);
        RuleWindow window = new RuleWindow("morning", RuleType.WINDOW_CONTINUOUS, 540, 660, 60_000L);
        window.interruptionPauseMillis = 60_000L;
        policy.windows.add(window);
        UsageEvidence used = evidence(at(10, 0), 0L, 0L, 0L, 0L, null)
                .withWindowContinuousMillis(Collections.singletonMap(window.id, 60_000L));
        RuleWindowSchedule.Interval interval = RuleWindowSchedule.activeInterval(window, at(10, 0), ZONE);
        for (ControlLevel level : ControlLevel.values()) {
            InterceptionDecision active = RuleEngine.evaluate("app.test", "app:app.test", 2L,
                    policy, level, used, PASS).decision;
            InterceptionDecision completed = RuleEngine.completedWindowReminder("app.test", "app:app.test",
                    2L, level, window, interval, 60_000L, interval.endMs, PASS);
            assertEquals(InterceptionDecision.Trigger.WINDOW_CONTINUOUS, active.trigger);
            assertEquals(active.trigger, completed.trigger);
            assertEquals(60_000L, active.minimumInterruptionPauseDurationMs);
            assertEquals(active.minimumInterruptionPauseDurationMs, completed.minimumInterruptionPauseDurationMs);
        }
    }

    /** 全局和规则组的提醒保留配置的短休息，所有作用域使用相同非使用边界。 */
    @Test public void groupAndGlobalRemindersKeepConfiguredRestDuration() {
        RuleGroup policy = policy(RuleType.CONTINUOUS);
        policy.continuousLimitMillis = 60_000L;
        policy.interruptionPauseMillis = 60_000L;
        for (ControlLevel level : ControlLevel.values()) {
            for (String policyId : new String[]{"group:1", "global"}) {
                InterceptionDecision decision = RuleEngine.evaluate("app.test", policyId, 2L, policy,
                        level, evidence(at(10, 0), 0L, 0L, 0L, 60_000L, null), PASS).decision;
                assertEquals(60_000L, decision.minimumInterruptionPauseDurationMs);
            }
        }
    }

    /** 一位小数分钟应按精确毫秒阈值触发，不能被截断为整数分钟。 */
    @Test
    public void dailyLimitSupportsOneDecimalMinute() {
        RuleGroup policy = policy(RuleType.DAILY_LIMIT);
        policy.dailyLimitMillis = 90_000L;

        InterceptionEvaluation result = evaluate(policy,
                evidence(at(10, 0), 90_000L, 0, 0, 0, null));

        assertEquals(InterceptionDecision.Trigger.DAILY_LIMIT, result.decision.trigger);
    }

    /**
     * 验证时段禁止优先于连续使用打断条件。
     */
    @Test
    public void banRuleTakesPriorityOverContinuousRule() {
        RuleGroup policy = policy(RuleType.WINDOW_BAN, RuleType.CONTINUOUS);
        policy.continuousLimitMillis = 60_000L;
        policy.windows.add(new RuleWindow("work", RuleType.WINDOW_BAN, 540, 660, 0));

        InterceptionEvaluation result = evaluate(policy, evidence(at(10, 0), 0, 0, 0, 600_000, null));

        assertEquals(InterceptionDecision.Kind.BLOCK, result.decision.kind);
        assertEquals(InterceptionDecision.Trigger.WINDOW_BAN, result.decision.trigger);
    }

    /** 额度和提醒达到阈值、次数超过允许值时，每种禁用都优先。 */
    @Test public void eachUsageBlockTakesPriorityAtTheSameThresholdAsReminder() {
        for (RuleType type : new RuleType[]{RuleType.DAILY_LIMIT, RuleType.DAILY_COUNT, RuleType.WINDOW_LIMIT}) {
            RuleGroup policy = policy(type, RuleType.CONTINUOUS);
            policy.dailyLimitMillis = 60_000;
            policy.count = 3;
            policy.continuousLimitMillis = 60_000;
            policy.windows.add(new RuleWindow("work", RuleType.WINDOW_LIMIT, 540, 660, 60_000));
            InterceptionEvaluation result = evaluate(policy, evidence(at(10, 0), 60_000,
                    60_000, 4, 60_000, Collections.singletonMap("work", 60_000L)));
            assertEquals(InterceptionDecision.Kind.BLOCK, result.decision.kind);
            assertEquals(InterceptionDecision.Trigger.valueOf(type.name()), result.decision.trigger);
        }
    }

    /**
     * 验证修心档位只改变交互步骤，不改变规则触发类别。
     */
    @Test
    public void repairLevelDoesNotChangeRuleKind() {
        RuleGroup policy = policy(RuleType.DAILY_COUNT);
        policy.count = 3;

        InterceptionEvaluation result = evaluate(policy, evidence(at(10, 0), 0, 0, 4, 0, null));

        assertEquals(ControlLevel.REPAIR, result.decision.level);
        assertEquals(InterceptionDecision.Kind.BLOCK, result.decision.kind);
    }

    /** 三档都完整允许前五次打开，第六次才进入各自的限制流程。 */
    @Test public void fifthOpeningIsAllowedAndSixthTriggersForEveryLevel() {
        RuleGroup policy = policy(RuleType.DAILY_COUNT);
        policy.count = 5;
        for (ControlLevel level : ControlLevel.values()) {
            for (int count : new int[]{4, 5}) {
                assertNull(RuleEngine.evaluate("app.test", "group:1", 2, policy, level,
                        evidence(at(10, 0), 0, 0, count, 0, null), PASS).decision);
            }
            InterceptionDecision decision = RuleEngine.evaluate("app.test", "group:1", 2, policy, level,
                    evidence(at(10, 0), 0, 0, 6, 0, null), PASS).decision;
            assertEquals(InterceptionDecision.Trigger.DAILY_COUNT, decision.trigger);
            assertEquals(level, decision.level);
        }
    }

    /**
     * 验证窗口尚未开始时允许使用并安排下次检查。
     */
    @Test
    public void inactiveWindowReturnsFutureRecheckWithoutBlocking() {
        RuleGroup policy = policy(RuleType.WINDOW_BAN);
        policy.windows.add(new RuleWindow("night", RuleType.WINDOW_BAN, 1320, 420, 0));

        InterceptionEvaluation result = evaluate(policy, evidence(at(12, 0), 0, 0, 0, 0, null));

        assertNull(result.decision);
        assertEquals(60_000L, result.recheckAfterMs);
    }

    /**
     * 验证时段限额只计算当前生效时段的用量。
     */
    @Test
    public void activeWindowLimitUsesOnlyCurrentWindowUsage() {
        RuleGroup policy = policy(RuleType.WINDOW_LIMIT);
        policy.windows.add(new RuleWindow("morning", RuleType.WINDOW_LIMIT,
                540, 660, 20 * 60_000L));
        Map<String, Long> windows = new HashMap<>();
        windows.put("morning", 20 * 60_000L);

        InterceptionEvaluation result = evaluate(policy,
                evidence(at(10, 0), 100 * 60_000L, 0, 0, 0, windows));

        assertEquals(InterceptionDecision.Trigger.WINDOW_LIMIT, result.decision.trigger);
        assertEquals("morning", result.decision.windowId);
    }

    /**
     * 验证尚未选中的草稿时段不会触发拦截。
     */
    @Test
    public void unselectedDraftWindowDoesNotBlock() {
        RuleGroup policy = policy(RuleType.DAILY_COUNT);
        policy.count = 5;
        policy.windows.add(new RuleWindow("draft", RuleType.WINDOW_BAN, 540, 660, 0));

        InterceptionEvaluation result = evaluate(policy, evidence(at(10, 0), 0, 0, 1, 0, null));

        assertNull(result.decision);
    }

    /**
     * 验证跨午夜禁止规则在当前早晨端点结束。
     */
    @Test
    public void crossMidnightBanEndsOnCurrentMorning() {
        RuleGroup policy = policy(RuleType.WINDOW_BAN);
        policy.windows.add(new RuleWindow("sleep", RuleType.WINDOW_BAN, 1320, 420, 0));
        long now = at(1, 0);

        InterceptionEvaluation result = evaluate(policy, evidence(now, 0, 0, 0, 0, null));

        assertEquals(InterceptionDecision.Trigger.WINDOW_BAN, result.decision.trigger);
        assertEquals(6 * 60 * 60 * 1000L, result.decision.resetAtMs - now);
    }

    /**
     * 按指定类型或主键创建、读取测试策略。
     *
     * @param types 当前需要选择的条件类型集合
     */
    private static RuleGroup policy(RuleType... types) {
        RuleGroup policy = new RuleGroup(1, "test");
        Collections.addAll(policy.types, types);
        policy.passDurationMillis = 10 * 60_000L;
        return policy;
    }

    /**
     * 按条件优先级检查使用证据，并返回拦截或下次检查时间。
     *
     * @param policy 待判断或持久化的策略
     * @param evidence 由系统用量事件汇总的规则判断证据
     */
    private static InterceptionEvaluation evaluate(RuleGroup policy, UsageEvidence evidence) {
        return RuleEngine.evaluate("app.test", "group:1", 2, policy, ControlLevel.REPAIR,
                evidence, PASS);
    }

    /**
     * 构造带固定时间和用量的规则判断证据。
     *
     * @param now 需要计算的当前日期或时间
     * @param appMs 当前计算使用的时间或时长，单位为毫秒
     * @param screenMs 当前计算使用的时间或时长，单位为毫秒
     * @param opens 测试已经累计的打开次数
     * @param continuousMs 本次连续前台时长，单位为毫秒
     * @param windows 按规则类型分类的时间窗口集合
     */
    private static UsageEvidence evidence(long now, long appMs, long screenMs, long opens,
            long continuousMs, Map<String, Long> windows) {
        return new UsageEvidence(true, now, ZONE, appMs, screenMs, opens, continuousMs,
                windows == null ? Collections.emptyMap() : windows);
    }

    /**
     * 在测试固定时区构造指定小时和分钟的时间戳。
     *
     * @param hour 二十四小时制的小时数
     * @param minute 一天内对应的分钟值
     */
    private static long at(int hour, int minute) {
        return LocalDateTime.of(2026, 10, 31, hour, minute).atZone(ZoneId.of(ZONE))
                .toInstant().toEpochMilli();
    }
}
