package github.vhoom.zhijian.runtime.interception;

import android.app.Activity;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

import github.vhoom.zhijian.R;
import github.vhoom.zhijian.data.model.interception.InterceptionChallenge;
import github.vhoom.zhijian.data.model.interception.InterceptionMode;
import github.vhoom.zhijian.data.model.policy.ControlLevel;
import github.vhoom.zhijian.data.model.policy.RuleGroup;
import github.vhoom.zhijian.data.model.policy.RuleType;
import github.vhoom.zhijian.data.model.policy.RuleWindow;
import github.vhoom.zhijian.data.model.runtime.BlockContinuation;
import github.vhoom.zhijian.data.model.runtime.PendingInterception;
import github.vhoom.zhijian.data.model.settings.InteractionSettings;
import github.vhoom.zhijian.data.store.RuntimeStateStore;
import github.vhoom.zhijian.data.store.interception.BlockContinuationStore;
import github.vhoom.zhijian.data.store.interception.PendingInterceptionStore;
import github.vhoom.zhijian.data.store.settings.InteractionSettingsStore;
import github.vhoom.zhijian.fixture.ProtectionDeviceSession;
import github.vhoom.zhijian.fixture.SudokuDeviceDriver;
import github.vhoom.zhijian.util.config.AppConfig;
import github.vhoom.zhijian.util.time.BootIdentity;

import org.junit.Test;

import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.*;

/**
 * 正式修心页面完成挑战并继续后留在目标应用，真实离开后恢复限制。
 *
 * 维护日期：2026年11月14日
 */
public class RepairContinuationTest {
    /** 三种挑战、开启或关闭反问，都通过正式写入器保存本次访问确认。 */
    @Test public void completedChallengesDoNotImmediatelyInterceptAgain() throws Exception {
        android.content.Context context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (SudokuDeviceDriver preserved = new SudokuDeviceDriver(context)) {
            for (InterceptionChallenge challenge : InterceptionChallenge.values()) {
                for (boolean confirmation : new boolean[]{false, true}) {
                    verifyContinuation(ControlLevel.REPAIR, challenge, true, confirmation, false);
                }
            }
        }
    }

    /** 修心关闭阻碍页面时可直接继续；进行中的旧会话不受后来开启临时放行影响。 */
    @Test public void directRepairContinuationSurvivesNewTemporaryPassOption() throws Exception {
        for (boolean changePass : new boolean[]{false, true}) {
            verifyContinuation(ControlLevel.REPAIR, InterceptionChallenge.BUTTON_HOLD, false, false, changePass);
        }
    }

    /** 无为的进行中会话同样保留冻结选项，新开关不能导致刚确认的规则重新弹页。 */
    @Test public void wuweiContinuationSurvivesNewTemporaryPassOption() throws Exception {
        verifyContinuation(ControlLevel.MORTAL, InterceptionChallenge.BUTTON_HOLD, false, false, true);
    }

    /** 正式页面刚签发临时放行后，原计算结果在展示入口也必须被识别为失效。 */
    @Test public void committedGrantSuppressesPreviouslyCalculatedInterception() throws Exception {
        try (ProtectionDeviceSession session = new ProtectionDeviceSession()) {
            InteractionSettingsStore settings = new InteractionSettingsStore(session.context, new AppConfig(session.context));
            session.start(activeBanPolicy(), ControlLevel.REPAIR, () -> {
                settings.setTemporaryPassEnabled(InterceptionMode.REPAIR, true);
                settings.setObstaclePagesEnabled(false);
            });
            Activity page = session.awaitInterception(8_000L);
            assertNotNull(page);
            PendingInterception oldResult = new PendingInterceptionStore(session.context).read(session.target);
            assertNotNull(oldResult);
            clickWhenReady(session, page, R.id.interception_general_continue);
            long deadline = SystemClock.elapsedRealtime() + 4_000L;
            while (!page.isDestroyed() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50L);
            assertTrue(page.isDestroyed());
            InterceptionRuntime runtime = new InterceptionRuntime(session.context);
            assertNotNull(new RuntimeStateStore(session.context).grantFor(session.target));
            assertTrue(runtime.isPresentationSuppressed(oldResult.decision));
            assertNull(runtime.evaluate(session.target).decision);
            SystemClock.sleep(1_800L);
            assertEquals(session.target, activePackage(session));
        }
    }

