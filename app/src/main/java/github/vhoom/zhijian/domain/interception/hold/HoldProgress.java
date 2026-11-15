package github.vhoom.zhijian.domain.interception.hold;

/**
 * 按压匀速推进，松手后以线性增长的速度倒退；计算只依赖单调时间，不依赖刷新帧数。
 *
 * 维护日期：2026年11月08日
 */
public final class HoldProgress {
    /** 每松手一秒，倒退速度增加一秒进度／秒。 */
    private static final double REWIND_ACCELERATION_MS_PER_SECOND_SQUARED = 1000d;
    private final long durationMs;
    private double progressMs;
    private double phaseStartProgressMs;
    private long phaseStartedAtMs;
    private long lastElapsedMs;
    private boolean holding;

    /** @param durationMs 完成挑战需要累计的有效按压毫秒数，必须大于零 */
    public HoldProgress(long durationMs) {
        if (durationMs <= 0L) throw new IllegalArgumentException("Hold duration must be positive");
        this.durationMs = durationMs;
    }

    /** @param pressed 当前是否按住 @param elapsedMs 本次触摸对应的单调时间，重复松手不重启倒退加速 */
    public void setHolding(boolean pressed, long elapsedMs) {
        update(elapsedMs);
        if (holding == pressed || isComplete()) return;
        holding = pressed;
        phaseStartProgressMs = progressMs;
        phaseStartedAtMs = lastElapsedMs;
    }

    /** @param elapsedMs 当前单调时间；延迟帧一次结算，乱序时间不回拨已经处理的进度 */
    public void update(long elapsedMs) {
        if (elapsedMs < 0L) throw new IllegalArgumentException("Elapsed time must not be negative");
        lastElapsedMs = Math.max(lastElapsedMs, elapsedMs);
        long elapsed = lastElapsedMs - phaseStartedAtMs;
        if (holding) {
            progressMs = Math.min(durationMs, phaseStartProgressMs + elapsed);
        } else {
            double seconds = elapsed / 1000d;
            double rewindMs = 0.5d * REWIND_ACCELERATION_MS_PER_SECOND_SQUARED * seconds * seconds;
            progressMs = Math.max(0d, phaseStartProgressMs - rewindMs);
        }
    }

    /** 返回零到一之间的实际完成比例。 */
    public double fraction() { return progressMs / durationMs; }

    /** 返回向上取整的剩余毫秒数，松手倒退时相应增加。 */
    public long remainingMs() { return (long) Math.ceil(durationMs - progressMs); }

    /** 返回当前是否仍按住按钮。 */
    public boolean isHolding() { return holding; }

    /** 返回本次挑战是否已经完成。 */
    public boolean isComplete() { return progressMs >= durationMs; }

    /** 返回是否仍需刷新推进或倒退，归零和完成后停止安排帧。 */
    public boolean needsUpdate() { return !isComplete() && (holding || progressMs > 0d); }

    /** 生命周期中断时清理触摸进度，后台停留不能累计按压。 */
    public void reset() {
        progressMs = 0d;
        phaseStartProgressMs = 0d;
        phaseStartedAtMs = 0L;
        lastElapsedMs = 0L;
        holding = false;
    }
}
