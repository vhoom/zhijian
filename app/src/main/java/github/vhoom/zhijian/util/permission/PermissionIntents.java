package github.vhoom.zhijian.util.permission;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.widget.Toast;

import github.vhoom.zhijian.R;
import github.vhoom.zhijian.accessibility.ZhijianAccessibilityService;


/**
 * 系统入口与按序回退集中在此。直接尝试启动，避免包可见性导致 resolveActivity 的误判。
 *
 * 维护日期：2026年11月14日
 */
public final class PermissionIntents {
    // 部分系统支持的无障碍详情入口，未公开到 SDK；打不开时使用公开的无障碍列表入口。
    private static final String ACCESSIBILITY_DETAILS = "android.settings.ACCESSIBILITY_DETAILS_SETTINGS";

    /**
     * 工具类不允许实例化，调用方直接使用静态方法。
     */
    private PermissionIntents() { }

    /**
     * 打开系统使用情况访问授权入口。
     *
     * @param activity 执行本次界面操作的 Activity
     */
    public static void openUsageAccess(Activity activity) {
        Intent detail = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, packageUri(activity))
                .putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName());
        open(activity, detail, new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
    }

    /**
     * 按当前白名单状态打开电池优化申请或管理列表。
     *
     * @param activity 执行本次界面操作的 Activity
     * @param exempt 当前是否已经处于电池优化白名单
     */
    public static void openBatterySettings(Activity activity, boolean exempt) {
        Intent list = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
        if (exempt) {
            open(activity, list);
        } else {
            open(activity, new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    packageUri(activity)), list);
        }
    }

    /**
     * 优先打开本应用的无障碍详情，失败时打开服务列表。
     *
     * @param activity 执行本次界面操作的 Activity
     */
    public static void openAccessibilitySettings(Activity activity) {
        Intent detail = new Intent(ACCESSIBILITY_DETAILS)
                .putExtra(Intent.EXTRA_COMPONENT_NAME, new ComponentName(activity, ZhijianAccessibilityService.class));
        open(activity, detail, new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
    }

    /**
     * 打开通用应用信息页，由用户检查系统实际提供的后台活动与弹出界面选项。
     * @param activity 用户主动操作的页面
     */
    public static void openBackgroundSettings(Activity activity) { openAppDetails(activity); }

    /**
     * 构造本应用的系统应用详情 Intent。
     *
     * @param context 访问系统服务、资源或持久化文件的上下文
     */
    public static Intent appDetails(Context context) {
        return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context));
    }

    /**
     * 打开本应用的信息页；入口不可用时打开系统设置。
     *
     * @param activity 执行本次界面操作的 Activity
     */
    public static void openAppDetails(Activity activity) { open(activity); }

    /**
     * 私有入口缺失或拒绝访问时继续尝试，最终回退到应用信息和系统设置首页。
     *
     * @param activity 执行本次界面操作的 Activity
     * @param candidates 按优先级排列的系统设置候选入口
     */
    public static void open(Activity activity, Intent... candidates) {
        for (Intent candidate : candidates) if (tryStart(activity, candidate)) return;
        if (tryStart(activity, appDetails(activity))) return;
        if (tryStart(activity, new Intent(Settings.ACTION_SETTINGS))) return;
        Toast.makeText(activity, activity.getString(R.string.permission_settings_unavailable,
                activity.getString(R.string.app_name)), Toast.LENGTH_LONG).show();
    }

    /**
     * 尝试启动一个系统配置入口，拒绝或不存在时返回失败。
     *
     * @param activity 执行本次界面操作的 Activity
     * @param intent 本次启动或复用页面的请求参数
     */
    private static boolean tryStart(Activity activity, Intent intent) {
        try {
            activity.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            github.vhoom.zhijian.util.diagnostics.DiagnosticLog.debug("permissions", "Settings route unavailable: "
                    + intent.getAction() + " " + intent.getComponent());
            return false;
        }
    }

    /**
     * 构造指向本应用包名的 package URI。
     *
     * @param context 访问系统服务、资源或持久化文件的上下文
     */
    private static Uri packageUri(Context context) {
        return Uri.fromParts("package", context.getPackageName(), null);
    }

}
