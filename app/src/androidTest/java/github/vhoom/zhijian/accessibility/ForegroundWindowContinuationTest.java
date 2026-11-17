package github.vhoom.zhijian.accessibility;

import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.view.accessibility.AccessibilityEvent;

import androidx.test.platform.app.InstrumentationRegistry;

import github.vhoom.zhijian.data.model.policy.ControlLevel;
import github.vhoom.zhijian.data.model.runtime.PendingInterception;
import github.vhoom.zhijian.data.store.ZhijianStorage;
import github.vhoom.zhijian.data.store.interception.BlockContinuationStore;
import github.vhoom.zhijian.domain.interception.InterceptionDecision;
import github.vhoom.zhijian.domain.interception.InterceptionEvaluation;
import github.vhoom.zhijian.runtime.interception.InterceptionRuntime;

import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * 用真实前台事件入口和隔离的继续确认存储验证键盘、弹窗及真实离开的访问边界。
 *
 * 维护日期：2026年11月17日
 */
public class ForegroundWindowContinuationTest {
    /** 测试线程先等待共享配置初始化，主线程不阻塞存储的就绪回调。 */
    @Before public void awaitStorageReady() throws Exception {
        ZhijianStorage.get(InstrumentationRegistry.getInstrumentation().getTargetContext())
                .ready().get(30L, TimeUnit.SECONDS);
    }

    /** 有启动图标的键盘窗口也不能清除随心或修心已保存的继续确认。 */
    @Test public void keyboardWithLauncherPreservesConfirmedVisit() throws Exception {
        for (ControlLevel level : new ControlLevel[]{ControlLevel.MORTAL, ControlLevel.REPAIR}) {
            try (Fixture fixture = new Fixture(level)) {
                fixture.onMain(() -> {
                    fixture.send("test.ime", "android.inputmethodservice.SoftInputWindow");
                    fixture.send("test.current", "test.current.MainActivity");
                    assertEquals("test.current", read(fixture.service, "foregroundPackage"));
                    assertEquals(0L, read(fixture.service, "foregroundVersion"));
                });
                fixture.assertContinuation(true);
            }
        }
    }

    /** 输入法子控件的包名不能直接结束访问，系统仍显示原应用时应保留确认。 */
    @Test public void genericKeyboardWindowUsesSystemForegroundBeforeEndingVisit() throws Exception {
        try (Fixture fixture = new Fixture(ControlLevel.MORTAL)) {
            fixture.onMain(() -> {
                fixture.send("test.ime", "android.widget.FrameLayout");
                assertTrue((boolean) read(fixture.service, "windowConfirmationPending"));
                assertEquals("test.current", read(fixture.service, "foregroundPackage"));
                fixture.confirm("test.current", null);
                fixture.send("test.current", "test.current.MainActivity");
                assertEquals(0L, read(fixture.service, "foregroundVersion"));
            });
            fixture.assertContinuation(true);
        }
    }

    /** 输入法自己的设置 Activity 是真实离开，不能按输入法包名整体排除。 */
    @Test public void inputMethodSettingsActivityEndsPreviousVisit() throws Exception {
        try (Fixture fixture = new Fixture(ControlLevel.REPAIR)) {
            fixture.onMain(() -> {
                fixture.send("test.ime", "test.ime.SettingsActivity");
                assertEquals("test.ime", read(fixture.service, "foregroundPackage"));
                assertFalse((boolean) read(fixture.service, "windowConfirmationPending"));
            });
            fixture.assertContinuation(false);
        }
    }

    /** 厂商事件没有提供 Activity 类名时，系统确认输入法应用真正前台仍应清除旧确认。 */
    @Test public void incompleteWindowMetadataCanStillConfirmRealApplicationDeparture() throws Exception {
        try (Fixture fixture = new Fixture(ControlLevel.MORTAL)) {
            fixture.onMain(() -> {
                fixture.send("test.ime", null);
                fixture.confirm("test.ime", null);
                assertEquals("test.ime", read(fixture.service, "foregroundPackage"));
            });
            fixture.assertContinuation(false);
        }
    }

    /** 有启动入口的其他应用弹出非 Activity 窗口，不能仅凭包名清除当前确认。 */
    @Test public void launchableApplicationOverlayPreservesUnderlyingVisit() throws Exception {
        try (Fixture fixture = new Fixture(ControlLevel.MORTAL)) {
            fixture.onMain(() -> {
                fixture.send("test.other", "android.app.Dialog");
                fixture.confirm("test.current", null);
                assertEquals("test.current", read(fixture.service, "foregroundPackage"));
            });
            fixture.assertContinuation(true);
        }
    }

    /** 键盘出现后真实返回桌面或切换应用，仍结束本次继续确认。 */
    @Test public void realDepartureAfterKeyboardClearsContinuation() throws Exception {
        for (String target : new String[]{"test.home", "test.other"}) {
            try (Fixture fixture = new Fixture(ControlLevel.MORTAL)) {
                fixture.onMain(() -> {
                    fixture.send("test.ime", "android.inputmethodservice.SoftInputWindow");
                    fixture.send(target, target + ".MainActivity");
                    assertEquals(target, read(fixture.service, "foregroundPackage"));
                });
                fixture.assertContinuation(false);
            }
        }
    }

