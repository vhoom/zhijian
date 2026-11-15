package github.vhoom.zhijian.ui.component.rule;

import android.app.Activity;
import android.app.AlertDialog;

import github.vhoom.zhijian.R;
import github.vhoom.zhijian.ui.component.dialog.AppDialogs;

/**
 * 单应用与规则组共用的重置确认弹窗工厂。
 *
 * 维护日期：2026年11月08日
 */
public final class RuleResetConfirmation {
    /** 弹窗工厂不持有页面实例。 */
    private RuleResetConfirmation() { }

    /**
     * 创建并显示确认框；取消、返回及点击外部均不执行重置。
     * @param activity 展示弹窗的编辑页面
     * @param message 描述当前页面重置范围的字符串资源
     * @param reset 用户确认后执行的重置操作
     * @return 由页面负责关闭和释放的弹窗
     */
    public static AlertDialog show(Activity activity, int message, Runnable reset) {
        return AppDialogs.builder(activity)
                .setTitle(R.string.rule_reset_all)
                .setMessage(message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.rule_reset_all, (dialog, which) -> reset.run())
                .show();
    }
}
