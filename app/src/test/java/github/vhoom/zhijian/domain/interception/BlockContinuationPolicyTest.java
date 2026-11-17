package github.vhoom.zhijian.domain.interception;

import github.vhoom.zhijian.data.model.policy.ControlLevel;
import github.vhoom.zhijian.data.model.policy.RuleGroup;
import github.vhoom.zhijian.data.model.policy.RuleType;
import github.vhoom.zhijian.data.model.policy.RuleWindow;
import github.vhoom.zhijian.data.model.runtime.BlockContinuation;
import github.vhoom.zhijian.domain.usage.UsageTimeline;

import org.junit.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;

import static org.junit.Assert.*;

/**
 * 覆盖本次访问确认后的全部禁用、连续提醒、配置版本和真实应用访问边界。
 *
 * 维护日期：2026年11月17日
 */
public class BlockContinuationPolicyTest {
    private static final String APP = "test.app";
    private static final long NOW = LocalDateTime.of(2026, 11, 9, 10, 0)
            .atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();

    /** 忽略当前禁用仍在连续使用阈值准确复查，达到阈值后正常打断。 */
    @Test public void ignoredBanKeepsContinuousReminderAndItsDeadline() {
        RuleGroup policy = ban();
        policy.types.add(RuleType.CONTINUOUS);
        policy.continuousLimitMillis = 60_000L;
        policy.interruptionPauseMillis = 30_000L;
        BlockContinuation ignored = continuation();
        InterceptionEvaluation before = evaluate(policy, ControlLevel.MORTAL, 20_000L, ignored);
        assertNull(before.decision);
        assertEquals(40_000L, before.recheckAfterMs);
        InterceptionEvaluation due = evaluate(policy, ControlLevel.MORTAL, 60_000L, ignored);
        assertEquals(InterceptionDecision.Trigger.CONTINUOUS, due.decision.trigger);
        assertEquals(30_000L, due.decision.minimumInterruptionPauseDurationMs);
    }

    /** 同版本访问确认覆盖全部禁用，配置版本变化后重新执行全天额度禁用。 */
    @Test public void confirmedVisitSuppressesAllBlocksUntilPolicyRevisionChanges() {
        RuleGroup policy = ban();
        BlockContinuation confirmed = continuation();
        assertNull(evaluate(policy, ControlLevel.MORTAL, 0L, confirmed).decision);
        policy.types.add(RuleType.DAILY_LIMIT);
        policy.dailyLimitMillis = 60_000L;
        assertNull(evaluate(policy, ControlLevel.MORTAL, 0L, confirmed).decision);
        // 新版本改为全天限时，明确验证原时段确认不能跳过新配置。
        policy.types.remove(RuleType.WINDOW_BAN);
        InterceptionDecision changed = evaluateWithRevision(policy, ControlLevel.MORTAL,
                2L, 0L, confirmed).decision;
        assertNotNull(changed);
        assertEquals(InterceptionDecision.Trigger.DAILY_LIMIT,
                changed.trigger);
    }

    /** 同应用同版本的其他窗口共享本次确认，其他应用、策略或版本不继承。 */
    @Test public void continuationIsScopedToApplicationPolicyAndRevision() {
        BlockContinuation ignored = continuation();
        assertTrue(ignored.matches(decision(APP, 1L, "work", ControlLevel.MORTAL)));
        assertFalse(ignored.matches(decision("test.other", 1L, "work", ControlLevel.MORTAL)));
        assertFalse(ignored.matches(decision(APP, 2L, "work", ControlLevel.MORTAL)));
        assertTrue(ignored.matches(decision(APP, 1L, "next", ControlLevel.MORTAL)));
        InterceptionDecision otherPolicy = new InterceptionDecision(APP, "group:2", 1L,
                InterceptionDecision.Kind.BLOCK, InterceptionDecision.Trigger.WINDOW_BAN,
                ControlLevel.MORTAL, "work", 1L, 1L, NOW + 60_000L, 180_000L, 0L);
        assertFalse(ignored.matches(otherPolicy));
    }

