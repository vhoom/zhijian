package github.vhoom.zhijian.util.permission;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.PowerManager;
import android.os.Process;
import android.view.accessibility.AccessibilityManager;

import github.vhoom.zhijian.accessibility.AccessibilityConnection;
import github.vhoom.zhijian.accessibility.ZhijianAccessibilityService;
import github.vhoom.zhijian.util.diagnostics.DiagnosticLog;
import github.vhoom.zhijian.util.diagnostics.InterceptionLaunchDiagnostics;

import java.util.List;

/**
 * 读取系统可核验的配置；厂商自启动、最近任务锁定等不伪装成可检测权限。
 *
 * 维护日期：2026年11月14日
 */
public final class PermissionStatusReader {
    /**
     * 工具类不允许实例化，调用方直接使用静态方法。
     */
    private PermissionStatusReader() { }

    /**
     * 同次系统查询得到的不可变统计或权限状态快照。
     */
    public static final class Snapshot {
        public final boolean usageAccess;
        public final boolean batteryExempt;
        public final boolean accessibilityInstalled;
        public final boolean accessibilityEnabled;
        public final boolean accessibilityConnected;
        public final boolean backgroundRestricted;
        public final boolean deviceOwner;
        /** 最近一次拦截请求缺少真实窗口回执，不等同于已判定某项权限关闭。 */
        public final boolean displayUnconfirmed;

        /**
         * 创建 Snapshot 实例，并初始化本次操作依赖或不可变字段。
         *
         * @param usageAccess 是否已经获得使用情况访问授权
         * @param batteryExempt 是否处于电池优化白名单
         * @param accessibilityInstalled 系统是否识别到本应用的无障碍服务
         * @param accessibilityEnabled 用户是否已经启用本应用的无障碍服务
         * @param accessibilityConnected 服务是否已经与系统建立实际连接
         * @param backgroundRestricted 系统是否限制本应用后台运行
         * @param deviceOwner 本应用是否为设备所有者
         * @param displayUnconfirmed 最近拦截页是否缺少真实窗口回执
         */
        private Snapshot(boolean usageAccess, boolean batteryExempt,
                boolean accessibilityInstalled, boolean accessibilityEnabled, boolean accessibilityConnected,
                boolean backgroundRestricted, boolean deviceOwner, boolean displayUnconfirmed) {
            this.usageAccess = usageAccess;
            this.batteryExempt = batteryExempt;
            this.accessibilityInstalled = accessibilityInstalled;
            this.accessibilityEnabled = accessibilityEnabled;
            this.accessibilityConnected = accessibilityConnected;
            this.backgroundRestricted = backgroundRestricted;
            this.deviceOwner = deviceOwner;
            this.displayUnconfirmed = displayUnconfirmed;
        }

        /** @return 可用于本地排查的系统状态，只包含开关值而不包含用户内容 */
        public String describe() {
            return "usage_access=" + usageAccess + " battery_exempt=" + batteryExempt
                    + " accessibility_installed=" + accessibilityInstalled
                    + " accessibility_enabled=" + accessibilityEnabled
                    + " accessibility_connected=" + accessibilityConnected
                    + " background_restricted=" + backgroundRestricted + " device_owner=" + deviceOwner
                    + " display_unconfirmed=" + displayUnconfirmed
                    + " background_popup_permission=manual_check";
        }

        /**
         * 统计已经满足的系统授权项数量。
         */
        public int enabledPermissionCount() {
            return (usageAccess ? 1 : 0) + (batteryExempt ? 1 : 0) + (accessibilityEnabled ? 1 : 0);
        }
    }

    /**
     * 检查系统是否允许读取本应用的使用情况统计。
     *
     * @param context 访问系统服务、资源或持久化文件的上下文
     */
    public static boolean hasUsageAccess(Context context) {
        AppOpsManager ops = context.getSystemService(AppOpsManager.class);
        if (ops == null) return false;
        int mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), context.getPackageName());
        if (mode == AppOpsManager.MODE_DEFAULT) {
            return context.checkSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED;
        }
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    /**
     * 判断是否需要显示“无障碍已开启但用量权限缺失”的持续提醒。
     *
     * @param accessibilityEnabled 止间无障碍服务是否已在系统中开启
     * @param usageAccess 是否已获得使用情况访问权限
     * @return 只有无障碍已开启且用量权限缺失时返回 true
     */
    public static boolean needsUsageAccessWarning(boolean accessibilityEnabled,
            boolean usageAccess) {
        return accessibilityEnabled && !usageAccess;
    }

    /**
     * 一次性查询系统授权与服务连接，组装权限快照。
     *
     * @param context 访问系统服务、资源或持久化文件的上下文
     */
    public static Snapshot read(Context context) {
        String packageName = context.getPackageName();
        PowerManager power = context.getSystemService(PowerManager.class);
        ActivityManager activity = context.getSystemService(ActivityManager.class);
        AccessibilityManager accessibility = context.getSystemService(AccessibilityManager.class);
        DevicePolicyManager policy = context.getSystemService(DevicePolicyManager.class);
        ComponentName component = new ComponentName(context, ZhijianAccessibilityService.class);
        boolean installed = accessibility != null
                && containsService(accessibility.getInstalledAccessibilityServiceList(), component);
        boolean enabled = installed && containsService(accessibility.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK), component);
        Snapshot result = new Snapshot(hasUsageAccess(context),
                power != null && power.isIgnoringBatteryOptimizations(packageName), installed, enabled,
                enabled && AccessibilityConnection.isConnected(),
                activity != null && activity.isBackgroundRestricted(),
                policy != null && policy.isDeviceOwnerApp(packageName),
                InterceptionLaunchDiagnostics.isUnconfirmed());
        DiagnosticLog.sample("permissions:" + result.describe(), "permissions", result.describe());
        return result;
    }

    /**
     * 检查系统服务列表是否包含本应用的精确组件。
     *
     * @param services 系统返回的无障碍服务列表
     * @param expected 需要匹配的精确服务组件或预期值
     */
    private static boolean containsService(List<AccessibilityServiceInfo> services, ComponentName expected) {
        if (services == null) return false;
        for (AccessibilityServiceInfo service : services) {
            if (service.getResolveInfo() == null || service.getResolveInfo().serviceInfo == null) continue;
            android.content.pm.ServiceInfo info = service.getResolveInfo().serviceInfo;
            if (expected.equals(new ComponentName(info.packageName, info.name))) return true;
        }
        return false;
    }
}
