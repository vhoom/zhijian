package github.vhoom.zhijian.util.permission;

import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import github.vhoom.zhijian.accessibility.AccessibilityConnection;
import github.vhoom.zhijian.util.diagnostics.InterceptionLaunchDiagnostics;
import github.vhoom.zhijian.util.diagnostics.DiagnosticLog;

/**
 * 页面可见时监听授权和连接变化，不轮询、不保留离开页面后的回调。
 *
 * 维护日期：2026年11月14日
 */
public final class PermissionStatusObserver implements AutoCloseable {
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable notifyUi;
    private final Runnable connectionChanged = this::scheduleRefresh;
    private final ContentObserver secureSettings = new ContentObserver(main) {
        /**
         * 系统设置变化后合并安排一次界面状态刷新。
         *
         * @param selfChange 当前设置通知是否由本观察方发起
         */
        @Override public void onChange(boolean selfChange) { scheduleRefresh(); }
    };
    private boolean started;
    private boolean observingSettings;

    /**
     * 创建 PermissionStatusObserver 实例，并初始化本次操作依赖或不可变字段。
     *
     * @param context 访问系统服务、资源或持久化文件的上下文
     * @param changed 授权状态变化后刷新界面的回调
     */
    public PermissionStatusObserver(Context context, Runnable changed) {
        this.context = context.getApplicationContext();
        notifyUi = () -> { if (started) changed.run(); };
    }

    /**
     * 开始页面可见期间的权限观察和合并通知。
     */
    public void start() {
        if (started) return;
        started = true;
        AccessibilityConnection.addListener(connectionChanged);
        InterceptionLaunchDiagnostics.addListener(connectionChanged);
        try {
            context.getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, secureSettings);
            observingSettings = true;
            context.getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_ENABLED), false, secureSettings);
        } catch (SecurityException unavailable) {
            // 部分系统限制观察设置项；服务回调及页面恢复时的查询仍可更新状态。
            Log.w("ZhijianPermissions", "Cannot observe accessibility settings", unavailable);
            DiagnosticLog.warn("permissions", "", "accessibility_observer_unavailable");
        }
    }

    /**
     * 停止页面可见期间的权限观察和合并通知。
     */
    public void stop() {
        started = false;
        AccessibilityConnection.removeListener(connectionChanged);
        InterceptionLaunchDiagnostics.removeListener(connectionChanged);
        if (observingSettings) context.getContentResolver().unregisterContentObserver(secureSettings);
        observingSettings = false;
        main.removeCallbacks(notifyUi);
    }

    /**
     * 合并重复的权限变化通知，仅在观察期间更新页面。
     */
    private void scheduleRefresh() {
        if (!started) return;
        main.removeCallbacks(notifyUi);
        main.post(notifyUi);
    }

    /**
     * 释放页面可见期间的权限观察和合并通知。
     */
    @Override public void close() { stop(); }
}
