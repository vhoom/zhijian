package github.vhoom.zhijian.ui.preview;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import github.vhoom.zhijian.BuildConfig;
import github.vhoom.zhijian.R;
import github.vhoom.zhijian.ui.activity.ThemedActivity;
import github.vhoom.zhijian.ui.binder.SetupGuideBinder;
import github.vhoom.zhijian.ui.component.dialog.AppDialogs;
import github.vhoom.zhijian.ui.interception.InterceptionActivity;
import github.vhoom.zhijian.ui.preview.SetupPreviewCatalog.Scenario;
import github.vhoom.zhijian.util.ui.SystemBars;

/**
 * Debug 统一预览入口：复用首页诗句、实际配置布局与说明，并保留全量拦截图集。
 *
 * 维护日期：2026年11月11日
 */
public final class DeveloperPreviewActivity extends ThemedActivity {
    private static final String STATE_SCENARIO = "developer.scenario";
    private static final String STATE_SETTINGS_LAYOUT = "developer.settings_layout";
    private static final String STATE_GRID_EXPANDED = "developer.grid_expanded";
    private SetupGuideBinder guideBinder;
    private PoemPreviewBinder poemPreview;
    private FrameLayout guideContainer;
    private TextView scenarioLabel;
    private TextView layoutButton;
    private AlertDialog selector;
    private int scenarioIndex;
    private boolean settingsLayout;
    private boolean gridExpanded = true;

    /**
     * 创建应用内开发者页面请求，页面自身检查 Debug 构建。
     * @param context 发起预览的设置页面
     * @return 不对外导出的开发者页面请求
     */
    public static Intent intent(Context context) {
        return new Intent(context, DeveloperPreviewActivity.class);
    }

    /**
     * 装配目录与共用界面，预览不等待数据库或查询系统权限。
     * @param savedInstanceState 上次选中的样例、布局、展开状态和诗句草稿
     */
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!BuildConfig.DEBUG) { finish(); return; }
        setContentView(R.layout.activity_developer_preview);
        SystemBars.applyPadding(findViewById(R.id.root));
        ((TextView) findViewById(R.id.page_title)).setText(R.string.developer_preview_title);
        findViewById(R.id.btn_back).setOnClickListener(view -> finish());
        guideContainer = findViewById(R.id.setup_preview_container);
        scenarioLabel = findViewById(R.id.setup_preview_scenario);
        layoutButton = findViewById(R.id.setup_preview_layout);
        poemPreview = new PoemPreviewBinder(this, findViewById(R.id.poem_preview_root), savedInstanceState);
        if (savedInstanceState != null) {
            scenarioIndex = SetupPreviewCatalog.normalize(savedInstanceState.getInt(STATE_SCENARIO));
            settingsLayout = savedInstanceState.getBoolean(STATE_SETTINGS_LAYOUT);
            gridExpanded = savedInstanceState.getBoolean(STATE_GRID_EXPANDED, true);
        }
        findViewById(R.id.developer_interception_preview).setOnClickListener(view ->
                startActivity(InterceptionActivity.debugGalleryIntent(this)));
        findViewById(R.id.setup_preview_choose).setOnClickListener(view -> chooseScenario());
        findViewById(R.id.setup_preview_previous).setOnClickListener(view -> selectScenario(scenarioIndex - 1));
        findViewById(R.id.setup_preview_next).setOnClickListener(view -> selectScenario(scenarioIndex + 1));
        findViewById(R.id.setup_preview_description).setOnClickListener(view ->
                guideBinder.showGuide(SetupPreviewCatalog.scenario(scenarioIndex).guide));
        layoutButton.setOnClickListener(view -> {
            if (!settingsLayout) gridExpanded = guideBinder.isExpanded();
            settingsLayout = !settingsLayout;
            renderGuide();
        });
        renderGuide();
        updateScenarioLabel();
    }

    /** 将实际网格或设置列表装入预览区域，保留首页的展开状态。 */
    private void renderGuide() {
        if (guideBinder != null) guideBinder.close();
        guideContainer.removeAllViews();
        View root = getLayoutInflater().inflate(settingsLayout
                ? R.layout.view_setup_settings : R.layout.view_setup_guide, guideContainer, false);
        guideContainer.addView(root);
        Scenario scenario = SetupPreviewCatalog.scenario(scenarioIndex);
        guideBinder = SetupGuideBinder.preview(this, root, !settingsLayout, scenario.state, scenario.sdkLevel);
        guideBinder.setExpanded(gridExpanded);
        layoutButton.setText(getString(R.string.setup_preview_layout_value, getString(settingsLayout
                ? R.string.setup_preview_settings_layout : R.string.setup_preview_home_layout)));
    }

    /** 打开原生目录，所有条件文案和组合警示均可直接选择。 */
    private void chooseScenario() {
        dismissSelector();
        selector = AppDialogs.builder(this).setTitle(R.string.setup_preview_choose)
                .setSingleChoiceItems(SetupPreviewCatalog.labels(this), scenarioIndex, (dialog, index) -> {
                    dialog.dismiss();
                    selectScenario(index);
                }).setNegativeButton(android.R.string.cancel, null).create();
        AppDialogs.show(selector);
    }

    /**
     * 切换样例时只更新预览状态，不改变当前布局和折叠选择。
     * @param index 目录索引，上一项和下一项可首尾循环
     */
    private void selectScenario(int index) {
        scenarioIndex = SetupPreviewCatalog.normalize(index);
        Scenario scenario = SetupPreviewCatalog.scenario(scenarioIndex);
        guideBinder.setPreviewState(scenario.state, scenario.sdkLevel);
        updateScenarioLabel();
    }

    /** 同步完整样例名称和页码，读屏可在状态切换后获取反馈。 */
    private void updateScenarioLabel() {
        scenarioLabel.setText(getString(R.string.setup_preview_scenario_value, scenarioIndex + 1,
                SetupPreviewCatalog.count(), getString(SetupPreviewCatalog.scenario(scenarioIndex).labelRes)));
    }

    /** 页面重新可见时保持样例显示，不启动真实权限观察。 */
    @Override protected void onStart() {
        super.onStart();
        if (guideBinder != null) guideBinder.start();
    }

    /**
     * 保存样例、首页展开选择和诗句草稿，主题切换或系统重建后继续查看。
     * @param state 系统用于恢复当前页面的快照
     */
    @Override protected void onSaveInstanceState(Bundle state) {
        if (guideBinder != null && !settingsLayout) gridExpanded = guideBinder.isExpanded();
        state.putInt(STATE_SCENARIO, scenarioIndex);
        state.putBoolean(STATE_SETTINGS_LAYOUT, settingsLayout);
        state.putBoolean(STATE_GRID_EXPANDED, gridExpanded);
        if (poemPreview != null) poemPreview.saveState(state);
        super.onSaveInstanceState(state);
    }

    /** 离开开发者页面时关闭所有预览说明和目录窗口。 */
    @Override protected void onStop() {
        dismissSelector();
        if (guideBinder != null) guideBinder.stop();
        super.onStop();
    }

    /** 释放共用界面的窗口引用，不持有已销毁的 Activity。 */
    @Override protected void onDestroy() {
        dismissSelector();
        if (guideBinder != null) guideBinder.close();
        if (poemPreview != null) poemPreview.close();
        super.onDestroy();
    }

    /** 关闭状态目录并释放其引用。 */
    private void dismissSelector() {
        if (selector != null) selector.dismiss();
        selector = null;
    }
}
