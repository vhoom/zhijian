package github.vhoom.zhijian.runtime.interception;

import android.app.Activity;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import github.vhoom.zhijian.R;
import github.vhoom.zhijian.data.model.policy.ControlLevel;
import github.vhoom.zhijian.data.model.policy.RuleGroup;
import github.vhoom.zhijian.data.model.policy.RuleType;
import github.vhoom.zhijian.data.model.policy.RuleWindow;
import github.vhoom.zhijian.fixture.ProtectionDeviceSession;
import github.vhoom.zhijian.data.store.interception.PendingInterceptionStore;
import github.vhoom.zhijian.data.store.settings.InteractionSettingsStore;
import github.vhoom.zhijian.data.model.interception.InterceptionMode;
import github.vhoom.zhijian.data.model.interception.InterceptionChallenge;
import github.vhoom.zhijian.util.config.AppConfig;

import org.junit.Test;

import java.time.LocalTime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 从桌面重新进入目标应用，验证无为继续、修心恢复页面及闭关返回动作。
 *
 * 维护日期：2026年11月17日
 */
public class InterceptionReentryTest {
    /** 旧禁用页面重进后继续只忽略当前访问；再次真实离开仍恢复同一规则。 */
    @Test public void wuweiContinueAfterReusedPageDoesNotImmediatelyBlockAgain() throws Exception {
        try (ProtectionDeviceSession session = new ProtectionDeviceSession()) {
            InteractionSettingsStore settings = new InteractionSettingsStore(session.context, new AppConfig(session.context));
            settings.setTemporaryPassEnabled(InterceptionMode.WUWEI, false);
            settings.setDynamicButtonsEnabled(false);
            session.start(activeBanPolicy(), ControlLevel.MORTAL);
            Activity blocked = session.awaitInterception(8_000L);
            assertNotNull(blocked);
            for (int attempt = 0; attempt < 3; attempt++) {
                String id = blocked.getIntent().getStringExtra("interception.session");
                long occurredAt = blocked.getIntent().getLongExtra("interception.occurred", -1L);
                session.goHome();
                SystemClock.sleep(350L);
                session.openTarget();
                Activity returned = session.awaitInterception(4_000L);
                assertNotNull("Abandoned block must cover the next visit", returned);
                assertEquals(id, returned.getIntent().getStringExtra("interception.session"));
                assertEquals(occurredAt, returned.getIntent().getLongExtra("interception.occurred", -2L));
                long confirmedAt = System.currentTimeMillis();
                session.instrumentation.runOnMainSync(() ->
                        returned.findViewById(R.id.interception_general_continue).performClick());
                long deadline = SystemClock.elapsedRealtime() + 4_000L;
                while (!returned.isDestroyed() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50L);
                assertTrue("Continue must close the block page", returned.isDestroyed());
                SystemClock.sleep(1_800L);
                assertEquals("The same rule must not immediately intercept again", session.target, activeWindowPackage(session));
                assertTrue(new InterceptionRuntime(session.context).evaluate(session.target).decision == null);
                assertTrue(new PendingInterceptionStore(session.context).read(session.target) == null);
                java.util.List<github.vhoom.zhijian.data.model.runtime.BlockContinuation> ignored =
                        new github.vhoom.zhijian.data.store.interception.BlockContinuationStore(session.context)
                                .read(session.target, github.vhoom.zhijian.util.time.BootIdentity.read(session.context));
                assertEquals(1, ignored.size());
                assertTrue("Visit boundary must use this continue, not the old occurrence", ignored.get(0).startedAtMs >= confirmedAt);
                assertTrue(new github.vhoom.zhijian.data.store.RuntimeStateStore(session.context)
                        .grantFor(session.target) == null);
                session.goHome();
                SystemClock.sleep(350L);
                session.openTarget();
                blocked = session.awaitInterception(4_000L);
                assertNotNull("A later real visit must enforce the rule again", blocked);
            }
        }
    }

