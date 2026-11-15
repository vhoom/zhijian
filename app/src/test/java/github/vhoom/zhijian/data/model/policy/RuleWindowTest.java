package github.vhoom.zhijian.data.model.policy;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * 覆盖 RuleWindow 的边界输入、状态变化与回归行为。
 *
 * 维护日期：2026年11月08日
 */
public class RuleWindowTest {
    /**
     * 验证复制规则组保留窗口 ID，同时隔离后续修改。
     */
    @Test
    public void copyingGroupKeepsWindowIdentityButIsolatesEdits() {
        RuleGroup original = new RuleGroup(1, "晚间专注");
        original.windows.add(new RuleWindow("evening", RuleType.WINDOW_LIMIT,
                1080, 1320, 60 * 60_000L));

        RuleGroup draft = original.copy();
        assertEquals("evening", draft.windows.get(0).id);
        assertNotSame(original.windows.get(0), draft.windows.get(0));
        draft.windows.get(0).limitDurationMillis = 30 * 60_000L;
        draft.windows.get(0).endMinute = 1200;
        draft.windows.clear();

        assertEquals(1, original.windows.size());
        assertEquals(60 * 60_000L, original.windows.get(0).limitDurationMillis);
        assertEquals(1320, original.windows.get(0).endMinute);
    }

    /**
     * 验证时段筛选保留顺序，并隔离不同类型的额度。
     */
    @Test
    public void filteringKeepsOrderAndQuotasSeparateAcrossTypes() {
        RuleGroup group = new RuleGroup(1, "专注");
        group.windows.add(new RuleWindow("morning", RuleType.WINDOW_LIMIT,
                480, 720, 30 * 60_000L));
        group.windows.add(new RuleWindow("night", RuleType.WINDOW_BAN, 1320, 480, 0));
        group.windows.add(new RuleWindow("evening", RuleType.WINDOW_LIMIT,
                1080, 1320, 60 * 60_000L));

        assertEquals(2, group.windowsFor(RuleType.WINDOW_LIMIT).size());
        assertEquals("morning", group.windowsFor(RuleType.WINDOW_LIMIT).get(0).id);
        assertEquals(60 * 60_000L,
                group.windowsFor(RuleType.WINDOW_LIMIT).get(1).limitDurationMillis);
        assertEquals("night", group.windowsFor(RuleType.WINDOW_BAN).get(0).id);
    }

    /**
     * 验证每日条件不能误当作时段条件。
     */
    @Test(expected = IllegalArgumentException.class)
    public void dailyRuleCannotBecomeAWindow() {
        RuleWindow.create(RuleType.DAILY_LIMIT, 480, 720);
    }
}
