package github.vhoom.zhijian.data.model.settings;

import github.vhoom.zhijian.data.model.interception.InterceptionMode;

/**
 * 独立于挑战参数的流程选项；运行判定和页面会话共用同一组默认值。
 *
 * 维护日期：2026年11月09日
 */
public final class InterceptionOptions {
    public final boolean temporaryPassEnabled;
    public final boolean obstaclePagesEnabled;
    public final boolean dynamicButtonsEnabled;
    public final boolean confirmationEnabled;

    /**
     * 冻结本次流程开关；模式是否支持相应能力由流程策略校验。
     * @param temporaryPassEnabled 继续时是否允许签发限时放行
     * @param obstaclePagesEnabled 修心是否经过挑战及后续选择
     */
    public InterceptionOptions(boolean temporaryPassEnabled, boolean obstaclePagesEnabled) {
        this(temporaryPassEnabled, obstaclePagesEnabled, true);
    }

    /**
     * 冻结流程与首屏样式，已有调用保留动态样式默认值。
     * @param temporaryPassEnabled 是否签发临时放行
     * @param obstaclePagesEnabled 修心是否经过挑战
     * @param dynamicButtonsEnabled 无为是否随机控件组合与上下位置
     */
    public InterceptionOptions(boolean temporaryPassEnabled, boolean obstaclePagesEnabled,
            boolean dynamicButtonsEnabled) {
        this(temporaryPassEnabled, obstaclePagesEnabled, dynamicButtonsEnabled, true);
    }

    /**
     * 冻结各模式的独立流程设置，运行中不会读取后来修改的选项。
     * @param temporaryPassEnabled 是否签发临时放行
     * @param obstaclePagesEnabled 修心是否经过挑战
     * @param dynamicButtonsEnabled 无为是否使用动态控件
     * @param confirmationEnabled 修心挑战后是否进入反问页
     */
    public InterceptionOptions(boolean temporaryPassEnabled, boolean obstaclePagesEnabled,
            boolean dynamicButtonsEnabled, boolean confirmationEnabled) {
        this.temporaryPassEnabled = temporaryPassEnabled;
        this.obstaclePagesEnabled = obstaclePagesEnabled;
        this.dynamicButtonsEnabled = dynamicButtonsEnabled;
        this.confirmationEnabled = confirmationEnabled;
    }

    /** @param mode 展示模式 @return 无为放行关闭、修心放行和挑战开启，动态样式默认开启 */
    public static InterceptionOptions defaultsFor(InterceptionMode mode) {
        boolean repair = mode == InterceptionMode.REPAIR;
        return new InterceptionOptions(repair, repair);
    }
}
