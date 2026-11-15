package github.vhoom.zhijian.domain.usage;

import org.junit.Test;
import java.util.function.Predicate;
import static org.junit.Assert.*;

/**
 * 使用真实区间重放周期，覆盖统一离组休息、单应用两分钟切换及双时钟边界。
 *
 * 维护日期：2026年11月14日
 */
public class ContinuousUsageCycleTest {
    private static final Predicate<String> GROUP = pkg -> pkg.equals("a") || pkg.equals("b");

    /** @return 固定时钟下的空周期 */
    private static ContinuousUsageCycle empty() {
        return new ContinuousUsageCycle("boot", 1_000L, 1_000L, 0L, 0L, null, "", 0L);
    }

    /** 退出重开只暂停，不清零；离组期间使用其他应用不算本组使用。 */
    @Test public void reopeningAfterShortRestKeepsAccumulatedUsage() {
        UsageTimeline timeline = new UsageTimeline("zhijian");
        timeline.resumed("a", "Main", 1_000L);
        timeline.resumed("other", "Main", 61_000L);
        timeline.resumed("a", "Main", 121_000L);
        ContinuousUsageCycle result = empty().advance(timeline, GROUP, 151_000L, 151_000L, 600_000L);
        assertEquals(90_000L, result.usedMs);
        assertEquals(0L, result.restMs);
        assertEquals(0L, result.generation);
    }

    /** 同组 A/B 直接交接共用使用量，不产生休息。 */
    @Test public void switchingWithinGroupSharesOneUsageCycle() {
        UsageTimeline timeline = new UsageTimeline("zhijian");
        timeline.resumed("a", "Main", 1_000L);
        timeline.resumed("b", "Main", 31_000L);
        ContinuousUsageCycle result = empty().advance(timeline, GROUP, 61_000L, 61_000L, 600_000L);
        assertEquals(60_000L, result.usedMs);
        assertEquals(0L, result.restMs);
    }

    /** 外组应用使用满休息时间后，本组开启新周期；达到阈值恰好清零。 */
    @Test public void usingAnotherGroupForFullRestStartsNewCycle() {
        UsageTimeline timeline = new UsageTimeline("zhijian");
        timeline.resumed("a", "Main", 1_000L);
        timeline.resumed("other", "Main", 61_000L);
        timeline.resumed("b", "Main", 661_000L);
        ContinuousUsageCycle result = empty().advance(timeline, GROUP, 691_000L, 691_000L, 600_000L);
        assertEquals(30_000L, result.usedMs);
        assertEquals(1L, result.generation);
    }

    /** 不同组的周期彼此独立，A 休息期间 B 可以正常累计自己的使用。 */
    @Test public void unrelatedGroupsHaveIndependentCounters() {
        UsageTimeline timeline = new UsageTimeline("zhijian");
        timeline.resumed("a", "Main", 1_000L);
        timeline.resumed("b", "Main", 61_000L);
        ContinuousUsageCycle a = empty().advance(timeline, pkg -> pkg.equals("a"), 661_000L, 661_000L, 600_000L);
        ContinuousUsageCycle b = empty().advance(timeline, pkg -> pkg.equals("b"), 661_000L, 661_000L, 600_000L);
        assertEquals(0L, a.usedMs);
        assertEquals(600_000L, b.usedMs);
    }

    /** 锁屏不足休息要求时保留用量；后续再离组必须重新完成连续休息。 */
    @Test public void returningEarlyInterruptsRestRatherThanCombiningShortBreaks() {
        UsageTimeline timeline = new UsageTimeline("zhijian");
        timeline.resumed("a", "Main", 1_000L);
        timeline.screenChanged(false, 61_000L);
        timeline.resumed("b", "Main", 361_000L);
        timeline.screenChanged(false, 362_000L);
        ContinuousUsageCycle result = empty().advance(timeline, GROUP, 662_000L, 662_000L, 600_000L);
        assertEquals(61_000L, result.usedMs);
        assertEquals(300_000L, result.restMs);
        assertEquals(0L, result.generation);
    }

