package github.vhoom.zhijian.ui.binder;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Typeface;
import android.os.Build;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import github.vhoom.zhijian.R;
import github.vhoom.zhijian.ui.component.dialog.AppDialogs;
import github.vhoom.zhijian.ui.setup.SetupGuideDialogFactory;
import github.vhoom.zhijian.ui.setup.SetupGuideDialogFactory.Guide;
import github.vhoom.zhijian.ui.setup.SetupGuideState;
import github.vhoom.zhijian.util.permission.PermissionIntents;
import github.vhoom.zhijian.util.permission.PermissionStatusObserver;
import github.vhoom.zhijian.util.permission.PermissionStatusReader;
import github.vhoom.zhijian.util.permission.ProtectionStatus;

/**
 * 首页网格和设置列表共享引导。可见时订阅连接变化，返回页面时重新查询系统权限。
 *
 * 维护日期：2026年11月10日
 */
public final class SetupGuideBinder {
    private final Activity activity;
    private final View root;
    private final View body;
    private final TextView toggle;
    private final TextView heading;
    private final TextView functionStatus;
    private final View header;
    private final boolean collapsible;
    private final PermissionStatusObserver observer;
    private final boolean previewMode;
    private final SetupGuideDialogFactory dialogFactory;
    private SetupGuideState state;
    private int sdkLevel;
    private Dialog dialog;

    /**
     * 创建 SetupGuideBinder 实例，并初始化本次操作依赖或不可变字段。
     *
     * @param activity 执行本次界面操作的 Activity
     * @param root 包含标题与配置入口的引导根视图
     * @param collapsible 是否允许折叠系统指导卡片
     */
    public SetupGuideBinder(Activity activity, View root, boolean collapsible) {
        this(activity, root, collapsible, null, Build.VERSION.SDK_INT);
    }

    /**
     * 创建独立预览绑定器，样例不订阅或覆盖真实权限。
     * @param activity 开发者预览页面
     * @param root 实际首页网格或设置列表的根视图
     * @param collapsible 是否支持首页的展开和收起
     * @param state 必须显式提供的预览状态
     * @param sdkLevel 用于条件文案的模拟 Android API 等级
     * @return 仅执行预览反馈的绑定器
     */
    public static SetupGuideBinder preview(Activity activity, View root, boolean collapsible,
            SetupGuideState state, int sdkLevel) {
        return new SetupGuideBinder(activity, root, collapsible,
                java.util.Objects.requireNonNull(state), sdkLevel);
    }

