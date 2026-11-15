package github.vhoom.zhijian.ui.component.rule;

import android.app.Activity;
import android.content.Context;
import android.graphics.Rect;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;

import github.vhoom.zhijian.R;

/**
 * 规则配置输入的失焦行为：外部点击和键盘完成结束编辑，保留原生事件分发及草稿。
 *
 * 维护日期：2026年11月08日
 */
public final class RuleInputFocus {
    /** 工具类只提供共用绑定与事件处理入口。 */
    private RuleInputFocus() { }

    /**
     * 为单行输入框绑定原生完成动作，硬件回车采用相同的失焦行为。
     * @param field 规则参数或名称输入框
     */
    public static void bind(EditText field) {
        field.setImeOptions(EditorInfo.IME_ACTION_DONE);
        field.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_DONE
                    && (event == null || event.getKeyCode() != KeyEvent.KEYCODE_ENTER)) return false;
            if (event == null || event.getAction() == KeyEvent.ACTION_UP) {
                releaseFocus(field);
                hideKeyboard(field);
            }
            return true;
        });
    }

    /**
     * 外部触摸先结束旧输入；事件继续交给原生控件，切换输入时保持键盘。
     * @param activity 当前规则配置页面
     * @param event 即将分发的原生触摸事件
     */
    public static void clearOnOutsideTouch(Activity activity, MotionEvent event) {
        if (event.getActionMasked() != MotionEvent.ACTION_DOWN) return;
        View focused = activity.getCurrentFocus();
        if (!(focused instanceof EditText)) return;
        EditText field = (EditText) focused;
        View target = field.getParent() instanceof FrameLayout ? (View) field.getParent() : field;
        Rect bounds = new Rect();
        if (target.getGlobalVisibleRect(bounds)
                && bounds.contains((int) event.getRawX(), (int) event.getRawY())) return;
        releaseFocus(field);
        // 下一次主线程回调在原生分发后执行，新的输入框接管焦点时不收起键盘。
        field.post(() -> {
            if (!(activity.getCurrentFocus() instanceof EditText)) hideKeyboard(field);
        });
    }

    /**
     * 将焦点移到页面根容器，避免清除后自动回到第一个输入框。
     * @param field 本次结束编辑的输入框
     */
    private static void releaseFocus(EditText field) {
        field.clearFocus();
        View root = field.getRootView().findViewById(R.id.root);
        if (root != null) root.requestFocus();
    }

    /**
     * 使用系统输入法服务关闭当前输入框的键盘。
     * @param field 提供窗口令牌的输入框
     */
    private static void hideKeyboard(EditText field) {
        InputMethodManager keyboard = (InputMethodManager)
                field.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(field.getWindowToken(), 0);
    }
}