    /** 保存后以同一边界增量恢复，不丢用量，也不重复计算已处理区间。 */
    @Test public void restoredCheckpointProcessesOnlyNewUsage() {
        UsageTimeline first = new UsageTimeline("zhijian");
        first.resumed("a", "Main", 1_000L);
        ContinuousUsageCycle saved = empty().advance(first, GROUP, 61_000L, 61_000L, 600_000L);
        ContinuousUsageCycle restored = new ContinuousUsageCycle(saved.bootId, saved.wallMs,
                saved.elapsedMs, saved.usedMs, saved.restMs, saved.foregroundPackage, saved.resumeId, saved.generation);
        UsageTimeline next = new UsageTimeline("zhijian");
        next.resumed(restored.foregroundPackage, null, restored.wallMs);
        next.resumed("b", "Main", 71_000L);
        assertEquals(90_000L, restored.advance(next, GROUP, 91_000L, 91_000L, 600_000L).usedMs);
    }

    /** 连续休息跨进程检查累加，重复检查同一时间不会重复开启周期。 */
    @Test public void fullRestAcrossSamplesResetsExactlyOnce() {
        ContinuousUsageCycle saved = new ContinuousUsageCycle("boot", 61_000L, 61_000L,
                60_000L, 0L, null, "", 0L);
        ContinuousUsageCycle half = saved.advance(new UsageTimeline("zhijian"), GROUP, 361_000L, 361_000L, 600_000L);
        ContinuousUsageCycle full = half.advance(new UsageTimeline("zhijian"), GROUP, 661_000L, 661_000L, 600_000L);
        assertEquals(0L, full.usedMs);
        assertEquals(1L, full.generation);
        assertEquals(1L, full.advance(new UsageTimeline("zhijian"), GROUP, 661_000L, 661_000L, 600_000L).generation);
    }

    /** 改墙钟不能把五秒实际使用变成足额休息或丢失已用量。 */
    @Test public void wallClockJumpUsesMonotonicUsageIncrement() {
        ContinuousUsageCycle saved = new ContinuousUsageCycle("boot", 61_000L, 61_000L,
                60_000L, 0L, "a", "", 0L);
        UsageTimeline future = new UsageTimeline("zhijian");
        future.resumed("a", null, 9_000_000L);
        assertEquals(65_000L, saved.advance(future, GROUP, 9_000_000L, 66_000L, 600_000L).usedMs);
        UsageTimeline past = new UsageTimeline("zhijian");
        past.resumed("a", null, 1_000L);
        assertEquals(65_000L, saved.advance(past, GROUP, 1_000L, 66_000L, 600_000L).usedMs);
    }

    /** 日期前移不能缩短离组休息；只有真实单调增量被计入。 */
    @Test public void wallClockJumpDoesNotCompleteRest() {
        ContinuousUsageCycle saved = new ContinuousUsageCycle("boot", 61_000L, 61_000L,
                60_000L, 0L, null, "", 0L);
        ContinuousUsageCycle result = saved.advance(new UsageTimeline("zhijian"), GROUP,
                9_000_000L, 66_000L, 600_000L);
        assertEquals(60_000L, result.usedMs);
        assertEquals(5_000L, result.restMs);
    }

    /** 跨开机保留用量，不把不可验证的关机墙钟当作已休息。 */
    @Test public void rebasedBootRetainsUsageAndVerifiedRest() {
        ContinuousUsageCycle rebased = new ContinuousUsageCycle("new-boot", 9_000_000L, 0L,
                60_000L, 1_000L, null, "", 0L);
        ContinuousUsageCycle result = rebased.advance(new UsageTimeline("zhijian"), GROUP,
                9_005_000L, 5_000L, 600_000L);
        assertEquals(60_000L, result.usedMs);
        assertEquals(6_000L, result.restMs);
    }

