package github.vhoom.zhijian.accessibility;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.Handler;
import android.os.SystemClock;

import androidx.test.platform.app.InstrumentationRegistry;

import github.vhoom.zhijian.domain.interception.InterceptionEvaluation;
import github.vhoom.zhijian.domain.interception.InterceptionDecision;
import github.vhoom.zhijian.data.model.policy.ControlLevel;
import github.vhoom.zhijian.data.model.runtime.PendingInterception;
import github.vhoom.zhijian.data.model.policy.RuleGroup;
import github.vhoom.zhijian.data.model.policy.RuleType;
import github.vhoom.zhijian.data.store.ZhijianStorage;
import github.vhoom.zhijian.data.store.RuleGroupStore;
import github.vhoom.zhijian.data.store.interception.PendingInterceptionStore;
import github.vhoom.zhijian.data.model.runtime.PassGrant;
import github.vhoom.zhijian.data.model.runtime.InterceptionRecord;
import github.vhoom.zhijian.data.store.RuntimeStateStore;
import github.vhoom.zhijian.data.store.settings.GlobalSettings;
import github.vhoom.zhijian.domain.interception.RetreatReentryPolicy;
import github.vhoom.zhijian.runtime.interception.InterceptionRuntime;
import github.vhoom.zhijian.util.config.AppConfig;
import github.vhoom.zhijian.util.diagnostics.InterceptionLaunchDiagnostics;
import github.vhoom.zhijian.util.time.BootIdentity;

import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

/**
 * 在主线程复现前台检查、闭关返回兜底及窗口回执等待之间的竞争。
 *
 * 维护日期：2026年11月14日
 */
