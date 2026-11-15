package github.vhoom.zhijian.accessibility;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 只让有启动入口的第三方应用进入规则判定，排除 止间、桌面与系统界面。
 *
 * 维护日期：2026年11月14日
 */
final class ManagedPackageFilter {
    private final PackageManager packageManager;
    private final String selfPackage;
    private final Map<String, Boolean> cache = new HashMap<>();
    private final Map<String, Boolean> transientCache = new HashMap<>();
    private final Set<String> homePackages = new HashSet<>();

    /**
     * 创建 ManagedPackageFilter 实例，并初始化本次操作依赖或不可变字段。
     *
     * @param context 访问系统服务、资源或持久化文件的上下文
     */
    ManagedPackageFilter(Context context) {
        packageManager = context.getPackageManager();
        selfPackage = context.getPackageName();
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        for (ResolveInfo info : packageManager.queryIntentActivities(home, PackageManager.MATCH_DEFAULT_ONLY)) {
            if (info.activityInfo != null) homePackages.add(info.activityInfo.packageName);
        }
    }

    /**
     * 区分输入法、系统浮层与真正的应用切换；桌面即使没有启动图标也属于真实离开。
     * @param packageName 窗口事件报告的包名
     * @return 没有独立应用入口且不属于桌面或 止间的瞬时窗口
     */
    boolean isTransientWindow(String packageName) {
        if (packageName == null || selfPackage.equals(packageName)
                || homePackages.contains(packageName) || accepts(packageName)) return false;
        Boolean known = transientCache.get(packageName);
        if (known != null) return known;
        boolean transientWindow = packageManager.getLaunchIntentForPackage(packageName) == null;
        transientCache.put(packageName, transientWindow);
        return transientWindow;
    }

    /** @param packageName 真实窗口包名 @return 是否为系统识别出的桌面，用于返回后弹页的前台校验 */
    boolean isHome(String packageName) { return homePackages.contains(packageName); }

    /**
     * 判断包名是否属于有启动入口的可管控应用，并缓存结果。
     *
     * @param packageName 需要查询或管控的应用包名
     */
    boolean accepts(String packageName) {
        if (packageName == null || packageName.isEmpty() || selfPackage.equals(packageName)
                || homePackages.contains(packageName)) return false;
        Boolean cached = cache.get(packageName);
        if (cached != null) return cached;
        boolean accepted = false;
        try {
            ApplicationInfo app = packageManager.getApplicationInfo(packageName, 0);
            boolean system = (app.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            boolean updated = (app.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
            accepted = (!system || updated) && packageManager.getLaunchIntentForPackage(packageName) != null;
        } catch (PackageManager.NameNotFoundException ignored) {
            // 窗口事件可能晚于卸载广播到达，按不可管控处理。
        }
        cache.put(packageName, accepted);
        return accepted;
    }

    /**
     * 读取应用显示名称；应用已卸载时保留包名作为回退。
     *
     * @param packageName 需要查询或管控的应用包名
     */
    String labelOf(String packageName) {
        try {
            ApplicationInfo app = packageManager.getApplicationInfo(packageName, 0);
            return packageManager.getApplicationLabel(app).toString();
        } catch (PackageManager.NameNotFoundException ignored) {
            return packageName;
        }
    }
}