    /** 多次从桌面重进仍恢复原中断身份，每次提前进入都重新要求完整休息。 */
    @Test public void repeatedBackgroundReentryCannotBypassRemainingRest() throws Exception {
        try (ProtectionDeviceSession session = new ProtectionDeviceSession()) {
            RuleGroup policy = shortInterruptionPolicy();
            policy.interruptionPauseMillis = 30_000L;
            session.start(policy, ControlLevel.REPAIR);
            Activity first = session.awaitInterception(12_000L);
            assertNotNull(first);
            String id = first.getIntent().getStringExtra("interception.session");
            for (int attempt = 0; attempt < 5; attempt++) {
                session.goHome();
                SystemClock.sleep(100L);
                session.openTarget();
                Activity returned = session.awaitInterception(4_000L);
                assertNotNull("Every premature reentry must be covered", returned);
                assertEquals(id, returned.getIntent().getStringExtra("interception.session"));
                github.vhoom.zhijian.data.model.runtime.PendingInterception pending =
                        new PendingInterceptionStore(session.context).read(session.target);
                assertNotNull(pending);
                assertTrue(pending.remainingPauseMs(
                        github.vhoom.zhijian.util.time.BootIdentity.read(session.context),
                        System.currentTimeMillis(), SystemClock.elapsedRealtime()) >= 27_000L);
            }
        }
    }

    /** 损坏的空挑战配置回退修心按压；提前重进保留休息，归零返回后重新计时。 */
    @Test
    public void emptyRepairConfigurationKeepsFrictionAndRestAfterReentry() throws Exception {
        try (ProtectionDeviceSession session = new ProtectionDeviceSession()) {
            RuleGroup policy = shortInterruptionPolicy();
            policy.interruptionPauseMillis = 12_000L;
            session.start(policy, ControlLevel.REPAIR, () -> {
                // 正常开关已不允许全部关闭；在服务连接前注入损坏偏好，单独验证恢复入口。
                android.content.SharedPreferences.Editor editor = session.context
                        .getSharedPreferences("global_settings", android.content.Context.MODE_PRIVATE).edit();
                for (InterceptionChallenge challenge : InterceptionChallenge.values()) {
                    editor.putBoolean(InterceptionMode.REPAIR.name() + ".challenge." + challenge.name() + ".enabled", false);
                }
                assertTrue(editor.commit());
            });
            Activity first = session.awaitInterception(12_000L);
            assertNotNull(first);
            String id = first.getIntent().getStringExtra("interception.session");
            String restId = restSessionId(session);
            session.instrumentation.runOnMainSync(() -> {
                assertNotNull(first.findViewById(R.id.interception_page_general));
                first.findViewById(R.id.interception_general_continue).performClick();
                assertNotNull(first.findViewById(R.id.interception_page_hold));
            });
            session.goHome();
            session.openTarget();
            Activity returned = session.awaitInterception(4_000L);
            assertNotNull(returned);
            assertEquals(id, returned.getIntent().getStringExtra("interception.session"));
            assertEquals(restId, restSessionId(session));
            assertTrue(new github.vhoom.zhijian.data.store.RuntimeStateStore(session.context)
                    .grantFor(session.target) == null);
            SystemClock.sleep(12_500L);
            assertFalse(returned.isFinishing());
            long resumedAt = SystemClock.elapsedRealtime();
            session.pressBack();
            awaitResume(new github.vhoom.zhijian.data.store.RuntimeStateStore(session.context), session.context, session.target, restId);
            Activity next = awaitNewInterception(session, id, 12_000L);
            assertNotNull(next);
            assertTrue(SystemClock.elapsedRealtime() - resumedAt >= 5_500L);
        }
    }
    /** 休息归零后直接重开应用即可恢复；原拦截页不需要点击，连续计时仍再次生效。 */
    @Test
    public void expiredRestAllowsReentryWithoutContinueAndStartsNewInterval() throws Exception {
        try (ProtectionDeviceSession session = new ProtectionDeviceSession()) {
            session.start(shortInterruptionPolicy(), ControlLevel.REPAIR);
            Activity first = session.awaitInterception(12_000L);
            assertNotNull(first);
            String id = first.getIntent().getStringExtra("interception.session");
            String restId = restSessionId(session);
            session.goHome();
            SystemClock.sleep(7_000L);
            assertFalse(first.isFinishing());
            long reopenedAt = SystemClock.elapsedRealtime();
            session.openTarget();
            github.vhoom.zhijian.data.store.RuntimeStateStore runtime =
                    new github.vhoom.zhijian.data.store.RuntimeStateStore(session.context);
            awaitResume(runtime, session.context, session.target, restId);
            assertTrue(new PendingInterceptionStore(session.context).read(session.target) == null);
            assertTrue(runtime.grantFor(session.target) == null);
            Activity next = awaitNewInterception(session, id, 12_000L);
            assertNotNull(next);
            assertTrue(SystemClock.elapsedRealtime() - reopenedAt >= 5_500L);
        }
    }

