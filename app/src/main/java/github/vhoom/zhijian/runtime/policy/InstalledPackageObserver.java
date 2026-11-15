package github.vhoom.zhijian.runtime.policy;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

import github.vhoom.zhijian.data.model.policy.BuiltInGroupPreset;
import github.vhoom.zhijian.data.store.ZhijianStorage;
import github.vhoom.zhijian.util.diagnostics.DiagnosticLog;

/**
 * 进程存活时观察目录应用的安装和卸载，进程重启则由存储初始化重新核对。
 *
 * 维护日期：2026年11月14日
 */
public final class InstalledPackageObserver extends BroadcastReceiver {
    private final ZhijianStorage storage;

    /** @param storage 负责后台事务与已提交快照的进程级存储 */
    private InstalledPackageObserver(ZhijianStorage storage) { this.storage = storage; }

    /** @param context 进程级应用上下文 @param storage 已开始后台初始化的存储 */
    public static void register(Context context, ZhijianStorage storage) {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addDataScheme("package");
        InstalledPackageObserver observer = new InstalledPackageObserver(storage);
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(observer, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(observer, filter);
        }
    }

    /** @param context 接收广播的上下文 @param intent 系统安装或卸载事件 */
    @Override public void onReceive(Context context, Intent intent) {
        String packageName = intent.getData() == null ? "" : intent.getData().getSchemeSpecificPart();
        if (!BuiltInGroupPreset.ALL_PACKAGES.contains(packageName)) return;
        if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())
                && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
        storage.ready().thenCompose(ignored -> storage.refreshBuiltInGroups())
                .whenComplete((ignored, failure) -> {
                    if (failure != null) DiagnosticLog.error("policy", packageName,
                            "built_in_members_refresh_failed", failure);
                });
    }
}