    /**
     * 驱动真实服务和正式页面，验证继续后无重复拦截、无通行证，离开重进恢复。
     * @param level 用户执行继续操作的档位
     * @param challenge 本次需要稳定选中的挑战；单词开启时仍保留正式的按压候选
     * @param obstacles 是否展示修心阻碍页面
     * @param confirmation 完成挑战后是否需要反问
     * @param changePass 页面创建后是否开启下一会话的临时放行
     */
    private static void verifyContinuation(ControlLevel level, InterceptionChallenge challenge,
            boolean obstacles, boolean confirmation, boolean changePass) throws Exception {
        try (ProtectionDeviceSession session = new ProtectionDeviceSession()) {
            InteractionSettingsStore settings = new InteractionSettingsStore(session.context, new AppConfig(session.context));
            InterceptionMode mode = level == ControlLevel.REPAIR ? InterceptionMode.REPAIR : InterceptionMode.WUWEI;
            String[] preparedSession = new String[1];
            session.start(activeBanPolicy(), level, () -> {
                settings.setTemporaryPassEnabled(mode, false);
                settings.setDynamicButtonsEnabled(false);
                settings.setObstaclePagesEnabled(obstacles);
                settings.setConfirmationEnabled(confirmation);
                settings.setEnabled(InterceptionMode.REPAIR, InterceptionChallenge.WORD_TRANSLATION,
                        challenge == InterceptionChallenge.WORD_TRANSLATION);
                settings.setEnabled(InterceptionMode.REPAIR, InterceptionChallenge.SUDOKU,
                        challenge == InterceptionChallenge.SUDOKU);
                if (level == ControlLevel.REPAIR && obstacles) {
                    preparedSession[0] = prepareChallengeSession(session, settings.read(mode), challenge);
                }
            });
            Activity page = session.awaitInterception(8_000L);
            assertNotNull(page);
            PendingInterception original = new PendingInterceptionStore(session.context).read(session.target);
            assertNotNull(original);
            if (preparedSession[0] != null) assertEquals(preparedSession[0], original.sessionId);
            if (changePass) settings.setTemporaryPassEnabled(mode, true);
            if (level == ControlLevel.REPAIR && obstacles) {
                clickWhenReady(session, page, R.id.interception_general_continue);
                completeChallenge(session, page, challenge);
                if (challenge == InterceptionChallenge.SUDOKU && confirmation) {
                    clickWhenReady(session, page, R.id.interception_sudoku_continue);
                }
            }
            int continueId = level != ControlLevel.REPAIR || !obstacles ? R.id.interception_general_continue
                    : confirmation ? R.id.interception_confirmation_continue
                    : challenge == InterceptionChallenge.BUTTON_HOLD ? R.id.interception_hold_continue
                    : challenge == InterceptionChallenge.SUDOKU ? R.id.interception_sudoku_continue
                    : R.id.interception_word_continue;
            long confirmedAt = System.currentTimeMillis();
            clickWhenReady(session, page, continueId);
            long deadline = SystemClock.elapsedRealtime() + 4_000L;
            while (!page.isDestroyed() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50L);
            assertTrue("Continue must close the formal interception page", page.isDestroyed());
            SystemClock.sleep(1_800L);
            assertEquals("The same rule must not immediately cover the target again", session.target, activePackage(session));
            InterceptionRuntime runtime = new InterceptionRuntime(session.context);
            assertNull(runtime.evaluate(session.target).decision);
            assertTrue("An old launch result must be suppressed after confirmation", runtime.isPresentationSuppressed(original.decision));
            assertNull(new PendingInterceptionStore(session.context).read(session.target));
            assertNull(new RuntimeStateStore(session.context).grantFor(session.target));
            List<BlockContinuation> ignored = new BlockContinuationStore(session.context)
                    .read(session.target, BootIdentity.read(session.context));
            assertEquals(1, ignored.size());
            assertEquals(level, ignored.get(0).level);
            assertTrue(ignored.get(0).startedAtMs >= confirmedAt);
            session.goHome();
            SystemClock.sleep(350L);
            session.openTarget();
            assertNotNull("A later real visit must enforce the same rule again", session.awaitInterception(4_000L));
        }
    }

