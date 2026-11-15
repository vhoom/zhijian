package github.vhoom.zhijian.domain.interception.count;

import github.vhoom.zhijian.data.model.policy.ControlLevel;
import github.vhoom.zhijian.data.model.policy.RuleGroup;
import github.vhoom.zhijian.data.model.policy.RuleType;
import github.vhoom.zhijian.data.model.policy.RuleWindow;
import github.vhoom.zhijian.data.model.runtime.PendingInterception;
import github.vhoom.zhijian.domain.interception.DailyResetSchedule;
import github.vhoom.zhijian.domain.interception.InterceptionDecision;
import github.vhoom.zhijian.domain.interception.InterceptionEvaluation;
import github.vhoom.zhijian.domain.interception.RuleEngine;
import github.vhoom.zhijian.domain.interception.UsageEvidence;
import github.vhoom.zhijian.domain.usage.OpenCountSnapshot;

import org.junit.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/**
 * 三种限次的真实打开额度、固定和滚动恢复边界，以及运行结果的复查回归。
 *
 * 维护日期：2026年11月13日
 */
public class CountRuleEvaluatorTest {
    private static final String ZONE = "Asia/Shanghai";
    private static final String PACKAGE = "app.example";
    private static final long PASS_MS = 180_000L;

    /** 所有限次在三档强度下都允许前 N 次，第 N+1 次触发，包含 1 和 99 两端。 */
    @Test
    public void eachCountRuleAllowsItsQuotaBeforeBlockingForEveryLevel() {
        long nowMs = at("2026-11-12T10:00");
        for (RuleType type : new RuleType[]{RuleType.DAILY_COUNT, RuleType.WINDOW_COUNT, RuleType.ROLLING_COUNT}) {
            for (int limit : new int[]{1, 99}) {
                RuleGroup policy = policy(type, limit);
                for (ControlLevel level : ControlLevel.values()) {
                    assertNull(RuleEngine.evaluate(PACKAGE, "group:1", 1L, policy, level,
                            evidence(policy, nowMs, openings(nowMs, limit)), PASS_MS).decision);
                    InterceptionDecision blocked = RuleEngine.evaluate(PACKAGE, "group:1", 1L, policy, level,
                            evidence(policy, nowMs, openings(nowMs, limit + 1)), PASS_MS).decision;
                    assertEquals(InterceptionDecision.Trigger.valueOf(type.name()), blocked.trigger);
                    assertEquals(InterceptionDecision.Kind.BLOCK, blocked.kind);
                    assertEquals(level, blocked.level);
                    assertEquals(limit, blocked.limitValue);
                    assertEquals(limit + 1L, blocked.currentValue);
                }
            }
        }
    }

    /** 未开始的时段限次也保留亚秒开始边界，不用猜测打开次数。 */
    @Test
    public void upcomingWindowKeepsItsFractionalStartDeadline() {
        RuleGroup policy = policy(RuleType.WINDOW_COUNT, 1);
        long nowMs = at("2026-11-12T09:00") - 250L;
        InterceptionEvaluation result = evaluate(policy, evidence(policy, nowMs));
        assertNull(result.decision);
        assertEquals(250L, result.recheckAfterMs);
        assertFalse(CountRuleEvaluator.needsUsage(policy, nowMs, ZONE));
    }

    /** 各时段只计算包含开始端点的打开，结束时立即解除并复查。 */
    @Test
    public void windowCountsClipTheirOwnIntervalAndReleaseAtItsEnd() {
        RuleGroup policy = policy(RuleType.WINDOW_COUNT, 1);
        long startMs = at("2026-11-12T09:00");
        long endMs = at("2026-11-12T11:00");
        long nowMs = endMs - 250L;
        assertNull(evaluate(policy, evidence(policy, nowMs, startMs - 1L, startMs)).decision);
        InterceptionEvaluation blocked = evaluate(policy, evidence(policy, nowMs, startMs, nowMs));
        assertEquals("work", blocked.decision.windowId);
        assertEquals(endMs, blocked.decision.resetAtMs);
        assertEquals(250L, blocked.recheckAfterMs);
        assertNull(evaluate(policy, evidence(policy, endMs, startMs, nowMs)).decision);
    }

