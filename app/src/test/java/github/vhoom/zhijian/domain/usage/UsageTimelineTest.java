package github.vhoom.zhijian.domain.usage;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * 回放恢复、休息、锁屏与延迟停止事件，验证首页与规则共用的用量口径。
 *
 * 维护日期：2026年11月14日
 */
public class UsageTimelineTest {
    /** 限次快照复用真实打开口径：页面交接与拦截返回不重复计数，锁屏后返回计数。 */
    @Test
    public void openingSnapshotsKeepRealVisitRulesAndRemainIndependent() {
        UsageTimeline timeline = new UsageTimeline("zhijian");
        timeline.resumed("target", "First", 1_000L);
        timeline.paused("target", "First", 2_000L);
        timeline.resumed("target", "Second", 2_010L);
        timeline.resumed("zhijian", "Interception", 3_000L);
        timeline.resumed("target", "Second", 4_000L);
        timeline.screenChanged(false, 5_000L);
        timeline.screenChanged(true, 6_000L);
        timeline.resumed("target", "Main", 7_000L);
        OpenCountSnapshot snapshot = timeline.openingSnapshot("target"::equals, 0L, 8_000L);
        assertEquals(2L, snapshot.count());
        assertEquals(timeline.openCount("target", 0L, 8_000L), snapshot.count());
        timeline.resumed("home", "Home", 9_000L);
        timeline.resumed("target", "Main", 10_000L);
        assertEquals(2L, snapshot.count());
        assertEquals(3L, timeline.openingSnapshot("target"::equals, 0L, 11_000L).count());
    }

    /** 共享范围只计实际组员，统计起止端点的打开均包含，范围外记录不消耗该窗口额度。 */
    @Test
    public void openingSnapshotsClipMembershipAndBothTimeEndpoints() {
        UsageTimeline timeline = new UsageTimeline("zhijian");
        timeline.resumed("a", "Main", 1_000L);
        timeline.resumed("outside", "Main", 2_000L);
        timeline.resumed("b", "Main", 3_000L);
        timeline.resumed("outside", "Main", 4_000L);
        timeline.resumed("a", "Main", 5_000L);
        timeline.resumed("outside", "Main", 6_000L);
        timeline.resumed("b", "Main", 7_000L);
        java.util.function.Predicate<String> group = pkg -> pkg.equals("a") || pkg.equals("b");
        assertEquals(2L, timeline.openingSnapshot(group, 3_000L, 5_000L).count());
        assertEquals(1L, timeline.openingSnapshot("a"::equals, 3_000L, 5_000L).count());
        assertEquals(4L, timeline.openingSnapshot(group, 0L, 8_000L).count());
    }

    /** 共享时段跨午夜、每日恢复边界裁剪时，两个应用只计各自在范围内的用量。 */
    @Test public void sharedUsageClipsOvernightAndDailyResetBoundaries() {
        UsageTimeline timeline = new UsageTimeline("zhijian");
        long midnight = 86_400_000L;
        timeline.resumed("a", "Main", midnight - 5_000L);
        timeline.resumed("b", "Main", midnight + 2_000L);
        timeline.resumed("outside", "Main", midnight + 8_000L);
        java.util.function.Predicate<String> group = pkg -> pkg.equals("a") || pkg.equals("b");
        assertEquals(10_000L, timeline.foregroundMillis(group, midnight - 3_000L, midnight + 7_000L));
        assertEquals(8_000L, timeline.foregroundMillis(group, midnight, midnight + 20_000L));
        assertEquals(1L, timeline.openCount(group, midnight, midnight + 20_000L));
    }

    /** 桌面上的短暂停留也是真实重开，不能套用 Activity 交接的次数豁免。 */
    @Test public void rapidHomeReentryStillConsumesSharedOpenCount() {
        UsageTimeline timeline = new UsageTimeline("zhijian");
        timeline.resumed("target", "Main", 1_000L);
        timeline.resumed("home", "Home", 2_000L);
        timeline.resumed("target", "Main", 2_100L);
        assertEquals(2L, timeline.openCount(pkg -> pkg.equals("target"), 0L, 3_000L));
    }

