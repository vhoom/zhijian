package github.vhoom.zhijian.ui.setup;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Build;

import java.util.function.Consumer;

import github.vhoom.zhijian.R;
import github.vhoom.zhijian.ui.component.dialog.AppDialogs;

/**
 * 正式配置与开发者预览共用的说明工厂；系统导航由宿主提供的策略执行。
 *
 * 维护日期：2026年11月10日
 */
public final class SetupGuideDialogFactory {
    /** 与首页和设置列表顺序一致的五个独立配置说明。 */
    public enum Guide {
        ACCESSIBILITY(R.string.setup_accessibility_title),
        USAGE(R.string.setup_usage_title),
        BATTERY(R.string.setup_battery_title),
        BACKGROUND(R.string.setup_background_title),
        AUTOSTART(R.string.setup_autostart_title);

        public final int titleRes;

        /** @param titleRes 当前入口或说明的标题资源 */
        Guide(int titleRes) { this.titleRes = titleRes; }
    }

    private final Activity activity;
    private final Consumer<Guide> openSettings;
    private final Runnable openAppDetails;

    /**
     * 注入正式跳转或预览反馈，不在文案工厂读取和修改权限。
     * @param activity 使用当前深浅色主题的页面
     * @param openSettings 用户点击前往设置时的导航策略
     * @param openAppDetails 用户点击无障碍说明中应用信息按钮时的操作
     */
    public SetupGuideDialogFactory(Activity activity, Consumer<Guide> openSettings,
            Runnable openAppDetails) {
        this.activity = activity;
        this.openSettings = openSettings;
        this.openAppDetails = openAppDetails;
    }

    /**
     * 为配置入口直接创建原生说明，显示与关闭由绑定器管理。
     * @param guide 当前独立配置项
     * @param state 本次说明采用的显示状态
     * @param sdkLevel 实际或预览使用的 Android API 等级
     * @return 尚未显示的共用主题原生弹窗
     */
    public AlertDialog create(Guide guide, SetupGuideState state, int sdkLevel) {
        AlertDialog.Builder builder = AppDialogs.builder(activity).setTitle(guide.titleRes)
                .setNegativeButton(android.R.string.cancel, null);
        builder.setMessage(message(guide, state, sdkLevel))
                .setPositiveButton(R.string.setup_go_settings, (dialog, which) -> openSettings.accept(guide));
        if (guide == Guide.ACCESSIBILITY) {
            builder.setNeutralButton(R.string.setup_open_app_details,
                    (dialog, which) -> openAppDetails.run());
        }
        return builder.create();
    }

    /**
     * 使用统一资源生成各状态的说明，预览与实际入口遵循相同分支。
     * @param guide 需要生成说明的独立配置项
     * @param state 本次使用的显示状态
     * @param sdkLevel 用于判断 Android 13 受限设置提示的 API 等级
     * @return 包含完整操作步骤及适用警示的说明
     */
    private String message(Guide guide, SetupGuideState state, int sdkLevel) {
        String appName = activity.getString(R.string.app_name);
        switch (guide) {
            case ACCESSIBILITY:
                int detail = state.accessibility == SetupGuideState.Accessibility.UNAVAILABLE
                        ? R.string.setup_accessibility_unavailable_detail
                        : state.accessibility == SetupGuideState.Accessibility.DISABLED
                        ? R.string.setup_accessibility_detail
                        : state.accessibility == SetupGuideState.Accessibility.CONNECTED
                        ? R.string.setup_accessibility_connected_detail
                        : R.string.setup_accessibility_disconnected_detail;
                String message = activity.getString(detail, appName,
                        activity.getString(R.string.setup_background_title));
                if (sdkLevel >= Build.VERSION_CODES.TIRAMISU && !state.accessibility.isEnabled()) {
                    message += "\n\n" + activity.getString(R.string.setup_accessibility_restricted_detail);
                }
                return message;
            case USAGE:
                return activity.getString(R.string.setup_usage_detail, appName);
            case BATTERY:
                return activity.getString(state.batteryExempt ? R.string.setup_battery_enabled_detail
                        : R.string.setup_battery_detail, appName);
            case BACKGROUND:
                String background = activity.getString(R.string.setup_background_detail, appName);
                if (state.displayUnconfirmed) {
                    background = activity.getString(R.string.setup_display_unconfirmed_detail,
                            activity.getString(R.string.settings_about_title),
                            activity.getString(R.string.diagnostics_title)) + "\n\n" + background;
                }
                if (state.backgroundRestricted) {
                    background = activity.getString(R.string.setup_background_restricted_detail)
                            + "\n\n" + background;
                }
                return background;
            case AUTOSTART:
                return activity.getString(R.string.setup_autostart_detail, appName);
            default:
                throw new IllegalArgumentException("Guide has no standalone message: " + guide);
        }
    }
}