    /** 休息归零后用系统返回键回到目标应用，不要求点击继续且保留新连续起点。 */
    @Test
    public void backAfterRestReturnsWithoutContinue() throws Exception {
        try (ProtectionDeviceSession session = new ProtectionDeviceSession()) {
            session.start(shortInterruptionPolicy(), ControlLevel.REPAIR);
            Activity first = session.awaitInterception(12_000L);
            assertNotNull(first);
            String id = first.getIntent().getStringExtra("interception.session");
            String restId = restSessionId(session);
            SystemClock.sleep(7_000L);
            assertFalse(first.isFinishing());
            long resumedAt = SystemClock.elapsedRealtime();
            session.pressBack();
            github.vhoom.zhijian.data.store.RuntimeStateStore runtime =
                    new github.vhoom.zhijian.data.store.RuntimeStateStore(session.context);
            awaitResume(runtime, session.context, session.target, restId);
            long deadline = SystemClock.elapsedRealtime() + 3_000L;
            while (!first.isDestroyed() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50L);
            assertTrue(first.isDestroyed());
            assertTrue(runtime.grantFor(session.target) == null);
            Activity next = awaitNewInterception(session, id, 12_000L);
            assertNotNull(next);
            assertTrue(SystemClock.elapsedRealtime() - resumedAt >= 5_500L);
        }
    }

    /** 创建有效的短连续阈值与休息时长，缩短真实服务测试等待时间。 */
    private static RuleGroup shortInterruptionPolicy() {
        RuleGroup policy = new RuleGroup(0L, "");
        policy.types.add(RuleType.CONTINUOUS);
        policy.continuousLimitMillis = 6_000L;
        policy.interruptionPauseMillis = 6_000L;
        policy.passDurationMillis = 180_000L;
        return policy;
    }

    /**
     * 等待审计与待办清理完成；自然休息无需覆盖已经开始的新周期检查点。
     *
     * @param runtime 读取实际运行事实的存储入口
     * @param context 读取待办清理结果的应用上下文
     * @param target 被恢复使用的目标应用
     * @param sessionId 本次已完成的独立休息事实身份，不是页面展示身份
     */
    private static void awaitResume(github.vhoom.zhijian.data.store.RuntimeStateStore runtime,
            android.content.Context context, String target, String sessionId) {
        PendingInterceptionStore pending = new PendingInterceptionStore(context);
        long deadline = SystemClock.elapsedRealtime() + 4_000L;
        while ((!runtime.wasAllowed(sessionId).join() || pending.read(target) != null)
                && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50L);
        assertTrue(pending.read(target) == null);
        assertTrue(pending.rests("app:" + target).isEmpty());
        assertTrue(runtime.wasAllowed(sessionId).join());
    }

    /**
     * 在休息完成前读取唯一规则的事实身份，避免把展示会话误当作独立休息审计。
     *
     * @param session 当前真实服务测试会话
     * @return 当前连续提醒的独立休息身份
     */
    private static String restSessionId(ProtectionDeviceSession session) {
        java.util.List<github.vhoom.zhijian.data.model.runtime.PendingInterception> rests =
                new PendingInterceptionStore(session.context).rests("app:" + session.target);
        assertEquals("One continuous rule must create one rest obligation", 1, rests.size());
        return rests.get(0).sessionId;
    }

