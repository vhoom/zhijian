package github.vhoom.zhijian.ui.component.rule;

/**
 * 星期编辑选择：空配置首次全选，后续点击始终只选择一个星期。
 *
 * 维护日期：2026年11月08日
 */
public final class WeekdaySelection {
    /** 星期一至星期日的完整选择掩码，仅用于首次初始化。 */
    public static final int ALL_DAYS = 127;
    /** 第零至第六位依次代表星期一至星期日。 */
    public final int mask;

    /** @param mask 单日掩码或首次初始化使用的七日全选掩码 */
    public WeekdaySelection(int mask) {
        if (mask <= 0 || (mask & ~ALL_DAYS) != 0
                || mask != ALL_DAYS && Integer.bitCount(mask) != 1) {
            throw new IllegalArgumentException("Expected one weekday or initial all-days selection");
        }
        this.mask = mask;
    }

    /** @return 空配置第一次进入编辑页时的全选状态 */
    public static WeekdaySelection initial() { return new WeekdaySelection(ALL_DAYS); }

    /** @param weekday 星期一为 1，星期日为 7 @return 仅选择该星期的状态 */
    public static WeekdaySelection single(int weekday) {
        if (weekday < 1 || weekday > 7) throw new IllegalArgumentException("Invalid weekday");
        return new WeekdaySelection(1 << (weekday - 1));
    }

    /** @param weekday 需要编辑的星期 @return 替换原选择的单日状态，不累积或取消选择 */
    public WeekdaySelection select(int weekday) { return single(weekday); }

    /** @param weekday 待检查的星期 @return 是否处于当前编辑选择中 */
    public boolean contains(int weekday) { return (mask & single(weekday).mask) != 0; }

    /** @param other 待比较的选择状态 @return 选择的星期是否相同 */
    public boolean sameAs(WeekdaySelection other) { return mask == other.mask; }
}
