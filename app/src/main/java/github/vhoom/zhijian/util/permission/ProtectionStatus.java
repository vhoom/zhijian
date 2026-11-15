package github.vhoom.zhijian.util.permission;

/**
 * 根据实际授权、服务连接和后台限制生成首页功能状态，颜色之外仍有明确文字。
 *
 * 维护日期：2026年11月14日
 */
public enum ProtectionStatus {
    MISSING_PERMISSION, WAITING_CONNECTION, BACKGROUND_RESTRICTED, DISPLAY_UNCONFIRMED, RUNNING;

    /**
     * 必要权限优先，其次检查服务连接与后台限制。
     *
     * @param usageAccess 是否拥有使用情况访问权限
     * @param accessibilityInstalled 系统是否注册了 止间无障碍服务
     * @param accessibilityEnabled 无障碍服务是否启用
     * @param accessibilityConnected 服务是否实际连接
     * @param backgroundRestricted 系统是否限制本应用后台运行
     */
    public static ProtectionStatus resolve(boolean usageAccess, boolean accessibilityInstalled,
            boolean accessibilityEnabled, boolean accessibilityConnected, boolean backgroundRestricted) {
        return resolve(usageAccess, accessibilityInstalled, accessibilityEnabled,
                accessibilityConnected, backgroundRestricted, false);
    }

    /**
     * 联合基础权限和实际页面回执生成状态，未确认页面时不能显示服务正常。
     * @param usageAccess 是否已取得使用统计权限
     * @param accessibilityInstalled 系统是否识别服务
     * @param accessibilityEnabled 是否已启用服务
     * @param accessibilityConnected 服务是否已连接
     * @param backgroundRestricted 系统是否限制后台运行
     * @param displayUnconfirmed 最近页面请求是否缺少真实窗口回执
     * @return 按故障优先级生成的状态
     */
    public static ProtectionStatus resolve(boolean usageAccess, boolean accessibilityInstalled,
            boolean accessibilityEnabled, boolean accessibilityConnected, boolean backgroundRestricted,
            boolean displayUnconfirmed) {
        if (!usageAccess || !accessibilityInstalled || !accessibilityEnabled) return MISSING_PERMISSION;
        if (!accessibilityConnected) return WAITING_CONNECTION;
        if (backgroundRestricted) return BACKGROUND_RESTRICTED;
        return displayUnconfirmed ? DISPLAY_UNCONFIRMED : RUNNING;
    }
}
