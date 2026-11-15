package github.vhoom.zhijian.domain.usage;

/**
 * 首页高度预算：完整应用行向下取整，剩余空间用于日期上方的稳定留白。
 *
 * 维护日期：2026年11月08日
 */
public final class UsageRowBudget {
    public final int rows;
    public final int remainingPixels;

    /** @param rows 完整应用行数 @param remainingPixels 不足一行的剩余像素 */
    private UsageRowBudget(int rows, int remainingPixels) {
        this.rows = rows;
        this.remainingPixels = remainingPixels;
    }

    /**
     * @param viewportHeight 已扣除系统栏和首页顶栏的内容高度
     * @param fixedHeight 强度卡片、日期和时长标题等不含榜单的实测高度
     * @param rowHeight 一条应用行的实测高度，含字体缩放与行内留白
     * @param dividerHeight 两条应用之间的分隔线高度
     * @return 至少一条完整行；屏幕过小时允许首页正常滚动
     */
    public static UsageRowBudget calculate(int viewportHeight, int fixedHeight, int rowHeight,
            int dividerHeight) {
        if (viewportHeight < 0 || fixedHeight < 0 || rowHeight <= 0 || dividerHeight < 0) {
            throw new IllegalArgumentException("Invalid layout dimensions");
        }
        long available = Math.max(0L, (long) viewportHeight - fixedHeight);
        int rows = (int) Math.max(1L, (available + dividerHeight) / ((long) rowHeight + dividerHeight));
        long occupied = (long) rows * rowHeight + (long) (rows - 1) * dividerHeight;
        return new UsageRowBudget(rows, (int) Math.max(0L, available - occupied));
    }
}
