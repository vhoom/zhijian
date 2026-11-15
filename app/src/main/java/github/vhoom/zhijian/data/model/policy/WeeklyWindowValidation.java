package github.vhoom.zhijian.data.model.policy;

import java.util.ArrayList;
import java.util.List;

/**
 * 将时段映射到完整一周，统一检查跨午夜、相邻星期和周日回绕的冲突。
 *
 * 维护日期：2026年11月08日
 */
public final class WeeklyWindowValidation {
    private static final int WEEK_MINUTES = 7 * RuleWindowValidation.MINUTES_PER_DAY;

    /** 校验策略不持有可变配置。 */
    private WeeklyWindowValidation() { }

    /** @param group 已通过字段校验的完整配置 @return 第一个冲突，无冲突返回 null */
    public static Conflict firstConflict(RuleGroup group) {
        List<Entry> entries = new ArrayList<>();
        for (int day = 1; day <= 7; day++) {
            RuleDayParameters parameters = RuleDayParameterAdapter.forDay(group, day);
            for (RuleWindow window : parameters.windows) {
                if (parameters.types.contains(window.type)) entries.add(new Entry(day, window));
            }
        }
        Entry[][] occupied = new Entry[RuleType.values().length][WEEK_MINUTES];
        for (Entry entry : entries) {
            int start = (entry.day - 1) * RuleWindowValidation.MINUTES_PER_DAY + entry.window.startMinute;
            int duration = RuleWindowValidation.durationMinutes(entry.window);
            for (int offset = 0; offset < duration; offset++) {
                int minute = (start + offset) % WEEK_MINUTES;
                for (RuleType type : RuleType.values()) {
                    if (!conflicting(entry.window.type, type)) continue;
                    Entry previous = occupied[type.ordinal()][minute];
                    if (previous != null) return new Conflict(entry, previous,
                            previous.day == entry.day && previous.window.type == entry.window.type
                                    && previous.window.startMinute == entry.window.startMinute
                                    && previous.window.endMinute == entry.window.endMinute);
                }
                occupied[entry.window.type.ordinal()][minute] = entry;
            }
        }
        return null;
    }

    /** @param first 本次类型 @param second 已占用类型 @return 同类或禁用与时段提醒是否冲突 */
    private static boolean conflicting(RuleType first, RuleType second) {
        return first == second || first == RuleType.WINDOW_BAN && second == RuleType.WINDOW_CONTINUOUS
                || first == RuleType.WINDOW_CONTINUOUS && second == RuleType.WINDOW_BAN;
    }

    /** 星期和窗口身份保留为错误定位依据。 */
    private static final class Entry {
        private final int day;
        private final RuleWindow window;
        /** @param day 开始星期 @param window 原始窗口 */
        private Entry(int day, RuleWindow window) { this.day = day; this.window = window; }
    }

    /** 不修改原数据的冲突位置。 */
    public static final class Conflict {
        public final int weekday, otherWeekday;
        public final RuleWindow window, otherWindow;
        public final boolean duplicate;
        /** @param current 冲突行 @param previous 已占用行 @param duplicate 是否同日同类重复 */
        private Conflict(Entry current, Entry previous, boolean duplicate) {
            weekday = current.day;
            otherWeekday = previous.day;
            window = current.window.copy();
            otherWindow = previous.window.copy();
            this.duplicate = duplicate;
        }
    }
}