    /** 前一个时段消耗的次数不能借给后一个时段，统计键保持各自窗口身份。 */
    @Test
    public void consecutiveWindowsHaveIndependentCountQuotas() {
        RuleGroup policy = policy(RuleType.WINDOW_COUNT, 1);
        policy.windows.get(0).endMinute = 600;
        RuleWindow next = new RuleWindow("next", RuleType.WINDOW_COUNT, 600, 660, 0L);
        next.limitCount = 1;
        policy.windows.add(next);
        long nowMs = at("2026-11-12T10:00") + 1L;
        long oldOpening = at("2026-11-12T09:30");
        UsageEvidence current = evidence(policy, nowMs, oldOpening, oldOpening + 1L, nowMs);
        assertNull(evaluate(policy, current).decision);
        assertEquals(1L, current.countSnapshot("window_count:next").count());
        InterceptionDecision blocked = evaluate(policy,
                evidence(policy, nowMs, oldOpening, oldOpening + 1L, nowMs - 1L, nowMs)).decision;
        assertEquals("next", blocked.windowId);
        assertEquals(2L, blocked.currentValue);
    }

    /** 滚动窗口排除恰好到期的记录，额度在下一毫秒释放时准确复查。 */
    @Test
    public void rollingOpeningExpiresAtItsExactMillisecond() {
        RuleGroup policy = policy(RuleType.ROLLING_COUNT, 1);
        long nowMs = at("2026-11-12T10:00");
        long firstOpening = nowMs - 5_999L;
        InterceptionEvaluation blocked = evaluate(policy, evidence(policy, nowMs, firstOpening, nowMs));
        assertEquals(nowMs + 1L, blocked.decision.resetAtMs);
        assertEquals(1L, blocked.recheckAfterMs);
        assertNull(evaluate(policy, evidence(policy, nowMs + 1L, firstOpening, nowMs)).decision);
        assertNull(evaluate(policy, evidence(policy, nowMs, nowMs - 6_000L, nowMs)).decision);
    }

    /** 多次重新打开后的恢复必须等待足够多旧记录过期，不能只等最老的一条。 */
    @Test
    public void repeatedBlockedOpeningsWaitUntilTheCountFitsAgain() {
        RuleGroup policy = policy(RuleType.ROLLING_COUNT, 2);
        long nowMs = at("2026-11-12T10:00");
        long[] times = {nowMs - 5_900L, nowMs - 5_800L, nowMs - 5_700L, nowMs};
        InterceptionEvaluation blocked = evaluate(policy, evidence(policy, nowMs, times));
        assertEquals(nowMs + 200L, blocked.decision.resetAtMs);
        assertEquals(200L, blocked.recheckAfterMs);
        assertEquals(3L, evaluate(policy, evidence(policy, nowMs + 100L, times)).decision.currentValue);
        assertNull(evaluate(policy, evidence(policy, nowMs + 200L, times)).decision);
    }

