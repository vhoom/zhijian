package github.vhoom.zhijian.runtime.interception;

import android.Manifest;
import android.app.Activity;
import android.app.AppOpsManager;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Process;
import android.os.SystemClock;
import android.os.ParcelFileDescriptor;
import android.provider.Settings;
import android.view.View;

import androidx.test.platform.app.InstrumentationRegistry;

import github.vhoom.zhijian.R;
import github.vhoom.zhijian.accessibility.AccessibilityConnection;
import github.vhoom.zhijian.accessibility.ZhijianAccessibilityService;
import github.vhoom.zhijian.data.model.policy.RuleGroup;
import github.vhoom.zhijian.data.model.policy.RuleType;
import github.vhoom.zhijian.data.store.AppPolicyStore;
import github.vhoom.zhijian.data.store.ZhijianStorage;
import github.vhoom.zhijian.domain.interception.InterceptionDecision;
import github.vhoom.zhijian.domain.interception.InterceptionEvaluation;
import github.vhoom.zhijian.ui.interception.InterceptionActivity;

import org.junit.Test;

import java.io.InputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 使用真实服务与 UsageEvents 验证“拦截→休息→继续→再次拦截”的完整链路。
 *
 * 维护日期：2026年11月14日
 */
public class InterruptionRuntimeTest {
    /** 休息结束留页，主动继续后从零计时并再次触发，累计限时仍正常生效。 */
    @Test
    public void resumedTargetIsTimedAndInterceptedAgain() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        String target = instrumentation.getContext().getPackageName();
        UiAutomation automation = instrumentation.getUiAutomation(
                UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        String oldServices = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        int oldEnabled = Settings.Secure.getInt(context.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_ENABLED, 0);
        AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        int oldUsageMode = appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), context.getPackageName());
        AppPolicyStore policies = new AppPolicyStore(context);
        ZhijianStorage.get(context).ready().join();
        RuleGroup original = policies.policyOf(target);
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(
                InterceptionActivity.class.getName(), null, false);
        Activity first = null;
        Activity second = null;
        try {
            RuleGroup policy = new RuleGroup(0L, "");
            policy.types.add(RuleType.CONTINUOUS);
            policy.continuousLimitMillis = 6_000L;
            policy.interruptionPauseMillis = 6_000L;
            policy.passDurationMillis = 180_000L;
            policies.save(target, policy).join();
            shell(automation, "appops set " + context.getPackageName() + " GET_USAGE_STATS allow");
            Intent launch = context.getPackageManager().getLaunchIntentForPackage(target);
            assertNotNull(launch);
            // 先让目标应用进入前台；连接后没有新窗口事件也必须启动连续检查。
            shell(automation, "am start -n " + launch.getComponent().flattenToString()
                    + " --ez zhijian.test.floodWindows true");
            automation.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS);
            String component = new ComponentName(context, ZhijianAccessibilityService.class).flattenToString();
            Settings.Secure.putString(context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    oldServices == null || oldServices.isEmpty() ? component : oldServices + ":" + component);
            Settings.Secure.putInt(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            long connectedDeadline = SystemClock.elapsedRealtime() + 8_000L;
            while (!AccessibilityConnection.isConnected() && SystemClock.elapsedRealtime() < connectedDeadline) {
                SystemClock.sleep(50L);
            }
            assertTrue("Real accessibility service must connect", AccessibilityConnection.isConnected());
            first = monitor.waitForActivityWithTimeout(12_000L);
            assertNotNull("Continuous usage must trigger first interception", first);
            // 休息归零后额外等待，确认页面没有自行退出。
            SystemClock.sleep(7_000L);
            Activity pausedPage = first;
            long[] resumedAt = new long[1];
            instrumentation.runOnMainSync(() -> {
                assertFalse(pausedPage.isFinishing());
                View holdContinue = pausedPage.findViewById(R.id.interception_hold_continue);
                View wordContinue = pausedPage.findViewById(R.id.interception_word_continue);
                View action = holdContinue != null && holdContinue.isShown() ? holdContinue : wordContinue;
                assertNotNull(action);
                assertTrue(action.isShown());
                resumedAt[0] = SystemClock.elapsedRealtime();
                assertTrue(action.performClick());
            });
            long nextDeadline = SystemClock.elapsedRealtime() + 12_000L;
            while (SystemClock.elapsedRealtime() < nextDeadline) {
                Activity candidate = monitor.waitForActivityWithTimeout(
                        nextDeadline - SystemClock.elapsedRealtime());
                // ActivityMonitor 会同时捕捉创建和恢复，忽略同一页面的重复生命周期通知。
                if (candidate != null && candidate != first && !candidate.isDestroyed()) {
                    second = candidate;
                    break;
                }
            }
            assertNotNull("Returning target must be intercepted on the next continuous interval", second);
            assertTrue("A fresh continuous interval must pass before the second interception",
                    SystemClock.elapsedRealtime() - resumedAt[0] >= 5_500L);
            assertEquals("CONTINUOUS", second.getIntent().getStringExtra("interception.trigger"));
            // 用真实累计用量检查另一种规则，中断恢复不能提供三分钟的全规则豁免。
            policy.types.add(RuleType.DAILY_LIMIT);
            policy.dailyLimitMillis = 6_000L;
            policies.save(target, policy).join();
            InterceptionEvaluation daily = new InterceptionRuntime(context).evaluate(target);
            assertNotNull(daily.decision);
            assertEquals(InterceptionDecision.Trigger.DAILY_LIMIT, daily.decision.trigger);
        } finally {
            Activity firstPage = first;
            Activity secondPage = second;
            instrumentation.runOnMainSync(() -> {
                if (firstPage != null && !firstPage.isDestroyed()) firstPage.finishAndRemoveTask();
                if (secondPage != null && !secondPage.isDestroyed()) secondPage.finishAndRemoveTask();
            });
            Settings.Secure.putString(context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, oldServices);
            Settings.Secure.putInt(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, oldEnabled);
            automation.dropShellPermissionIdentity();
            String mode = oldUsageMode == AppOpsManager.MODE_ALLOWED ? "allow"
                    : oldUsageMode == AppOpsManager.MODE_IGNORED ? "ignore"
                    : oldUsageMode == AppOpsManager.MODE_ERRORED ? "deny" : "default";
            shell(automation, "appops set " + context.getPackageName() + " GET_USAGE_STATS " + mode);
            shell(automation, "am start -a android.intent.action.MAIN -c android.intent.category.HOME");
            if (original == null) policies.reset(target).join();
            else policies.save(target, original).join();
            instrumentation.removeMonitor(monitor);
        }
    }

    /**
     * 执行测试设备上的系统授权命令，并读完输出以等待命令完成。
     *
     * @param automation 当前测试的系统自动化连接
     * @param command 只针对测试应用或测试授权的固定命令
     */
    private static void shell(UiAutomation automation, String command) throws Exception {
        try (InputStream output = new ParcelFileDescriptor.AutoCloseInputStream(
                automation.executeShellCommand(command))) {
            byte[] buffer = new byte[256];
            while (output.read(buffer) != -1) { /* 等待命令输出关闭。 */ }
        }
    }
}
