package github.vhoom.zhijian.data.model.policy;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 按一天内的半开区间检查时段；相邻端点合法，跨午夜拆为两段参与判定。
 *
 * 维护日期：2026年11月08日
 */
public final class RuleWindowValidation {
    public static final int MINUTES_PER_DAY = 1440;

    /** 纯校验工具不创建实例。 */
    private RuleWindowValidation() { }

    /**
     * 检查分钟端点是否属于一天且表示非空时段。
     * @param window 待检查的时段草稿
     * @return 可以安全计算长度时返回 true
     */
    public static boolean hasValidEndpoints(RuleWindow window) {
        return window != null && window.startMinute >= 0
                && window.startMinute < MINUTES_PER_DAY && window.endMinute >= 0
                && window.endMinute < MINUTES_PER_DAY && window.startMinute != window.endMinute;
    }

    /**
     * 计算名义时段长度，跨午夜时累加午夜两侧分钟。
     * @param window 已通过端点校验的时段
     * @return 时段的分钟长度
     */
    public static int durationMinutes(RuleWindow window) {
        if (!hasValidEndpoints(window)) throw new IllegalArgumentException("Invalid window endpoints");
        return Math.floorMod(window.endMinute - window.startMinute, MINUTES_PER_DAY);
    }

    /**
     * 检查同一类型的时段，返回第一个冲突位置；总占用至多一天，避免两两比较。
     * @param windows 当前类型按添加顺序排列的时段
     * @return 合法时返回 null，否则返回携带冲突窗口的检查结果
     */
    public static Conflict firstConflict(List<RuleWindow> windows) {
        boolean[] occupied = new boolean[MINUTES_PER_DAY];
        Set<Integer> endpoints = new HashSet<>();
        for (RuleWindow window : windows) {
            if (!hasValidEndpoints(window)) throw new IllegalArgumentException("Invalid window endpoints");
            if (!endpoints.add(window.startMinute * MINUTES_PER_DAY + window.endMinute)) {
                return new Conflict(window, true);
            }
            int duration = durationMinutes(window);
            for (int offset = 0; offset < duration; offset++) {
                int minute = (window.startMinute + offset) % MINUTES_PER_DAY;
                if (occupied[minute]) return new Conflict(window, false);
                occupied[minute] = true;
            }
        }
        return null;
    }

    /** 一个明确的冲突窗口，区分完全重复与部分重叠。 */
    public static final class Conflict {
        public final RuleWindow window;
        public final boolean duplicate;

        /**
         * 保存冲突位置，不修改原草稿。
         * @param window 后添加的冲突时段
         * @param duplicate 是否与已有时段完全一致
         */
        private Conflict(RuleWindow window, boolean duplicate) {
            this.window = window;
            this.duplicate = duplicate;
        }
    }
}
