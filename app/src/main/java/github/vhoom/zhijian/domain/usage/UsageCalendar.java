package github.vhoom.zhijian.domain.usage;

import java.time.LocalDate;
import java.util.Objects;

/**
 * 星期一到星期日的日期选择状态，独立于界面、系统权限和实时管控档位。
 *
 * 维护日期：2026年11月08日
 */
public final class UsageCalendar {
    private LocalDate today;
    private LocalDate selected;
    private LocalDate weekStart;

    /** @param today 当前设备本地日期 @param selected 恢复的选择日期，null 时选择今天 */
    public UsageCalendar(LocalDate today, LocalDate selected) {
        this.today = Objects.requireNonNull(today);
        this.selected = selected == null || selected.isAfter(today) ? today : selected;
        weekStart = monday(this.selected);
    }

    /** @return 当前选择的实际日期 */
    public LocalDate selected() { return selected; }

    /** @return 当前展示周的星期一 */
    public LocalDate weekStart() { return weekStart; }

    /** @param index 星期一为零、星期日为六 @return 此列对应的日期 */
    public LocalDate dateAt(int index) {
        if (index < 0 || index > 6) throw new IllegalArgumentException("Invalid weekday");
        return weekStart.plusDays(index);
    }

    /** @param date 候选日期 @return 是否为今天或过去的日期 */
    public boolean canSelect(LocalDate date) { return !date.isAfter(today); }

    /** @param date 被点击的日期 @return 选择成功；未来日期不会改变状态 */
    public boolean select(LocalDate date) {
        if (!canSelect(date)) return false;
        selected = date;
        weekStart = monday(date);
        return true;
    }

    /** @param offset 按周翻页的方向，-1 为前一周、1 为后一周 @return 是否完成翻页 */
    public boolean moveWeek(int offset) {
        if (offset != -1 && offset != 1) throw new IllegalArgumentException("Invalid direction");
        LocalDate target = weekStart.plusWeeks(offset);
        if (target.isAfter(today)) return false;
        LocalDate candidate = target.plusDays(selected.getDayOfWeek().getValue() - 1L);
        selected = candidate.isAfter(today) ? today : candidate;
        weekStart = target;
        return true;
    }

    /**
     * 跨日时跟随今天的选择自动前进，历史选择保持不变。
     * @param date 新的设备本地日期
     */
    public void updateToday(LocalDate date) {
        boolean followingToday = selected.equals(today);
        today = Objects.requireNonNull(date);
        if (followingToday || selected.isAfter(today)) {
            selected = today;
            weekStart = monday(today);
        }
    }

    /** @param date 任意日期 @return 所在周的星期一，使用日历运算处理跨月跨年 */
    private static LocalDate monday(LocalDate date) {
        return date.minusDays(date.getDayOfWeek().getValue() - 1L);
    }
}
