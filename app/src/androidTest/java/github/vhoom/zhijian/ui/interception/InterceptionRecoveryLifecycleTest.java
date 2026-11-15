package github.vhoom.zhijian.ui.interception;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.SystemClock;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import github.vhoom.zhijian.data.model.policy.ControlLevel;
import github.vhoom.zhijian.data.model.policy.RuleGroup;
import github.vhoom.zhijian.data.model.policy.RuleType;
import github.vhoom.zhijian.data.store.AppPolicyStore;
import github.vhoom.zhijian.data.store.ZhijianStorage;
import github.vhoom.zhijian.domain.interception.InterceptionDecision;
import java.lang.reflect.Field;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 页面离开前台时停止恢复任务，恢复旧请求时立即使用最新规则。
 * 维护日期：2026年11月14日
 */
public class InterceptionRecoveryLifecycleTest {
    /** 后台页面没有休息或规则回调；回到前台后恢复倒计时。 */
    @Test public void pausedPageStopsTimersAndResumeRestartsThem() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (ActivityScenario<InterceptionActivity> scenario = ActivityScenario.launch(
                InterceptionActivity.debugIntent(context, InterceptionDecision.Kind.INTERRUPT,
                        ControlLevel.SEAL, "background-recovery").putExtra("interception.interrupt_pause", 60_000L))) {
            scenario.onActivity(activity -> assertTrue(timerScheduled(activity, "resetTick")));
            scenario.moveToState(Lifecycle.State.STARTED);
            scenario.onActivity(activity -> {
                assertFalse(timerScheduled(activity, "resetTick"));
                assertFalse(timerScheduled(activity, "ruleTick"));
            });
            scenario.moveToState(Lifecycle.State.RESUMED);
            scenario.onActivity(activity -> assertTrue(timerScheduled(activity, "resetTick")));
        }
    }

    /** 配置已放宽的旧禁用请求不再等原恢复时间，也不会反复申请过期版本的放行。 */
    @Test public void restoredBlockImmediatelyRechecksChangedConfiguration() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ZhijianStorage storage = ZhijianStorage.get(context);
        storage.ready().join();
        String target = "test.recovery." + java.util.UUID.randomUUID().toString().replace("-", "");
        AppPolicyStore policies = new AppPolicyStore(context);
        RuleGroup policy = new RuleGroup(0L, "恢复测试");
        policy.types.add(RuleType.DAILY_COUNT);
        policy.count = 1;
        policy.passDurationMillis = 180_000L;
        policies.save(target, policy).join();
        try {
            String policyId = storage.snapshot().effectivePolicyId(target);
            InterceptionDecision previous = new InterceptionDecision(target, policyId,
                    storage.snapshot().revisionOf(policyId), InterceptionDecision.Kind.BLOCK,
                    InterceptionDecision.Trigger.DAILY_COUNT, storage.snapshot().level(), "", 2L, 1L,
                    System.currentTimeMillis() + 3_600_000L, 180_000L, 0L);
            Intent oldRequest = InterceptionActivity.intent(context, previous, "测试应用", "stale-rule-page");
            policy.count = 99;
            policies.save(target, policy).join();
            try (ActivityScenario<InterceptionActivity> scenario = ActivityScenario.launch(oldRequest)) {
                long deadline = SystemClock.elapsedRealtime() + 5_000L;
                while (scenario.getState() != Lifecycle.State.DESTROYED && SystemClock.elapsedRealtime() < deadline) {
                    SystemClock.sleep(25L);
                }
                assertEquals(Lifecycle.State.DESTROYED, scenario.getState());
            }
        } finally { policies.reset(target).join(); }
    }

    /** @param activity 当前页面 @param name 计时回调字段 @return 主线程是否仍排有该回调 */
    private static boolean timerScheduled(InterceptionActivity activity, String name) {
        try {
            Field handler = InterceptionActivity.class.getDeclaredField("main");
            Field callback = InterceptionActivity.class.getDeclaredField(name);
            handler.setAccessible(true);
            callback.setAccessible(true);
            return ((Handler) handler.get(activity)).hasCallbacks((Runnable) callback.get(activity));
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
}