    /** 三档共用的离组策略允许使用其他应用完成本组休息，不把其用量累加给原组。 */
    @Test public void usingAnotherApplicationCompletesGroupRest() {
        UsageTimeline timeline = new UsageTimeline("zhijian");
        timeline.resumed("a", "Main", 1_000L);
        timeline.resumed("other", "Main", 61_000L);
        ContinuousUsageCycle result = groupCycle(empty(), timeline, 661_000L, Long.MAX_VALUE);
        assertEquals(0L, result.usedMs);
        assertEquals(600_000L, result.restMs);
        assertEquals(1L, result.generation);
    }

    /** 桌面、止间休息页面和锁屏连续衔接，不要求必须熄屏。 */
    @Test public void desktopRestPageAndLockScreenShareOneContinuousRest() {
        UsageTimeline timeline = new UsageTimeline("zhijian");
        timeline.resumed("a", "Main", 1_000L);
        timeline.resumed("home", "Launcher", 61_000L);
        timeline.resumed("zhijian", "github.vhoom.zhijian.ui.interception.InterceptionActivity", 261_000L);
        timeline.screenChanged(false, 461_000L);
        ContinuousUsageCycle near = groupCycle(empty(), timeline, 660_000L, Long.MAX_VALUE);
        assertEquals(599_000L, near.restMs);
        assertEquals(60_000L, near.usedMs);
        ContinuousUsageCycle full = groupCycle(empty(), timeline, 661_000L, Long.MAX_VALUE);
        assertEquals(600_000L, full.restMs);
        assertEquals(0L, full.usedMs);
        assertEquals(1L, full.generation);
    }

    /** 止间休息页切到设置仍是离组休息；同包页面交接不增加打开次数。 */
    @Test public void zhijianSettingsContinueTheSameRest() {
        UsageTimeline timeline = new UsageTimeline("zhijian");
        timeline.resumed("a", "Main", 1_000L);
        timeline.resumed("zhijian", "github.vhoom.zhijian.ui.interception.InterceptionActivity", 61_000L);
        timeline.resumed("zhijian", "github.vhoom.zhijian.ui.activity.SettingsActivity", 361_000L);
        ContinuousUsageCycle result = groupCycle(empty(), timeline, 661_000L, Long.MAX_VALUE);
        assertEquals(0L, result.usedMs);
        assertEquals(600_000L, result.restMs);
        assertEquals(1L, result.generation);
        assertEquals(1L, timeline.openCount("zhijian", 0L, 661_000L));
        assertEquals(600_000L, timeline.foregroundMillis("zhijian", 0L, 661_000L));
    }

    /** 已保存的休息与使用在继续结算时保留，零增量不改变代次。 */
    @Test public void continuingGroupRestKeepsVerifiedProgressAtTheBoundary() {
        UsageTimeline prior = new UsageTimeline("zhijian");
        prior.resumed("a", "Main", 1_000L);
        prior.resumed("home", "Launcher", 61_000L);
        ContinuousUsageCycle saved = empty().advance(prior, GROUP, 361_000L, 361_000L, 600_000L);
        UsageTimeline after = new UsageTimeline("zhijian");
        after.resumed(saved.foregroundPackage, saved.foregroundClass, saved.wallMs);
        ContinuousUsageCycle unchanged = groupCycle(saved, after, 361_000L, Long.MAX_VALUE);
        assertEquals(saved.usedMs, unchanged.usedMs);
        assertEquals(saved.restMs, unchanged.restMs);
        assertEquals(saved.generation, unchanged.generation);
        assertEquals(0L, groupCycle(unchanged, after, 661_000L, Long.MAX_VALUE).usedMs);
    }

