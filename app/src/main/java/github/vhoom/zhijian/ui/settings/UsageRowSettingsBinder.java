package github.vhoom.zhijian.ui.settings;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;

import github.vhoom.zhijian.R;
import github.vhoom.zhijian.data.model.AppInfo;
import github.vhoom.zhijian.data.store.apps.AppCatalogFileStore;
import github.vhoom.zhijian.data.store.apps.AppCatalogCache;
import github.vhoom.zhijian.data.store.settings.GlobalSettings;
import github.vhoom.zhijian.domain.usage.UsageRowLimit;
import github.vhoom.zhijian.ui.component.dialog.AppDialogs;
import github.vhoom.zhijian.ui.component.input.InputError;
import github.vhoom.zhijian.ui.component.input.ManualInput;
import github.vhoom.zhijian.util.format.UnsignedInteger;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 首页条数编辑适配器：后台只读已提交扫描快照，保存时重新核对实际容量。
 *
 * 维护日期：2026年11月13日
 */
public final class UsageRowSettingsBinder {
    private final Activity activity;
    private final GlobalSettings settings;
    private final Runnable saved;
    private final ExecutorService reader;
    private final AppCatalogCache catalog;
    private AlertDialog dialog;
    private boolean closed;
    /** 过期读取不能更新已关闭或后来打开的弹窗。 */
    private long generation;

    /** @param activity 设置页面 @param settings 全局设置 @param saved 保存后刷新摘要 */
    public UsageRowSettingsBinder(Activity activity, GlobalSettings settings, Runnable saved) {
        this(activity, settings, saved,
                new AppCatalogFileStore(activity.getNoBackupFilesDir().toPath().resolve("launchable_apps.cache")),
                Executors.newSingleThreadExecutor());
    }

    /**
     * 注入只读快照与调度边界，设备回归可控制读取完成的顺序。
     * @param activity 设置页面
     * @param settings 全局设置
     * @param saved 保存后刷新摘要
     * @param catalog 已提交扫描快照，读取失败不能触发重新扫描
     * @param reader 页面持有并负责关闭的读取队列
     */
    UsageRowSettingsBinder(Activity activity, GlobalSettings settings, Runnable saved,
            AppCatalogCache catalog, ExecutorService reader) {
        this.activity = activity;
        this.settings = settings;
        this.saved = saved;
        this.catalog = catalog;
        this.reader = reader;
    }

    /** 展示原始已保存条数，不按加载中的容量回填或截断。 */
    public void show() {
        closeDialog();
        if (closed) return;
        View content = activity.getLayoutInflater().inflate(R.layout.dialog_usage_rows, null, false);
        EditText field = content.findViewById(R.id.field_rows);
        ManualInput.configure(field, InputType.TYPE_CLASS_NUMBER);
        field.setText(String.valueOf(settings.usageRowLimit()));
        AlertDialog shown = AppDialogs.builder(activity)
                .setTitle(R.string.settings_rows_title)
                .setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.settings_restore_default, (ignored, which) -> {
                    settings.setUsageRowLimit(0);
                    saved.run();
                })
                .setPositiveButton(android.R.string.ok, null)
                .create();
        dialog = shown;
        shown.setOnDismissListener(ignored -> {
            if (dialog == shown) { dialog = null; generation++; }
        });
        shown.setOnShowListener(ignored -> {
            shown.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(
                    view -> readCapacity(shown, content, field, true));
            readCapacity(shown, content, field, false);
        });
        if (shown.getWindow() != null) {
            shown.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        AppDialogs.show(shown);
    }

    /**
     * 在后台读取最近一次完整快照，缺失或损坏不会触发系统扫描。
     * @param shown 当前弹窗
     * @param content 当前表单
     * @param field 原始条数输入
     * @param save 本次读取后是否尝试提交用户输入
     */
    private void readCapacity(AlertDialog shown, View content, EditText field, boolean save) {
        long request = ++generation;
        field.setEnabled(false);
        shown.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        TextView capacity = content.findViewById(R.id.usage_rows_capacity);
        capacity.setText(R.string.settings_rows_loading);
        reader.execute(() -> {
            int count;
            try {
                List<AppInfo> apps = catalog.read();
                count = apps == null ? -1 : apps.size();
            } catch (IOException | SecurityException unreadable) {
                count = -2;
            }
            int scanned = count;
            activity.runOnUiThread(() -> {
                if (closed || request != generation || dialog != shown || !shown.isShowing()
                        || activity.isFinishing() || activity.isDestroyed()) return;
                if (scanned <= 0) {
                    capacity.setText("");
                    InputError.show(field, activity.getString(scanned == -1 ? R.string.settings_rows_scan_missing
                            : scanned == 0 ? R.string.settings_rows_scan_empty : R.string.settings_rows_scan_unreadable));
                    return;
                }
                capacity.setText(activity.getString(R.string.settings_rows_capacity, scanned));
                field.setEnabled(true);
                shown.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                if (save) {
                    long rows = UnsignedInteger.parse(field.getText().toString());
                    if (!UsageRowLimit.isValid(rows, scanned)) {
                        InputError.show(field, activity.getString(R.string.settings_rows_invalid, scanned));
                        field.requestFocus();
                        return;
                    }
                    settings.setUsageRowLimit((int) rows);
                    saved.run();
                    shown.dismiss();
                } else {
                    field.requestFocus();
                    field.selectAll();
                }
            });
        });
    }

    /** 关闭弹窗并使所有未完成的读取回调失效。 */
    public void closeDialog() {
        generation++;
        if (dialog != null) dialog.dismiss();
        dialog = null;
    }

    /** 页面销毁时释放后台读取队列与弹窗。 */
    public void close() {
        closed = true;
        closeDialog();
        reader.shutdownNow();
    }
}
