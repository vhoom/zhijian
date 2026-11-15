package github.vhoom.zhijian.domain.usage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * 按事件顺序汇总真实前台区间，供首页用量与规则证据共用同一统计口径。
 *
 * 维护日期：2026年11月14日
 */
public final class UsageTimeline {
    /** 前台以恢复和暂停为界线；停止事件属于已离开前台的页面。 */
    public enum ActivityEvent { RESUMED, PAUSED, STOPPED }
    /** 同应用 Activity 交接的短暂暂停不应让连续使用从零开始。 */
    private static final long ACTIVITY_HANDOFF_MS = 1_000L;
    private final String selfPackage;
    private final List<Segment> foregroundSegments = new ArrayList<>();
    private final List<Segment> screenSegments = new ArrayList<>();
    private final List<Segment> opens = new ArrayList<>();
    private String foregroundPackage;
    private String foregroundClass;
    private String previousPackage;
    private String interceptedReturnPackage;
    private long foregroundStartedMs;
    private long continuousStartedMs;
    private long lastPauseMs;
    private Long screenStartedMs;
    private long lastEventMs = -1L;

    /**
     * 创建单次查询的事件汇总器。
     *
     * @param selfPackage 止间自身包名，从拦截页返回不重复计算打开次数
     */
    public UsageTimeline(String selfPackage) {
        this.selfPackage = Objects.requireNonNull(selfPackage);
    }

    /** 返回事件流结束时仍在前台的应用，已暂停或灭屏时为 null。 */
    public String foregroundPackage() {
        return foregroundPackage;
    }

    /** @return 当前 Activity 类名，系统未提供时为 null */
    public String foregroundClass() { return foregroundClass; }

    /**
     * 接收 Activity 生命周期事件；STOPPED 不得再次结算后来恢复的同名页面。
     *
     * @param type 系统事件对应的恢复、暂停或停止阶段
     * @param packageName 事件所属应用包名
     * @param className 事件所属 Activity 类名
     * @param timeMs 事件的墙钟时间
     */
    public void activityChanged(ActivityEvent type, String packageName, String className, long timeMs) {
        if (type == ActivityEvent.RESUMED) resumed(packageName, className, timeMs);
        else if (type == ActivityEvent.PAUSED) paused(packageName, className, timeMs);
        // STOPPED 确认旧页面已经进入后台；真实前台离开已由 PAUSED 或下一次 RESUMED 结算。
    }

    /**
     * 记录前台恢复；同包重复恢复和 Activity 切换保留原连续起点。
     *
     * @param packageName 恢复到前台的应用包名
     * @param className 恢复的 Activity 类名，补足窗口事件时可为 null
     * @param timeMs 事件的墙钟时间
     */
    public void resumed(String packageName, String className, long timeMs) {
        if (packageName == null || !acceptTime(timeMs)) return;
        if (packageName.equals(foregroundPackage)) {
            if (className != null && !className.equals(foregroundClass)) {
                foregroundSegments.add(new Segment(packageName, foregroundClass, foregroundStartedMs, timeMs));
                foregroundStartedMs = timeMs;
                foregroundClass = className;
                if (selfPackage.equals(packageName)) interceptedReturnPackage = null;
            }
            return;
        }
        boolean sameAppHandoff = packageName.equals(previousPackage)
                && lastPauseMs > 0L && timeMs - lastPauseMs <= ACTIVITY_HANDOFF_MS;
        closeForeground(timeMs);
        boolean interceptionReturn = selfPackage.equals(previousPackage)
                && packageName.equals(interceptedReturnPackage);
        if (!sameAppHandoff && !interceptionReturn) {
            opens.add(new Segment(packageName, timeMs, timeMs));
        }
        interceptedReturnPackage = selfPackage.equals(packageName) && className != null
                && (className.endsWith(".InterceptionActivity") || className.equals("Interception"))
                ? previousPackage : null;
        foregroundPackage = packageName;
        foregroundClass = className;
        foregroundStartedMs = timeMs;
        if (!sameAppHandoff) continuousStartedMs = timeMs;
        previousPackage = packageName;
    }

    /**
     * 结束匹配的前台 Activity；旧页面延迟暂停或停止不能结束新的前台页面。
     *
     * @param packageName 暂停或停止的应用包名
     * @param className 对应 Activity 的类名
     * @param timeMs 暂停或停止的墙钟时间
     */
    public void paused(String packageName, String className, long timeMs) {
        if (!acceptTime(timeMs)) return;
        if (Objects.equals(packageName, foregroundPackage)
                && (className == null || foregroundClass == null || className.equals(foregroundClass))) {
            closeForeground(timeMs);
        }
    }