    /**
     * 从正式选择策略中寻找会话种子，再保存当前规则的有效展示，由真实服务启动页面。
     * @param session 当前设备会话，服务尚未连接、目标尚未打开
     * @param settings 正式冻结选项和挑战候选
     * @param challenge 本轮需要覆盖的挑战
     * @return 正式页面应继续使用的会话身份
     */
    private static String prepareChallengeSession(ProtectionDeviceSession session, InteractionSettings settings,
            InterceptionChallenge challenge) {
        PendingInterception pending = new InterceptionRuntime(session.context).evaluate(session.target).pending;
        assertNotNull("The configured ban must produce a formal pending interception", pending);
        String prefix = "repair-continuation-" + UUID.randomUUID();
        for (int index = 0; index < 10_000; index++) {
            String candidate = prefix + ':' + index;
            if (settings.selectChallenge(candidate) != challenge) continue;
            new PendingInterceptionStore(session.context).save(new PendingInterception(pending.decision,
                    candidate, pending.bootId, pending.startedAtMs, pending.startedElapsedMs,
                    pending.cycleGeneration, pending.occurredAtMs, pending.continuousKey,
                    pending.continuousFloorMs, pending.acknowledgedUsageMs));
            return candidate;
        }
        throw new AssertionError("No formal session selects " + challenge);
    }

    /**
     * 完成真实挑战组件，不使用预览 Intent 或直接调用结果写入器。
     * @param session 当前设备回归会话
     * @param page 正式修心页面
     * @param challenge 本轮会话按正式策略稳定选中的挑战
     */
    private static void completeChallenge(ProtectionDeviceSession session, Activity page, InterceptionChallenge challenge) {
        if (challenge == InterceptionChallenge.SUDOKU) {
            SudokuDeviceDriver.complete(session.instrumentation, page);
        } else if (challenge == InterceptionChallenge.BUTTON_HOLD) {
            awaitAction(session, page, R.id.interception_hold_button);
            View[] held = new View[1];
            long downTime = SystemClock.uptimeMillis();
            session.instrumentation.runOnMainSync(() -> {
                held[0] = page.findViewById(R.id.interception_hold_button);
                assertNotNull("The formal hold control must receive the gesture", held[0]);
                touchHold(held[0], downTime, MotionEvent.ACTION_DOWN);
            });
            SystemClock.sleep(300L);
            // 挑战可能已跳到反问页；同一手势仍结束在原按钮，不能重新查找已移除的控件。
            session.instrumentation.runOnMainSync(() -> touchHold(held[0], downTime, MotionEvent.ACTION_UP));
        } else {
            session.completeWordChallenge(page);
        }
    }

    /** @param held 本次手势开始时的原按钮 @param downTime 手势开始的单调时间 @param action 按下或抬起动作 */
    private static void touchHold(View held, long downTime, int action) {
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(downTime, now, action, 20f, 20f, 0);
        try { held.dispatchTouchEvent(event); }
        finally { event.recycle(); }
    }

    /** @param session 当前设备会话 @param page 当前页面 @param id 需等待并主动点击的控件 */
    private static void clickWhenReady(ProtectionDeviceSession session, Activity page, int id) {
        awaitAction(session, page, id);
        // 页面过渡结束后再进行新的主动操作，避免动画门禁把测试点击视为误触。
        SystemClock.sleep(500L);
        session.instrumentation.runOnMainSync(() -> page.findViewById(id).performClick());
    }

    /** @param session 当前设备会话 @param page 当前页面 @param id 初始化后应可操作的控件 */
    private static void awaitAction(ProtectionDeviceSession session, Activity page, int id) {
        boolean[] ready = {false};
        long deadline = SystemClock.elapsedRealtime() + 5_000L;
        while (!ready[0] && SystemClock.elapsedRealtime() < deadline) {
            session.instrumentation.runOnMainSync(() -> {
                View view = page.findViewById(id);
                ready[0] = view != null && view.isShown() && view.isEnabled();
            });
            if (!ready[0]) SystemClock.sleep(50L);
        }
        assertTrue("Expected formal interception action " + id, ready[0]);
    }

    /** @param session 当前设备会话 @return 实际活动窗口的包名，不用历史事件代替前台证据 */
    private static String activePackage(ProtectionDeviceSession session) {
        AccessibilityNodeInfo root = session.instrumentation.getUiAutomation(
                android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).getRootInActiveWindow();
        if (root == null) return null;
        try { return root.getPackageName() == null ? null : root.getPackageName().toString(); }
        finally { root.recycle(); }
    }

    /** @return 当前已生效且足够覆盖本次设备回归的禁用规则 */
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
}