public class ForegroundEvaluationTest {
    /** 在测试线程等待提交快照就绪，避免单独运行时依赖其他用例的初始化顺序。 */
    @Before public void awaitStorageReady() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ZhijianStorage.get(context).ready().get(30L, TimeUnit.SECONDS);
    }

    /** 带普通应用启动入口的第三方桌面，也必须在缓存资格之前排除。 */
    @Test public void homePackageOverridesCachedApplicationEligibility() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ManagedPackageFilter filter = new ManagedPackageFilter(context);
        @SuppressWarnings("unchecked")
        Map<String, Boolean> cache = (Map<String, Boolean>) read(filter, "cache");
        @SuppressWarnings("unchecked")
        java.util.Set<String> homes = (java.util.Set<String>) read(filter, "homePackages");
        cache.put("test.launcher", true);
        homes.add("test.launcher");
        assertFalse(filter.accepts("test.launcher"));
        assertFalse(filter.isTransientWindow("test.launcher"));
    }

    /** 旧页面关闭的通知不能把当前另一应用的前台身份改成原目标。 */
    @Test public void completionCannotReplaceAnotherApplicationsForeground() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ZhijianAccessibilityService service = prepareBusyService();
            try {
                ManagedPackageFilter filter = (ManagedPackageFilter) read(service, "packageFilter");
                @SuppressWarnings("unchecked")
                Map<String, Boolean> cache = (Map<String, Boolean>) read(filter, "cache");
                cache.put("test.previous", true);
                long version = (long) read(service, "foregroundVersion");
                service.resumeTarget("test.previous");
                assertTrue("test.current".equals(read(service, "foregroundPackage")));
                assertTrue(version == (long) read(service, "foregroundVersion"));
            } finally { service.onDestroy(); }
        });
    }

    /** 同组超限结果在交互档位重定向，闭关档位保留完整复查入口。 */
    @Test public void sharedCountResultFollowsCurrentGroupMember() {
        assertSharedCountRouting(false, false);
    }

    /** 当前组员已取得有效临时放行时，其他组员的旧异步结果不能覆盖放行。 */
    @Test public void sharedCountResultRespectsCurrentMembersActivePass() {
        assertSharedCountRouting(true, false);
    }

    /** 组内切换后的旧提醒不能直接展示，必须先复查当前应用可能刚命中的禁用规则。 */
    @Test public void staleSharedReminderRechecksCurrentMembersRulesBeforeLaunching() {
        assertSharedCountRouting(false, true);
    }

    /**
     * @param activePass 是否先给当前组员签发有效的临时放行
     * @param reminder 是否模拟需要复查禁用边界的旧提醒结果
     */
    private static void assertSharedCountRouting(boolean activePass, boolean reminder) {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ZhijianStorage.get(context).ready().join();
        RuleGroupStore groups = new RuleGroupStore(context);
        RuleGroup group = groups.create();
        group.types.add(RuleType.DAILY_COUNT);
        group.count = 1;
        if (reminder) {
            group.types.add(RuleType.CONTINUOUS);
            group.continuousLimitMillis = 60_000L;
            group.interruptionPauseMillis = 60_000L;
        }
        group.apps.add("test.current");
        group.apps.add("test.previous");
        groups.save(group).join();
        try {
            github.vhoom.zhijian.data.store.PolicySnapshot snapshot = ZhijianStorage.get(context).snapshot();
            String policyId = snapshot.effectivePolicyId("test.previous");
            InterceptionDecision decision = new InterceptionDecision("test.previous", policyId,
                    snapshot.revisionOf(policyId), reminder ? InterceptionDecision.Kind.INTERRUPT
                            : InterceptionDecision.Kind.BLOCK,
                    reminder ? InterceptionDecision.Trigger.CONTINUOUS : InterceptionDecision.Trigger.DAILY_COUNT,
                    snapshot.level(), "", reminder ? 60_000L : 2L, reminder ? 60_000L : 1L,
                    reminder ? 0L : System.currentTimeMillis() + 60_000L, 180_000L,
                    reminder ? 60_000L : 0L);
            PendingInterception pending = new PendingInterception(decision, "redirect-shared-count",
                    "test-boot", System.currentTimeMillis(), SystemClock.elapsedRealtime());
            new PendingInterceptionStore(context).save(pending);
            if (activePass) {
                long wall = System.currentTimeMillis();
                long elapsed = SystemClock.elapsedRealtime();
                InterceptionRecord record = new InterceptionRecord(java.util.UUID.randomUUID().toString(), "test.current",
                        policyId, decision.policyRevision, "block", snapshot.level().code,
                        "daily_count", "none", "allowed", wall, wall);
                PassGrant grant = new PassGrant("test.current", policyId, decision.policyRevision,
                        BootIdentity.read(context), wall, 60_000L, elapsed + 60_000L);
                new RuntimeStateStore(context).recordInterception(record, grant).join();
            }
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                ZhijianAccessibilityService service = prepareBusyService();
                try {
                    completeEvaluation(service, "test.previous", InterceptionEvaluation.restore(pending));
                    Intent launch = ((RecordingContext) service.getBaseContext()).lastLaunch;
                    if (activePass || reminder || snapshot.level() == ControlLevel.SEAL) {
                        assertTrue("A pass, stale reminder or seal mode must recheck before launching", launch == null);
                        assertScheduled(service);
                    } else {
                        assertTrue("An exceeded group count must follow the current member", launch != null);
                        assertTrue("test.current".equals(launch.getStringExtra("interception.package")));
                        assertTrue(pending.sessionId.equals(launch.getStringExtra("interception.session")));
                    }
                } finally { service.onDestroy(); }
            });
        } finally {
            PendingInterceptionStore pendingStore = new PendingInterceptionStore(context);
            for (PendingInterception fact : pendingStore.restrictions()) {
                if (!("group:" + group.id).equals(fact.decision.policyId)) continue;
                if (fact.decision.kind == InterceptionDecision.Kind.BLOCK) pendingStore.clearBlock(fact);
                pendingStore.clear(fact.decision.packageName, fact.sessionId);
            }
            pendingStore.clear("test.previous", "redirect-shared-count");
            groups.remove(group.id).join();
        }
    }

    /** 真正回到桌面要取消应用检查，不能把桌面当作输入法浮层。 */
    @Test public void homeWindowEndsManagedForegroundCheck() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ZhijianAccessibilityService service = prepareBusyService();
            Context context = service.getBaseContext();
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            String homePackage = context.getPackageManager().resolveActivity(home, 0).activityInfo.packageName;
            android.view.accessibility.AccessibilityEvent event = android.view.accessibility.AccessibilityEvent.obtain(
                    android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
            event.setPackageName(homePackage);
            try {
                invoke(service, "scheduleEvaluation", new Class<?>[]{long.class}, 1_000L);
                service.onAccessibilityEvent(event);
                assertTrue(homePackage.equals(read(service, "foregroundPackage")));
                Handler handler = (Handler) read(service, "main");
                assertFalse(handler.hasCallbacks((Runnable) read(service, "scheduledEvaluation")));
            } finally { event.recycle(); service.onDestroy(); }
        });
    }

    /** 输入法或系统浮层的窗口事件不能结束目标应用的阈值检查。 */
    @Test public void transientWindowCannotCancelForegroundDeadline() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ZhijianAccessibilityService service = prepareBusyService();
            android.view.accessibility.AccessibilityEvent event = android.view.accessibility.AccessibilityEvent.obtain(
                    android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
            event.setPackageName("test.transient.window");
            try {
                invoke(service, "scheduleEvaluation", new Class<?>[]{long.class}, 1_000L);
                long due = (long) read(service, "scheduledAtUptimeMs");
                service.onAccessibilityEvent(event);
                assertTrue("Floating windows must retain the managed foreground",
                        "test.current".equals(read(service, "foregroundPackage")));
                assertTrue(due == (long) read(service, "scheduledAtUptimeMs"));
                assertScheduled(service);
            } finally { event.recycle(); service.onDestroy(); }
        });
    }

    /** 组内快速切换也不能一再把即将执行的检查推迟到切换之后。 */
    @Test public void switchingManagedAppsKeepsEarlierDeadline() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ZhijianAccessibilityService service = prepareBusyService();
            ManagedPackageFilter filter = (ManagedPackageFilter) read(service, "packageFilter");
            @SuppressWarnings("unchecked")
            Map<String, Boolean> cache = (Map<String, Boolean>) read(filter, "cache");
            cache.put("test.next", true);
            android.view.accessibility.AccessibilityEvent event = android.view.accessibility.AccessibilityEvent.obtain(
                    android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
            event.setPackageName("test.next");
            try {
                invoke(service, "scheduleEvaluation", new Class<?>[]{long.class}, 10L);
                long due = (long) read(service, "scheduledAtUptimeMs");
                service.onAccessibilityEvent(event);
                assertTrue(due == (long) read(service, "scheduledAtUptimeMs"));
                assertScheduled(service);
            } finally { event.recycle(); service.onDestroy(); }
        });
    }

    /** 系统接受启动请求但页面没有显示时，必须持续复查，不能仅等待下一次应用切换。 */
    @Test public void acceptedLaunchWithoutVisibleWindowKeepsChecking() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ZhijianAccessibilityService service = prepareBusyService();
            try {
                PendingInterception pending = currentPending(service.getBaseContext(), "launch-without-window");
                completeEvaluation(service, "test.current", InterceptionEvaluation.restore(pending));
                Intent launch = ((RecordingContext) service.getBaseContext()).lastLaunch;
                assertTrue("The valid request must reach startActivity before checking retries", launch != null);
                assertTrue(pending.sessionId.equals(launch.getStringExtra("interception.session")));
                assertScheduled(service);
            } finally { service.onDestroy(); }
        });
    }

    /** 已接受的返回仍停留在目标内部页面时，只返回分支的下一次完整复查必须弹页兜底。 */
    @Test public void acceptedBackOnlyWithoutLeavingTargetFallsBackToPage() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ZhijianStorage.get(context).ready().join();
        GlobalSettings settings = new GlobalSettings(context, new AppConfig(context));
        ControlLevel originalLevel = settings.level();
        org.junit.Assume.assumeTrue("Level-switch regression requires a device without active user restrictions",
                originalLevel == ControlLevel.SEAL || new PendingInterceptionStore(context).restrictions().isEmpty());
        settings.setLevel(ControlLevel.SEAL).join();
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                ZhijianAccessibilityService service = prepareBusyService();
                try {
                    PendingInterception pending = currentPending(context, "back-only-still-in-target");
                    RetreatReentryPolicy policy = new RetreatReentryPolicy(() -> true);
                    policy.actionFor(pending, 0L);
                    assertTrue(policy.actionFor(pending, 1L) == RetreatReentryPolicy.Action.BACK_ONLY);
                    write(service, "retreatReentry", policy);
                    write(service, "foregroundVersion", 1L);
                    write(service, "backHandledVersion", 1L);
                    invoke(service, "handleInterception", new Class<?>[]{PendingInterception.class, String.class},
                            pending, "Test target");
                    Intent launch = ((RecordingContext) service.getBaseContext()).lastLaunch;
                    assertTrue("A back action that leaves the target in front must show the page", launch != null);
                    assertTrue(pending.sessionId.equals(launch.getStringExtra("interception.session")));
                    assertScheduled(service);
                } finally { service.onDestroy(); }
            });
        } finally { settings.setLevel(originalLevel).join(); }
    }

    /** 弹页请求发出后才到达的桌面事件，不能取消仍未取得实际窗口回执的返回后请求。 */
    @Test public void lateHomeWindowKeepsBackPageLaunchAwaitingReceipt() {
        assertBackPageReceiptAfterWindows(false);
    }

    /** 返回后弹页期间切到另一应用应取消旧等待，之后的桌面事件不能恢复旧请求。 */
    @Test public void anotherApplicationCancelsBackPageReceiptBeforeReturningHome() {
        assertBackPageReceiptAfterWindows(true);
    }

    /**
     * 从真实服务启动入口发出请求，再按顺序提交窗口事件并核对回执期限。
     * @param visitAnotherApplication 是否在桌面事件之前先发生另一应用的真实前台访问
     */
    private static void assertBackPageReceiptAfterWindows(boolean visitAnotherApplication) {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ZhijianStorage.get(context).ready().join();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ZhijianAccessibilityService service = prepareBusyService();
            PendingInterception pending = currentPending(context, "back-page-receipt");
            try {
                ManagedPackageFilter filter = (ManagedPackageFilter) read(service, "packageFilter");
                @SuppressWarnings("unchecked")
                java.util.Set<String> homes = (java.util.Set<String>) read(filter, "homePackages");
                homes.add("test.launcher");
                @SuppressWarnings("unchecked")
                Map<String, Boolean> cache = (Map<String, Boolean>) read(filter, "cache");
                cache.put("test.next", true);
                write(service, "foregroundVersion", 1L);
                write(service, "backHandledVersion", 1L);
                invoke(service, "launchInterception",
                        new Class<?>[]{PendingInterception.class, String.class, boolean.class},
                        pending, "Test target", true);
                long request = (long) read(service, "launchRequest");
                if (visitAnotherApplication) sendWindow(service, "test.next");
                sendWindow(service, "test.launcher");
                boolean expired = InterceptionLaunchDiagnostics.expired(request,
                        SystemClock.elapsedRealtime() + InterceptionLaunchDiagnostics.TIMEOUT_MS);
                if (visitAnotherApplication) {
                    assertFalse("An unrelated visit must cancel the previous receipt wait", expired);
                } else {
                    assertTrue("A missing page must still time out after the late HOME event", expired);
                }
            } finally {
                InterceptionLaunchDiagnostics.shown(pending.sessionId);
                service.onDestroy();
            }
        });
    }

    /**
     * 构造与当前已提交配置一致的待显示限制，仅驻留内存而不新增用户限制事实。
     * @param context 已完成存储初始化的应用上下文
     * @param sessionId 用于隔离本次回执的测试身份
     * @return 属于测试目标当前策略和强度的限制
     */
    private static PendingInterception currentPending(Context context, String sessionId) {
        github.vhoom.zhijian.data.store.PolicySnapshot snapshot = ZhijianStorage.get(context).snapshot();
        String policyId = snapshot.effectivePolicyId("test.current");
        InterceptionDecision decision = new InterceptionDecision("test.current", policyId,
                snapshot.revisionOf(policyId), InterceptionDecision.Kind.BLOCK,
                InterceptionDecision.Trigger.DAILY_COUNT, snapshot.level(), "", 2L, 1L,
                System.currentTimeMillis() + 60_000L, 180_000L, 0L);
        return new PendingInterception(decision, sessionId, "test-boot",
                System.currentTimeMillis(), SystemClock.elapsedRealtime());
    }

    /**
     * 向真实服务发送并回收一个前台窗口事件。
     * @param service 本次等待页面回执的服务实例
     * @param packageName 系统窗口确认的真实前台包名
     */
    private static void sendWindow(ZhijianAccessibilityService service, String packageName) {
        android.view.accessibility.AccessibilityEvent event = android.view.accessibility.AccessibilityEvent.obtain(
                android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        event.setPackageName(packageName);
        try { service.onAccessibilityEvent(event); }
        finally { event.recycle(); }
    }

    /** 高频同包窗口事件不能改变前台代次或推迟已安排的阈值检查。 */
    @Test public void repeatedWindowEventsCannotPostponeThresholdEvaluation() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ZhijianAccessibilityService service = prepareBusyService();
            android.view.accessibility.AccessibilityEvent event = android.view.accessibility.AccessibilityEvent.obtain(
                    android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
            event.setPackageName("test.current");
            try {
                invoke(service, "scheduleEvaluation", new Class<?>[]{long.class}, 1_000L);
                long due = (long) read(service, "scheduledAtUptimeMs");
                long version = (long) read(service, "foregroundVersion");
                for (int i = 0; i < 200; i++) service.onAccessibilityEvent(event);
                assertTrue((long) read(service, "scheduledAtUptimeMs") <= due);
                assertTrue(version == (long) read(service, "foregroundVersion"));
                assertScheduled(service);
            } finally {
                event.recycle();
                service.onDestroy();
            }
        });
    }

    /** 桌面事件遗漏后的同包返回仍应尽快复查系统访问证据，不能等上一轮分钟级定时器。 */
    @Test public void samePackageReturnRequestsEvidenceRecheckWithoutInventingVisit() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ZhijianAccessibilityService service = prepareBusyService();
            android.view.accessibility.AccessibilityEvent event = android.view.accessibility.AccessibilityEvent.obtain(
                    android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
            event.setPackageName("test.current");
            try {
                invoke(service, "scheduleEvaluation", new Class<?>[]{long.class}, 60_000L);
                long version = (long) read(service, "foregroundVersion");
                long observation = (long) read(service, "foregroundObservedAtMs");
                service.onAccessibilityEvent(event);
                long due = (long) read(service, "scheduledAtUptimeMs");
                assertTrue("A return must trigger a prompt evidence recheck", due <= SystemClock.uptimeMillis() + 500L);
                assertTrue(version == (long) read(service, "foregroundVersion"));
                assertTrue(observation == (long) read(service, "foregroundObservedAtMs"));
                assertScheduled(service);
            } finally { event.recycle(); service.onDestroy(); }
        });
    }

    /** 较晚的重试或恢复请求不能覆盖已存在的较早检查。 */
    @Test public void laterRetryKeepsExistingEarlierDeadline() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ZhijianAccessibilityService service = prepareBusyService();
            try {
                invoke(service, "scheduleEvaluation", new Class<?>[]{long.class}, 1_000L);
                long due = (long) read(service, "scheduledAtUptimeMs");
                invoke(service, "scheduleEvaluation", new Class<?>[]{long.class}, 30_000L);
                assertTrue(due == (long) read(service, "scheduledAtUptimeMs"));
                assertScheduled(service);
            } finally { service.onDestroy(); }
        });
    }

    /** 没有当天规则的允许结果也必须在午夜检查，不能继续等待完整一分钟。 */
    @Test public void unrestrictedForegroundRechecksAtMidnight() {
        assertAllowedResultDelay(60_000L, 23, 59, 50, 0L, 10_000L);
    }

    /** 计算和主线程排队已经消耗两秒时，六秒额度只剩四秒。 */
    @Test public void delayedEvaluationDoesNotRestartQuotaCountdown() {
        assertAllowedResultDelay(6_000L, 12, 0, 0, 2_000L, 4_000L);
    }

    /** 计算结果到达时已跨过午夜，真实 Handler 应立即安排复查。 */
    @Test public void midnightPassedDuringEvaluationRechecksImmediately() {
        assertAllowedResultDelay(60_000L, 23, 59, 50, 11_000L, 1L);
    }

    /** 剩余额度在计算期间耗尽时，真实 Handler 不能再次等待原额度。 */
    @Test public void quotaExpiredDuringEvaluationRechecksImmediately() {
        assertAllowedResultDelay(6_000L, 12, 0, 0, 7_000L, 1L);
    }

    /**
     * 把固定墙上时间和已耗时传入真实完成回调，检查 Handler 期限而不改设备时钟。
     * @param recheckAfterMs 规则计算出的原复查间隔
     * @param hour 计算开始时的当地小时
     * @param minute 计算开始时的当地分钟
     * @param second 计算开始时的当地秒数
     * @param spentMs 模拟后台计算及回调排队已经消耗的时间
     * @param maximumDelayMs 主线程收到结果后允许的最大等待时间
     */
    private static void assertAllowedResultDelay(long recheckAfterMs, int hour, int minute, int second,
            long spentMs, long maximumDelayMs) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ZhijianAccessibilityService service = prepareBusyService();
            try {
                long wall = LocalDate.of(2026, 11, 15).atTime(hour, minute, second)
                        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                long startedElapsed = SystemClock.elapsedRealtime() - spentMs;
                invoke(service, "completeEvaluation",
                        new Class<?>[]{String.class, InterceptionEvaluation.class, Throwable.class,
                                long.class, long.class},
                        "test.current", InterceptionEvaluation.allow(recheckAfterMs), null, wall, startedElapsed);
                long due = (long) read(service, "scheduledAtUptimeMs");
                assertTrue("The original rule or midnight deadline must bound the next check",
                        due <= SystemClock.uptimeMillis() + maximumDelayMs);
                assertScheduled(service);
            } finally { service.onDestroy(); }
        });
    }

    /** 目标包名相同但已经离开又返回时，旧拦截结果也必须被丢弃并补查。 */
    @Test
    public void previousVisitResultCannotBlockResumedTarget() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ZhijianAccessibilityService service = prepareBusyService();
            try {
                write(service, "foregroundVersion", 2L);
                write(service, "evaluationVersion", 1L);
                PendingInterception stale = currentPending(service.getBaseContext(), "previous-visit");
                completeEvaluation(service, "test.current", InterceptionEvaluation.restore(stale));
                assertTrue("A previous visit must not launch a page",
                        ((RecordingContext) service.getBaseContext()).lastLaunch == null);
                assertScheduled(service);
            } finally {
                service.onDestroy();
            }
        });
    }

    /** 服务销毁后的异步结果不能重新排队或启动页面。 */
    @Test
    public void destroyedServiceIgnoresLateEvaluation() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ZhijianAccessibilityService service = prepareBusyService();
            PendingInterception pending = currentPending(service.getBaseContext(), "destroyed-service");
            service.onDestroy();
            completeEvaluation(service, "test.current", InterceptionEvaluation.restore(pending));
            Handler handler = (Handler) read(service, "main");
            assertFalse(handler.hasCallbacks((Runnable) read(service, "scheduledEvaluation")));
            assertTrue("A destroyed service must not launch a late blocking result",
                    ((RecordingContext) service.getBaseContext()).lastLaunch == null);
        });
    }
    /** 新检查遇到旧检查未完成时仍须安排重试，不能丢掉返回应用的事件。 */
    @Test
    public void busyEvaluationRetainsPendingForegroundCheck() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ZhijianAccessibilityService service = prepareBusyService();
            try {
                invoke(service, "evaluateForeground", new Class<?>[0]);
                assertScheduled(service);
            } finally {
                service.onDestroy();
            }
        });
    }

    /** 旧应用的检查完成后，仍需检查已经切换到的最新前台应用。 */
    @Test
    public void staleEvaluationSchedulesLatestForeground() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ZhijianAccessibilityService service = prepareBusyService();
            try {
                completeEvaluation(service, "test.previous", InterceptionEvaluation.allow(1_000L));
                assertScheduled(service);
            } finally {
                service.onDestroy();
            }
        });
    }

    /**
     * 提交未模拟计算耗时的规则结果，继续覆盖生产回调的前台与生命周期分支。
     * @param service 本次接收异步结果的真实服务实例
     * @param packageName 本轮计算的目标包名
     * @param result 需要交给真实完成回调的结果
     */
    private static void completeEvaluation(ZhijianAccessibilityService service, String packageName,
            InterceptionEvaluation result) {
        invoke(service, "completeEvaluation",
                new Class<?>[]{String.class, InterceptionEvaluation.class, Throwable.class, long.class, long.class},
                packageName, result, null, System.currentTimeMillis(), SystemClock.elapsedRealtime());
    }

    /** 建立有最新前台应用、旧检查尚在执行的真实服务实例。 */
    private static ZhijianAccessibilityService prepareBusyService() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ManagedPackageFilter filter = new ManagedPackageFilter(context);
        @SuppressWarnings("unchecked")
        Map<String, Boolean> cache = (Map<String, Boolean>) read(filter, "cache");
        cache.put("test.current", true);
        ZhijianAccessibilityService service = new ZhijianAccessibilityService();
        try {
            Method attach = ContextWrapper.class.getDeclaredMethod("attachBaseContext", Context.class);
            attach.setAccessible(true);
            attach.invoke(service, new RecordingContext(context));
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        write(service, "packageFilter", filter);
        write(service, "runtime", new InterceptionRuntime(context));
        write(service, "foregroundPackage", "test.current");
        write(service, "evaluating", true);
        return service;
    }

    /** 记录真实服务的启动 Intent，模拟系统接受请求而未实际展示窗口。 */
    private static final class RecordingContext extends ContextWrapper {
        private Intent lastLaunch;

        /** @param context 提供系统服务与资源的测试应用上下文 */
        private RecordingContext(Context context) { super(context); }

        /** @param intent 服务请求展示的拦截页 */
        @Override public void startActivity(Intent intent) { lastLaunch = intent; }
    }

    /**
     * 检查服务自己的 Handler 中存在后续规则检查。
     *
     * @param service 当前接受事件的服务实例
     */
    private static void assertScheduled(ZhijianAccessibilityService service) {
        Handler handler = (Handler) read(service, "main");
        Runnable check = (Runnable) read(service, "scheduledEvaluation");
        assertTrue("Returning foreground must keep a scheduled check", handler.hasCallbacks(check));
    }

    /**
     * 读取服务或过滤器的内部状态，保持测试覆盖真实调度入口。
     *
     * @param object 待观察的实例
     * @param name 内部字段名称
     * @return 当前字段值
     */
    private static Object read(Object object, String name) {
        try {
            Field field = object.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(object);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    /**
     * 准备测试需要的并发状态，避免依赖设备安装其他第三方应用。
     *
     * @param object 待配置的实例
     * @param name 内部字段名称
     * @param value 本次测试使用的字段值
     */
    private static void write(Object object, String name, Object value) {
        try {
            Field field = object.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(object, value);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    /**
     * 调用真实服务的规则调度方法。
     *
     * @param service 当前服务实例
     * @param name 待执行的方法名称
     * @param types 方法参数类型
     * @param arguments 本次检查使用的参数
     */
    private static void invoke(ZhijianAccessibilityService service, String name,
            Class<?>[] types, Object... arguments) {
        try {
            Method method = ZhijianAccessibilityService.class.getDeclaredMethod(name, types);
            method.setAccessible(true);
            method.invoke(service, arguments);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }
}
