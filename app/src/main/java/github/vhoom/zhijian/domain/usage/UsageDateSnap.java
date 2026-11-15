package github.vhoom.zhijian.domain.usage;

/**
 * 日期条释放策略，低速短距离归位，越过吸附边界或明确滑动时进入相邻日期段。
 *
 * 维护日期：2026年11月08日
 */
public final class UsageDateSnap {
    private static final float SNAP_FRACTION = 0.35f;

    /** 不持有视图或动画状态。 */
    private UsageDateSnap() { }

    /**
     * @param displacement 日期条相对当前对齐位置的像素位移，向右为正
     * @param width 可见日期区域宽度
     * @param velocity 手指释放时的水平速度，向右为正
     * @param minimumVelocity 平台判定滑动意图的最低速度
     * @param canAdvance 是否允许进入后一周，未来整周不允许进入
     * @return -1 为上一周，1 为下一周，0 为原位置
     */
    public static int direction(float displacement, int width, float velocity, int minimumVelocity,
            boolean canAdvance) {
        if (width <= 0) return 0;
        float direction = Math.abs(velocity) >= minimumVelocity ? velocity
                : Math.abs(displacement) >= width * SNAP_FRACTION ? displacement : 0f;
        if (direction > 0f) return -1;
        return direction < 0f && canAdvance ? 1 : 0;
    }
}
