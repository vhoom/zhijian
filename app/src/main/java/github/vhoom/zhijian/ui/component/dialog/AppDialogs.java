package github.vhoom.zhijian.ui.component.dialog;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.res.Resources;
import android.view.Window;
import android.view.WindowManager;

import github.vhoom.zhijian.R;

/**
 * 原生弹窗的外观工厂：共用主题、圆角和窗口边界，保留系统的焦点、列表及按钮行为。
 * 不替换调用方的显示、关闭或校验监听，也不持有页面和业务状态。
 *
 * 维护日期：2026年11月14日
 */
public final class AppDialogs {
    /** 弹窗工厂仅通过静态方法使用。 */
    private AppDialogs() { }

    /**
     * 创建使用当前页面深浅色配置的原生构造器。
     * @param context 所属页面的主题上下文，不能使用 Application 上下文
     * @return 支持原生链式配置的构造器；先 create 的调用方使用 show(dialog) 展示
     */
    public static AlertDialog.Builder builder(Context context) {
        return new AlertDialog.Builder(context, R.style.Theme_Zhijian_Dialog) {
            /** 直接展示与先创建再展示共用同一套窗口规范。 */
            @Override public AlertDialog show() {
                return AppDialogs.show(create());
            }
        };
    }

    /**
     * 展示已配置的确认框、日期或时间选择器，不覆盖其 OnShowListener。
     * @param dialog 由所属页面负责关闭的原生弹窗
     * @param <T> 原生弹窗类型，返回值保留调用方的具体类型
     * @return 已展示且应用共用窗口边界的弹窗
     */
    public static <T extends Dialog> T show(T dialog) {
        dialog.show();
        Window window = dialog.getWindow();
        if (window == null) return dialog;
        Resources resources = dialog.getContext().getResources();
        int available = resources.getDisplayMetrics().widthPixels;
        int margin = resources.getDimensionPixelSize(R.dimen.dialog_edge_margin);
        int maximum = resources.getDimensionPixelSize(R.dimen.dialog_max_width);
        window.setBackgroundDrawableResource(R.drawable.bg_dialog);
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(resources.getFloat(R.dimen.dialog_dim_amount));
        window.setLayout(Math.max(1, Math.min(available - margin * 2, maximum)),
                WindowManager.LayoutParams.WRAP_CONTENT);
        window.getDecorView().setClipToOutline(true);
        return dialog;
    }
}
