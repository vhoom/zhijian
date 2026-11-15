package github.vhoom.zhijian.fixture;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;

/**
 * 安装在测试 APK 包名下的真实前台页面，供无障碍拦截和用量回归使用。
 *
 * 维护日期：2026年11月14日
 */
public final class UsageTargetActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private View content;

    /** @param state 系统重建状态；仅测试请求可开启持续窗口事件 */
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        content = new View(this);
        setContentView(content);
        if (getIntent().getBooleanExtra("zhijian.test.floodWindows", false)) {
            main.post(new Runnable() {
                /** 模拟同应用高频窗口状态变化，失焦期间不发送事件。 */
                @Override public void run() {
                    if (isFinishing() || isDestroyed()) return;
                    if (hasWindowFocus()) content.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
                    main.postDelayed(this, 50L);
                }
            });
        }
    }

    /** @param focused 系统确认的真实窗口焦点；空白测试页热返回也提供标准窗口状态事件 */
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused && content != null) {
            AccessibilityManager accessibility = getSystemService(AccessibilityManager.class);
            if (accessibility != null && accessibility.isEnabled()) {
                AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
                event.setPackageName(getPackageName());
                event.setClassName(getClass().getName());
                accessibility.sendAccessibilityEvent(event);
            }
        }
    }

    /** 测试页面销毁时清理事件生成，避免影响后续模拟器用例。 */
    @Override protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
