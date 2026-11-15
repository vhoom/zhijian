package github.vhoom.zhijian.domain.usage;

/**
 * 将真实窗口观察限定在尚未结算的事件区间，旧观察不能重新启动已经结束的前台。
 * 维护日期：2026年11月08日
 */
public final class ForegroundObservation {
    /** 时间边界策略不持有系统或存储状态。 */
    private ForegroundObservation() { }

    /**
     * 保留本次查询内的真实观察时间，不把历史观察推迟到查询起点。
     * @param observedAtMs 原始窗口观察时间，零表示没有观察
     * @param queryStartMs 已结算时间边界
     * @param nowMs 本次事件查询终点
     * @return 本区间内的原始时间；过期、未来或缺失观察返回零
     */
    public static long withinInterval(long observedAtMs, long queryStartMs, long nowMs) {
        return observedAtMs > 0L && observedAtMs >= queryStartMs && observedAtMs <= nowMs
                ? observedAtMs : 0L;
    }
}
