package github.vhoom.zhijian.domain.interception;

import github.vhoom.zhijian.data.model.policy.ControlLevel;
import github.vhoom.zhijian.data.model.policy.RuleGroup;
import github.vhoom.zhijian.data.model.policy.RuleType;
import github.vhoom.zhijian.data.model.policy.RuleWindow;
import github.vhoom.zhijian.data.model.runtime.PendingInterception;
import github.vhoom.zhijian.domain.usage.ContinuousUsageCycle;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * 再次提醒只依赖新增实际用量，不能重置使用、伪造休息或屏蔽独立禁用。
 *
 * 维护日期：2026年11月13日
 */
public class ReminderRepeatPolicyTest {
    private static final long THRESHOLD_MS = 20_000L;
    private static final long PASS_MS = 180_000L;
    private static final String ZONE = "Asia/Shanghai";

    /** 返回后不会立即重弹；再使用完整阈值才触发，并保留亚秒复查边界。 */
    @Test public void repeatsOnlyAfterAFullAdditionalUsageThreshold() {
        PendingInterception rest = rest(InterceptionDecision.Trigger.CONTINUOUS, "", THRESHOLD_MS);
        RuleGroup policy = policy();
        for (long added : new long[]{0L, THRESHOLD_MS - 1L, THRESHOLD_MS}) {
            UsageEvidence original = evidence(at(10, 0), THRESHOLD_MS + added);
            UsageEvidence adjusted = ReminderRepeatPolicy.forEvaluation(original, Collections.singletonList(rest), ControlLevel.MORTAL);
            InterceptionEvaluation result = evaluate(policy, adjusted);
            assertEquals(THRESHOLD_MS + added, original.continuousMs);
            assertEquals(added, adjusted.continuousMs);
            assertEquals(Math.max(0L, THRESHOLD_MS - added),
                    ReminderRepeatPolicy.remainingUsage(rest, cycle(THRESHOLD_MS + added), ControlLevel.MORTAL));
            if (added < THRESHOLD_MS) {
                assertNull(result.decision);
                assertEquals(THRESHOLD_MS - added, result.recheckAfterMs);
            } else {
                assertEquals(InterceptionDecision.Kind.INTERRUPT, result.decision.kind);
            }
        }
    }

    /** 单纯经过原来的三分钟放行时长或更久，不会消耗尚未实际使用的提醒间隔。 */
    @Test public void wallTimeDoesNotActAsATemporaryGrantDeadline() {
        PendingInterception rest = rest(InterceptionDecision.Trigger.CONTINUOUS, "", THRESHOLD_MS);
        for (long elapsed : new long[]{0L, PASS_MS, 3_600_000L}) {
            UsageEvidence adjusted = ReminderRepeatPolicy.forEvaluation(
                    evidence(at(10, 0) + elapsed, THRESHOLD_MS), Collections.singletonList(rest), ControlLevel.MORTAL);
            assertNull(evaluate(policy(), adjusted).decision);
            assertEquals(THRESHOLD_MS,
                    ReminderRepeatPolicy.remainingUsage(rest, cycle(THRESHOLD_MS), ControlLevel.MORTAL));
        }
    }

    /** 全天和各窗口独立扣除提醒起点，累计额度、次数及原证据均保持原值。 */
    @Test public void adjustingRemindersPreservesUsageCountsAndOtherWindows() {
        Map<String, Long> windows = new HashMap<>();
        windows.put("first", 37_000L);
        windows.put("second", 8_000L);
        UsageEvidence original = new UsageEvidence(true, at(10, 0), ZONE, 60_000L, 120_000L,
                4L, 50_000L, Collections.singletonMap("budget", 9_000L), windows);
        UsageEvidence adjusted = ReminderRepeatPolicy.forEvaluation(original, Arrays.asList(
                rest(InterceptionDecision.Trigger.CONTINUOUS, "", 20_000L),
                rest(InterceptionDecision.Trigger.WINDOW_CONTINUOUS, "first", 20_000L)), ControlLevel.MORTAL);
        assertEquals(30_000L, adjusted.continuousMs);
        assertEquals(17_000L, adjusted.windowContinuousMs("first"));
        assertEquals(8_000L, adjusted.windowContinuousMs("second"));
        assertEquals(original.appForegroundMs, adjusted.appForegroundMs);
        assertEquals(original.screenForegroundMs, adjusted.screenForegroundMs);
        assertEquals(original.openCount, adjusted.openCount);
        assertEquals(9_000L, adjusted.windowForegroundMs("budget"));
        assertEquals(50_000L, original.continuousMs);
        assertEquals(37_000L, original.windowContinuousMs("first"));
    }