    /** 自定义每日恢复只影响全天限次，滚动限次继续保留午夜前的记录。 */
    @Test
    public void dailyAndRollingCountsUseTheirOwnRecoveryBoundaries() {
        long nowMs = at("2026-11-12T10:00") - 250L;
        RuleGroup daily = policy(RuleType.DAILY_COUNT, 1);
        UsageEvidence opened = new UsageEvidence(true, nowMs, ZONE, 0L, 0L, 2L, 0L, Collections.emptyMap());
        InterceptionEvaluation blocked = RuleEngine.evaluate(PACKAGE, "group:1", 1L, daily,
                ControlLevel.REPAIR, opened, PASS_MS, 600);
        assertEquals(nowMs + 250L, blocked.decision.resetAtMs);
        assertEquals(250L, blocked.recheckAfterMs);
        assertEquals(at("2026-11-11T10:00"), CountRuleEvaluator.queries(daily, nowMs, ZONE, 600).get(0).startMs);

        RuleGroup rolling = policy(RuleType.ROLLING_COUNT, 1);
        rolling.frequencyWindowMillis = 86_400_000L;
        long midnight = at("2026-11-12T00:00");
        UsageEvidence recent = evidence(rolling, midnight, midnight - 1_000L, midnight);
        InterceptionDecision rollingBlock = RuleEngine.evaluate(PACKAGE, "group:1", 1L, rolling,
                ControlLevel.REPAIR, recent, PASS_MS, 600).decision;
        assertEquals(midnight - 1_000L + rolling.frequencyWindowMillis, rollingBlock.resetAtMs);
    }

    /** 多条禁用等待最晚的解除时刻，复查仍指向较早的滚动释放边界。 */
    @Test
    public void combinedBlocksKeepTheLatestRecoveryAndEarlierRecheck() {
        RuleGroup policy = policy(RuleType.ROLLING_COUNT, 1);
        policy.types.add(RuleType.WINDOW_BAN);
        policy.windows.add(new RuleWindow("ban", RuleType.WINDOW_BAN, 540, 660, 0L));
        long nowMs = at("2026-11-12T10:00");
        InterceptionEvaluation blocked = evaluate(policy, evidence(policy, nowMs, nowMs - 5_750L, nowMs));
        assertEquals(InterceptionDecision.Trigger.WINDOW_BAN, blocked.decision.trigger);
        assertEquals(at("2026-11-12T11:00"), blocked.decision.resetAtMs);
        assertEquals(250L, blocked.recheckAfterMs);
        InterceptionDecision remaining = evaluate(policy,
                evidence(policy, nowMs + 250L, nowMs - 5_750L, nowMs)).decision;
        assertEquals(InterceptionDecision.Trigger.WINDOW_BAN, remaining.trigger);
    }

    /** 没有成功取得系统证据时不猜测次数，但仍按活动时段结束安排复查。 */
    @Test
    public void unavailableEvidenceKeepsWindowDeadlineWithoutInventingCounts() {
        RuleGroup policy = policy(RuleType.WINDOW_COUNT, 1);
        long nowMs = at("2026-11-12T11:00") - 250L;
        UsageEvidence unavailable = new UsageEvidence(false, nowMs, ZONE, 0L, 0L, 0L, 0L,
                Collections.emptyMap());
        InterceptionEvaluation result = evaluate(policy, unavailable);
        assertNull(result.decision);
        assertEquals(250L, result.recheckAfterMs);
    }

    /** 连续用量补入和窗口提醒证据替换不能丢掉同次读取的限次快照。 */
    @Test
    public void reminderEvidenceCopiesPreserveCountSnapshots() {
        RuleGroup policy = policy(RuleType.ROLLING_COUNT, 1);
        long nowMs = at("2026-11-12T10:00");
        UsageEvidence copied = evidence(policy, nowMs, nowMs - 5_750L, nowMs)
                .withContinuousMillis(60_000L)
                .withWindowContinuousMillis(Collections.singletonMap("reminder", 30_000L))
                .withWindowContinuousMillis("other", 10_000L);
        assertEquals(2L, copied.countSnapshot("rolling_count").count());
        assertEquals(250L, evaluate(policy, copied).recheckAfterMs);
    }