    /** 结束后的使用不能补进旧窗口；完成休息后复查也不能重新建立旧用量。 */
    @Test public void endedWindowCapsUsageAndCannotRetriggerAfterRest() {
        UsageTimeline timeline = new UsageTimeline("zhijian");
        timeline.resumed("a", "Main", 1_000L);
        ContinuousUsageCycle ended = groupCycle(empty(), timeline, 121_000L, 61_000L);
        assertEquals(60_000L, ended.usedMs);
        UsageTimeline rest = new UsageTimeline("zhijian");
        rest.resumed("home", "Launcher", 121_000L);
        ContinuousUsageCycle complete = groupCycle(ended, rest, 721_000L, 61_000L);
        assertEquals(0L, complete.usedMs);
        UsageTimeline reopened = new UsageTimeline("zhijian");
        reopened.resumed("a", "Main", 721_000L);
        ContinuousUsageCycle later = groupCycle(complete, reopened, 841_000L, 61_000L);
        assertEquals(0L, later.usedMs);
        assertEquals(complete.generation, later.generation);
    }

    /** 规则组及全局仍按原配置休息，不套用独立应用的两分钟容错。 */
    @Test public void groupAndGlobalKeepConfiguredRestDuration() {
        for (String policyId : new String[]{"group:1", "global"}) {
            UsageTimeline timeline = new UsageTimeline("zhijian");
            timeline.resumed("a", "Main", 1_000L);
            timeline.resumed("other", "Main", 61_000L);
            ContinuousUsageCycle result = empty().advance(timeline, GROUP,
                    ContinuousRestPolicy.forScope(GROUP), 121_000L, 121_000L,
                    ContinuousRestPolicy.requiredRestMillis(policyId, 60_000L), Long.MAX_VALUE);
            assertEquals(0L, result.usedMs);
            assertEquals(60_000L, result.restMs);
            assertEquals(1L, result.generation);
        }
    }

    /** 单应用离开一分钟、接近两分钟或恰好两分钟都保留累计，离开时间不算使用。 */
    @Test public void singleApplicationKeepsUsageThroughTwoMinuteSwitches() {
        for (long restMs : new long[]{60_000L, 120_000L, 600_000L}) {
            for (long awayMs : new long[]{60_000L, 119_999L, 120_000L}) {
                ContinuousUsageCycle result = singleAppAfterSwitch(awayMs, restMs);
                assertEquals(90_000L, result.usedMs);
                assertEquals(0L, result.restMs);
                assertEquals(0L, result.generation);
            }
        }
    }

    /** 配置休息不足或等于两分钟时，超过两分钟一毫秒才开始新周期。 */
    @Test public void singleApplicationStartsNewCycleOnlyAfterTwoMinutes() {
        for (long restMs : new long[]{60_000L, 120_000L}) {
            ContinuousUsageCycle result = singleAppAfterSwitch(120_001L, restMs);
            assertEquals(30_000L, result.usedMs);
            assertEquals(1L, result.generation);
        }
    }

    /** 更长的休息要求继续生效，满足配置的精确边界才清零。 */
    @Test public void singleApplicationWaitsForLongerConfiguredRest() {
        for (long awayMs : new long[]{120_001L, 599_999L}) {
            assertEquals(90_000L, singleAppAfterSwitch(awayMs, 600_000L).usedMs);
        }
        ContinuousUsageCycle full = singleAppAfterSwitch(600_000L, 600_000L);
        assertEquals(30_000L, full.usedMs);
        assertEquals(1L, full.generation);
    }

    /** 桌面和锁屏的短暂离开也保留单应用累计，不要求必须切换到另一个应用。 */
    @Test public void singleApplicationKeepsUsageDuringShortDesktopAndScreenOffBreaks() {
        for (boolean screenOff : new boolean[]{false, true}) {
            UsageTimeline timeline = new UsageTimeline("zhijian");
            timeline.resumed("a", "Main", 1_000L);
            if (screenOff) timeline.screenChanged(false, 61_000L);
            else timeline.resumed("home", "Launcher", 61_000L);
            timeline.resumed("a", "Main", 181_000L);
            ContinuousUsageCycle result = empty().advance(timeline, "a"::equals,
                    ContinuousRestPolicy.forScope("a"::equals), 211_000L, 211_000L,
                    ContinuousRestPolicy.requiredRestMillis("app:a", 60_000L), Long.MAX_VALUE);
            assertEquals(90_000L, result.usedMs);
            assertEquals(0L, result.generation);
        }
    }

