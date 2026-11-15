package github.vhoom.zhijian.ui.component.async;

import android.app.Activity;

import java.lang.ref.WeakReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

/**
 * 生命周期适配器：后台任务可继续完成，已关闭页面的回调和 View 引用及时释放。
 *
 * 维护日期：2026年11月08日
 */
public final class FutureUiObserver<T> implements AutoCloseable {
    private final WeakReference<Activity> activity;
    private final AtomicReference<BiConsumer<T, Throwable>> callback;

    /**
     * 将任务结果转交仍然有效的页面，不取消可能正在提交的数据库工作。
     * @param task 正在后台执行的任务
     * @param activity 接收结果的页面
     * @param callback 在主线程处理成功或失败的回调
     */
    public FutureUiObserver(CompletableFuture<T> task, Activity activity,
            BiConsumer<T, Throwable> callback) {
        this.activity = new WeakReference<>(activity);
        this.callback = new AtomicReference<>(callback);
        task.whenComplete(this::post);
    }

    /**
     * 后台完成后安排主线程通知；通知至多一次，关闭后不再持有回调。
     * @param value 任务返回值
     * @param failure 任务失败原因，成功时为 null
     */
    private void post(T value, Throwable failure) {
        Activity page = activity.get();
        if (page == null || isClosed()) return;
        page.runOnUiThread(() -> {
            BiConsumer<T, Throwable> action = callback.getAndSet(null);
            Activity target = activity.get();
            if (action != null && target != null && !target.isDestroyed()) action.accept(value, failure);
        });
    }

    /** @return 已完成通知或页面关闭后返回 true */
    public boolean isClosed() { return callback.get() == null; }

    /** 页面销毁时释放回调捕获的页面、草稿和控件，不取消后台提交。 */
    @Override public void close() {
        callback.set(null);
        activity.clear();
    }
}
