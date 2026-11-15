package github.vhoom.zhijian.data.source;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 从系统可见的 HOME 入口识别桌面，包含用户安装的启动器，不依赖应用名单。
 *
 * 维护日期：2026年11月08日
 */
public final class HomePackageSource {
    /** 系统查询适配器不需要实例状态。 */
    private HomePackageSource() { }

    /** @param packages 系统包管理器 @return 本次已安装桌面的包名集合 */
    public static Set<String> read(PackageManager packages) {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        List<ResolveInfo> activities = packages.queryIntentActivities(home, PackageManager.MATCH_DEFAULT_ONLY);
        Set<String> result = new HashSet<>();
        for (ResolveInfo info : activities) {
            if (info.activityInfo != null) result.add(info.activityInfo.packageName);
        }
        return result;
    }
}