    /**
     * 配对亮灭屏事件；灭屏同时结束应用前台，避免漏掉 PAUSE 后继续计时。
     *
     * @param interactive 屏幕是否处于交互状态
     * @param timeMs 屏幕事件的墙钟时间
     */
    public void screenChanged(boolean interactive, long timeMs) {
        if (!acceptTime(timeMs)) return;
        if (interactive) {
            if (screenStartedMs == null) screenStartedMs = timeMs;
        } else {
            if (screenStartedMs != null) {
                screenSegments.add(new Segment("", screenStartedMs, timeMs));
            }
            screenStartedMs = null;
            closeForeground(timeMs);
            previousPackage = null;
        }
    }

    /**
     * 获取指定应用在查询区间内的累计真实前台毫秒数。
     *
     * @param packageName 待查询应用的包名
     * @param startMs 统计区间起点
     * @param endMs 统计区间终点，也是未结束会话的结算时刻
     */
    public long foregroundMillis(String packageName, long startMs, long endMs) {
        return totals(startMs, endMs).getOrDefault(packageName, 0L);
    }

    /**
     * 对实际生效来源相同的应用汇总额度，每段真实前台区间只计算一次。
     * @param belongs 当前配置快照给出的用量归属策略
     * @param startMs 每日恢复点或当前时段的开始时刻
     * @param endMs 本次统计边界
     * @return 组内累计前台毫秒数
     */
    public long foregroundMillis(Predicate<String> belongs, long startMs, long endMs) {
        long total = 0L;
        for (Map.Entry<String, Long> entry : totals(startMs, endMs).entrySet()) {
            if (belongs.test(entry.getKey())) total += entry.getValue();
        }
        return total;
    }

    /**
     * 汇总各应用在区间内的前台时间，跨日会话按区间边界裁剪。
     *
     * @param startMs 统计区间起点
     * @param endMs 统计区间终点
     */
    public Map<String, Long> totals(long startMs, long endMs) {
        Map<String, Long> result = new HashMap<>();
        for (Segment segment : foregroundSegments) {
            result.merge(segment.packageName,
                    overlap(segment.startMs, segment.endMs, startMs, endMs), Long::sum);
        }
        if (foregroundPackage != null) {
            result.merge(foregroundPackage,
                    overlap(foregroundStartedMs, endMs, startMs, endMs), Long::sum);
        }
        return result;
    }

    /**
     * 读取当前前台会话时长，休息恢复边界只约束连续使用，不删掉当天用量。
     *
     * @param packageName 当前检查的目标应用包名
     * @param nowMs 本次检查时间
     * @param floorMs 已持久化的连续计时最早起点
     */
    public long continuousMillis(String packageName, long nowMs, long floorMs) {
        return packageName.equals(foregroundPackage)
                ? Math.max(0L, nowMs - Math.max(continuousStartedMs, floorMs)) : 0L;
    }

    /**
     * 返回区间内的亮屏时间；重复亮屏事件不会覆盖起点。
     *
     * @param startMs 统计区间起点
     * @param endMs 统计区间终点
     */
    public long screenMillis(long startMs, long endMs) {
        long total = 0L;
        for (Segment segment : screenSegments) {
            total += overlap(segment.startMs, segment.endMs, startMs, endMs);
        }
        return total + (screenStartedMs == null ? 0L
                : overlap(screenStartedMs, endMs, startMs, endMs));
    }

    /**
     * 返回统计区间内的应用打开次数。
     *
     * @param packageName 需要查询的应用包名
     * @param startMs 统计区间起点
     * @param endMs 统计区间终点
     */
    public long openCount(String packageName, long startMs, long endMs) {
        return openCount(packageName::equals, startMs, endMs);
    }

    /**
     * 合计当前生效组的真实打开次数，从实际拦截页返回的豁免仍由时间轴统一处理。
     * @param belongs 本组应用的归属策略
     * @param startMs 当前每日统计周期起点
     * @param endMs 统计终点
     * @return 消耗共享次数额度的打开次数
     */
    public long openCount(Predicate<String> belongs, long startMs, long endMs) {
        long count = 0L;
        for (Segment opening : opens) {
            if (belongs.test(opening.packageName)
                    && opening.startMs >= startMs && opening.startMs <= endMs) count++;
        }
        return count;
    }