    /**
     * 忽略同一 Activity 的重复生命周期通知，只接受新的中断周期。
     *
     * @param session 当前真实服务测试会话
     * @param previousId 上一次拦截的会话身份
     * @param timeoutMs 等待新周期的最大时间
     */
    private static Activity awaitNewInterception(ProtectionDeviceSession session,
            String previousId, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            Activity next = session.awaitInterception(deadline - SystemClock.elapsedRealtime());
            if (next != null && !next.isDestroyed()
                    && !previousId.equals(next.getIntent().getStringExtra("interception.session"))) return next;
        }
        return null;
    }

    /** 完成长按或答题后离开，未结束的休息必须继续拦截同一应用。 */
    @Test
    public void completedChallengeCannotBypassRemainingRestThroughHome() throws Exception {
        try (ProtectionDeviceSession session = new ProtectionDeviceSession()) {
            RuleGroup policy = new RuleGroup(0L, "");
            policy.types.add(RuleType.CONTINUOUS);
            policy.continuousLimitMillis = 6_000L;
            policy.interruptionPauseMillis = 30_000L;
            policy.passDurationMillis = 180_000L;
            session.start(policy, ControlLevel.REPAIR);
            Activity first = session.awaitInterception(12_000L);
            assertNotNull(first);
            String restId = restSessionId(session);
            finishChallenge(session, first);
            String id = first.getIntent().getStringExtra("interception.session");
            long occurredAt = first.getIntent().getLongExtra("interception.occurred", -1L);
            session.goHome();
            session.openTarget();
            Activity returned = session.awaitInterception(4_000L);
            assertNotNull("Unfinished rest must block immediate reentry", returned);
            assertEquals(id, returned.getIntent().getStringExtra("interception.session"));
            assertEquals(occurredAt, returned.getIntent().getLongExtra("interception.occurred", -2L));
            assertEquals(restId, restSessionId(session));
            assertTrue(new github.vhoom.zhijian.data.store.RuntimeStateStore(session.context)
                    .grantFor(session.target) == null);
            github.vhoom.zhijian.data.model.runtime.PendingInterception remaining =
                    new InterceptionRuntime(session.context).evaluate(session.target).pending;
            assertNotNull("The returned page must still enforce the unfinished rest", remaining);
            assertTrue("Returning to the target restarts the required continuous rest",
                    remaining.remainingPauseMs(github.vhoom.zhijian.util.time.BootIdentity.read(session.context),
                            System.currentTimeMillis(), SystemClock.elapsedRealtime()) >= 27_000L);
            session.instrumentation.runOnMainSync(() -> assertFalse(returned.isFinishing()));
        }
    }

    /** 修心在返回桌面后仍恢复同一拦截，不能借页面离开获得通行证。 */
    @Test
    public void repairRemainsBlockedOnReentry() throws Exception {
        try (ProtectionDeviceSession session = new ProtectionDeviceSession()) {
            session.start(activeBanPolicy(), ControlLevel.REPAIR);
            Activity first = session.awaitInterception(8_000L);
            assertNotNull(first);
            session.instrumentation.runOnMainSync(() ->
                    first.findViewById(R.id.interception_general_continue).performClick());
            finishChallenge(session, first);
            assertFalse(first.isFinishing());
            String id = first.getIntent().getStringExtra("interception.session");
            session.goHome();
            session.openTarget();
            Activity returned = session.awaitInterception(4_000L);
            assertNotNull(returned);
            assertEquals(id, returned.getIntent().getStringExtra("interception.session"));
            assertFalse(returned.isFinishing());
            assertNotNull(new PendingInterceptionStore(session.context).read(session.target));
        }
    }

    /** 闭关第二次及更多次重新进入都退出测试目标，随机显示与否不解除限制或签发通行证。 */
    @Test public void sealRepeatedReentryReturnsWithoutReleasingTheRestriction() throws Exception {
        try (ProtectionDeviceSession session = new ProtectionDeviceSession()) {
            session.start(activeBanPolicy(), ControlLevel.SEAL);
            Activity first = session.awaitInterception(8_000L);
            assertNotNull(first);
            String id = first.getIntent().getStringExtra("interception.session");
            PendingInterceptionStore pending = new PendingInterceptionStore(session.context);
            for (int attempt = 0; attempt < 4; attempt++) {
                session.goHome();
                session.openTarget();
                long deadline = SystemClock.elapsedRealtime() + 4_000L;
                String foreground = activeWindowPackage(session);
                while ((foreground == null || session.target.equals(foreground))
                        && SystemClock.elapsedRealtime() < deadline) {
                    SystemClock.sleep(50L);
                    foreground = activeWindowPackage(session);
                }
                assertNotNull("A live window must confirm the result of returning", foreground);
                assertFalse("Neither random action leaves the test target in front", session.target.equals(foreground));
                github.vhoom.zhijian.data.model.runtime.PendingInterception restriction = pending.read(session.target);
                assertNotNull(restriction);
                assertEquals(id, restriction.sessionId);
                assertTrue(new github.vhoom.zhijian.data.store.RuntimeStateStore(session.context)
                        .grantFor(session.target) == null);
            }
        }
    }

    /** @param session 当前隔离设备会话 @return 实际活动窗口的包名，避免用尚未更新的历史事件证明返回 */
    private static String activeWindowPackage(ProtectionDeviceSession session) {
        android.view.accessibility.AccessibilityNodeInfo root = session.instrumentation.getUiAutomation(
                android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).getRootInActiveWindow();
        if (root == null) return null;
        try {
            CharSequence packageName = root.getPackageName();
            return packageName == null ? null : packageName.toString();
        } finally {
            root.recycle();
        }
    }

    /** @return 当前时刻已生效且一小时内不会自然解除的禁用配置 */
    private static RuleGroup activeBanPolicy() {
        RuleGroup policy = new RuleGroup(0L, "");
        policy.types.add(RuleType.WINDOW_BAN);
        LocalTime now = LocalTime.now();
        int minute = now.getHour() * 60 + now.getMinute();
        policy.windows.add(RuleWindow.create(RuleType.WINDOW_BAN,
                Math.floorMod(minute - 1, 1440), Math.floorMod(minute + 60, 1440)));
        policy.passDurationMillis = 180_000L;
        return policy;
    }

    /** 离开并销毁窗口后重新创建运行入口，原休息与会话仍然存在。 */
    @Test
    public void abandoningRestAndRecreatingRuntimeDoesNotReleaseTarget() throws Exception {
        try (ProtectionDeviceSession session = new ProtectionDeviceSession()) {
            RuleGroup policy = new RuleGroup(0L, "");
            policy.types.add(RuleType.CONTINUOUS);
            policy.continuousLimitMillis = 6_000L;
            policy.interruptionPauseMillis = 6_000L;
            policy.passDurationMillis = 180_000L;
            session.start(policy, ControlLevel.REPAIR);
            Activity first = session.awaitInterception(12_000L);
            assertNotNull(first);
            String id = first.getIntent().getStringExtra("interception.session");
            String restId = restSessionId(session);
            session.instrumentation.runOnMainSync(() ->
                    first.findViewById(R.id.interception_general_exit).performClick());
            long deadline = SystemClock.elapsedRealtime() + 4_000L;
            while (!first.isDestroyed() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50L);
            assertTrue(first.isDestroyed());
            assertEquals(id, new InterceptionRuntime(session.context).evaluate(session.target).pending.sessionId);
            session.goHome();
            session.openTarget();
            Activity returned = session.awaitInterception(4_000L);
            assertNotNull(returned);
            assertEquals(id, returned.getIntent().getStringExtra("interception.session"));
            SystemClock.sleep(6_100L);
            session.instrumentation.runOnMainSync(() -> {
                assertFalse(returned.isFinishing());
                View action = returned.findViewById(R.id.interception_general_continue);
                assertNotNull(action);
                assertTrue(action.isShown());
                action.performClick();
            });
            github.vhoom.zhijian.data.store.RuntimeStateStore runtime =
                    new github.vhoom.zhijian.data.store.RuntimeStateStore(session.context);
            deadline = SystemClock.elapsedRealtime() + 4_000L;
            while (!runtime.wasAllowed(restId).join() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50L);
            assertTrue("Abandoning must not consume the independent rest audit", runtime.wasAllowed(restId).join());
            assertTrue(new PendingInterceptionStore(session.context).rests("app:" + session.target).isEmpty());
            assertTrue(runtime.grantFor(session.target) == null);
            deadline = SystemClock.elapsedRealtime() + 2_000L;
            while (new PendingInterceptionStore(session.context).read(session.target) != null
                    && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50L);
            assertTrue(new PendingInterceptionStore(session.context).read(session.target) == null);
        }
    }

    /**
     * 在真实页面完成一种短挑战，覆盖随机选中的长按和单词方式。
     *
     * @param session 当前测试设备会话
     * @param activity 正在显示的拦截 Activity
     */
    private static void finishChallenge(ProtectionDeviceSession session, Activity activity) {
        boolean[] hold = new boolean[1];
        session.instrumentation.runOnMainSync(() -> {
            View entry = activity.findViewById(R.id.interception_general_continue);
            if (entry != null && entry.isShown()) entry.performClick();
            hold[0] = activity.findViewById(R.id.interception_page_hold) != null;
            if (hold[0]) pressHold(activity, MotionEvent.ACTION_DOWN);
        });
        SystemClock.sleep(300L);
        if (hold[0]) session.instrumentation.runOnMainSync(() -> pressHold(activity, MotionEvent.ACTION_UP));
        else session.completeWordChallenge(activity);
    }

    /**
     * 发送真实按钮触摸事件，驱动长按计时。
     *
     * @param activity 当前拦截页面
     * @param action 按下或释放动作
     */
    private static void pressHold(Activity activity, int action) {
        long time = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(time, time, action, 20f, 20f, 0);
        try {
            View button = activity.findViewById(R.id.interception_hold_button);
            if (button != null) button.dispatchTouchEvent(event);
            else assertEquals(MotionEvent.ACTION_UP, action);
        } finally {
            event.recycle();
        }
    }
}
