package github.vhoom.zhijian.util.ui;

import android.graphics.Insets;
import android.os.Build;
import android.view.View;
import android.view.WindowInsets;

/**
 * targetSdk 35 起强制 edge-to-edge，内容需要自己避开状态栏与导航条。
 *
 * 维护日期：2026年11月08日
 */
public final class SystemBars {

    /**
     * 工具类不允许实例化，调用方直接使用静态方法。
     */
    private SystemBars() {
    }

    /**
     * 为根视图设置系统栏 Insets 监听，避免内容被状态栏和导航栏遮挡。
     *
     * @param root 需要绑定系统栏内边距的根视图
     */
    public static void applyPadding(View root) {
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return WindowInsets.CONSUMED;
            }
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
    }
}
