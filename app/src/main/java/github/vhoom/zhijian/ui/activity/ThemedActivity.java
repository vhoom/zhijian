package github.vhoom.zhijian.ui.activity;

import android.app.Activity;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Build;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import github.vhoom.zhijian.R;
import github.vhoom.zhijian.ui.component.dialog.AppDialogs;
import github.vhoom.zhijian.ZhijianApplication;
import github.vhoom.zhijian.data.store.ZhijianStorage;
import github.vhoom.zhijian.data.store.settings.GlobalSettings;
import github.vhoom.zhijian.data.model.settings.ThemeMode;
import github.vhoom.zhijian.data.model.policy.RetreatRestrictionException;
import github.vhoom.zhijian.util.diagnostics.DiagnosticLog;

import java.util.concurrent.CompletableFuture;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import github.vhoom.zhijian.ui.component.async.FutureUiObserver;

/**
 * 所有页面共用的主题基类：把设置里的配色模式落到资源配置上，模式变了就重建本页。
 * 颜色全部通过 @color 引用，所以翻面只需要换资源配置，不需要动任何布局。
 *
 * 维护日期：2026年11月14日
 */
public abstract class ThemedActivity extends Activity {

    /** 本实例真正生效的模式；从设置页返回时靠它判断要不要重建。 */
    private ThemeMode appliedMode;
    private boolean storageBusy;
    private int activeWrites;
    private final List<FutureUiObserver<Void>> taskObservers = new ArrayList<>();

    /**
     * 创建页面或进程依赖，并绑定本次启动所需的状态。
     *
     * @param savedInstanceState 系统保存的上次页面状态，首次创建时为 null
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::onBackRequested);
        }
    }

    /**
     * 数据库初始化和必要资源校验在后台完成，页面不阻塞主线程等待。
     *
     * @param initialize 存储及资源检查成功后执行的页面创建动作
     */
    protected final void whenStorageReady(Runnable initialize) {
        CompletableFuture<Void> ready = ((ZhijianApplication) getApplication()).ready();
        if (ready.isDone() && !ready.isCompletedExceptionally()) {
            initialize.run();
            return;
        }
        TextView loading = new TextView(this, null, 0, R.style.Text_App_Supporting);
        loading.setGravity(Gravity.CENTER);
        loading.setText(R.string.storage_loading);
        setContentView(loading);
        observeUi(ready, (ignored, failure) -> {
            if (isDestroyed() || isFinishing()) return;
            if (failure == null) {
                initialize.run();
            } else {
                Log.e("ZhijianStorage", "Storage initialization failed", failure);
                DiagnosticLog.error("storage", "", "page_initialization_failed page="
                        + getClass().getSimpleName(), failure);
                AppDialogs.builder(this)
                        .setTitle(R.string.storage_unavailable)
                        .setMessage(R.string.storage_load_failed)
                        .setPositiveButton(android.R.string.ok, (dialog, which) -> finish())
                        .setCancelable(false)
                        .show();
            }
        });
    }

    /**
     * 返回当前页面是否仍在等待一次存储写入完成。
     */
    protected final boolean isStorageBusy() { return storageBusy; }

    /**
     * 提交确认前保留编辑页面，失败时草稿仍在，用户可重试。
     *
     * @param operation 正在等待完成的异步存储操作
     * @param success 持久化成功后执行的界面回调
     */
    protected final void persist(CompletableFuture<Void> operation, Runnable success) {
        storageBusy = true;
        activeWrites++;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        observeUi(operation, (ignored, failure) -> {
            activeWrites--;
            storageBusy = activeWrites > 0;
            if (!storageBusy) getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
            if (isFinishing()) return;
            if (failure == null) {
                if (!isFinishing()) success.run();
            } else {
                Throwable reason = failure;
                while (reason.getCause() != null && reason.getCause() != reason) reason = reason.getCause();
                if (reason instanceof RetreatRestrictionException) {
                    Toast.makeText(this, ((RetreatRestrictionException) reason).levelChange
                            ? R.string.retreat_level_locked : R.string.retreat_rule_locked, Toast.LENGTH_LONG).show();
                    return;
                }
                Log.e("ZhijianStorage", "Storage write failed", failure);
                DiagnosticLog.error("storage", "", "commit_failed page=" + getClass().getSimpleName(), failure);
                Toast.makeText(this, R.string.storage_save_failed, Toast.LENGTH_LONG).show();
            }
        });
    }

    /**
     * 注册可随页面销毁释放的任务观察，不影响后台事务执行。
     * @param task 存储或启动任务
     * @param callback 有效页面在主线程接收的完成动作
     */
    private void observeUi(CompletableFuture<Void> task, BiConsumer<Void, Throwable> callback) {
        taskObservers.removeIf(FutureUiObserver::isClosed);
        taskObservers.add(new FutureUiObserver<>(task, this, callback));
    }

    /** 释放等待中的页面回调；后台提交依然通过存储队列完整结算。 */
    @Override protected void onDestroy() {
        for (FutureUiObserver<Void> observer : taskObservers) observer.close();
        taskObservers.clear();
        super.onDestroy();
    }

    /**
     * 把旧版本系统的返回键转交统一返回处理。
     */
    @Override
    @SuppressLint("GestureBackNavigation") // API 33+ 使用上面注册的回调；此入口兼容旧系统。
    public void onBackPressed() {
        onBackRequested();
    }

    /**
     * 处理系统返回动作，必要时先校验并保存当前草稿。
     */
    protected void onBackRequested() {
        if (!storageBusy) finish();
    }

    /**
     * 在窗口首次读取资源之前应用已保存的配色配置。
     *
     * @param newBase 尚未绑定窗口资源的基础上下文
     */
    @Override
    protected void attachBaseContext(Context newBase) {
        // 覆盖配置必须赶在窗口资源被建出来之前提交。本机 ROM 在 onCreate() 之前就已经用过
        // Activity 自己的 Resources，那时候再调 applyOverrideConfiguration() 会抛
        // 「getResources() or getAssets() has already been called」，直接把启动搞崩。
        appliedMode = GlobalSettings.readThemeMode(newBase);
        applyThemeMode(newBase, appliedMode);
        super.attachBaseContext(newBase);
    }

    /**
     * 页面恢复可见后刷新配置和界面状态。
     */
    @Override
    protected void onResume() {
        super.onResume();
        ThemeMode saved = GlobalSettings.readThemeMode(this);
        if (saved != appliedMode) recreate();
    }

    /** 本页当前生效的模式，设置页改完配色拿它对比，决定是否需要重建。 */
    protected ThemeMode appliedThemeMode() {
        return appliedMode;
    }

    /**
     * 只改写 uiMode 的夜间位，其余字段照抄窗口配置，否则屏幕方向、字体缩放也会被顺手写死。
     * 跟随系统不提交覆盖：资源本来就会按设备当前的 uiMode 自己挑白天还是夜间目录。
     *
     * @param newBase 尚未绑定窗口资源的基础上下文
     * @param mode 界面配色模式或设置文件的访问模式
     */
    private void applyThemeMode(Context newBase, ThemeMode mode) {
        if (mode.nightBits == 0) return;
        Configuration base = newBase.getResources().getConfiguration();
        Configuration override = new Configuration(base);
        override.uiMode = (base.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | mode.nightBits;
        applyOverrideConfiguration(override);
    }
}
