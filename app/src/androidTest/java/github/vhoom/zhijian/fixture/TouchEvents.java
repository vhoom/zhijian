package github.vhoom.zhijian.fixture;

import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

/**
 * 为独立组件测试分发触摸事件，集中回收平台事件对象。
 *
 * 维护日期：2026年11月09日
 */
public final class TouchEvents {
    /** 触摸事件工具不创建实例。 */
    private TouchEvents() { }

    /**
     * 按原组件测试的时间语义分发一次事件，并在处理完成后回收。
     *
     * @param view 接收事件的独立组件
     * @param action 当前触摸阶段
     * @param x 组件内横坐标，单位为像素
     * @param y 组件内纵坐标，单位为像素
     */
    public static void dispatch(View view, int action, float x, float y) {
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, x, y, 0);
        try {
            view.dispatchTouchEvent(event);
        } finally {
            event.recycle();
        }
    }
}
