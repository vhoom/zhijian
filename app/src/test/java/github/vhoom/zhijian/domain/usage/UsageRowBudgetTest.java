package github.vhoom.zhijian.domain.usage;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * 实测高度预算在不同屏幕、字体和额度卡片高度下的业务回归。
 *
 * 维护日期：2026年11月08日
 */
public class UsageRowBudgetTest {
    /** 不足一行的余量填入日期上方，日期底部保持在同一可用高度。 */
    @Test public void completeRowsAndRemainderExactlyFillAvailableHeight() {
        UsageRowBudget value = UsageRowBudget.calculate(640, 300, 52, 1);
        assertEquals(6, value.rows);
        assertEquals(640, 300 + value.rows * 52 + (value.rows - 1) + value.remainingPixels);
        assertTrue(value.remainingPixels < 53);
    }

    /** 增大字体或展开额度区域时减少完整行，日期仍保留可用空间。 */
    @Test public void largerRowsAndStrengthCardReduceDefaultCount() {
        assertEquals(6, UsageRowBudget.calculate(640, 300, 52, 1).rows);
        assertEquals(4, UsageRowBudget.calculate(640, 300, 80, 1).rows);
        assertEquals(4, UsageRowBudget.calculate(640, 400, 52, 1).rows);
    }

    /** 屏幕过小时至少保留一条应用行，交给正常纵向滚动展示。 */
    @Test public void compactWindowKeepsOneRowWithoutNegativeSpacer() {
        UsageRowBudget value = UsageRowBudget.calculate(220, 300, 52, 1);
        assertEquals(1, value.rows);
        assertEquals(0, value.remainingPixels);
    }
}