    /** 已处理标记保留原会话语义，不解除真实休息；闭关仍不接受确认。 */
    @Test public void acknowledgementKeepsRestAndSealRestrictions() {
        PendingInterception rest = rest(InterceptionDecision.Trigger.CONTINUOUS, "", THRESHOLD_MS);
        ContinuousUsageCycle cycle = cycle(THRESHOLD_MS);
        assertFalse(ReminderRestPolicy.satisfied(rest, cycle));
        UsageEvidence evidence = evidence(at(10, 0), THRESHOLD_MS);
        for (ControlLevel level : ControlLevel.values()) {
            UsageEvidence adjusted = ReminderRepeatPolicy.forEvaluation(evidence, Collections.singletonList(rest), level);
            assertEquals(level == ControlLevel.SEAL ? THRESHOLD_MS : 0L, adjusted.continuousMs);
            assertEquals(level == ControlLevel.SEAL ? 0L : THRESHOLD_MS,
                    ReminderRepeatPolicy.remainingUsage(rest, cycle, level));
        }
    }

    /** 无为和修心保存过的确认不受后来放行开关影响；新会话仍按开关选择结束效果。 */
    @Test public void savedAcknowledgementSurvivesNewOptionsForBothReminderTypes() {
        for (InterceptionDecision.Trigger trigger : new InterceptionDecision.Trigger[]{
                InterceptionDecision.Trigger.CONTINUOUS, InterceptionDecision.Trigger.WINDOW_CONTINUOUS}) {
            boolean window = trigger == InterceptionDecision.Trigger.WINDOW_CONTINUOUS;
            PendingInterception rest = rest(trigger, window ? "active" : "", THRESHOLD_MS);
            UsageEvidence original = evidence(at(10, 0), THRESHOLD_MS + 5_000L)
                    .withWindowContinuousMillis(Collections.singletonMap("active", THRESHOLD_MS + 5_000L));
            for (ControlLevel level : ControlLevel.values()) {
                for (boolean pass : new boolean[]{false, true}) {
                    boolean repeats = level != ControlLevel.SEAL;
                    UsageEvidence adjusted = ReminderRepeatPolicy.forEvaluation(
                            original, Collections.singletonList(rest), level);
                    assertEquals(repeats ? 5_000L : THRESHOLD_MS + 5_000L,
                            window ? adjusted.windowContinuousMs("active") : adjusted.continuousMs);
                    assertEquals(repeats ? THRESHOLD_MS - 5_000L : 0L,
                            ReminderRepeatPolicy.remainingUsage(rest, cycle(THRESHOLD_MS + 5_000L), level));
                    assertFalse(ReminderRepeatPolicy.isDirectReminder(InterceptionDecision.Kind.BLOCK, level, pass));
                    assertEquals(repeats && !pass, ReminderRepeatPolicy.isDirectReminder(InterceptionDecision.Kind.INTERRUPT, level, pass));
                }
            }
        }
    }

    /** 提醒间隔期间所有累计额度和限次禁用仍能触发。 */
    @Test public void acknowledgementDoesNotMaskUsageBlocks() {
        PendingInterception rest = rest(InterceptionDecision.Trigger.CONTINUOUS, "", THRESHOLD_MS);
        for (RuleType type : new RuleType[]{RuleType.DAILY_LIMIT, RuleType.DAILY_COUNT, RuleType.WINDOW_LIMIT}) {
            RuleGroup policy = policy();
            policy.types.add(type);
            policy.dailyLimitMillis = 60_000L;
            policy.count = 3;
            policy.windows.add(new RuleWindow("budget", RuleType.WINDOW_LIMIT, 540, 660, 60_000L));
            UsageEvidence actual = new UsageEvidence(true, at(10, 0), ZONE, 60_000L, 60_000L,
                    4L, THRESHOLD_MS, Collections.singletonMap("budget", 60_000L));
            InterceptionEvaluation result = evaluate(policy,
                    ReminderRepeatPolicy.forEvaluation(actual, Collections.singletonList(rest), ControlLevel.MORTAL));
            assertEquals(InterceptionDecision.Kind.BLOCK, result.decision.kind);
            assertEquals(InterceptionDecision.Trigger.valueOf(type.name()), result.decision.trigger);
        }
    }

    /** 不应让提醒的再次触发间隔推迟即将开始的禁用时段。 */
    @Test public void upcomingBanKeepsItsOwnCheckDeadline() {
        RuleGroup policy = policy();
        policy.types.add(RuleType.WINDOW_BAN);
        policy.windows.add(new RuleWindow("work", RuleType.WINDOW_BAN, 540, 600, 0L));
        PendingInterception rest = rest(InterceptionDecision.Trigger.CONTINUOUS, "", THRESHOLD_MS);
        UsageEvidence adjusted = ReminderRepeatPolicy.forEvaluation(evidence(at(9, 0) - 250L, THRESHOLD_MS),
                Collections.singletonList(rest), ControlLevel.MORTAL);
        assertNull(evaluate(policy, adjusted).decision);
        assertEquals(250L, evaluate(policy, adjusted).recheckAfterMs);
        adjusted = ReminderRepeatPolicy.forEvaluation(evidence(at(9, 0), THRESHOLD_MS),
                Collections.singletonList(rest), ControlLevel.MORTAL);
        assertEquals(InterceptionDecision.Trigger.WINDOW_BAN, evaluate(policy, adjusted).decision.trigger);
    }