    /** 等待模糊窗口核实时已发生真实切换，旧核实回调不能覆盖新前台或恢复旧访问。 */
    @Test public void lateWindowConfirmationCannotOverwriteNewForeground() throws Exception {
        try (Fixture fixture = new Fixture(ControlLevel.MORTAL)) {
            fixture.onMain(() -> {
                fixture.send("test.ime", "android.widget.FrameLayout");
                long original = (long) read(fixture.service, "windowEventGeneration");
                fixture.send("test.other", "test.other.MainActivity");
                fixture.confirm(original, "test.ime", "test.ime", null);
                assertEquals("test.other", read(fixture.service, "foregroundPackage"));
                assertFalse((boolean) read(fixture.service, "windowConfirmationPending"));
            });
            fixture.assertContinuation(false);
        }
    }

    /** 窗口身份尚未核实时，旧应用的异步计算与直接弹页请求都不得显示拦截。 */
    @Test public void pendingWindowConfirmationDefersOldInterceptionResults() throws Exception {
        try (Fixture fixture = new Fixture(ControlLevel.MORTAL)) {
            fixture.onMain(() -> {
                fixture.send("test.other", "android.app.Dialog");
                invoke(fixture.service, "completeEvaluation",
                        new Class<?>[]{String.class, InterceptionEvaluation.class, Throwable.class,
                                long.class, long.class},
                        "test.current", InterceptionEvaluation.restore(fixture.pending), null, 0L, 0L);
                invoke(fixture.service, "launchInterception",
                        new Class<?>[]{PendingInterception.class, String.class, boolean.class},
                        fixture.pending, "Test", false);
                assertNull(fixture.context.lastLaunch);
                // 本用例核对回调竞争；后续计时器保留为忙状态，不启动真实规则查询。
                write(fixture.service, "evaluating", true);
                fixture.confirm("test.current", null);
            });
            fixture.assertContinuation(true);
        }
    }

    /** 高频键盘通知不能推迟连续提醒的既有期限，也不能伪造新的前台访问。 */
    @Test public void repeatedKeyboardWindowsKeepReminderDeadlineAndVisitIdentity() throws Exception {
        try (Fixture fixture = new Fixture(ControlLevel.REPAIR)) {
            fixture.onMain(() -> {
                invoke(fixture.service, "scheduleEvaluation", new Class<?>[]{long.class}, 1_000L);
                long due = (long) read(fixture.service, "scheduledAtUptimeMs");
                for (int index = 0; index < 100; index++) {
                    fixture.send("test.ime", "test.ime.KeyboardService");
                }
                assertEquals(due, read(fixture.service, "scheduledAtUptimeMs"));
                assertEquals(0L, read(fixture.service, "foregroundVersion"));
                assertEquals(0L, read(fixture.service, "foregroundObservedAtMs"));
                fixture.send("test.ime", "android.widget.FrameLayout");
                long request = (long) read(fixture.service, "windowEventGeneration");
                for (int index = 0; index < 100; index++) {
                    fixture.send("test.ime", "android.widget.FrameLayout");
                }
                assertEquals("Repeated ambiguous windows must reuse the first confirmation",
                        request, read(fixture.service, "windowEventGeneration"));
                fixture.confirm("test.current", null);
                assertTrue((long) read(fixture.service, "scheduledAtUptimeMs") <= due);
            });
            fixture.assertContinuation(true);
        }
    }

    /** 系统证据读取失败不推测离开，保留原确认并继续安排规则复查。 */
    @Test public void failedWindowConfirmationPreservesVisitAndSchedulesRecheck() throws Exception {
        try (Fixture fixture = new Fixture(ControlLevel.MORTAL)) {
            fixture.onMain(() -> {
                fixture.send("test.ime", "android.widget.FrameLayout");
                fixture.confirm(null, new IllegalStateException("test evidence failure"));
                assertEquals("test.current", read(fixture.service, "foregroundPackage"));
                assertFalse((boolean) read(fixture.service, "windowConfirmationPending"));
                Handler handler = (Handler) read(fixture.service, "main");
                assertTrue(handler.hasCallbacks((Runnable) read(fixture.service, "scheduledEvaluation")));
            });
            fixture.assertContinuation(true);
        }
    }

    /** 为每个场景隔离偏好，保持系统元数据可替换而事件与存储路径使用正式实现。 */
    private static final class Fixture implements AutoCloseable {
        private final IsolatedContext context;
        private final ZhijianAccessibilityService service = new ZhijianAccessibilityService();
        private final BlockContinuationStore store;
        private final PendingInterception pending;
        private String candidate;

