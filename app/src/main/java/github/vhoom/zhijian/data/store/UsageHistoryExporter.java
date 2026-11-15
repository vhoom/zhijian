package github.vhoom.zhijian.data.store;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * 应用持有一次导出任务，页面重建或暂时离开不会取消；文件流始终在后台打开和关闭。
 *
 * 维护日期：2026年11月14日
 */
public final class UsageHistoryExporter {
    private final Context context;
    private final UsageHistoryStore history;
    private CompletableFuture<Long> current;

    /**
     * 创建 UsageHistoryExporter 实例，并初始化本次操作依赖或不可变字段。
     *
     * @param context 访问系统服务、资源或持久化文件的上下文
     */
    public UsageHistoryExporter(Context context) {
        this(context, new UsageHistoryStore(context));
    }

    /**
     * 创建 UsageHistoryExporter 实例，并初始化本次操作依赖或不可变字段。
     *
     * @param context 访问系统服务、资源或持久化文件的上下文
     * @param history 查询和导出使用历史的存储入口
     */
    UsageHistoryExporter(Context context, UsageHistoryStore history) {
        this.context = context.getApplicationContext();
        this.history = history;
    }

    /**
     * 返回当前进程正在执行的历史导出任务。
     */
    public synchronized CompletableFuture<Long> currentExport() { return current; }

    /**
     * 向调用方指定的文档 URI 导出历史，失败保留可重试状态。
     *
     * @param destination 由调用方提供的输出文档或字符流
     */
    public synchronized CompletableFuture<Long> exportTo(Uri destination) {
        if (current != null && !current.isDone()) return current;
        current = CompletableFuture.supplyAsync(() -> write(destination));
        current.whenComplete((count, failure) -> {
            if (failure != null) Log.e("ZhijianExport", "Usage export failed", failure);
        });
        return current;
    }

    /**
     * 打开文档输出流并导出明细，覆盖旧内容并保留 Unicode 文本。
     *
     * @param destination 由调用方提供的输出文档或字符流
     */
    private long write(Uri destination) {
        try {
            OutputStream output = context.getContentResolver().openOutputStream(destination, "wt");
            if (output == null) throw new IOException("Document provider returned no output stream");
            try (Writer writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8))) {
                // 等待读取任务完成后再关闭流；此处在后台线程，不阻塞页面或数据库写线程。
                return history.exportCompletedSessions(writer).get();
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new CompletionException(failure);
        } catch (ExecutionException failure) {
            throw new CompletionException(failure.getCause());
        } catch (IOException | RuntimeException failure) {
            throw new CompletionException(failure);
        }
    }
}