    /** 两分钟容错跨检查点结算保持有效，达到边界不会提前开启代次。 */
    @Test public void singleApplicationGraceSurvivesCheckpointRestoration() {
        long required = ContinuousRestPolicy.requiredRestMillis("app:a", 60_000L);
        ContinuousUsageCycle saved = new ContinuousUsageCycle("boot", 61_000L, 61_000L,
                60_000L, 0L, "other", "Main", "", 0L);
        UsageTimeline first = new UsageTimeline("zhijian");
        first.resumed("other", "Main", 61_000L);
        ContinuousUsageCycle half = saved.advance(first, "a"::equals,
                ContinuousRestPolicy.forScope("a"::equals), 121_000L, 121_000L, required, Long.MAX_VALUE);
        assertEquals(60_000L, half.usedMs);
        assertEquals(60_000L, half.restMs);
        UsageTimeline next = new UsageTimeline("zhijian");
        next.resumed(half.foregroundPackage, half.foregroundClass, half.wallMs);
        next.resumed("a", "Main", 181_000L);
        ContinuousUsageCycle result = half.advance(next, "a"::equals,
                ContinuousRestPolicy.forScope("a"::equals), 211_000L, 211_000L, required, Long.MAX_VALUE);
        assertEquals(90_000L, result.usedMs);
        assertEquals(0L, result.generation);
    }

    /** 多次短暂离开中间使用该应用会重新计算休息，不能拼成足额休息。 */
    @Test public void singleApplicationDoesNotCombineSeparateShortSwitches() {
        UsageTimeline timeline = new UsageTimeline("zhijian");
        timeline.resumed("a", "Main", 1_000L);
        timeline.resumed("other", "Main", 61_000L);
        timeline.resumed("a", "Main", 151_000L);
        timeline.resumed("other", "Main", 181_000L);
        timeline.resumed("a", "Main", 271_000L);
        ContinuousUsageCycle result = empty().advance(timeline, "a"::equals,
                ContinuousRestPolicy.forScope("a"::equals), 301_000L, 301_000L,
                ContinuousRestPolicy.requiredRestMillis("app:a", 60_000L), Long.MAX_VALUE);
        assertEquals(120_000L, result.usedMs);
        assertEquals(0L, result.generation);
    }

    /** @param awayMs 离开独立应用的连续时长 @param restMs 原配置休息 @return 返回并再使用三十秒的周期 */
    private static ContinuousUsageCycle singleAppAfterSwitch(long awayMs, long restMs) {
        UsageTimeline timeline = new UsageTimeline("zhijian");
        timeline.resumed("a", "Main", 1_000L);
        timeline.resumed("other", "Main", 61_000L);
        timeline.resumed("a", "Main", 61_000L + awayMs);
        long now = 91_000L + awayMs;
        return empty().advance(timeline, "a"::equals, ContinuousRestPolicy.forScope("a"::equals),
                now, now, ContinuousRestPolicy.requiredRestMillis("app:a", restMs), Long.MAX_VALUE);
    }

    /** @param old 已保存周期 @param timeline 真实事件 @param now 同步双时钟 @param end 使用终点
     * @return 固定十分钟休息要求的共享规则组周期 */
    private static ContinuousUsageCycle groupCycle(ContinuousUsageCycle old, UsageTimeline timeline, long now, long end) {
        return old.advance(timeline, GROUP,
                ContinuousRestPolicy.forScope(GROUP),
                now, now, 600_000L, end);
    }
}
