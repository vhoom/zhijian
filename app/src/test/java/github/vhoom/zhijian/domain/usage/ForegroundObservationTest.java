package github.vhoom.zhijian.domain.usage;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

/**
 * 用真实周期转移验证过期窗口观察不会把锁屏休息变成使用。
 * 维护日期：2026年11月14日
 */
public class ForegroundObservationTest {
    /** 跨次查询保留已验证的锁屏状态，不重放更早的应用进入事件。 */
    @Test public void staleObservationKeepsAccumulatedLockScreenRest() {
        ContinuousUsageCycle saved = new ContinuousUsageCycle("boot", 91_000L, 91_000L,
                60_000L, 30_000L, null, "", 0L);
        UsageTimeline timeline = new UsageTimeline("zhijian");
        long observed = ForegroundObservation.withinInterval(1_000L, saved.wallMs, 151_000L);
        if (observed > 0L) timeline.resumed("video", null, observed);
        ContinuousUsageCycle next = saved.advance(timeline, "video"::equals,
                151_000L, 151_000L, 300_000L);
        assertEquals(60_000L, next.usedMs);
        assertEquals(90_000L, next.restMs);
    }

    /** 本次真正恢复到应用的观察仍中断休息，并从真实时刻继续累计。 */
    @Test public void freshObservationResumesUsageAtItsActualTime() {
        ContinuousUsageCycle saved = new ContinuousUsageCycle("boot", 91_000L, 91_000L,
                60_000L, 30_000L, null, "", 0L);
        UsageTimeline timeline = new UsageTimeline("zhijian");
        timeline.resumed("video", null, ForegroundObservation.withinInterval(141_000L, saved.wallMs, 151_000L));
        ContinuousUsageCycle next = saved.advance(timeline, "video"::equals,
                151_000L, 151_000L, 300_000L);
        assertEquals(70_000L, next.usedMs);
        assertEquals(0L, next.restMs);
    }

    /** 缺失或未来时间不制造前台，恰好落在已处理边界的新观察仍有效。 */
    @Test public void observationMustBelongToTheQueriedInterval() {
        assertEquals(0L, ForegroundObservation.withinInterval(0L, 100L, 200L));
        assertEquals(0L, ForegroundObservation.withinInterval(99L, 100L, 200L));
        assertEquals(0L, ForegroundObservation.withinInterval(201L, 100L, 200L));
        assertEquals(100L, ForegroundObservation.withinInterval(100L, 100L, 200L));
    }
}
