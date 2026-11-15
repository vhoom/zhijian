package github.vhoom.zhijian.domain.interception.hold;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * 按压推进、加速倒退、重新按住及暂停的纯时间回归。
 *
 * 维护日期：2026年11月08日
 */
public class HoldProgressTest {
    /** 零时刻也能开始按压，只有有效进度达到要求才完成。 */
    @Test public void holdingAdvancesAtRealTimeAndCompletesAtTheThreshold() {
        HoldProgress progress = new HoldProgress(10_000L);
        progress.setHolding(true, 0L);
        progress.update(9_999L);
        assertFalse(progress.isComplete());
        assertEquals(1L, progress.remainingMs());
        progress.update(10_000L);
        assertTrue(progress.isComplete());
        assertEquals(1d, progress.fraction(), 0d);
        assertFalse(progress.needsUpdate());
    }

    /** 松手时保留进度，随后每一秒损失的进度线性增加，剩余时间同步增加。 */
    @Test public void releaseRewindsAtALinearlyIncreasingSpeed() {
        HoldProgress progress = heldFor(10_000L, 6_000L);
        progress.setHolding(false, 6_000L);
        assertEquals(4_000L, progress.remainingMs());
        progress.update(7_000L);
        assertEquals(4_500L, progress.remainingMs());
        progress.update(8_000L);
        assertEquals(6_000L, progress.remainingMs());
        progress.update(9_000L);
        assertEquals(8_500L, progress.remainingMs());
        progress.update(10_000L);
        assertEquals(10_000L, progress.remainingMs());
        assertFalse(progress.needsUpdate());
    }

    /** 再次按住从倒退后的实际进度继续，下一次松手重新从零倒退速度起步。 */
    @Test public void pressingAgainKeepsProgressAndRestartsReleaseAcceleration() {
        HoldProgress progress = heldFor(10_000L, 4_000L);
        progress.setHolding(false, 4_000L);
        progress.setHolding(true, 5_500L);
        assertEquals(7_125L, progress.remainingMs());
        progress.update(7_500L);
        assertEquals(5_125L, progress.remainingMs());
        progress.setHolding(false, 7_500L);
        progress.update(8_500L);
        assertEquals(5_625L, progress.remainingMs());
    }

    /** 滑出后的松手或取消事件不能反复重置倒退速度。 */
    @Test public void repeatedReleaseDoesNotSlowDownRewinding() {
        HoldProgress progress = heldFor(10_000L, 4_000L);
        progress.setHolding(false, 4_000L);
        progress.setHolding(false, 5_000L);
        progress.update(6_000L);
        assertEquals(8_000L, progress.remainingMs());
    }

    /** 丢帧与每五十毫秒刷新得到相同进度，倒退不会依赖设备帧率。 */
    @Test public void rewindIsIndependentOfUpdateFrequency() {
        HoldProgress stepped = heldFor(10_000L, 6_000L);
        HoldProgress delayed = heldFor(10_000L, 6_000L);
        stepped.setHolding(false, 6_000L);
        delayed.setHolding(false, 6_000L);
        for (long time = 6_050L; time <= 8_500L; time += 50L) stepped.update(time);
        delayed.update(8_500L);
        assertEquals(delayed.remainingMs(), stepped.remainingMs());
        assertEquals(delayed.fraction(), stepped.fraction(), 0d);
    }

    /** 后台暂停清零；重新进入后必须重新按住，后台经过的时间不能完成挑战。 */
    @Test public void resetStopsProgressAndBackgroundAccrual() {
        HoldProgress progress = heldFor(10_000L, 4_000L);
        progress.reset();
        progress.update(60_000L);
        assertEquals(10_000L, progress.remainingMs());
        assertFalse(progress.isHolding());
        assertFalse(progress.needsUpdate());
        progress.setHolding(true, 60_000L);
        progress.update(61_000L);
        assertEquals(9_000L, progress.remainingMs());
    }

    /** 极长松手时间也只退到零，已经完成的挑战不会被松手撤销。 */
    @Test public void progressStaysBoundedAtZeroAndCompletion() {
        HoldProgress progress = heldFor(10_000L, 4_000L);
        progress.setHolding(false, 4_000L);
        progress.update(Long.MAX_VALUE);
        assertEquals(0d, progress.fraction(), 0d);
        assertEquals(10_000L, progress.remainingMs());
        HoldProgress completed = heldFor(1_000L, 1_000L);
        completed.setHolding(false, 1_001L);
        completed.update(100_000L);
        assertTrue(completed.isComplete());
    }

    /** @param durationMs 挑战要求 @param heldMs 已按住时长 @return 已结算到该时刻的进度 */
    private static HoldProgress heldFor(long durationMs, long heldMs) {
        HoldProgress progress = new HoldProgress(durationMs);
        progress.setHolding(true, 0L);
        progress.update(heldMs);
        return progress;
    }
}
