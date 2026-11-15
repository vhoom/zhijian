package github.vhoom.zhijian.ui.activity;

import android.app.Dialog;
import android.os.Bundle;
import android.view.View;
import android.widget.Switch;
import android.widget.TextView;

import github.vhoom.zhijian.R;
import github.vhoom.zhijian.ui.component.dialog.AppDialogs;
import github.vhoom.zhijian.data.model.settings.ThemeMode;
import github.vhoom.zhijian.data.store.settings.GlobalSettings;
import github.vhoom.zhijian.ui.binder.SetupGuideBinder;
import github.vhoom.zhijian.ui.settings.InterceptionSettingsBinder;
import github.vhoom.zhijian.ui.settings.AboutSettingsBinder;
import github.vhoom.zhijian.ui.settings.UsageRowSettingsBinder;
import github.vhoom.zhijian.util.config.AppConfig;
import github.vhoom.zhijian.util.ui.SystemBars;

/**
 * 设置中心：外观、首页显示、系统配置入口与拦截交互参数。
 * 通用配置接入 GlobalSettings；模式交互由独立绑定器接入独立设置存储。
 *
 * 维护日期：2026年11月13日
 */
public final class SettingsActivity extends ThemedActivity {
    private GlobalSettings settings;
    private InterceptionSettingsBinder interceptionSettings;
    private SetupGuideBinder setupGuide;
    /** 异步初始化完成时只在页面可见阶段启动系统配置观察。 */
    private boolean resumed;

    private Dialog settingsDialog;
    private UsageRowSettingsBinder usageRows;

    /**
     * 创建页面或进程依赖，并绑定本次启动所需的状态。
     *
     * @param savedInstanceState 系统保存的上次页面状态，首次创建时为 null
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        whenStorageReady(this::initializeSettings);
    }

    /** 必要资源就绪后创建设置视图，词库容量校验不再与冷启动加载竞争。 */
    private void initializeSettings() {
        setContentView(R.layout.activity_settings);
        SystemBars.applyPadding(findViewById(R.id.root));

        settings = new GlobalSettings(this, new AppConfig(this));
        ((TextView) findViewById(R.id.page_title)).setText(R.string.bar_settings);
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        findViewById(R.id.row_theme).setOnClickListener(v -> chooseTheme());
        findViewById(R.id.row_usage_limit).setOnClickListener(v -> chooseRowLimit());
        interceptionSettings = new InterceptionSettingsBinder(this, settings);
        usageRows = new UsageRowSettingsBinder(this, settings, this::renderAppearance);
        renderAppearance();
        new AboutSettingsBinder(this);

        Switch showGuide = findViewById(R.id.switch_show_guide);
        showGuide.setChecked(settings.showSetupGuide());
        showGuide.setOnCheckedChangeListener((button, checked) -> settings.setShowSetupGuide(checked));
        Switch showPoem = findViewById(R.id.switch_show_daily_poem);
        showPoem.setChecked(settings.showDailyPoem());
        showPoem.setOnCheckedChangeListener((button, checked) -> settings.setShowDailyPoem(checked));
        setupGuide = new SetupGuideBinder(this, findViewById(R.id.setup_card), false);
        if (resumed) setupGuide.start();
    }

    /**
     * 页面恢复可见后刷新配置和界面状态。
     */
    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (setupGuide != null) setupGuide.start();
        if (settings != null) renderAppearance();
    }

    /**
     * 页面离开前台时停止仅在可见期间运行的工作。
     */
    @Override
    protected void onPause() {
        resumed = false;
        if (setupGuide != null) setupGuide.stop();
        super.onPause();
    }

    /**
     * 释放本实例持有的监听、弹窗或后台回调。
     */
    @Override
    protected void onDestroy() {
        if (setupGuide != null) setupGuide.close();
        closeSettingsDialog();
        if (usageRows != null) usageRows.close();
        super.onDestroy();
    }

    /**
     * 刷新配色模式、显示条数及其无障碍说明。
     */
    private void renderAppearance() {
        String theme = getString(settings.themeMode().nameRes);
        ((TextView) findViewById(R.id.theme_value)).setText(theme);
        findViewById(R.id.row_theme).setContentDescription(getString(R.string.settings_row_description,
                getString(R.string.settings_theme_title), theme));
        String rows = getString(R.string.settings_rows_value, settings.usageRowLimit());
        ((TextView) findViewById(R.id.rows_value)).setText(rows);
        findViewById(R.id.row_usage_limit).setContentDescription(getString(R.string.settings_row_description,
                getString(R.string.settings_rows_title), rows));
    }

    /**
     * 展示配色模式列表。
     */
    private void chooseTheme() {
        closeSettingsDialog();
        ThemeMode[] modes = ThemeMode.values();
        String[] labels = new String[modes.length];
        for (int i = 0; i < modes.length; i++) labels[i] = getString(modes[i].nameRes);
        settingsDialog = AppDialogs.builder(this)
                .setTitle(R.string.settings_theme_title)
                .setSingleChoiceItems(labels, settings.themeMode().ordinal(), (dialog, which) -> {
                    dialog.dismiss();
                    chooseThemeMode(modes[which]);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * 保存选中的配色模式，变化时重建页面应用资源。
     *
     * @param mode 界面配色模式或设置文件的访问模式
     */
    private void chooseThemeMode(ThemeMode mode) {
        settings.setThemeMode(mode);
        renderAppearance();
        if (mode != appliedThemeMode()) recreate();
    }

    /** 展示以已扫描可管控应用数为上界的条数表单。 */
    private void chooseRowLimit() {
        closeSettingsDialog();
        usageRows.show();
    }

    /**
     * 关闭设置弹窗并清除引用。
     */
    private void closeSettingsDialog() {
        if (interceptionSettings != null) interceptionSettings.closeSettingsDialog();
        if (usageRows != null) usageRows.closeDialog();
        if (settingsDialog != null) settingsDialog.dismiss();
        settingsDialog = null;
    }
}
