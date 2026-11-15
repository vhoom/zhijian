package github.vhoom.zhijian.ui.component.rule;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * 验证首次全选与后续严格单日编辑，不再提供每天入口或多选。
 *
 * 维护日期：2026年11月08日
 */
public class WeekdaySelectionTest {
    /** 初次全选状态点击任一星期立即进入该日。 */
    @Test public void choosingOneWeekdayLeavesInitialAllDaysSelection() {
        WeekdaySelection selection = WeekdaySelection.initial().select(3);
        assertEquals(4, selection.mask);
    }

    /** 连续点击七个星期也不形成多选。 */
    @Test public void selectingAnotherDayReplacesPreviousDay() {
        WeekdaySelection selection = WeekdaySelection.initial();
        for (int day = 1; day <= 7; day++) {
            selection = selection.select(day);
            assertEquals(1 << (day - 1), selection.mask);
        }
    }

    /** 重复点击当前星期保持选择，不取消或重新进入全选。 */
    @Test public void selectingCurrentDayKeepsItSelected() {
        assertTrue(WeekdaySelection.single(5).select(5).sameAs(WeekdaySelection.single(5)));
    }

    /** 非法星期不被掩码静默截断。 */
    @Test(expected = IllegalArgumentException.class) public void invalidWeekdayIsRejected() {
        WeekdaySelection.initial().select(8);
    }

    /** 恢复状态也不能进入普通多选。 */
    @Test(expected = IllegalArgumentException.class) public void partialMultiSelectionIsRejected() {
        new WeekdaySelection(3);
    }
}
