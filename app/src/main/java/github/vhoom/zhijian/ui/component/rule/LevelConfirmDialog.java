package github.vhoom.zhijian.ui.component.rule;

import android.app.Activity;
import android.app.Dialog;

import github.vhoom.zhijian.R;
import github.vhoom.zhijian.data.model.policy.ControlLevel;
import github.vhoom.zhijian.ui.component.dialog.AppDialogs;

/**
 * 档位切换的模态确认框：细则只在框内展开，确认之后才落到设置里。
 *
 * 维护日期：2026年11月08日
 */
public final class LevelConfirmDialog {

    /**
     * 用户确认档位变更后执行的回调。
     */
    public interface OnConfirm {
        /**
         * 执行用户确认后的管控档位切换。
         *
         * @param level 全局管控档位
         */
        void confirm(ControlLevel level);
    }

    /**
     * 工具类不允许实例化，调用方直接使用静态方法。
     */
    private LevelConfirmDialog() {
    }

    /**
     * 创建并显示当前组件的确认弹窗。
     *
     * @param activity 执行本次界面操作的 Activity
     * @param level 全局管控档位
     * @param onConfirm 用户确认切换管控档位后执行的回调
     */
    public static Dialog show(Activity activity, ControlLevel level, OnConfirm onConfirm) {
        return AppDialogs.builder(activity)
                .setTitle(activity.getString(R.string.level_dialog_title, activity.getString(level.nameRes)))
                .setMessage(level.detailRes)
                .setNegativeButton(R.string.dialog_cancel, null)
                .setPositiveButton(R.string.dialog_confirm, (dialog, which) -> onConfirm.confirm(level))
                .show();
    }
}
