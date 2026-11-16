package github.vhoom.zhijian.domain.usage;

import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * 一个生效策略的使用/休息周期；离组暂停累计，足额连续休息才清零。
 *
 * 维护日期：2026年11月16日
 */
public final class ContinuousUsageCycle {
    public final String bootId, foregroundPackage, foregroundClass, resumeId;
    public final long wallMs, elapsedMs, usedMs, restMs, generation;
    /** 本轮有效休息配置，未开始使用时为零；增大立即接管，减小留到完成本轮后。 */
    public final long requiredRestMs;

    /**
     * 保存可重放的周期检查点。
     * @param bootId 检查点所属启动身份
     * @param wallMs 已处理事件的墙钟边界
     * @param elapsedMs 同一边界的单调时间
     * @param usedMs 本周期已累计的组内前台时间
     * @param restMs 尚未被组内使用打断的休息时间
     * @param foregroundPackage 边界时的前台包名，灭屏或暂停时为空
     * @param resumeId 已应用的主动休息恢复身份，避免反复重置
     * @param generation 已完成足额休息的次数，用于判定旧中断是否已结束
     */
    public ContinuousUsageCycle(String bootId, long wallMs, long elapsedMs, long usedMs,
            long restMs, String foregroundPackage, String resumeId, long generation) {
        this(bootId, wallMs, elapsedMs, usedMs, restMs, foregroundPackage, null, resumeId, generation);
    }

    /**
     * 保存包含前台页面身份的周期，未知页面使用空类名。
     * @param bootId 启动身份 @param wallMs 已处理墙钟 @param elapsedMs 同一边界单调时间
     * @param usedMs 累计使用 @param restMs 已验证连续休息 @param foregroundPackage 前台包名
     * @param foregroundClass 前台 Activity 类名，可空 @param resumeId 恢复身份 @param generation 休息完成代次
     */
    public ContinuousUsageCycle(String bootId, long wallMs, long elapsedMs, long usedMs,
            long restMs, String foregroundPackage, String foregroundClass, String resumeId, long generation) {
        this(bootId, wallMs, elapsedMs, usedMs, restMs, foregroundPackage, foregroundClass, resumeId, generation, 0L);
    }

    /** @param bootId 启动身份 @param wallMs 墙钟边界 @param elapsedMs 单调边界 @param usedMs 使用毫秒
     * @param restMs 连续非使用毫秒 @param foregroundPackage 前台包名 @param foregroundClass 页面类名
     * @param resumeId 恢复身份 @param generation 已完成代次 @param requiredRestMs 本轮有效休息配置 */
    public ContinuousUsageCycle(String bootId, long wallMs, long elapsedMs, long usedMs,
            long restMs, String foregroundPackage, String foregroundClass, String resumeId, long generation,
            long requiredRestMs) {
        this.bootId = Objects.requireNonNull(bootId);
        this.resumeId = Objects.requireNonNull(resumeId);
        this.wallMs = wallMs;
        this.elapsedMs = elapsedMs;
        this.usedMs = usedMs;
        this.restMs = restMs;
        this.foregroundPackage = foregroundPackage;
        this.foregroundClass = foregroundClass;
        this.generation = generation;
        this.requiredRestMs = requiredRestMs;
        if (bootId.isEmpty() || wallMs < 0 || elapsedMs < 0 || usedMs < 0 || restMs < 0 || generation < 0 || requiredRestMs < 0) {
            throw new IllegalArgumentException("Invalid continuous usage checkpoint");
        }
    }

    /**
     * 增量重放前台区间；日界线不会影响连续周期，乱钟时保守使用单调增量。
     * @param timeline 本次查询的真实前台时间轴
     * @param belongs 包名是否属于此生效策略的判断策略
     * @param nowWallMs 本次墙钟时间
     * @param nowElapsedMs 本次单调时间
     * @param requiredRestMs 清零需要的连续离组休息时长
     * @return 可持久化的新检查点
     */
    public ContinuousUsageCycle advance(UsageTimeline timeline, Predicate<String> belongs,
            long nowWallMs, long nowElapsedMs, long requiredRestMs) {
        return advance(timeline, belongs, (pkg, activity) -> !member(belongs, pkg),
                nowWallMs, nowElapsedMs, requiredRestMs, Long.MAX_VALUE);
    }