    /** 修心确认覆盖本次访问的全部禁用，保留提醒期限，配置更新后恢复禁用。 */
    @Test public void repairContinuationKeepsReminderDeadlineAndRejectsNewPolicyRevision() {
        RuleGroup policy = ban();
        BlockContinuation ignored = continuation(ControlLevel.REPAIR);
        assertNull(evaluate(policy, ControlLevel.REPAIR, 0L, ignored).decision);
        policy.types.add(RuleType.CONTINUOUS);
        policy.continuousLimitMillis = 60_000L;
        policy.interruptionPauseMillis = 30_000L;
        assertEquals(40_000L, evaluate(policy, ControlLevel.REPAIR, 20_000L, ignored).recheckAfterMs);
        assertEquals(InterceptionDecision.Trigger.CONTINUOUS,
                evaluate(policy, ControlLevel.REPAIR, 60_000L, ignored).decision.trigger);
        policy.types.add(RuleType.DAILY_LIMIT);
        policy.dailyLimitMillis = 60_000L;
        assertNull(evaluate(policy, ControlLevel.REPAIR, 0L, ignored).decision);
        assertEquals(60_000L, evaluate(policy, ControlLevel.REPAIR, 0L, ignored).recheckAfterMs);
        policy.types.remove(RuleType.WINDOW_BAN);
        InterceptionDecision changed = evaluateWithRevision(policy, ControlLevel.REPAIR,
                2L, 0L, ignored).decision;
        assertNotNull(changed);
        assertEquals(InterceptionDecision.Trigger.DAILY_LIMIT,
                changed.trigger);
    }

    /** 修心超过每日允许次数时，本次访问确认跳过禁用，不清零真实次数。 */
    @Test public void repairDailyCountContinuationPreservesActualUsage() {
        RuleGroup policy = new RuleGroup(1L, "count");
        policy.types.add(RuleType.DAILY_COUNT);
        policy.count = 1;
        UsageEvidence actual = new UsageEvidence(true, NOW, "Asia/Shanghai", 60_000L, 60_000L,
                2L, 0L, Collections.emptyMap());
        assertEquals(InterceptionDecision.Trigger.DAILY_COUNT,
                RuleEngine.evaluate(APP, "group:1", 1L, policy, ControlLevel.REPAIR, actual, 180_000L).decision.trigger);
        BlockContinuation ignored = new BlockContinuation(APP, "group:1", 1L, ControlLevel.REPAIR,
                InterceptionDecision.Trigger.DAILY_COUNT, "", "boot", NOW);
        assertNull(RuleEngine.evaluate(APP, "group:1", 1L, policy, ControlLevel.REPAIR,
                actual, 180_000L, 0, ignored::matches).decision);
        assertEquals(2L, actual.openCount);
        assertEquals(60_000L, actual.appForegroundMs);
    }

    /** 确认绑定实际完成挑战时的档位，切换档位不能继承另一档的确认。 */
    @Test public void continuationCannotCrossControlLevels() {
        for (ControlLevel level : new ControlLevel[]{ControlLevel.MORTAL, ControlLevel.REPAIR}) {
            BlockContinuation ignored = continuation(level);
            ControlLevel other = level == ControlLevel.MORTAL ? ControlLevel.REPAIR : ControlLevel.MORTAL;
            assertTrue(ignored.matches(decision(APP, 1L, "work", level)));
            assertFalse(ignored.matches(decision(APP, 1L, "work", other)));
            assertEquals(InterceptionDecision.Kind.BLOCK, evaluate(ban(), other, 0L, ignored).decision.kind);
        }
    }

    /** 闭关始终执行禁用，即使调用方错误地提供全匹配忽略策略。 */
    @Test public void sealKeepsItsBlockRules() {
        assertEquals(InterceptionDecision.Kind.BLOCK, RuleEngine.evaluate(APP, "group:1", 1L,
                ban(), ControlLevel.SEAL, evidence(0L), 180_000L, 0, block -> true).decision.kind);
    }

