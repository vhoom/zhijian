package github.vhoom.zhijian.util.permission;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.provider.Settings;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import github.vhoom.zhijian.accessibility.ZhijianAccessibilityService;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.Assert.*;

/**
 * 拦截 startActivity，只检查跳转请求和回退，不打开系统页面或改变设备授权。
 *
 * 维护日期：2026年11月14日
 */
@RunWith(AndroidJUnit4.class)
public class PermissionIntentsTest {
    /**
     * 验证使用访问详情失败后打开不携带包数据的授权列表。
     */
    @Test
    public void usageDetailsFallBackToTheUsageListWithoutPackageData() {
        onMain(() -> {
            CapturingActivity activity = new CapturingActivity(intent ->
                    Settings.ACTION_USAGE_ACCESS_SETTINGS.equals(intent.getAction()) && intent.getData() == null);
            PermissionIntents.openUsageAccess(activity);
            assertEquals(2, activity.attempts.size());
            assertEquals("package:github.vhoom.zhijian", activity.attempts.get(0).getDataString());
            assertEquals("github.vhoom.zhijian", activity.attempts.get(0).getStringExtra(Settings.EXTRA_APP_PACKAGE));
        });
    }

    /**
     * 验证电池白名单申请被拒绝后可回退管理列表。
     */
    @Test
    public void rejectedBatteryRequestFallsBackToOptimizationList() {
        onMain(() -> {
            CapturingActivity activity = new CapturingActivity(intent ->
                    Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS.equals(intent.getAction()));
            PermissionIntents.openBatterySettings(activity, false);
            assertEquals(2, activity.attempts.size());
            assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    activity.attempts.get(0).getAction());
            assertEquals("package:github.vhoom.zhijian", activity.attempts.get(0).getDataString());
        });
    }

    /**
     * 验证已有白名单时打开管理列表，不重复申请。
     */
    @Test
    public void grantedBatteryAccessOpensListWithoutRequestingAgain() {
        onMain(() -> {
            CapturingActivity activity = new CapturingActivity(intent -> true);
            PermissionIntents.openBatterySettings(activity, true);
            assertEquals(1, activity.attempts.size());
            assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
                    activity.attempts.get(0).getAction());
        });
    }

    /**
     * 验证无障碍入口优先精确服务详情，再回退服务列表。
     */
    @Test
    public void accessibilityTargetsTheExactServiceThenFallsBackToTheList() {
        onMain(() -> {
            CapturingActivity activity = new CapturingActivity(intent ->
                    Settings.ACTION_ACCESSIBILITY_SETTINGS.equals(intent.getAction()));
            PermissionIntents.openAccessibilitySettings(activity);
            assertEquals(2, activity.attempts.size());
            ComponentName expected = new ComponentName("github.vhoom.zhijian", ZhijianAccessibilityService.class.getName());
            assertEquals(expected, activity.attempts.get(0).getParcelableExtra(Intent.EXTRA_COMPONENT_NAME));
        });
    }

    /**
     * 后台引导直接使用本应用信息页，不按品牌挑选私有入口。
     */
    @Test
    public void backgroundGuideUsesThisAppsInformationPage() {
        onMain(() -> {
            CapturingActivity activity = new CapturingActivity(intent ->
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS.equals(intent.getAction()));
            PermissionIntents.openBackgroundSettings(activity);
            assertEquals(1, activity.attempts.size());
            assertNull(activity.attempts.get(0).getComponent());
            Intent last = activity.attempts.get(activity.attempts.size() - 1);
            assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, last.getAction());
            assertEquals("package:github.vhoom.zhijian", last.getDataString());
        });
    }

    /**
     * 验证应用信息入口缺失时回退系统设置。
     */
    @Test
    public void unavailableAppInformationFallsBackToSystemSettings() {
        onMain(() -> {
            CapturingActivity activity = new CapturingActivity(intent -> Settings.ACTION_SETTINGS.equals(intent.getAction()));
            PermissionIntents.openAppDetails(activity);
            assertEquals(2, activity.attempts.size());
            assertEquals(Settings.ACTION_SETTINGS, activity.attempts.get(1).getAction());
        });
    }

    /**
     * 把测试断言和 Activity 操作调度到主线程。
     *
     * @param assertions 需要在测试主线程执行的断言动作
     */
    private static void onMain(Runnable assertions) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(assertions);
    }

    /**
     * 测试用 Activity，记录设置入口并模拟系统拒绝启动。
     */
    private static final class CapturingActivity extends Activity {
        final List<Intent> attempts = new ArrayList<>();
        private final Predicate<Intent> accepts;

        /**
         * 创建 CapturingActivity 实例，并初始化本次操作依赖或不可变字段。
         *
         * @param accepts 是否模拟允许启动系统入口
         */
        CapturingActivity(Predicate<Intent> accepts) { this.accepts = accepts; }

        /**
         * 返回测试预设的包名，隔离真实应用入口。
         */
        @Override public String getPackageName() { return "github.vhoom.zhijian"; }

        /**
         * 记录测试发起的系统入口，并按预设模拟允许或拒绝。
         *
         * @param intent 本次启动或复用页面的请求参数
         */
        @Override
        public void startActivity(Intent intent) {
            attempts.add(new Intent(intent));
            if (accepts.test(intent)) return;
            if (attempts.size() == 1) throw new SecurityException("Entry is not exported");
            throw new ActivityNotFoundException("Entry is absent on this ROM");
        }
    }
}
