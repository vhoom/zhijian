package github.vhoom.zhijian.util.diagnostics;

/**
 * 实际窗口回执策略：过期回调和离开目标应用不会被误报为权限故障。
 *
 * 维护日期：2026年11月08日
 */
public final class LaunchAttemptTracker {
    private long generation;
    private String session = "";
    private long deadline;
    private boolean outstanding;
    private boolean unconfirmed;
    private boolean timeoutReported;

    /**
     * 开始跟踪一次页面请求，保留既有故障直到收到真实回执。
     * @param sessionId 当前拦截会话
     * @param elapsedMs 请求时的单调时间
     * @param timeoutMs 等待真实窗口的预算
     * @return 用于取消或超时检查的请求代次
     */
    public synchronized long requested(String sessionId, long elapsedMs, long timeoutMs) {
        session = sessionId;
        deadline = elapsedMs + timeoutMs;
        outstanding = true;
        timeoutReported = false;
        return ++generation;
    }

    /**
     * 仅当前会话的真实窗口能完成请求，晚到回执也可清除超时状态。
     * @param sessionId 真正获得窗口焦点的会话
     * @return 是否首次确认当前请求
     */
    public synchronized boolean shown(String sessionId) {
        if (!session.equals(sessionId) || !outstanding) return false;
        outstanding = false;
        unconfirmed = false;
        return true;
    }

    /**
     * 只有当前请求超过期限且没有窗口回执才标记未确认。
     * @param request 请求代次
     * @param elapsedMs 检查时的单调时间
     * @return 是否产生新的超时状态
     */
    public synchronized boolean expired(long request, long elapsedMs) {
        if (request != generation || !outstanding || timeoutReported || elapsedMs < deadline) return false;
        timeoutReported = true;
        unconfirmed = true;
        return true;
    }

    /**
     * 用户离开目标应用或服务解绑时停止本次等待，保留既有诊断状态。
     * @param request 需要取消的请求代次
     */
    public synchronized void cancel(long request) {
        if (request == generation) outstanding = false;
    }

    /** @return 最近请求是否缺少实际页面显示回执 */
    public synchronized boolean isUnconfirmed() { return unconfirmed; }
}