    /**
     * 用同一套真实打开口径输出时刻快照，供时段和滚动频率规则复用。
     * @param belongs 已提交配置给出的实际应用归属
     * @param startMs 统计起点，包含该时刻
     * @param endMs 统计终点，包含该时刻
     * @return 不共享时间轴集合的只读打开记录
     */
    public OpenCountSnapshot openingSnapshot(Predicate<String> belongs, long startMs, long endMs) {
        List<Long> times = new ArrayList<>();
        for (Segment opening : opens) {
            if (belongs.test(opening.packageName) && opening.startMs >= startMs && opening.startMs <= endMs) {
                times.add(opening.startMs);
            }
        }
        return new OpenCountSnapshot(times);
    }

    /**
     * 输出裁剪后的前台区间，连续周期按策略归属重放，不重复计算重叠窗口。
     * @param startMs 增量处理起点
     * @param endMs 增量处理终点
     * @return 按时间排列的只读区间
     */
    public List<ForegroundInterval> intervals(long startMs, long endMs) {
        List<ForegroundInterval> result = new ArrayList<>();
        for (Segment segment : foregroundSegments) {
            addInterval(result, segment.packageName, segment.className, segment.startMs, segment.endMs, startMs, endMs);
        }
        if (foregroundPackage != null) {
            addInterval(result, foregroundPackage, foregroundClass, foregroundStartedMs, endMs, startMs, endMs);
        }
        return java.util.Collections.unmodifiableList(result);
    }

    /** @param result 输出 @param pkg 包名 @param activity Activity 类名 @param start 原起点 @param end 原终点
     * @param floor 查询起点 @param ceiling 查询终点 */
    private static void addInterval(List<ForegroundInterval> result, String pkg, String activity,
            long start, long end, long floor, long ceiling) {
        long clippedStart = Math.max(start, floor), clippedEnd = Math.min(end, ceiling);
        if (clippedEnd > clippedStart) result.add(new ForegroundInterval(pkg, activity, clippedStart, clippedEnd));
    }

    /** @param timeMs 事件时间 @return 是否能推进时间轴，拒绝负值及迟到的乱序事件 */
    private boolean acceptTime(long timeMs) {
        if (timeMs < 0L || timeMs < lastEventMs) return false;
        lastEventMs = timeMs;
        return true;
    }

    /** 不可变的单前台区间；调用者只负责确定所属策略。 */
    public static final class ForegroundInterval {
        public final String packageName, className;
        public final long startMs, endMs;

        /** @param packageName 前台包名 @param className Activity 类名 @param startMs 起点 @param endMs 终点 */
        private ForegroundInterval(String packageName, String className, long startMs, long endMs) {
            this.packageName = packageName;
            this.className = className;
            this.startMs = startMs;
            this.endMs = endMs;
        }
    }

    /**
     * 关闭当前前台区间，保留最近包名用于识别同应用页面切换和拦截返回。
     *
     * @param timeMs 当前前台区间的结束时间
     */
    private void closeForeground(long timeMs) {
        if (foregroundPackage != null) {
            foregroundSegments.add(new Segment(foregroundPackage, foregroundClass, foregroundStartedMs, timeMs));
            lastPauseMs = timeMs;
        }
        foregroundPackage = null;
        foregroundClass = null;
    }

    /**
     * 计算两个区间的相交时长。
     *
     * @param firstStartMs 第一区间起点
     * @param firstEndMs 第一区间终点
     * @param secondStartMs 第二区间起点
     * @param secondEndMs 第二区间终点
     */
    private static long overlap(long firstStartMs, long firstEndMs, long secondStartMs, long secondEndMs) {
        return Math.max(0L, Math.min(firstEndMs, secondEndMs) - Math.max(firstStartMs, secondStartMs));
    }

    /** 保存已结束的前台或屏幕时间区间，也用于保存单次打开时间。 */
    private static final class Segment {
        private final String packageName;
        private final String className;
        private final long startMs;
        private final long endMs;

        /**
         * 保存一段时间区间。
         *
         * @param packageName 区间所属应用，屏幕区间使用空字符串
         * @param startMs 区间起点
         * @param endMs 区间终点
         */
        private Segment(String packageName, long startMs, long endMs) {
            this(packageName, null, startMs, endMs);
        }

        /** @param packageName 区间所属包名 @param className Activity 类名 @param startMs 起点 @param endMs 终点 */
        private Segment(String packageName, String className, long startMs, long endMs) {
            this.packageName = packageName;
            this.className = className;
            this.startMs = startMs;
            this.endMs = endMs;
        }
    }
}
