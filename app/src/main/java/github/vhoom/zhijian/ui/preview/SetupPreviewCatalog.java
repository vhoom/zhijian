package github.vhoom.zhijian.ui.preview;

import android.content.Context;

import github.vhoom.zhijian.R;
import github.vhoom.zhijian.ui.setup.SetupGuideDialogFactory.Guide;
import github.vhoom.zhijian.ui.setup.SetupGuideState;
import github.vhoom.zhijian.ui.setup.SetupGuideState.Accessibility;

/**
 * 配置预览样例目录；显式覆盖授权、连接、Android 13 提示和两种后台警示组合。
 *
 * 维护日期：2026年11月10日
 */
public final class SetupPreviewCatalog {
    /** 每个样例指定显示状态、说明入口和条件文案使用的系统版本。 */
    public enum Scenario {
        ACCESSIBILITY_UNAVAILABLE(R.string.setup_preview_accessibility_unavailable, Guide.ACCESSIBILITY,
                32, accessibility(Accessibility.UNAVAILABLE)),
        ACCESSIBILITY_UNAVAILABLE_13(R.string.setup_preview_accessibility_unavailable_13, Guide.ACCESSIBILITY,
                33, accessibility(Accessibility.UNAVAILABLE)),
        ACCESSIBILITY_DISABLED(R.string.setup_preview_accessibility_disabled, Guide.ACCESSIBILITY,
                32, accessibility(Accessibility.DISABLED)),
        ACCESSIBILITY_DISABLED_13(R.string.setup_preview_accessibility_disabled_13, Guide.ACCESSIBILITY,
                33, accessibility(Accessibility.DISABLED)),
        ACCESSIBILITY_DISCONNECTED(R.string.setup_preview_accessibility_disconnected, Guide.ACCESSIBILITY,
                33, accessibility(Accessibility.DISCONNECTED)),
        ACCESSIBILITY_CONNECTED(R.string.setup_preview_accessibility_connected, Guide.ACCESSIBILITY,
                33, accessibility(Accessibility.CONNECTED)),
        USAGE_DISABLED(R.string.setup_preview_usage_disabled, Guide.USAGE, 33, usage(false)),
        USAGE_ENABLED(R.string.setup_preview_usage_enabled, Guide.USAGE, 33, usage(true)),
        BATTERY_DISABLED(R.string.setup_preview_battery_disabled, Guide.BATTERY, 33, battery(false)),
        BATTERY_ENABLED(R.string.setup_preview_battery_enabled, Guide.BATTERY, 33, battery(true)),
        BACKGROUND_MANUAL(R.string.setup_preview_background_manual, Guide.BACKGROUND,
                33, background(false, false)),
        BACKGROUND_RESTRICTED(R.string.setup_preview_background_restricted, Guide.BACKGROUND,
                33, background(true, false)),
        BACKGROUND_DISPLAY_UNCONFIRMED(R.string.setup_preview_background_unconfirmed, Guide.BACKGROUND,
                33, background(false, true)),
        BACKGROUND_BOTH(R.string.setup_preview_background_both, Guide.BACKGROUND,
                33, background(true, true)),
        AUTOSTART(R.string.setup_preview_autostart, Guide.AUTOSTART, 33, background(false, false));

        public final int labelRes;
        public final Guide guide;
        public final int sdkLevel;
        public final SetupGuideState state;

        /**
         * 保存一个完整且不会访问真实授权的预览样例。
         * @param labelRes 状态选择列表中的完整名称
         * @param guide 查看说明时打开的配置项
         * @param sdkLevel 条件文案使用的模拟 Android API 等级
         * @param state 网格、列表和说明共用的显示状态
         */
        Scenario(int labelRes, Guide guide, int sdkLevel, SetupGuideState state) {
            this.labelRes = labelRes;
            this.guide = guide;
            this.sdkLevel = sdkLevel;
            this.state = state;
        }

        /** @param value 需要预览的授权与连接状态 @return 其余基础权限已开启的无障碍样例 */
        private static SetupGuideState accessibility(Accessibility value) {
            return new SetupGuideState(true, true, value, false, false);
        }

        /** @param enabled 使用情况访问是否开启 @return 无障碍已连接的用量权限样例 */
        private static SetupGuideState usage(boolean enabled) {
            return new SetupGuideState(enabled, true, Accessibility.CONNECTED, false, false);
        }

        /** @param exempt 是否已允许忽略电池优化 @return 基础服务已连接的电源样例 */
        private static SetupGuideState battery(boolean exempt) {
            return new SetupGuideState(true, exempt, Accessibility.CONNECTED, false, false);
        }

        /**
         * 创建允许两个警示同时出现的后台样例。
         * @param restricted 是否显示系统后台受限说明
         * @param unconfirmed 是否显示最近拦截页面尚未确认的说明
         * @return 基础权限和服务连接已满足的后台显示状态
         */
        private static SetupGuideState background(boolean restricted, boolean unconfirmed) {
            return new SetupGuideState(true, true, Accessibility.CONNECTED, restricted, unconfirmed);
        }
    }

    /** 目录只通过静态方法读取。 */
    private SetupPreviewCatalog() { }

    /** @param index 可循环的样例索引 @return 目录内的有效索引 */
    public static int normalize(int index) { return Math.floorMod(index, Scenario.values().length); }

    /** @param index 当前或恢复的样例索引 @return 规范化后的完整样例 */
    public static Scenario scenario(int index) { return Scenario.values()[normalize(index)]; }

    /** @return 当前可查看的样例总数 */
    public static int count() { return Scenario.values().length; }

    /**
     * 为原生状态选择列表读取完整名称。
     * @param context 采用当前语言资源的页面
     * @return 与目录顺序一致的状态名称
     */
    public static String[] labels(Context context) {
        Scenario[] scenarios = Scenario.values();
        String[] labels = new String[scenarios.length];
        for (int index = 0; index < scenarios.length; index++) labels[index] = context.getString(scenarios[index].labelRes);
        return labels;
    }
}
