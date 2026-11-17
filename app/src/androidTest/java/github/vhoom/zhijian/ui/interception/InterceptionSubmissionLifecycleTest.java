package github.vhoom.zhijian.ui.interception;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import github.vhoom.zhijian.R;
import github.vhoom.zhijian.data.model.interception.InterceptionEffect;
import github.vhoom.zhijian.data.model.interception.InterceptionMode;
import github.vhoom.zhijian.data.model.policy.ControlLevel;
import github.vhoom.zhijian.data.model.settings.InteractionSettings;
import github.vhoom.zhijian.data.model.settings.InterceptionOptions;
import github.vhoom.zhijian.data.store.RuntimeStateStore;
import github.vhoom.zhijian.data.store.ZhijianStorage;
import github.vhoom.zhijian.data.store.settings.InteractionSettingsStore;
import github.vhoom.zhijian.domain.interception.InterceptionDecision;
import github.vhoom.zhijian.ui.interception.session.InterceptionSubmission;
import github.vhoom.zhijian.util.config.AppConfig;
import github.vhoom.zhijian.util.time.BootIdentity;
import org.junit.Test;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import static org.junit.Assert.*;

/**
 * 验证全部页面竖屏、保存中重建保留返回出口，以及进程恢复重放不重复审计。
 *
 * 维护日期：2026年11月16日
 */
public final class InterceptionSubmissionLifecycleTest {
    /** 安装包中全部止间页面，包括调试入口，均声明固定竖屏。 */
    @Test public void allApplicationActivitiesRequestPortrait() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ActivityInfo[] activities = context.getPackageManager().getPackageInfo(context.getPackageName(),
                PackageManager.GET_ACTIVITIES).activities;
        assertNotNull(activities);
        for (ActivityInfo activity : activities) assertEquals(activity.name,
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, activity.screenOrientation);
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            assertTrue(context.getPackageManager().getProperty(
                    "android.window.PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY", context.getPackageName()).getBoolean());
        }
    }

    /** 暂停后台完成点后重建，仍显示保存页；原任务完成后当前实例执行已确认的继续。 */
    @Test public void recreatedSavingPageKeepsOriginalContinueUntilCompletion() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        CompletableFuture<Void> gate = new CompletableFuture<>();
        String session = "submission-recreate-" + UUID.randomUUID();
        try (ActivityScenario<InterceptionActivity> scenario = ActivityScenario.launch(
                InterceptionActivity.debugIntent(context, InterceptionDecision.Kind.BLOCK, ControlLevel.MORTAL, session))) {
            scenario.onActivity(activity -> {
                Bundle state = new Bundle();
                invoke(activity, "onSaveInstanceState", Bundle.class, state);
                InterceptionSubmission submission = new InterceptionSubmission(UUID.randomUUID().toString(),
                        (InterceptionDecision) get(activity, "decision"), session, System.currentTimeMillis(), null,
                        (InteractionSettings) get(activity, "interactionSettings"), InterceptionEffect.RETURN_TO_TARGET,
                        System.currentTimeMillis(), SystemClock.elapsedRealtime(), BootIdentity.read(context));
                set(submission, "task", gate);
                set(activity, "submission", submission);
                set(activity, "completionPageState", state);
                set(activity, "busy", true);
                invoke(activity, "observeSubmission", null, null);
            });
            scenario.recreate();
            scenario.onActivity(activity -> {
                assertTrue((boolean) get(activity, "busy"));
                assertNull(get(activity, "activePage"));
                assertEquals(View.VISIBLE, activity.findViewById(R.id.interception_page_saving).getVisibility());
                assertEquals(InterceptionEffect.RETURN_TO_TARGET,
                        ((InterceptionSubmission) get(activity, "submission")).effect);
            });
            gate.complete(null);
            long deadline = SystemClock.elapsedRealtime() + 5_000L;
            while (scenario.getState() != Lifecycle.State.DESTROYED && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(25L);
            }
            assertEquals(Lifecycle.State.DESTROYED, scenario.getState());
        }
    }

    /** 新进程没有原 Future 时，相同身份与确认时间重放仍只保存一条审计。 */
    @Test public void restoredSubmissionReplaysWithSameAuditIdentityAndClock() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ZhijianStorage.get(context).ready().join();
        String id = UUID.randomUUID().toString();
        String session = "replay-" + id;
        long wall = System.currentTimeMillis();
        InterceptionDecision decision = new InterceptionDecision("test.submission." + id, "test.submission", 1L,
                InterceptionDecision.Kind.BLOCK, InterceptionDecision.Trigger.DAILY_COUNT,
                ControlLevel.REPAIR, "", 2L, 1L, wall + 60_000L, 60_000L, 0L);
        InteractionSettings current = new InteractionSettingsStore(context, new AppConfig(context)).read(InterceptionMode.REPAIR);
        InteractionSettings settings = new InteractionSettings(current.enabledChallenges, current.holdRange,
                current.word, new InterceptionOptions(false, false));
        InterceptionSubmission submission = new InterceptionSubmission(id, decision, session, wall, null, settings,
                InterceptionEffect.RETURN_TO_TARGET, wall, SystemClock.elapsedRealtime(), BootIdentity.read(context));
        CompletableFuture<Void> saved = submission.save(context);
        assertSame(saved, submission.save(context));
        saved.join();
        Bundle state = new Bundle();
        submission.saveState(state);
        InterceptionSubmission restored = InterceptionSubmission.restore(state, null, decision, session, wall, null, settings);
        assertEquals(submission.id, restored.id);
        assertEquals(submission.effect, restored.effect);
        restored.save(context).join();
        assertEquals(1L, new RuntimeStateStore(context).page(null, 100).join().stream()
                .filter(record -> record.id.equals("result:" + id) && record.finishedAtMs == wall).count());
    }

    /** @param target 待观察对象 @param name 私有状态字段 @return 当前字段值 */
    private static Object get(Object target, String name) {
        try { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    /** @param target 测试对象 @param name 待注入字段 @param value 可控后台任务或页面状态 */
    private static void set(Object target, String name, Object value) {
        try { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    /** @param activity 当前页面 @param name 生命周期接线入口 @param type 唯一参数类型，可空 @param value 参数值 */
    private static void invoke(InterceptionActivity activity, String name, Class<?> type, Object value) {
        try {
            Method method = type == null ? InterceptionActivity.class.getDeclaredMethod(name)
                    : InterceptionActivity.class.getDeclaredMethod(name, type);
            method.setAccessible(true);
            if (type == null) method.invoke(activity); else method.invoke(activity, value);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
}
