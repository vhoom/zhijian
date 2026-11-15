package github.vhoom.zhijian.data.source;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.util.LruCache;

import github.vhoom.zhijian.data.model.AppInfo;
import github.vhoom.zhijian.util.config.AppConfig;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 从系统读取本机已安装、且有启动入口的第三方应用。
 *
 * 维护日期：2026年11月08日
 */
public final class InstalledAppSource {

    private final PackageManager pm;
    private final Resources resources;

    /** 防沉迷应用不管控自己，否则会把用户锁在门外。 */
    private final String selfPackage;

    /**
     * 图标取一次要走一次 binder，列表滚动和首页定时刷新都会反复命中同一批包名。
     * 缓存 ConstantState 而不是 Drawable 本体：同一个 Drawable 挂到两个 ImageView 上时，
     * 失效回调只会剩下后挂的那个，另一处就不再重绘了。
     */
    private final LruCache<String, Drawable.ConstantState> iconCache;

    /**
     * 创建 InstalledAppSource 实例，并初始化本次操作依赖或不可变字段。
     *
     * @param context 访问系统服务、资源或持久化文件的上下文
     * @param config 从资源读取的出厂运行参数
     */
    public InstalledAppSource(Context context, AppConfig config) {
        this.pm = context.getPackageManager();
        this.resources = context.getResources();
        this.selfPackage = context.getPackageName();
        this.iconCache = new LruCache<>(config.appIconCacheSize());
    }

    /**
     * 需要遍历全部应用清单，调用方应在后台线程执行。
     * flags 传 0：MATCH_DEFAULT_ONLY 还要求界面声明了 CATEGORY_DEFAULT，
     * 本机 150 个启动入口只剩 19 个，用量最大的应用会被静默过滤掉。
     */
    public List<AppInfo> loadLaunchableApps() {
        Intent launcher = new Intent(Intent.ACTION_MAIN);
        launcher.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved = pm.queryIntentActivities(launcher, 0);

        Set<String> seen = new HashSet<>();
        List<AppInfo> apps = new ArrayList<>(resolved.size());
        for (ResolveInfo info : resolved) {
            String pkg = info.activityInfo.packageName;
            if (selfPackage.equals(pkg) || !seen.add(pkg))
                continue;
            if (isPreinstalledSystemApp(info.activityInfo.applicationInfo))
                continue;
            apps.add(new AppInfo(pkg, info.loadLabel(pm).toString()));
        }

        Collator zh = Collator.getInstance(Locale.CHINA);
        apps.sort((a, b) -> zh.compare(a.label, b.label));
        return apps;
    }

    /** @param packages 默认组成员包名 @return 本机已安装成员的名称，包含没有启动入口的应用 */
    public List<AppInfo> loadPackages(Collection<String> packages) {
        List<AppInfo> apps = new ArrayList<>();
        for (String packageName : packages) {
            try {
                ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
                apps.add(new AppInfo(packageName, pm.getApplicationLabel(info).toString()));
            } catch (PackageManager.NameNotFoundException absent) {
                // 读取期间已卸载的应用不显示，后台同步负责更新实际成员。
            }
        }
        return apps;
    }

    /**
     * 出厂预装的系统应用（设置、桌面、计算器…）不是防沉迷的对象，全部剔除；
     * 但被用户更新过的系统应用（Chrome、Gmail 这类）照旧算第三方，它们确实是耗时长项。
     *
     * @param app 当前应用数据
     */
    private static boolean isPreinstalledSystemApp(ApplicationInfo app) {
        boolean preinstalled = (app.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        boolean updatedByUser = (app.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
        return preinstalled && !updatedByUser;
    }

    /**
     * 应用自身图标，一次 binder 取回、之后按包名复用；卸载或不可见时返回 null，由列表显示占位。
     *
     * @param packageName 需要查询或管控的应用包名
     */
    public Drawable iconOf(String packageName) {
        Drawable.ConstantState cached = iconCache.get(packageName);
        if (cached != null)
            return cached.newDrawable(resources);
        try {
            Drawable icon = pm.getApplicationIcon(packageName);
            Drawable.ConstantState state = icon.getConstantState();
            if (state != null)
                iconCache.put(packageName, state);
            return icon;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }
}
