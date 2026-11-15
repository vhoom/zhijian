package github.vhoom.zhijian.accessibility;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 仅记录本进程的真实服务连接；授权状态仍由系统查询，不保存到偏好或数据库。
 *
 * 维护日期：2026年11月14日
 */
public final class AccessibilityConnection {
    private static volatile Object connectedService;
    private static final Set<Runnable> listeners = new CopyOnWriteArraySet<>();

    /**
     * 工具类不允许实例化，调用方直接使用静态方法。
     */
    private AccessibilityConnection() { }

    /**
     * 返回当前无障碍服务是否已与系统建立连接。
     */
    public static boolean isConnected() { return connectedService != null; }

    /**
     * 通知当前连接的服务在拦截任务关闭后恢复规则检查。
     *
     * @param packageName 用户主动返回的目标应用包名
     */
    public static void resumeTarget(String packageName) {
        Object service = connectedService;
        if (service instanceof ZhijianAccessibilityService) {
            ((ZhijianAccessibilityService) service).resumeTarget(packageName);
        }
    }

    /**
     * 注册状态变化监听，并避免同一回调重复加入。
     *
     * @param listener 状态发生变化后执行的监听回调
     */
    public static void addListener(Runnable listener) { listeners.add(listener); }

    /**
     * 移除状态监听，使离开页面后不再收到通知。
     *
     * @param listener 状态发生变化后执行的监听回调
     */
    public static void removeListener(Runnable listener) { listeners.remove(listener); }

    /**
     * 由服务生命周期在主线程调用；保留实例身份，旧实例销毁不能清除新连接。
     *
     * @param service 需要发布连接状态的无障碍服务实例
     */
    static void connected(Object service) {
        if (service == null) throw new IllegalArgumentException("Missing service");
        if (connectedService == service) return;
        boolean wasConnected = isConnected();
        connectedService = service;
        if (!wasConnected) notifyListeners();
    }

    /**
     * 仅撤销当前服务实例的连接，避免旧实例清除新连接。
     *
     * @param service 需要发布连接状态的无障碍服务实例
     */
    static void disconnected(Object service) {
        if (service == null || connectedService != service) return;
        connectedService = null;
        notifyListeners();
    }

    /**
     * 通知连接状态监听方重新读取授权状态。
     */
    private static void notifyListeners() {
        for (Runnable listener : listeners) listener.run();
    }
}
