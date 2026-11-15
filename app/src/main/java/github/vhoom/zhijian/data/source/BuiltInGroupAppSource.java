package github.vhoom.zhijian.data.source;

import android.content.Context;
import android.content.pm.PackageManager;

import github.vhoom.zhijian.data.model.policy.BuiltInGroupPreset;

import java.util.ArrayList;
import java.util.List;

/**
 * 将系统安装状态适配为三个默认组目录的包名交集，调用方在后台执行。
 *
 * 维护日期：2026年11月08日
 */
public final class BuiltInGroupAppSource {
    private final PackageManager packages;

    /** @param context 用于读取系统安装信息的应用上下文 */
    public BuiltInGroupAppSource(Context context) { packages = context.getPackageManager(); }

    /** @return 本用户已安装的目录成员；读取失败不会被当作空交集 */
    public List<String> loadInstalledPackages() {
        List<String> installed = new ArrayList<>();
        for (String packageName : BuiltInGroupPreset.ALL_PACKAGES) {
            try {
                packages.getApplicationInfo(packageName, 0);
                installed.add(packageName);
            } catch (PackageManager.NameNotFoundException absent) {
                // 未安装的应用不显示，也不计入组内数量。
            }
        }
        return installed;
    }
}