    /**
     * 使用量只累计到原时段结束，休息按所选策略继续验证。
     * @param timeline 真实前台时间轴 @param belongs 使用归属 @param canRest 包名和页面是否满足休息
     * @param nowWallMs 本次墙钟 @param nowElapsedMs 本次单调时间 @param requiredRestMs 足额休息
     * @param usageCeilingMs 原时段结束边界，全天周期使用 Long.MAX_VALUE
     * @return 已处理到当前边界的周期
     */
    public ContinuousUsageCycle advance(UsageTimeline timeline, Predicate<String> belongs,
            BiPredicate<String, String> canRest, long nowWallMs, long nowElapsedMs,
            long requiredRestMs, long usageCeilingMs) {
        if (requiredRestMs <= 0 || nowElapsedMs < elapsedMs) {
            throw new IllegalArgumentException("Invalid continuous usage interval");
        }
        long effectiveRest = usedMs > 0L ? Math.max(this.requiredRestMs, requiredRestMs) : requiredRestMs;
        Counter counter = new Counter(usedMs, restMs, effectiveRest, requiredRestMs, generation);
        long realDelta = nowElapsedMs - elapsedMs;
        long wallDelta = nowWallMs - wallMs;
        if (wallDelta < 0 || Math.abs(wallDelta - realDelta) > 2_000L) {
            // 修改系统日期不能伪造足额休息；无法重放的间隙保留已用量。
            if (member(belongs, foregroundPackage) || member(belongs, timeline.foregroundPackage())) {
                counter.use(Math.min(realDelta, Math.max(0L, usageCeilingMs - wallMs)));
            } else if (canRest.test(foregroundPackage, foregroundClass)
                    && canRest.test(timeline.foregroundPackage(), timeline.foregroundClass())) counter.rest(realDelta);
            else counter.interruptRest();
        } else {
            long cursor = wallMs;
            for (UsageTimeline.ForegroundInterval interval : timeline.intervals(wallMs, nowWallMs)) {
                counter.rest(Math.max(0L, interval.startMs - cursor));
                if (member(belongs, interval.packageName)) {
                    counter.use(Math.max(0L, Math.min(interval.endMs, usageCeilingMs) - interval.startMs));
                } else if (canRest.test(interval.packageName, interval.className)) {
                    counter.rest(interval.endMs - interval.startMs);
                } else counter.interruptRest();
                cursor = interval.endMs;
            }
            counter.rest(Math.max(0L, nowWallMs - cursor));
        }
        if (member(belongs, timeline.foregroundPackage())) counter.use(0L);
        else if (!canRest.test(timeline.foregroundPackage(), timeline.foregroundClass())) counter.interruptRest();
        return new ContinuousUsageCycle(bootId, nowWallMs, nowElapsedMs, counter.used,
                counter.rest, timeline.foregroundPackage(), timeline.foregroundClass(), resumeId, counter.generation,
                counter.effectiveRest);
    }

    /** @param belongs 所属策略 @param pkg 可空包名 @return 是否属于组内使用 */
    private static boolean member(Predicate<String> belongs, String pkg) {
        return pkg != null && belongs.test(pkg);
    }

    /** 使用和休息的唯一转移实现，防止 UI 与服务各自维护不同的清零规则。 */
    private static final class Counter {
        private long used, rest, generation;
        private long required, effectiveRest;
        private final long configuredRest;

        /** @param used 已用量 @param rest 连续休息 @param effectiveRest 本轮要求
         * @param configuredRest 下一轮配置 @param generation 已完成周期 */
        private Counter(long used, long rest, long effectiveRest, long configuredRest, long generation) {
            this.effectiveRest = effectiveRest;
            this.configuredRest = configuredRest;
            this.required = ContinuousRestPolicy.requiredRestMillis("", effectiveRest);
            this.used = used;
            this.rest = Math.min(rest, this.required);
            this.generation = generation;
        }

        /** @param duration 本次组内使用，进入前台的瞬间允许为零 */
        private void use(long duration) {
            if (rest >= required) used = 0L;
            used = add(used, duration);
            rest = 0L;
        }

        /** 不满足传入休息策略的区间中断连续休息，并保留原累计使用。 */
        private void interruptRest() { rest = 0L; }

        /** @param duration 真实离组时长；不同外部应用及锁屏连续合并 */
        private void rest(long duration) {
            boolean completed = rest >= required;
            rest = Math.min(required, add(rest, duration));
            if (rest >= required) {
                used = 0L;
                if (!completed) {
                    generation = add(generation, 1L);
                    effectiveRest = configuredRest;
                    required = ContinuousRestPolicy.requiredRestMillis("", configuredRest);
                }
            }
        }

        /** @param left 已累计值 @param right 非负增量 @return 不溢出的累计值 */
        private static long add(long left, long right) {
            return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
        }
    }
}
