package github.vhoom.zhijian.util.diagnostics;

import android.app.Application;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import github.vhoom.zhijian.BuildConfig;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 统一日志 Facade：Logcat 即时输出，私有文件通过有界单线程队列写入。
 *
 * 维护日期：2026年11月14日
 */
public final class DiagnosticLog {
    public static final String TAG = "ZhijianDiagnostics";
    public static final int FILE_BYTES = 512 * 1024;
    public static final int FILE_COUNT = 3;
    private static final AtomicInteger dropped = new AtomicInteger();
    private static final Map<String, Long> lastSamples = new LinkedHashMap<>();
    private static volatile ThreadPoolExecutor writer;
    private static RollingDiagnosticJournal journal;

    /** 静态日志入口不允许实例化。 */
    private DiagnosticLog() { }

    /**
     * 两种构建都安装日志；仅发生未捕获异常时短暂等待写入，正常调用不阻塞。
     * @param application 当前应用进程
     */
    public static synchronized void install(Application application) {
        if (writer != null) return;
        journal = new RollingDiagnosticJournal(new File(application.getFilesDir(), "diagnostics"),
                FILE_BYTES, FILE_COUNT);
        writer = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(256), task -> {
                    Thread thread = new Thread(task, "zhijian-diagnostics");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> {
            try {
                error("crash", "", "uncaught thread=" + thread.getName(), failure);
                flushBeforeCrash();
            } catch (Throwable unavailable) {
                // 内存不足等情况下诊断自身也可能失败，仍必须把原异常交给系统。
                Log.e(TAG, "Crash diagnostics unavailable");
            }
            if (previous != null) previous.uncaughtException(thread, failure);
            else {
                Process.killProcess(Process.myPid());
                System.exit(10);
            }
        });
        info("process", "", "started pid=" + Process.myPid() + " build=" + BuildConfig.BUILD_TYPE
                + " version=" + BuildConfig.VERSION_NAME);
    }

    /**
     * 写入仅在 Debug 保留的详细过程。
     * @param area 所属模块
     * @param message 有界运行说明，不传用户输入或应用内容
     */
    public static void debug(String area, String message) {
        record(DiagnosticLevel.DEBUG, area, "", message, null);
    }

    /**
     * 两种构建均记录关键事件。
     * @param area 所属模块
     * @param session 稳定会话标识，可为空
     * @param message 必要运行信息
     */
    public static void info(String area, String session, String message) {
        record(DiagnosticLevel.INFO, area, session, message, null);
    }

    /**
     * 保留重复检查的最近证据，同时限制正常运行时的写入频率。
     * @param key 同类检查的稳定标识
     * @param area 所属模块
     * @param message 当前运行证据
     */
    public static void sample(String key, String area, String message) {
        if (BuildConfig.DEBUG) {
            debug(area, message);
            return;
        }
        synchronized (lastSamples) {
            long now = SystemClock.elapsedRealtime();
            Long last = lastSamples.get(key);
            if (last != null && now - last < 30_000L) return;
            if (lastSamples.size() >= 64 && !lastSamples.containsKey(key)) {
                lastSamples.remove(lastSamples.keySet().iterator().next());
            }
            lastSamples.put(key, now);
        }
        info(area, "", message);
    }

    /**
     * 记录可恢复的问题，不宣称页面已出现或操作已成功。
     * @param area 所属模块
     * @param session 相关会话，可为空
     * @param message 具体失败边界
     */
    public static void warn(String area, String session, String message) {
        record(DiagnosticLevel.WARN, area, session, message, null);
    }

    /**
     * 两种构建均保留异常堆栈。
     * @param area 所属模块
     * @param session 相关会话，可为空
     * @param message 失败的操作
     * @param failure 原异常
     */
    public static void error(String area, String session, String message, Throwable failure) {
        record(DiagnosticLevel.ERROR, area, session, message, failure);
    }

    /** @return 在此前所有事件写入后生成的日志快照 */
    public static CompletableFuture<DiagnosticSnapshot> snapshot() {
        return submit(() -> journal.snapshot());
    }

    /** @return 队列中此前事件处理完后清空日志，不影响用量记录和应用设置 */
    public static CompletableFuture<Void> clear() {
        return submit(() -> {
            journal.clear();
            dropped.set(0);
            return null;
        });
    }

    /**
     * 固定事件后排队，拥塞时丢弃并计数，绝不退回调用线程写磁盘。
     * @param level 记录级别
     * @param area 模块名称
     * @param session 会话标识
     * @param message 运行说明
     * @param failure 异常，可为空
     */
    private static void record(DiagnosticLevel level, String area, String session,
            String message, Throwable failure) {
        if (!level.retainedIn(BuildConfig.DEBUG)) return;
        String stack = "";
        if (failure != null) {
            StringWriter trace = new StringWriter();
            failure.printStackTrace(new PrintWriter(trace));
            stack = trace.toString();
        }
        DiagnosticEvent event = new DiagnosticEvent(System.currentTimeMillis(),
                SystemClock.elapsedRealtime(), level, area, session, message,
                stack, Thread.currentThread().getName());
        String formatted = event.format();
        int priority = level == DiagnosticLevel.ERROR ? Log.ERROR
                : level == DiagnosticLevel.WARN ? Log.WARN
                : level == DiagnosticLevel.DEBUG ? Log.DEBUG : Log.INFO;
        Log.println(priority, TAG, formatted);
        ThreadPoolExecutor queue = writer;
        if (queue == null) return;
        try {
            queue.execute(() -> {
                try {
                    int missed = dropped.getAndSet(0);
                    if (missed > 0) journal.append(new DiagnosticEvent(System.currentTimeMillis(),
                            SystemClock.elapsedRealtime(), DiagnosticLevel.WARN, "journal", "",
                            "queue_overflow dropped=" + missed, "", "zhijian-diagnostics").format());
                    journal.append(formatted);
                } catch (Exception unavailable) {
                    Log.e(TAG, "Could not write local diagnostics", unavailable);
                }
            });
        } catch (RejectedExecutionException full) {
            dropped.incrementAndGet();
        }
    }

    /**
     * 查看、清空和导出通过同一队列取得明确完成结果。
     * @param task 要在日志队列执行的操作
     * @param <T> 操作返回类型
     * @return 完成或失败的 Future
     */
    private static <T> CompletableFuture<T> submit(Callable<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        ThreadPoolExecutor queue = writer;
        if (queue == null) {
            future.completeExceptionally(new IllegalStateException("Diagnostics not installed"));
            return future;
        }
        try {
            queue.execute(() -> {
                try { future.complete(task.call()); }
                catch (Exception failure) { future.completeExceptionally(failure); }
            });
        } catch (RejectedExecutionException full) {
            future.completeExceptionally(full);
        }
        return future;
    }

    /** 进程即将崩溃时尽力排空队列，诊断自身异常不覆盖原崩溃。 */
    private static void flushBeforeCrash() {
        if ("zhijian-diagnostics".equals(Thread.currentThread().getName())) return;
        try { submit(() -> null).get(400L, TimeUnit.MILLISECONDS); }
        catch (Exception unavailable) { Log.w(TAG, "Crash log flush incomplete"); }
    }
}

