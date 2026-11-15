package github.vhoom.zhijian.util.diagnostics;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * 在真实调用边界模拟请求、窗口回执、目标离开和过期异步回调。
 *
 * 维护日期：2026年11月08日
 */
public class LaunchAttemptTrackerTest {
    /** 规则命中但页面无回执时才产生故障，期限前不能误报。 */
    @Test public void missingWindowReceiptTimesOutOnce() {
        LaunchAttemptTracker tracker = new LaunchAttemptTracker();
        long request = tracker.requested("session", 100L, 5000L);
        assertFalse(tracker.expired(request, 5099L));
        assertTrue(tracker.expired(request, 5100L));
        assertTrue(tracker.isUnconfirmed());
        assertFalse(tracker.expired(request, 5200L));
    }

    /** 实际页面显示后不会被旧超时回调重新标记失败。 */
    @Test public void actualWindowReceiptPreventsFalseFailure() {
        LaunchAttemptTracker tracker = new LaunchAttemptTracker();
        long request = tracker.requested("session", 0L, 5000L);
        assertTrue(tracker.shown("session"));
        assertFalse(tracker.expired(request, 6000L));
        assertFalse(tracker.isUnconfirmed());
    }

    /** 新会话发起后，旧会话的显示或超时结果均不能确认新会话。 */
    @Test public void staleCallbacksCannotAffectNewRequest() {
        LaunchAttemptTracker tracker = new LaunchAttemptTracker();
        long old = tracker.requested("old", 0L, 5000L);
        long current = tracker.requested("new", 100L, 5000L);
        assertFalse(tracker.shown("old"));
        assertFalse(tracker.expired(old, 6000L));
        tracker.cancel(old);
        assertTrue(tracker.expired(current, 6000L));
    }

    /** 用户离开目标应用时取消等待，避免将正常切换误诊为系统拒绝。 */
    @Test public void leavingTargetCancelsFailureDetection() {
        LaunchAttemptTracker tracker = new LaunchAttemptTracker();
        long request = tracker.requested("session", 0L, 5000L);
        tracker.cancel(request);
        assertFalse(tracker.expired(request, 6000L));
        assertFalse(tracker.isUnconfirmed());
    }

    /** 初始化较慢的页面晚到回执后可以恢复状态。 */
    @Test public void lateReceiptClearsPriorTimeout() {
        LaunchAttemptTracker tracker = new LaunchAttemptTracker();
        long request = tracker.requested("session", 0L, 5000L);
        assertTrue(tracker.expired(request, 6000L));
        assertTrue(tracker.shown("session"));
        assertFalse(tracker.isUnconfirmed());
    }
}