    /** 返回拦截应用、同包页面交接和第二次提醒返回均不成为下一次访问。 */
    @Test public void interceptionReturnsPreserveTheApplicationVisit() {
        UsageTimeline timeline = new UsageTimeline("zhijian");
        timeline.resumed(APP, "Main", 1_000L);
        timeline.resumed("zhijian", "github.vhoom.zhijian.ui.interception.InterceptionActivity", 2_000L);
        timeline.resumed(APP, "Main", 3_000L);
        timeline.resumed(APP, "Detail", 4_000L);
        timeline.resumed("zhijian", "github.vhoom.zhijian.ui.interception.InterceptionActivity", 5_000L);
        timeline.resumed(APP, "Detail", 6_000L);
        assertEquals(0L, timeline.openCount(APP, 1_001L, 6_000L));
        timeline.resumed("home", "Launcher", 7_000L);
        timeline.resumed(APP, "Main", 8_000L);
        assertEquals(1L, timeline.openCount(APP, 1_001L, 8_000L));
    }

    /** 锁屏后再进入应用属于下一次访问，应恢复原禁用检查。 */
    @Test public void screenOffThenReopeningStartsANewVisit() {
        UsageTimeline timeline = new UsageTimeline("zhijian");
        timeline.resumed(APP, "Main", 1_000L);
        timeline.screenChanged(false, 2_000L);
        timeline.screenChanged(true, 3_000L);
        timeline.resumed(APP, "Main", 4_000L);
        assertEquals(1L, timeline.openCount(APP, 1_001L, 4_000L));
    }

    /** @return 上午活动的独立时段禁用策略 */
    private static RuleGroup ban() {
        RuleGroup policy = new RuleGroup(1L, "test");
        policy.types.add(RuleType.WINDOW_BAN);
        policy.windows.add(new RuleWindow("work", RuleType.WINDOW_BAN, 540, 660, 0L));
        return policy;
    }

    /** @param continuous 真实连续用量 @return 已达全天额度的证据，忽略不得改写真实用量 */
    private static UsageEvidence evidence(long continuous) {
        return new UsageEvidence(true, NOW, "Asia/Shanghai", 60_000L, 60_000L, 1L,
                continuous, Collections.emptyMap());
    }

    /** @return 从时段禁用继续后保存的原应用本次访问确认 */
    private static BlockContinuation continuation() {
        return continuation(ControlLevel.MORTAL);
    }

    /** @param level 用户完成继续操作的档位 @return 仅属于该档位的规则确认 */
    private static BlockContinuation continuation(ControlLevel level) {
        return new BlockContinuation(APP, "group:1", 1L, level, InterceptionDecision.Trigger.WINDOW_BAN,
                "work", "boot", NOW);
    }

    /** @param app 目标应用 @param revision 配置版本 @param window 时段 @param level 强度 @return 待匹配禁用 */
    private static InterceptionDecision decision(String app, long revision, String window, ControlLevel level) {
        return new InterceptionDecision(app, "group:1", revision, InterceptionDecision.Kind.BLOCK,
                InterceptionDecision.Trigger.WINDOW_BAN, level, window, 1L, 1L, NOW + 60_000L, 180_000L, 0L);
    }

    /** @param policy 当前规则 @param level 强度 @param continuous 连续用量 @param ignored 本次忽略 @return 剩余规则结果 */
    private static InterceptionEvaluation evaluate(RuleGroup policy, ControlLevel level,
            long continuous, BlockContinuation ignored) {
        return evaluateWithRevision(policy, level, 1L, continuous, ignored);
    }

    /**
     * @param policy 当前规则
     * @param level 执行强度
     * @param revision 已提交配置版本，变化后旧访问确认失效
     * @param continuous 连续使用量，单位毫秒
     * @param ignored 原访问确认，不修改其身份或真实用量
     * @return 正式规则引擎对当前版本的计算结果
     */
    private static InterceptionEvaluation evaluateWithRevision(RuleGroup policy, ControlLevel level,
            long revision, long continuous, BlockContinuation ignored) {
        return RuleEngine.evaluate(APP, "group:1", revision, policy, level, evidence(continuous),
                180_000L, 0, ignored::matches);
    }
}
