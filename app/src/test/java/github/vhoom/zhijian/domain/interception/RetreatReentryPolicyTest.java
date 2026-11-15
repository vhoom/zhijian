package github.vhoom.zhijian.domain.interception;

import github.vhoom.zhijian.data.model.policy.ControlLevel;
import github.vhoom.zhijian.data.model.runtime.PendingInterception;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

/**
 * 验证闭关首轮、逐次独立随机、同组共享、复查去重及限制解除后的新一轮。
 *
 * 维护日期：2026年11月11日
 */
public class RetreatReentryPolicyTest {
    /** 首次只显示页面，后续第二次、第三次及更多访问各自使用一次随机选择。 */
    @Test public void firstEntryShowsPageAndLaterEntriesChooseIndependently() {
        AtomicInteger draws = new AtomicInteger();
        RetreatReentryPolicy policy = new RetreatReentryPolicy(() -> draws.getAndIncrement() % 2 == 0);
        PendingInterception rest = rest("a", "app:a", 1L, 1_000L, 1_000L);
        assertEquals(RetreatReentryPolicy.Action.SHOW_PAGE, policy.actionFor(rest, 1L));
        assertEquals(0, draws.get());
        assertEquals(RetreatReentryPolicy.Action.BACK_ONLY, policy.actionFor(rest, 3L));
        assertEquals(RetreatReentryPolicy.Action.BACK_AND_SHOW_PAGE, policy.actionFor(rest, 5L));
        assertEquals(RetreatReentryPolicy.Action.BACK_ONLY, policy.actionFor(rest, 7L));
        assertEquals(3, draws.get());
    }

    /** 同次前台访问的定时复查、页面会话重建及休息起点更新都不重抽随机动作。 */
    @Test public void repeatedChecksKeepTheCurrentVisitChoice() {
        AtomicInteger draws = new AtomicInteger();
        RetreatReentryPolicy policy = new RetreatReentryPolicy(() -> { draws.incrementAndGet(); return false; });
        policy.actionFor(rest("a", "app:a", 1L, 1_000L, 1_000L), 1L);
        PendingInterception reopened = rest("a", "app:a", 1L, 1_000L, 9_000L);
        for (int check = 0; check < 5; check++) {
            assertEquals(RetreatReentryPolicy.Action.BACK_AND_SHOW_PAGE, policy.actionFor(reopened, 3L));
        }
        assertEquals(1, draws.get());
    }

    /** 同组 A 首次触发后，B 和 A 的后续真实进入共享一轮，其他组独立。 */
    @Test public void groupMembersShareTheRoundAndOtherGroupsRemainIndependent() {
        RetreatReentryPolicy policy = new RetreatReentryPolicy(() -> true);
        assertEquals(RetreatReentryPolicy.Action.SHOW_PAGE,
                policy.actionFor(rest("a", "group:1", 1L, 1_000L, 1_000L), 1L));
        assertEquals(RetreatReentryPolicy.Action.BACK_ONLY,
                policy.actionFor(rest("b", "group:1", 1L, 1_000L, 3_000L), 3L));
        assertEquals(RetreatReentryPolicy.Action.SHOW_PAGE,
                policy.actionFor(rest("c", "group:2", 1L, 1_000L, 4_000L), 4L));
        assertEquals(RetreatReentryPolicy.Action.BACK_ONLY,
                policy.actionFor(rest("a", "group:1", 1L, 1_000L, 5_000L), 5L));
    }

    /** 独立应用策略和全局默认配置的不同目标不共享返回动作。 */
    @Test public void independentApplicationsAndGlobalTargetsHaveSeparateRounds() {
        RetreatReentryPolicy policy = new RetreatReentryPolicy(() -> true);
        policy.actionFor(rest("a", "group:1", 1L, 1_000L, 1_000L), 1L);
        assertEquals(RetreatReentryPolicy.Action.SHOW_PAGE,
                policy.actionFor(rest("a", "app:a", 1L, 1_000L, 2_000L), 2L));
        assertEquals(RetreatReentryPolicy.Action.SHOW_PAGE,
                policy.actionFor(rest("b", "app:b", 1L, 1_000L, 3_000L), 3L));
        policy.actionFor(rest("c", "global", 1L, 1_000L, 4_000L), 4L);
        assertEquals(RetreatReentryPolicy.Action.SHOW_PAGE,
                policy.actionFor(rest("d", "global", 1L, 1_000L, 5_000L), 5L));
    }

    /** 同组禁用在各应用上首次保存的时刻不同，仍按同一恢复边界共享。 */
    @Test public void groupBansShareTheirRecoveryBoundaryRatherThanPresentationTime() {
        RetreatReentryPolicy policy = new RetreatReentryPolicy(() -> false);
        assertEquals(RetreatReentryPolicy.Action.SHOW_PAGE,
                policy.actionFor(ban("a", 1L, "work", 60_000L, 1_000L), 1L));
        assertEquals(RetreatReentryPolicy.Action.BACK_AND_SHOW_PAGE,
                policy.actionFor(ban("b", 1L, "work", 60_000L, 5_000L), 3L));
        assertEquals(RetreatReentryPolicy.Action.SHOW_PAGE,
                policy.actionFor(ban("a", 1L, "work", 120_000L, 61_000L), 5L));
    }