    /**
     * 装配共用界面，并在创建时固定真实导航或预览策略。
     * @param activity 配置引导的宿主页面
     * @param root 包含标题与配置入口的根视图
     * @param collapsible 是否允许折叠系统指导卡片
     * @param previewState 预览状态；为空时使用真实系统观察
     * @param sdkLevel 实际或模拟的 Android API 等级
     */
    private SetupGuideBinder(Activity activity, View root, boolean collapsible,
            SetupGuideState previewState, int sdkLevel) {
        this.activity = activity;
        this.root = root;
        this.collapsible = collapsible;
        this.sdkLevel = sdkLevel;
        state = previewState;
        previewMode = previewState != null;
        observer = previewMode ? null : new PermissionStatusObserver(activity, this::refresh);
        dialogFactory = new SetupGuideDialogFactory(activity, this::openSettings, this::openAppDetails);
        body = root.findViewById(R.id.setup_body);
        header = root.findViewById(R.id.setup_header);
        heading = root.findViewById(R.id.setup_heading);
        functionStatus = root.findViewById(R.id.setup_function_status);
        if (functionStatus != null) functionStatus.setImportantForAccessibility(
                View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        toggle = root.findViewById(R.id.setup_toggle);
        toggle.setVisibility(collapsible ? View.VISIBLE : View.GONE);
        toggle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        header.setOnClickListener(collapsible ? v -> setExpanded(!isExpanded()) : null);
        header.setClickable(collapsible);
        header.setFocusable(collapsible);
        header.setAccessibilityHeading(collapsible);
        heading.setImportantForAccessibility(collapsible
                ? View.IMPORTANT_FOR_ACCESSIBILITY_NO : View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        configureTile(R.id.setup_accessibility, Guide.ACCESSIBILITY, R.drawable.ic_setup_accessibility);
        configureTile(R.id.setup_usage, Guide.USAGE, R.drawable.ic_setup_usage);
        configureTile(R.id.setup_battery, Guide.BATTERY, R.drawable.ic_setup_battery);
        configureTile(R.id.setup_background, Guide.BACKGROUND, R.drawable.ic_setup_background);
        configureTile(R.id.setup_autostart, Guide.AUTOSTART, R.drawable.ic_setup_autostart);
        setExpanded(true);
        if (previewMode) refresh();
    }

    /**
     * 开始当前组件的观察、数据或弹窗状态。
     */
    public void start() {
        if (observer != null) observer.start();
        refresh();
    }

    /**
     * 停止当前组件的观察、数据或弹窗状态。
     */
    public void stop() {
        if (observer != null) observer.stop();
        dismissDialog();
    }

    /**
     * 刷新当前组件的观察、数据或弹窗状态。
     */
    public void refresh() {
        if (!previewMode) state = SetupGuideState.from(PermissionStatusReader.read(activity));
        updateHeader();
        bindStatus(R.id.setup_accessibility, activity.getString(state.accessibility.labelRes),
                state.accessibility == SetupGuideState.Accessibility.CONNECTED
                        ? R.color.level_selected_text : R.color.body);
        bindStatus(R.id.setup_usage, activity.getString(state.usageAccess
                        ? R.string.setup_enabled : R.string.setup_disabled),
                state.usageAccess ? R.color.level_selected_text : R.color.body);
        bindStatus(R.id.setup_battery, activity.getString(state.batteryExempt
                        ? R.string.setup_enabled : R.string.setup_disabled),
                state.batteryExempt ? R.color.level_selected_text : R.color.body);
        bindStatus(R.id.setup_background, activity.getString(state.backgroundLabelRes()),
                state.backgroundRestricted || state.displayUnconfirmed ? R.color.error_deep : R.color.body);
        bindStatus(R.id.setup_autostart, activity.getString(R.string.setup_manual), R.color.body);
    }

    /**
     * 切换开发者样例，关闭上一份说明后同步状态和条件文案。
     * @param state 新的预览显示状态
     * @param sdkLevel 新样例使用的模拟 Android API 等级
     */
    public void setPreviewState(SetupGuideState state, int sdkLevel) {
        if (!previewMode) throw new IllegalStateException("Only preview binders accept sample states");
        dismissDialog();
        this.state = java.util.Objects.requireNonNull(state);
        this.sdkLevel = sdkLevel;
        refresh();
    }

    /**
     * 更新系统配置指导卡片的可见状态。
     *
     * @param visible 卡片是否应当可见
     */
    public void setVisible(boolean visible) {
        root.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    /**
     * 返回系统指导卡片当前是否展开。
     */
    public boolean isExpanded() { return body.getVisibility() == View.VISIBLE; }

    /**
     * 切换指导卡片的展开状态并同步标题。
     *
     * @param expanded 卡片需要切换到的展开状态
     */
    public void setExpanded(boolean expanded) {
        boolean show = !collapsible || expanded;
        body.setVisibility(show ? View.VISIBLE : View.GONE);
        if (collapsible) {
            int horizontal = activity.getResources().getDimensionPixelSize(R.dimen.space_md);
            int vertical = activity.getResources().getDimensionPixelSize(show ? R.dimen.space_md : R.dimen.space_xxs);
            // 留白归属可点击的标题行，让边缘触摸和水波纹覆盖同一范围。
            header.setPaddingRelative(horizontal, vertical, horizontal, show ? 0 : vertical);
        }
        toggle.setText(show ? R.string.setup_collapse : R.string.setup_expand);
        updateHeader();
    }

    /**
     * 按授权完成情况更新指导卡片标题和提示。
     */
    private void updateHeader() {
        if (!collapsible) return;
        boolean expanded = isExpanded();
        int title = R.string.setup_title;
        updateFunctionStatus();
        heading.setText(title);
        heading.setTextAppearance(expanded
                ? R.style.TextAppearance_App_BodyLg : R.style.TextAppearance_App_BodyMd);
        heading.setTypeface(Typeface.DEFAULT, expanded ? Typeface.BOLD : Typeface.NORMAL);
        if (collapsible) header.setContentDescription(activity.getString(R.string.setup_heading_description,
                activity.getString(title) + (functionStatus == null ? "" : "，" + functionStatus.getText()), activity.getString(expanded
                        ? R.string.setup_collapse : R.string.setup_expand)));
    }

    /** 按当前引导状态显示有语义颜色的功能提示；设置列表没有此控件时直接跳过。 */
    private void updateFunctionStatus() {
        if (functionStatus == null || state == null) return;
        ProtectionStatus status = state.protectionStatus();
        int text;
        int color;
        switch (status) {
            case MISSING_PERMISSION:
                text = R.string.setup_function_missing_permission;
                color = R.color.error_deep;
                break;
            case WAITING_CONNECTION:
                text = R.string.setup_function_waiting_connection;
                color = R.color.warning_deep;
                break;
            case BACKGROUND_RESTRICTED:
                text = R.string.setup_function_background_restricted;
                color = R.color.warning_deep;
                break;
            case DISPLAY_UNCONFIRMED:
                text = R.string.setup_function_display_unconfirmed;
                color = R.color.warning_deep;
                break;
            default:
                text = R.string.setup_function_running;
                color = R.color.level_selected_text;
                break;
        }
        functionStatus.setText(text);
        functionStatus.setTextColor(activity.getColor(color));
    }

    /**
     * 释放当前组件的观察、数据或弹窗状态。
     */
    public void close() {
        if (observer != null) observer.close();
        dismissDialog();
    }

    /**
     * 关闭当前弹窗并释放其引用。
     */
    private void dismissDialog() {
        if (dialog != null) dialog.dismiss();
        dialog = null;
    }

    /**
     * 绑定五项独立配置入口的图标、文案和统一说明操作。
     * @param id 当前入口的视图标识
     * @param guide 点击时显示的配置说明
     * @param icon 当前入口的图标资源
     */
    private void configureTile(int id, Guide guide, int icon) {
        View tile = root.findViewById(id);
        ((TextView) tile.findViewById(R.id.setup_title)).setText(guide.titleRes);
        ((ImageView) tile.findViewById(R.id.setup_icon)).setImageResource(icon);
        tile.setOnClickListener(view -> showGuide(guide));
    }

    /**
     * 渲染实际或预览状态，每项入口提供独立的读屏说明。
     * @param id 当前配置入口的视图标识
     * @param label 完整状态文字
     * @param color 当前状态的语义颜色资源
     */
    private void bindStatus(int id, String label, int color) {
        View tile = root.findViewById(id);
        TextView status = tile.findViewById(R.id.setup_status);
        status.setText(label);
        status.setTextColor(activity.getColor(color));
        tile.setContentDescription(activity.getString(R.string.setup_item_description,
                ((TextView) tile.findViewById(R.id.setup_title)).getText(), label.replace('\n', '，')));
    }

    /** 显示使用情况访问说明，供首页持续权限提醒复用。 */
    public void showUsageGuide() { showGuide(Guide.USAGE); }

    /**
     * 显示共用说明；真实入口即时读取权限，预览使用选中的样例。
     * @param guide 需要打开的配置入口或独立说明
     */
    public void showGuide(Guide guide) {
        dismissDialog();
        refresh();
        dialog = AppDialogs.show(dialogFactory.create(guide, state, sdkLevel));
    }

    /**
     * 执行用户主动选择的系统导航，预览仅反馈对应动作。
     * @param guide 用户点击前往设置的独立配置说明
     */
    private void openSettings(Guide guide) {
        if (previewMode) {
            showPreviewResult(activity.getString(guide.titleRes));
            return;
        }
        switch (guide) {
            case ACCESSIBILITY: PermissionIntents.openAccessibilitySettings(activity); break;
            case USAGE: PermissionIntents.openUsageAccess(activity); break;
            case BATTERY:
                PermissionIntents.openBatterySettings(activity, PermissionStatusReader.read(activity).batteryExempt);
                break;
            case BACKGROUND: PermissionIntents.openBackgroundSettings(activity); break;
            case AUTOSTART: PermissionIntents.openAppDetails(activity); break;
            default: throw new IllegalArgumentException("Guide has no system destination: " + guide);
        }
    }

    /** 无障碍说明的应用信息操作采用与前往设置相同的真实或预览策略。 */
    private void openAppDetails() {
        if (previewMode) showPreviewResult(activity.getString(R.string.setup_open_app_details));
        else PermissionIntents.openAppDetails(activity);
    }

    /**
     * 使用 Android 原生短提示反馈预览按钮，不打开系统权限页面。
     * @param action 当前操作的可读名称
     */
    private void showPreviewResult(String action) {
        Toast.makeText(activity, activity.getString(R.string.setup_preview_action_result, action),
                Toast.LENGTH_SHORT).show();
    }
}