    /** 真正退出后重新打开同应用需要增加打开次数，Activity 交接仍只算一次。 */
    @Test public void reopeningSamePackageCountsAnotherOpen() {
        UsageTimeline timeline = new UsageTimeline("zhijian");
        timeline.resumed("target", "Main", 1_000L);
        timeline.paused("target", "Main", 5_000L);
        timeline.resumed("target", "Main", 10_000L);
        assertEquals(2L, timeline.openCount("target", 0L, 11_000L));
    }

    /** 止间的设置页不能成为限次豁免跳板，仅实际拦截返回原目标免于重复计次。 */
    @Test public void openingZhijianSettingsDoesNotExemptNextOpen() {
        UsageTimeline timeline = new UsageTimeline("zhijian");
        timeline.resumed("target", "Main", 1_000L);
        timeline.resumed("zhijian", "Settings", 5_000L);
        timeline.resumed("target", "Main", 10_000L);
        assertEquals(2L, timeline.openCount("target", 0L, 11_000L));
    }

    /** 迟到事件和负时间不得回退游标并生成虚假的重叠用量。 */
    @Test public void outOfOrderEventsCannotCreateOverlappingUsage() {
        UsageTimeline timeline = new UsageTimeline("zhijian");
        timeline.resumed("target", "Main", 1_000L);
        timeline.resumed("other", "Main", 5_000L);
        timeline.resumed("target", "Main", 2_000L);
        timeline.screenChanged(false, -1L);
        assertEquals(4_000L, timeline.foregroundMillis("target", 0L, 10_000L));
        assertEquals(5_000L, timeline.foregroundMillis("other", 0L, 10_000L));
    }
    /** 回放实测同名 Activity 的实例交接，旧实例延迟 STOPPED 不得结算新实例。 */
    @Test
    public void delayedStopOfSameClassDoesNotEndNewInstance() {
        UsageTimeline timeline = new UsageTimeline("zhijian");
        timeline.activityChanged(UsageTimeline.ActivityEvent.RESUMED, "target", "Main", 1_000L);
        timeline.activityChanged(UsageTimeline.ActivityEvent.PAUSED, "target", "Main", 5_000L);
        timeline.activityChanged(UsageTimeline.ActivityEvent.RESUMED, "target", "Main", 5_010L);
        timeline.activityChanged(UsageTimeline.ActivityEvent.STOPPED, "target", "Main", 6_000L);
        assertEquals(10_000L, timeline.continuousMillis("target", 11_000L, 0L));
        assertEquals(9_990L, timeline.foregroundMillis("target", 0L, 11_000L));
        timeline.activityChanged(UsageTimeline.ActivityEvent.PAUSED, "target", "Main", 11_000L);
        assertEquals(0L, timeline.continuousMillis("target", 12_000L, 0L));
    }
    /** 同应用内部页面先暂停再恢复时，不得重置连续使用限制。 */
    @Test
    public void activityHandoffPreservesContinuousUsage() {
        UsageTimeline timeline = new UsageTimeline("zhijian");
        timeline.resumed("target", "First", 1_000L);
        timeline.paused("target", "First", 5_000L);
        timeline.resumed("target", "Second", 5_010L);
        timeline.paused("target", "First", 5_100L);
        assertEquals(10_000L, timeline.continuousMillis("target", 11_000L, 0L));
        assertEquals(9_990L, timeline.foregroundMillis("target", 0L, 11_000L));
    }
    /** 从 止间返回只算目标应用前台，连续时间从恢复起点开始，打开次数不增加。 */
    @Test
    public void interruptionReturnExcludesRestAndRestartsContinuousUsage() {
        UsageTimeline timeline = new UsageTimeline("zhijian");
        timeline.resumed("target", "Main", 1_000L);
        timeline.paused("target", "Main", 61_000L);
        timeline.resumed("zhijian", "Interception", 61_000L);
        timeline.paused("zhijian", "Interception", 121_000L);
        timeline.resumed("target", "Main", 121_000L);
        assertEquals(90_000L, timeline.foregroundMillis("target", 0L, 151_000L));
        assertEquals(30_000L, timeline.continuousMillis("target", 151_000L, 121_000L));
        assertEquals(1L, timeline.openCount("target", 0L, 151_000L));
    }