        /** @param level 本轮确认所属的交互档位 */
        private Fixture(ControlLevel level) {
            context = new IsolatedContext(InstrumentationRegistry.getInstrumentation().getTargetContext());
            store = new BlockContinuationStore(context);
            InterceptionDecision decision = new InterceptionDecision("test.current", "group:test", 1L,
                    InterceptionDecision.Kind.BLOCK, InterceptionDecision.Trigger.WINDOW_BAN,
                    level, "test-window", 1L, 1L, 10_000L, 180_000L, 0L);
            pending = new PendingInterception(decision, "test-session", "test-boot", 1_000L, 1_000L);
            store.observeForeground("test.current");
            assertTrue(store.ignore(pending, decision, 2_000L));
            onMain(() -> {
                try {
                    Method attach = ContextWrapper.class.getDeclaredMethod("attachBaseContext", Context.class);
                    attach.setAccessible(true);
                    attach.invoke(service, context);
                } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
                ManagedPackageFilter filter = new ManagedPackageFilter(context);
                @SuppressWarnings("unchecked")
                Map<String, Boolean> cache = (Map<String, Boolean>) read(filter, "cache");
                for (String name : new String[]{"test.current", "test.ime", "test.other"}) cache.put(name, true);
                @SuppressWarnings("unchecked")
                Set<String> homes = (Set<String>) read(filter, "homePackages");
                homes.add("test.home");
                write(service, "packageFilter", filter);
                write(service, "windowClassifier", new ForegroundWindowClassifier(
                        component -> component.getClassName().endsWith("Activity"),
                        component -> component.equals(new ComponentName("test.ime", "test.ime.KeyboardService"))));
                write(service, "runtime", new InterceptionRuntime(context));
                write(service, "foregroundPackage", "test.current");
                write(service, "evaluating", true);
            });
        }

        /** @param operation 在同一主线程完成的事件序列与断言 */
        private void onMain(Runnable operation) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(operation);
        }

        /** @param packageName 窗口包名 @param className 系统窗口类名，null 表示元数据缺失 */
        private void send(String packageName, String className) {
            candidate = packageName;
            AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
            event.setPackageName(packageName);
            event.setClassName(className);
            try { service.onAccessibilityEvent(event); }
            finally { event.recycle(); }
        }

        /** @param actualPackage 系统证据确认的前台 @param failure 证据读取异常，可为 null */
        private void confirm(String actualPackage, Throwable failure) {
            confirm((long) read(service, "windowEventGeneration"), candidate, actualPackage, failure);
        }

        /**
         * @param generation 原窗口核实代次 @param requestedPackage 原待核实包名
         * @param actualPackage 系统确认的前台 @param failure 证据读取异常，可为 null
         */
        private void confirm(long generation, String requestedPackage, String actualPackage, Throwable failure) {
            invoke(service, "completeWindowConfirmation",
                    new Class<?>[]{long.class, String.class, long.class, String.class, Throwable.class},
                    generation, requestedPackage, System.currentTimeMillis(), actualPackage, failure);
        }

        /** @param expected 是否应保留原确认；排空正式串行前台写入后核对实际偏好 */
        private void assertContinuation(boolean expected) throws Exception {
            ((ExecutorService) read(service, "evaluator")).submit(() -> { }).get(5L, TimeUnit.SECONDS);
            assertEquals(expected ? 1 : 0, store.read("test.current", "test-boot").size());
        }

        /** 关闭服务与主线程回调，排空后台队列后只清除本场景的独立偏好。 */
        @Override public void close() throws Exception {
            onMain(service::onDestroy);
            ((ExecutorService) read(service, "evaluator")).awaitTermination(5L, TimeUnit.SECONDS);
            context.getSharedPreferences("block_continuations", Context.MODE_PRIVATE).edit().clear().commit();
        }
    }

    /** 隔离所有偏好并记录启动请求，不启用系统无障碍服务或修改用户规则。 */
    private static final class IsolatedContext extends ContextWrapper {
        private final String prefix = "test.window." + UUID.randomUUID() + ".";
        private Intent lastLaunch;

        /** @param context 提供真实系统资源与配置的目标应用上下文 */
        private IsolatedContext(Context context) { super(context); }

        /** @param name 正式偏好名 @param mode 私有模式 @return 当前场景的独立偏好 */
        @Override public SharedPreferences getSharedPreferences(String name, int mode) {
            return super.getSharedPreferences(prefix + name, mode);
        }

        /** @param intent 正式服务请求启动的页面，仅记录而不实际打开 */
        @Override public void startActivity(Intent intent) { lastLaunch = intent; }
    }

    /** @param object 被测实例 @param name 字段名 @return 真实运行状态 */
    private static Object read(Object object, String name) {
        try {
            Field field = object.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(object);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    /** @param object 被测实例 @param name 字段名 @param value 本场景需要的初始状态 */
    private static void write(Object object, String name, Object value) {
        try {
            Field field = object.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(object, value);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    /** @param object 被测实例 @param name 方法名 @param types 参数类型 @param arguments 场景输入 */
    private static void invoke(Object object, String name, Class<?>[] types, Object... arguments) {
        try {
            Method method = object.getClass().getDeclaredMethod(name, types);
            method.setAccessible(true);
            method.invoke(object, arguments);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
}
