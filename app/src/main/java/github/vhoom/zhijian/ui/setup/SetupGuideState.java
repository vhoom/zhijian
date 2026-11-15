package github.vhoom.zhijian.ui.setup;

import github.vhoom.zhijian.R;
import github.vhoom.zhijian.util.permission.PermissionStatusReader;
import github.vhoom.zhijian.util.permission.ProtectionStatus;

/**
 * 配置引导的不可变显示状态；真实权限快照和调试样例在界面边界完成适配。
 *
 * 维护日期：2026年11月10日
 */
public final class SetupGuideState {
    /** 授权与连接分别表达，避免把授权误报为服务正在运行。 */
    public enum Accessibility {
        UNAVAILABLE(R.string.setup_accessibility_unavailable),
        DISABLED(R.string.setup_disabled),
        DISCONNECTED(R.string.setup_accessibility_disconnected),
        CONNECTED(R.string.setup_accessibility_connected);

        public final int labelRes;

        /** @param labelRes 当前状态的文字资源 */
        Accessibility(int labelRes) { this.labelRes = labelRes; }

        /** @return 是否已获得系统授权，与实际连接状态独立 */
        public boolean isEnabled() { return this == DISCONNECTED || this == CONNECTED; }
    }

    public final boolean usageAccess;
    public final boolean batteryExempt;
    public final Accessibility accessibility;
    public final boolean backgroundRestricted;
    public final boolean displayUnconfirmed;

    /**
     * 创建供界面使用的状态，不会授予权限或写入运行记录。
     * @param usageAccess 使用情况访问是否已开启
     * @param batteryExempt 是否已允许忽略电池优化
     * @param accessibility 无障碍授权与实际连接的组合状态
     * @param backgroundRestricted 系统是否检测到后台运行受限
     * @param displayUnconfirmed 最近拦截页面是否尚未确认显示
     */
    public SetupGuideState(boolean usageAccess, boolean batteryExempt,
            Accessibility accessibility, boolean backgroundRestricted, boolean displayUnconfirmed) {
        this.usageAccess = usageAccess;
        this.batteryExempt = batteryExempt;
        this.accessibility = java.util.Objects.requireNonNull(accessibility);
        this.backgroundRestricted = backgroundRestricted;
        this.displayUnconfirmed = displayUnconfirmed;
    }

    /**
     * 在界面边界适配真实系统快照，不为预览伪造权限读取结果。
     * @param snapshot 权限读取器返回的实际系统状态
     * @return 只包含配置引导所需信号的显示状态
     */
    public static SetupGuideState from(PermissionStatusReader.Snapshot snapshot) {
        Accessibility accessibility = !snapshot.accessibilityInstalled ? Accessibility.UNAVAILABLE
                : !snapshot.accessibilityEnabled ? Accessibility.DISABLED
                : snapshot.accessibilityConnected ? Accessibility.CONNECTED : Accessibility.DISCONNECTED;
        return new SetupGuideState(snapshot.usageAccess, snapshot.batteryExempt, accessibility,
                snapshot.backgroundRestricted, snapshot.displayUnconfirmed);
    }

    /** @return 按必要权限、连接和后台信号的原有优先级生成首页提示 */
    public ProtectionStatus protectionStatus() {
        return ProtectionStatus.resolve(usageAccess, accessibility != Accessibility.UNAVAILABLE,
                accessibility.isEnabled(), accessibility == Accessibility.CONNECTED,
                backgroundRestricted, displayUnconfirmed);
    }

    /** @return 后台行的提示资源；具体检查步骤始终保留在说明内 */
    public int backgroundLabelRes() {
        return backgroundRestricted ? R.string.setup_background_restricted
                : displayUnconfirmed ? R.string.setup_display_unconfirmed : R.string.setup_manual;
    }
}