    /** 重复前台事件不能覆盖已有起点或丢掉已用时长。 */
    @Test
    public void duplicateResumePreservesUsageStart() {
        UsageTimeline timeline = new UsageTimeline("zhijian");
        timeline.resumed("target", "Main", 1_000L);
        timeline.resumed("target", "Main", 5_000L);
        timeline.paused("target", "Main", 11_000L);
        assertEquals(10_000L, timeline.foregroundMillis("target", 0L, 20_000L));
        assertEquals(0L, timeline.continuousMillis("target", 20_000L, 0L));
    }

    /** 旧 Activity 的停止事件不能结束已经恢复的新页面。 */
    @Test
    public void delayedStopDoesNotEndNewActivity() {
        UsageTimeline timeline = new UsageTimeline("zhijian");
        timeline.resumed("target", "First", 1_000L);
        timeline.resumed("target", "Second", 5_000L);
        timeline.paused("target", "First", 6_000L);
        assertEquals(10_000L, timeline.continuousMillis("target", 11_000L, 0L));
        timeline.paused("target", "Second", 11_000L);
        assertEquals(10_000L, timeline.foregroundMillis("target", 0L, 20_000L));
    }

    /** 灭屏缺少应用暂停事件时也要结束计时，待机时间不算用量。 */
    @Test
    public void screenOffEndsAppUsageEvenWithoutPause() {
        UsageTimeline timeline = new UsageTimeline("zhijian");
        timeline.screenChanged(true, 500L);
        timeline.resumed("target", "Main", 1_000L);
        timeline.screenChanged(true, 2_000L);
        timeline.screenChanged(false, 6_000L);
        assertEquals(5_000L, timeline.foregroundMillis("target", 0L, 20_000L));
        assertEquals(5_500L, timeline.screenMillis(0L, 20_000L));
        assertEquals(0L, timeline.continuousMillis("target", 20_000L, 0L));
    }

    /** 暂停后没有新恢复事件时不应继续累加到当前时间。 */
    @Test
    public void pauseStopsUsageWithoutAnotherAppResume() {
        UsageTimeline timeline = new UsageTimeline("zhijian");
        timeline.resumed("target", "Main", 1_000L);
        timeline.paused("target", "Main", 5_000L);
        assertEquals(4_000L, timeline.foregroundMillis("target", 0L, 20_000L));
    }

    /** 查询日界线和时段额度时，跨边界会话只计区间内的部分。 */
    @Test
    public void windowUsageClipsSessionsToInterval() {
        UsageTimeline timeline = new UsageTimeline("zhijian");
        timeline.resumed("target", "Main", 1_000L);
        timeline.paused("target", "Main", 11_000L);
        assertEquals(3_000L, timeline.foregroundMillis("target", 5_000L, 8_000L));
        assertEquals(0L, timeline.foregroundMillis("target", 12_000L, 15_000L));
    }

    /** 持久化起点在每次检查都生效，持续推进时间时不能跳回休息前。 */
    @Test
    public void resumeFloorRemainsStableAcrossChecks() {
        UsageTimeline timeline = new UsageTimeline("zhijian");
        timeline.resumed("target", "Main", 1_000L);
        assertEquals(1_000L, timeline.continuousMillis("target", 11_000L, 10_000L));
        assertEquals(2_000L, timeline.continuousMillis("target", 12_000L, 10_000L));
        assertEquals(11_000L, timeline.foregroundMillis("target", 0L, 12_000L));
    }
}
