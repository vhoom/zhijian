package github.vhoom.zhijian.util.diagnostics;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 服务和页面通过中立回执入口通信，权限界面只观察结果，不依赖业务模块。
 *
 * 维护日期：2026年11月08日
 */
public final class InterceptionLaunchDiagnostics {
    public static final long TIMEOUT_MS = 5_000L;
    private static final LaunchAttemptTracker tracker = new LaunchAttemptTracker();
    private static final Set<Runnable> listeners = new CopyOnWriteArraySet<>();

    /** 进程级回执入口不允许实例化。 */
    private InterceptionLaunchDiagnostics() { }

    /**
     * 标记服务已尝试启动页面，不将 startActivity 返回当成显示成功。
     * @param session 会话标识
     * @param elapsedMs 请求时的单调时间
     * @return 请求代次
     */
    public static long requested(String session, long elapsedMs) {
        return tracker.requested(session, elapsedMs, TIMEOUT_MS);
    }

    /**
     * 当前页面实际获得焦点后提交回执。
     * @param session 实际显示的会话
     */
    public static void shown(String session) {
        if (!tracker.shown(session)) return;
        DiagnosticLog.info("launch", session, "window_confirmed");
        notifyListeners();
    }

    /**
     * 检查页面未出现的边界，不把超时直接归因于某种系统权限。
     * @param request 请求代次
     * @param elapsedMs 当前单调时间
     * @return 当前请求是否仍缺少窗口回执
     */
    public static boolean expired(long request, long elapsedMs) {
        if (!tracker.expired(request, elapsedMs)) return false;
        notifyListeners();
        return true;
    }

    /**
     * 取消已不相关的页面请求。
     * @param request 请求代次
     */
    public static void cancel(long request) { tracker.cancel(request); }

    /** @return 是否应提示检查后台弹出界面和服务状态 */
    public static boolean isUnconfirmed() { return tracker.isUnconfirmed(); }

    /**
     * 页面可见期间注册观察者。
     * @param listener 状态改变后的刷新动作
     */
    public static void addListener(Runnable listener) { listeners.add(listener); }

    /**
     * 页面离开时解除观察。
     * @param listener 原刷新动作
     */
    public static void removeListener(Runnable listener) { listeners.remove(listener); }

    /** 只通知当前仍在观察的界面。 */
    private static void notifyListeners() {
        for (Runnable listener : listeners) listener.run();
    }
}
