package github.vhoom.zhijian.util.concurrent;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 有界后台任务队列；拥塞与关闭通过 Future 报错，绝不退回调用线程执行 I/O。
 *
 * 维护日期：2026年11月08日
 */
public final class TaskQueue implements Executor {
    private final CompletableFuture<Void> drained = new CompletableFuture<>();
    private final ThreadPoolExecutor pool;

    /**
     * 创建固定并发数的队列，线程空闲时允许回收。
     * @param name 后台线程的诊断名称
     * @param threads 同时执行任务的最大数量
     * @param capacity 尚未执行的任务容量
     */
    public TaskQueue(String name, int threads, int capacity) {
        pool = new ThreadPoolExecutor(threads, threads, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(capacity), task -> new Thread(task, name),
                new ThreadPoolExecutor.AbortPolicy()) {
            /** 全部已接受任务执行完才发布关闭完成信号。 */
            @Override protected void terminated() { drained.complete(null); }
        };
        pool.allowCoreThreadTimeOut(true);
    }

    /**
     * 遵循 Executor 契约，队列拥塞或关闭时明确拒绝。
     * @param command 要在后台运行的任务
     */
    @Override public void execute(Runnable command) { pool.execute(command); }

    /**
     * 将计算异常和队列拒绝统一转换为完成结果。
     * @param work 后台工作，不得同步等待同一队列中的后续任务
     * @param <T> 工作的返回类型
     * @return 成功、异常或拒绝时均可完成的独立 Future
     */
    public <T> CompletableFuture<T> submit(Callable<T> work) {
        CompletableFuture<T> result = new CompletableFuture<>();
        try {
            execute(() -> {
                try {
                    result.complete(work.call());
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                }
            });
        } catch (RejectedExecutionException failure) {
            result.completeExceptionally(failure);
        }
        return result;
    }

    /**
     * 停止接收任务并异步等待已接受任务完成；不打断仍持有资源的工作。
     * @return 队列完全停止后的完成信号，调用方不能修改内部信号
     */
    public CompletableFuture<Void> shutdown() {
        pool.shutdown();
        return drained.thenApply(ignored -> null);
    }
}