    /** 缺少新证据不会凭时间重弹，接近长整数上限也不会因加法溢出提前提醒。 */
    @Test public void missingEvidenceAndCounterLimitsDoNotInventUsage() {
        PendingInterception rest = rest(InterceptionDecision.Trigger.CONTINUOUS, "", Long.MAX_VALUE - 10L);
        assertEquals(THRESHOLD_MS, ReminderRepeatPolicy.remainingUsage(rest, null, ControlLevel.MORTAL));
        assertEquals(THRESHOLD_MS - 10L,
                ReminderRepeatPolicy.remainingUsage(rest, cycle(Long.MAX_VALUE), ControlLevel.MORTAL));
    }

    /** 同组换目标继承同一提醒起点，新一轮未处理提醒仍正常触发。 */
    @Test public void groupedTargetsShareAcknowledgementAndNewRestsStartUnacknowledged() {
        PendingInterception rest = rest(InterceptionDecision.Trigger.CONTINUOUS, "", THRESHOLD_MS);
        PendingInterception moved = rest.forTarget("app.second");
        assertEquals(THRESHOLD_MS, moved.acknowledgedUsageMs);
        assertEquals(rest.continuousKey, moved.continuousKey);
        assertEquals(THRESHOLD_MS, ReminderRepeatPolicy.remainingUsage(moved, cycle(THRESHOLD_MS), ControlLevel.MORTAL));
        PendingInterception fresh = rest(InterceptionDecision.Trigger.CONTINUOUS, "", -1L);
        assertEquals(0L, ReminderRepeatPolicy.remainingUsage(fresh, cycle(THRESHOLD_MS), ControlLevel.MORTAL));
        UsageEvidence evidence = evidence(at(10, 0), THRESHOLD_MS);
        assertSame(evidence, ReminderRepeatPolicy.forEvaluation(evidence, Collections.singletonList(fresh), ControlLevel.MORTAL));
    }

    /** @return 带连续提醒和原放行配置的测试规则 */
    private static RuleGroup policy() {
        RuleGroup policy = new RuleGroup(1L, "repeat");
        policy.types.add(RuleType.CONTINUOUS);
        policy.continuousLimitMillis = THRESHOLD_MS;
        policy.interruptionPauseMillis = 6_000L;
        policy.passDurationMillis = PASS_MS;
        return policy;
    }

    /** @param trigger 提醒来源 @param window 时段身份 @param acknowledged 处理时真实用量 @return 原休息事实 */
    private static PendingInterception rest(InterceptionDecision.Trigger trigger, String window, long acknowledged) {
        InterceptionDecision decision = new InterceptionDecision("app.test", "group:1", 1L,
                InterceptionDecision.Kind.INTERRUPT, trigger, ControlLevel.MORTAL, window,
                THRESHOLD_MS, THRESHOLD_MS, 0L, PASS_MS, 6_000L);
        return new PendingInterception(decision, "rest-" + trigger + window, "boot", 1_000L, 1_000L,
                2L, 1_000L, window.isEmpty() ? "group:1" : "window:" + window, 0L, acknowledged);
    }

    /** @param used 本周期真实用量 @return 未完成休息的周期 */
    private static ContinuousUsageCycle cycle(long used) {
        return new ContinuousUsageCycle("boot", 1_000L, 1_000L, used, 0L, "app.test", "", 2L);
    }

    /** @param wall 当前墙钟 @param used 本周期真实用量 @return 其他统计均未触发禁用的证据 */
    private static UsageEvidence evidence(long wall, long used) {
        return new UsageEvidence(true, wall, ZONE, 0L, 0L, 0L, used, Collections.emptyMap());
    }

    /** @param policy 当前规则 @param evidence 提醒适配后的证据 @return 真实规则引擎的判定 */
    private static InterceptionEvaluation evaluate(RuleGroup policy, UsageEvidence evidence) {
        return RuleEngine.evaluate("app.test", "group:1", 1L, policy, ControlLevel.MORTAL, evidence, PASS_MS);
    }

    /** @param hour 小时 @param minute 分钟 @return 固定本地日期的墙钟时间 */
    private static long at(int hour, int minute) {
        return LocalDateTime.of(2026, 11, 8, hour, minute).atZone(ZoneId.of(ZONE)).toInstant().toEpochMilli();
    }
}
