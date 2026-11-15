package github.vhoom.zhijian.ui.component.settings;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import github.vhoom.zhijian.R;

import java.util.function.BooleanSupplier;

/**
 * 原生底部弹窗工厂，统一外壳、安全区和确认行为，不拥有设置数据。
 *
 * 维护日期：2026年11月14日
 */
public final class SettingsBottomSheet {
    /** 工具工厂不创建实例。 */
    private SettingsBottomSheet() { }

    /**
     * 显示底部配置弹窗，确认校验失败时保留输入，返回或点击遮罩取消未保存编辑。
     *
     * @param activity 弹窗所属的设置页面
     * @param title 带模式名称的弹窗标题
     * @param content 已绑定输入和校验的配置视图
     * @param save 保存草稿并返回是否允许关闭，空值表示纯信息弹窗
     * @param restore 可选的恢复默认操作，空值隐藏入口
     * @return 由调用方在页面销毁时释放的已显示弹窗
     */
    public static Dialog show(Activity activity, CharSequence title, View content,
            BooleanSupplier save, Runnable restore) {
        Dialog dialog = new Dialog(activity, R.style.Theme_Zhijian_Dialog);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        View root = activity.getLayoutInflater().inflate(R.layout.dialog_settings_sheet, null, false);
        ((TextView) root.findViewById(R.id.settings_sheet_title)).setText(title);
        ((FrameLayout) root.findViewById(R.id.settings_sheet_content)).addView(content);
        root.findViewById(R.id.settings_sheet_done).setOnClickListener(view -> {
            if (save == null || save.getAsBoolean()) dialog.dismiss();
        });
        if (restore != null) {
            View restoreButton = root.findViewById(R.id.settings_sheet_restore);
            restoreButton.setVisibility(View.VISIBLE);
            restoreButton.setOnClickListener(view -> {
                restore.run();
                dialog.dismiss();
            });
        }
        int baseBottom = root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int bottom = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    ? insets.getInsets(WindowInsets.Type.systemBars()).bottom
                    : insets.getStableInsetBottom();
            view.setPadding(view.getPaddingLeft(), view.getPaddingTop(),
                    view.getPaddingRight(), baseBottom + bottom);
            return insets;
        });
        dialog.setContentView(root);
        dialog.setCanceledOnTouchOutside(true);
        Window window = dialog.getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(activity.getResources().getFloat(R.dimen.dialog_dim_amount));
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        window.setNavigationBarColor(activity.getColor(R.color.canvas));
        dialog.show();
        int width = Math.min(activity.getResources().getDisplayMetrics().widthPixels,
                activity.getResources().getDimensionPixelSize(R.dimen.sheet_max_width));
        int maximumHeight = Math.round(activity.getResources().getDisplayMetrics().heightPixels
                * activity.getResources().getFraction(R.fraction.sheet_max_height, 1, 1));
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(maximumHeight, View.MeasureSpec.AT_MOST));
        window.setLayout(width, root.getMeasuredHeight());
        root.requestApplyInsets();
        return dialog;
    }
}
