package github.vhoom.zhijian.domain.interception;

import github.vhoom.zhijian.data.model.policy.ControlLevel;
import github.vhoom.zhijian.data.model.policy.RuleGroup;
import github.vhoom.zhijian.data.model.policy.RuleType;
import github.vhoom.zhijian.data.model.policy.RuleWindow;

import org.junit.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * 通过正式规则入口验证日历禁用与连续提醒在时间边界处的优先级。
 *
 * 维护日期：2026年11月14日
 */
public final class RuleEnginePriorityTest {
    private static final String ZONE = "Asia/Shanghai";
    private static final long START = LocalDateTime.of(2026, 11, 4, 9, 0)
            .atZone(ZoneId.of(ZONE)).toInstant().toEpochMilli();
    private static final long END = START + 60 * 60_000L;

    /** 连续提醒已达到阈值时，三档强度都应在禁用开始的精确边界改为禁用。 */
    @Test public void calendarBanOverridesReminderAtStartBoundary() {
        for (ControlLevel level : ControlLevel.values()) {
            assertEquals(InterceptionDecision.Kind.INTERRUPT,
                    evaluate(level, START - 1L, true).decision.kind);
            InterceptionEvaluation current = evaluate(level, START, true);
            assertEquals(InterceptionDecision.Kind.BLOCK, current.decision.kind);
            assertEquals(InterceptionDecision.Trigger.WINDOW_BAN, current.decision.trigger);
        }
    }

    /** 用量证据不可用时，仍应根据日历触发当前禁用，不能依赖旧提醒结果。 */
    @Test public void calendarBanDoesNotRequireUsageEvidence() {
        for (ControlLevel level : ControlLevel.values()) {
            InterceptionEvaluation current = evaluate(level, START, false);
            assertEquals(InterceptionDecision.Kind.BLOCK, current.decision.kind);
            assertEquals(InterceptionDecision.Trigger.WINDOW_BAN, current.decision.trigger);
        }
    }

    /** 禁用结束前一毫秒仍有效，结束时应重新选择仍超限的连续提醒。 */
    @Test public void expiredCalendarBanYieldsToCurrentReminder() {
        for (ControlLevel level : ControlLevel.values()) {
            InterceptionEvaluation beforeEnd = evaluate(level, END - 1L, true);
            assertEquals(InterceptionDecision.Kind.BLOCK, beforeEnd.decision.kind);
            assertEquals(1L, beforeEnd.recheckAfterMs);
            InterceptionEvaluation current = evaluate(level, END, true);
            assertEquals(InterceptionDecision.Kind.INTERRUPT, current.decision.kind);
            assertEquals(InterceptionDecision.Trigger.CONTINUOUS, current.decision.trigger);
        }
    }

    /** 结束禁用且缺少用量证据时，不沿用上次禁用或凭空生成连续提醒。 */
    @Test public void expiredBanWithoutUsageEvidenceDoesNotKeepBlocking() {
        for (ControlLevel level : ControlLevel.values()) {
            assertEquals(InterceptionDecision.Kind.BLOCK,
                    evaluate(level, END - 1L, false).decision.kind);
            assertNull(evaluate(level, END, false).decision);
        }
    }

    /**
     * 用固定日历和相同的连续用量调用正式规则计算入口。
     * @param level 本次验证的管控强度
     * @param now 当前墙钟毫秒
     * @param available 系统用量证据是否可用
     * @return 当次规则判定及复查间隔
     */
    private static InterceptionEvaluation evaluate(ControlLevel level, long now, boolean available) {
        RuleGroup policy = new RuleGroup(1L, "工作");
        policy.types.add(RuleType.CONTINUOUS);
        policy.types.add(RuleType.WINDOW_BAN);
        policy.continuousLimitMillis = 60_000L;
        policy.interruptionPauseMillis = 60_000L;
        policy.passDurationMillis = 180_000L;
        policy.windows.add(new RuleWindow("work", RuleType.WINDOW_BAN, 540, 600, 0L));
        UsageEvidence evidence = new UsageEvidence(available, now, ZONE, 0L, 0L, 0L,
                60_000L, Collections.emptyMap());
        return RuleEngine.evaluate("app.test", "group:1", 2L, policy, level, evidence, 180_000L);
    }
}
