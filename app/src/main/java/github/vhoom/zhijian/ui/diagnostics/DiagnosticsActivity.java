package github.vhoom.zhijian.ui.diagnostics;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

import github.vhoom.zhijian.BuildConfig;
import github.vhoom.zhijian.R;
import github.vhoom.zhijian.ui.component.dialog.AppDialogs;
import github.vhoom.zhijian.data.export.DiagnosticReportExporter;
import github.vhoom.zhijian.ui.activity.ThemedActivity;
import github.vhoom.zhijian.ui.component.async.FutureUiObserver;
import github.vhoom.zhijian.util.diagnostics.DiagnosticLog;
import github.vhoom.zhijian.util.diagnostics.DiagnosticSnapshot;
import github.vhoom.zhijian.util.ui.SystemBars;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;

/**
 * 本地诊断查看与主动导出入口，Debug 和 Release 使用相同页面。
 *
 * 维护日期：2026年11月08日
 */
public final class DiagnosticsActivity extends ThemedActivity {
    private static final int REQUEST_EXPORT = 701;
    private static final String STATE_SELECTING = "diagnostics.selecting";
    private DiagnosticReportExporter exporter;
    private FutureUiObserver<DiagnosticSnapshot> snapshotObserver;
    private FutureUiObserver<Void> operationObserver;
    private CompletableFuture<Void> operation;
    private CompletableFuture<Void> observedExport;
    private TextView preview;
    private TextView status;
    private boolean selecting;
    private boolean reading;
    private AlertDialog clearDialog;

    /**
     * 装配诊断页面；启动资源失败时也可读取已有日志。
     * @param state 系统保存的文件选择状态
     */
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_diagnostics);
        SystemBars.applyPadding(findViewById(R.id.root));
        ((TextView) findViewById(R.id.page_title)).setText(R.string.diagnostics_title);
        ((TextView) findViewById(R.id.diagnostics_build)).setText(BuildConfig.DEBUG
                ? R.string.diagnostics_debug_detail : R.string.diagnostics_release_detail);
        findViewById(R.id.btn_back).setOnClickListener(view -> finish());
        preview = findViewById(R.id.diagnostics_preview);
        status = findViewById(R.id.diagnostics_status);
        exporter = DiagnosticReportExporter.get(this);
        selecting = state != null && state.getBoolean(STATE_SELECTING);
        findViewById(R.id.btn_refresh_logs).setOnClickListener(view -> refresh());
        findViewById(R.id.btn_export_logs).setOnClickListener(view -> chooseDestination());
        findViewById(R.id.btn_clear_logs).setOnClickListener(view -> confirmClear());
    }

    /** 返回页面时连接进程级导出任务，页面重建不会重新导出。 */
    @Override protected void onResume() {
        super.onResume();
        CompletableFuture<Void> current = exporter.currentExport();
        if (operation == null && current != null && current != observedExport) {
            observedExport = current;
            observeOperation(current, R.string.diagnostics_exported);
        }
        refresh();
    }

    /** 在后台取得有界快照，只显示最近记录。 */
    private void refresh() {
        if (reading) return;
        reading = true;
        updateControls();
        if (!isBusy()) status.setText(R.string.diagnostics_loading);
        if (snapshotObserver != null) snapshotObserver.close();
        snapshotObserver = new FutureUiObserver<>(DiagnosticLog.snapshot(), this, (snapshot, failure) -> {
            reading = false;
            if (failure != null) {
                if (!isBusy()) status.setText(R.string.diagnostics_read_failed);
                DiagnosticLog.error("diagnostics", "", "snapshot_failed", failure);
            } else {
                String text = snapshot.preview(64 * 1024);
                preview.setText(text.isEmpty() ? getString(R.string.diagnostics_empty) : text);
                if (!isBusy()) status.setText(getString(R.string.diagnostics_size,
                        (snapshot.sizeBytes + 1023L) / 1024L));
            }
            updateControls();
        });
    }

    /** 用户选择保存位置后才能导出，不申请广泛文件访问权限。 */
    private void chooseDestination() {
        if (isBusy()) return;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/zip").putExtra(Intent.EXTRA_TITLE, getString(R.string.app_name) + "-diagnostics-"
                        + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".zip");
        selecting = true;
        status.setText(R.string.diagnostics_selecting);
        updateControls();
        try { startActivityForResult(intent, REQUEST_EXPORT); }
        catch (ActivityNotFoundException | SecurityException unavailable) {
            selecting = false;
            status.setText(R.string.diagnostics_picker_unavailable);
            DiagnosticLog.error("diagnostics", "", "document_picker_unavailable", unavailable);
            updateControls();
        }
    }

    /**
     * 接收用户主动创建的文档，仅在成功选择后开始后台写入。
     * @param requestCode 对应的导出请求
     * @param resultCode 系统文件选择结果
     * @param data 包含用户选择文档的 Intent
     */
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_EXPORT) return;
        selecting = false;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            status.setText(R.string.diagnostics_cancelled);
            updateControls();
            return;
        }
        CompletableFuture<Void> task = exporter.exportTo(data.getData());
        observedExport = task;
        observeOperation(task, R.string.diagnostics_exported);
    }

    /** 清空前说明作用范围，由用户选择是否执行。 */
    private void confirmClear() {
        if (isBusy()) return;
        clearDialog = AppDialogs.builder(this).setTitle(R.string.diagnostics_clear_title)
                .setMessage(R.string.diagnostics_clear_detail)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.diagnostics_clear, (dialog, which) ->
                        observeOperation(DiagnosticLog.clear(), R.string.diagnostics_cleared))
                .show();
    }

    /**
     * 观察一个后台操作，关闭页面只解除 UI 回调。
     * @param task 导出或清空任务
     * @param successText 成功时显示的文本
     */
    private void observeOperation(CompletableFuture<Void> task, int successText) {
        if (operationObserver != null) operationObserver.close();
        operation = task;
        status.setText(successText == R.string.diagnostics_exported
                ? R.string.diagnostics_exporting : R.string.diagnostics_clearing);
        updateControls();
        operationObserver = new FutureUiObserver<>(task, this, (ignored, failure) -> {
            operation = null;
            status.setText(failure == null ? successText : R.string.diagnostics_operation_failed);
            updateControls();
            if (failure == null && successText == R.string.diagnostics_cleared) refresh();
        });
    }

    /** @return 是否已有保存位置选择或后台操作，避免重复操作 */
    private boolean isBusy() { return selecting || operation != null; }

    /** 将当前任务状态同步到按钮，读取和导出均不阻塞页面。 */
    private void updateControls() {
        findViewById(R.id.btn_refresh_logs).setEnabled(!reading);
        findViewById(R.id.btn_export_logs).setEnabled(!isBusy());
        findViewById(R.id.btn_clear_logs).setEnabled(!isBusy());
    }

    /**
     * 保存文件选择状态，旋转后不再次打开选择器。
     * @param state 系统用于重建页面的快照
     */
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean(STATE_SELECTING, selecting);
        super.onSaveInstanceState(state);
    }

    /** 释放观察者和确认框，应用持有的导出任务继续执行。 */
    @Override protected void onDestroy() {
        if (snapshotObserver != null) snapshotObserver.close();
        if (operationObserver != null) operationObserver.close();
        if (clearDialog != null) clearDialog.dismiss();
        super.onDestroy();
    }
}

