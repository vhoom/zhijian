package github.vhoom.zhijian.runtime.usage;

import android.content.Context;
import android.os.SystemClock;

import github.vhoom.zhijian.data.source.UsageArchiveReader;
import github.vhoom.zhijian.data.store.UsageArchiveStore;
import github.vhoom.zhijian.util.diagnostics.DiagnosticLog;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 进程级归档协调：合并并发采集，后台读取系统事件，等待数据库提交后才通知读取者。
 *
 * 维护日期：2026年11月14日
 */
public final class UsageArchiveCoordinator {
    private static final long MIN_INTERVAL_MS = 30_000L;
    private static final ExecutorService READER = Executors.newSingleThreadExecutor(runnable ->
            new Thread(runnable, "zhijian-usage-archive"));
    private static CompletableFuture<Void> pending;
    private static long lastCompletedElapsed;

    /** 仅提供进程共享的采集入口。 */
    private UsageArchiveCoordinator() { }

    /**
     * @param context 采集和归档使用的应用上下文
     * @param force 首页主动刷新或后台 Job 为 true，窗口事件为 false 并合并高频请求
     * @return 当次保存完成信号；采集失败不会发布空数据或删除已有记录
     */
    public static synchronized CompletableFuture<Void> synchronize(Context context, boolean force) {
        if (pending != null && !pending.isDone()) return pending;
        if (!force && lastCompletedElapsed > 0L
                && SystemClock.elapsedRealtime() - lastCompletedElapsed < MIN_INTERVAL_MS) {
            return CompletableFuture.completedFuture(null);
        }
        Context application = context.getApplicationContext();
        int recentDays = lastCompletedElapsed == 0L ? 7 : 2;
        pending = CompletableFuture.supplyAsync(() -> new UsageArchiveReader(application)
                .readRecent(recentDays, System.currentTimeMillis()), READER)
                .thenCompose(days -> new UsageArchiveStore(application).save(days));
        pending.whenComplete((ignored, failure) -> {
            synchronized (UsageArchiveCoordinator.class) {
                if (failure == null) lastCompletedElapsed = SystemClock.elapsedRealtime();
            }
            if (failure != null) DiagnosticLog.error("usage_archive", "", "save_failed", failure);
        });
        return pending;
    }
}