    /** 休息完成后下一次提醒的原触发身份不同，重新从首次正常显示开始。 */
    @Test public void aNewRestObligationStartsWithTheNormalPage() {
        RetreatReentryPolicy policy = new RetreatReentryPolicy(() -> true);
        policy.actionFor(rest("a", "group:1", 1L, 1_000L, 1_000L), 1L);
        policy.actionFor(rest("b", "group:1", 1L, 1_000L, 3_000L), 3L);
        assertEquals(RetreatReentryPolicy.Action.SHOW_PAGE,
                policy.actionFor(rest("a", "group:1", 1L, 10_000L, 10_000L), 5L));
    }

    /** 规则解除清除整组动作，另一组和独立应用的当前轮次仍保留。 */
    @Test public void clearingOneGroupDoesNotResetOtherScopes() {
        RetreatReentryPolicy policy = new RetreatReentryPolicy(() -> true);
        policy.actionFor(rest("a", "group:1", 1L, 1_000L, 1_000L), 1L);
        policy.actionFor(rest("c", "group:2", 1L, 1_000L, 2_000L), 2L);
        policy.clear("group:1", "b");
        assertEquals(RetreatReentryPolicy.Action.SHOW_PAGE,
                policy.actionFor(rest("b", "group:1", 1L, 1_000L, 3_000L), 3L));
        assertEquals(RetreatReentryPolicy.Action.BACK_ONLY,
                policy.actionFor(rest("c", "group:2", 1L, 1_000L, 4_000L), 4L));
    }

    /** 配置版本或禁用窗口改变时，新命中规则不继承旧轮的返回动作。 */
    @Test public void changedRevisionOrRuleStartsANewRound() {
        RetreatReentryPolicy policy = new RetreatReentryPolicy(() -> true);
        policy.actionFor(ban("a", 1L, "work", 60_000L, 1_000L), 1L);
        policy.actionFor(ban("a", 1L, "work", 60_000L, 3_000L), 3L);
        assertEquals(RetreatReentryPolicy.Action.SHOW_PAGE,
                policy.actionFor(ban("a", 2L, "work", 60_000L, 4_000L), 4L));
        assertEquals(RetreatReentryPolicy.Action.SHOW_PAGE,
                policy.actionFor(ban("a", 2L, "next", 60_000L, 5_000L), 5L));
    }

    /** 无为、修心不执行闭关随机返回，再切回闭关时重新正常显示首次页面。 */
    @Test public void otherLevelsKeepTheirNormalInterceptionFlow() {
        AtomicInteger draws = new AtomicInteger();
        RetreatReentryPolicy policy = new RetreatReentryPolicy(() -> { draws.incrementAndGet(); return true; });
        PendingInterception sealed = rest("a", "group:1", 1L, 1_000L, 1_000L);
        policy.actionFor(sealed, 1L);
        for (ControlLevel level : new ControlLevel[]{ControlLevel.MORTAL, ControlLevel.REPAIR}) {
            InterceptionDecision old = sealed.decision;
            InterceptionDecision decision = new InterceptionDecision(old.packageName, old.policyId, old.policyRevision,
                    old.kind, old.trigger, level, old.windowId, old.currentValue, old.limitValue, old.resetAtMs,
                    old.passDurationMs, old.minimumInterruptionPauseDurationMs);
            assertEquals(RetreatReentryPolicy.Action.SHOW_PAGE,
                    policy.actionFor(new PendingInterception(decision, "other", "boot", 2_000L, 2_000L), 2L));
        }
        assertEquals(RetreatReentryPolicy.Action.SHOW_PAGE, policy.actionFor(sealed, 3L));
        assertEquals(0, draws.get());
    }

    /** @param pkg 目标 @param scope 生效策略 @param revision 修订 @param occurred 原触发 @param started 当前休息起点
     * @return 原触发稳定、页面会话和起点可变化的休息事实 */
    private static PendingInterception rest(String pkg, String scope, long revision, long occurred, long started) {
        InterceptionDecision decision = new InterceptionDecision(pkg, scope, revision, InterceptionDecision.Kind.INTERRUPT,
                InterceptionDecision.Trigger.CONTINUOUS, ControlLevel.SEAL, "", 60_000L, 60_000L,
                0L, 180_000L, 120_001L);
        return new PendingInterception(decision, pkg + started, "boot", started, started, 0L, occurred);
    }

    /** @param pkg 目标 @param revision 配置修订 @param window 时段身份 @param reset 恢复边界 @param started 本应用触发时刻
     * @return 同组独立保存、规则恢复边界共享的禁用事实 */
    private static PendingInterception ban(String pkg, long revision, String window, long reset, long started) {
        InterceptionDecision decision = new InterceptionDecision(pkg, "group:1", revision, InterceptionDecision.Kind.BLOCK,
                InterceptionDecision.Trigger.WINDOW_BAN, ControlLevel.SEAL, window, 1L, 1L, reset, 180_000L, 0L);
        return new PendingInterception(decision, pkg + started, "boot", started, started);
    }
}
