package github.vhoom.zhijian.data.export;

import android.content.Context;
import android.net.Uri;
import android.os.Build;

import github.vhoom.zhijian.BuildConfig;
import github.vhoom.zhijian.R;
import github.vhoom.zhijian.util.diagnostics.DiagnosticArchive;
import github.vhoom.zhijian.util.diagnostics.DiagnosticLog;
import github.vhoom.zhijian.util.diagnostics.DiagnosticSnapshot;
import github.vhoom.zhijian.util.permission.PermissionStatusReader;

import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;
import java.util.TimeZone;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * 进程持有一次诊断导出；页面重建不会取消，文档由用户通过系统选择。
 *
 * 维护日期：2026年11月08日
 */
public final class DiagnosticReportExporter {
    private static DiagnosticReportExporter instance;
    private final Context context;
    private CompletableFuture<Void> current;

    /**
     * 只持有应用上下文，不保留页面。
     * @param context 当前应用上下文
     */
    private DiagnosticReportExporter(Context context) { this.context = context.getApplicationContext(); }

    /**
     * 获取进程共享的导出入口。
     * @param context 用于取得应用上下文
     * @return 共用导出器
     */
    public static synchronized DiagnosticReportExporter get(Context context) {
        if (instance == null) instance = new DiagnosticReportExporter(context);
        return instance;
    }

    /** @return 正在执行或最近完成的导出任务，尚未导出时为空 */
    public synchronized CompletableFuture<Void> currentExport() { return current; }

    /**
     * 冻结日志后在后台写入 ZIP；不申请存储权限，不主动发送数据。
     * @param destination 用户主动创建的文档 URI
     * @return 文档已关闭后完成的任务
     */
    public synchronized CompletableFuture<Void> exportTo(Uri destination) {
        if (current != null && !current.isDone()) return current;
        DiagnosticLog.info("diagnostics", "", "export_requested");
        current = DiagnosticLog.snapshot().thenAcceptAsync(snapshot -> write(destination, snapshot));
        current.whenComplete((ignored, failure) -> {
            if (failure == null) DiagnosticLog.info("diagnostics", "", "export_completed");
            else DiagnosticLog.error("diagnostics", "", "export_failed", failure);
        });
        return current;
    }

    /**
     * 以覆盖模式打开文档，归档器负责完成和关闭输出流。
     * @param destination 系统返回的文档 URI
     * @param snapshot 不受后续轮转影响的日志副本
     */
    private void write(Uri destination, DiagnosticSnapshot snapshot) {
        try {
            String environment = environment();
            OutputStream output = context.getContentResolver().openOutputStream(destination, "wt");
            if (output == null) throw new IOException("Document provider returned no output stream");
            DiagnosticArchive.write(output, snapshot, environment);
        } catch (IOException | RuntimeException failure) {
            throw new CompletionException(failure);
        }
    }

    /** @return 必要设备与系统状态，不包含账号、设备唯一标识或其他应用内容 */
    private String environment() {
        String appName = context.getString(R.string.app_name);
        return context.getString(R.string.diagnostics_environment_title, appName) + "\n"
                + "app_name=" + appName + "\n"
                + "exported_at_utc=" + Instant.now() + "\n"
                + "version=" + BuildConfig.VERSION_NAME + "\nversion_code=" + BuildConfig.VERSION_CODE
                + "\nbuild=" + BuildConfig.BUILD_TYPE + "\nandroid=" + Build.VERSION.RELEASE
                + "\nsdk=" + Build.VERSION.SDK_INT + "\nmanufacturer=" + Build.MANUFACTURER
                + "\nmodel=" + Build.MODEL + "\ntimezone=" + TimeZone.getDefault().getID()
                + "\n" + PermissionStatusReader.read(context).describe()
                + "\n\n" + context.getString(R.string.diagnostics_environment_detail) + "\n";
    }
}