    /** 恢复持久化展示会话保留较早复查，不能退回固定一分钟。 */
    @Test
    public void restoredPresentationRetainsItsQuotaRecheck() {
        RuleGroup policy = policy(RuleType.ROLLING_COUNT, 1);
        long nowMs = at("2026-11-12T10:00");
        InterceptionEvaluation blocked = evaluate(policy, evidence(policy, nowMs, nowMs - 5_750L, nowMs));
        PendingInterception pending = new PendingInterception(blocked.decision, "count-session", "boot",
                nowMs, 1_000L);
        InterceptionEvaluation restored = InterceptionEvaluation.restore(pending, blocked.recheckAfterMs);
        assertSame(pending, restored.pending);
        assertSame(pending.decision, restored.decision);
        assertEquals(250L, restored.recheckAfterMs);
    }

    /**
     * 创建只启用目标限次的有效测试策略。
     *
     * @param type 需要验证的限次类型
     * @param limit 允许打开的最大次数
     * @return 带独立时间或次数参数的规则组
     */
    private static RuleGroup policy(RuleType type, int limit) {
        RuleGroup policy = new RuleGroup(1L, "限次");
        policy.apps.add(PACKAGE);
        policy.types.add(type);
        policy.passDurationMillis = PASS_MS;
        if (type == RuleType.DAILY_COUNT) policy.count = limit;
        if (type == RuleType.ROLLING_COUNT) {
            policy.frequencyWindowMillis = 6_000L;
            policy.frequencyCount = limit;
        }
        if (type == RuleType.WINDOW_COUNT) {
            RuleWindow window = new RuleWindow("work", type, 540, 660, 0L);
            window.limitCount = limit;
            policy.windows.add(window);
        }
        return policy;
    }

    /**
     * 按正式查询范围裁剪已确认的打开时刻，构造本次只读证据。
     *
     * @param policy 已启用的测试规则
     * @param nowMs 本次固定墙钟时间
     * @param openingTimes 按时间升序排列的真实打开记录
     * @return 同时包含全天次数和各活动范围快照的证据
     */
    private static UsageEvidence evidence(RuleGroup policy, long nowMs, long... openingTimes) {
        Map<String, OpenCountSnapshot> snapshots = new HashMap<>();
        for (CountRuleEvaluator.Query query : CountRuleEvaluator.queries(policy, nowMs, ZONE, 0)) {
            List<Long> included = new ArrayList<>();
            for (long time : openingTimes) {
                if (time >= query.startMs && time <= query.endMs) included.add(time);
            }
            snapshots.put(query.key, new OpenCountSnapshot(included));
        }
        long dayStart = DailyResetSchedule.periodStart(nowMs, ZONE, 0);
        long dailyCount = 0L;
        for (long time : openingTimes) if (time >= dayStart && time <= nowMs) dailyCount++;
        return new UsageEvidence(true, nowMs, ZONE, 0L, 0L, dailyCount, 0L, Collections.emptyMap())
                .withCountSnapshots(snapshots);
    }

    /**
     * 通过正式引擎验证限次与禁用合并行为。
     *
     * @param policy 本次测试规则
     * @param evidence 与本次时刻一致的使用证据
     * @return 修心档位下的纯判定结果
     */
    private static InterceptionEvaluation evaluate(RuleGroup policy, UsageEvidence evidence) {
        return RuleEngine.evaluate(PACKAGE, "group:1", 1L, policy, ControlLevel.REPAIR, evidence, PASS_MS);
    }

    /**
     * 在当前滚动范围内排列指定数量的打开记录。
     *
     * @param nowMs 最后一次打开和查询的墙钟时间
     * @param count 需要生成的真实打开记录数量
     * @return 按毫秒升序排列的独立数组
     */
    private static long[] openings(long nowMs, int count) {
        long[] result = new long[count];
        for (int index = 0; index < count; index++) result[index] = nowMs - count + index + 1L;
        return result;
    }

    /**
     * 将固定上海本地时间换算为测试墙钟。
     *
     * @param local 本地日期时间的 ISO 文本
     * @return 对应的墙钟毫秒数
     */
    private static long at(String local) {
        return LocalDateTime.parse(local).atZone(ZoneId.of(ZONE)).toInstant().toEpochMilli();
    }
}
