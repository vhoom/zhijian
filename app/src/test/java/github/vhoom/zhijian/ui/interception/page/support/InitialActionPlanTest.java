package github.vhoom.zhijian.ui.interception.page.support;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * 首屏方案的互斥概率、重建稳定性、控件分布及修心隔离回归。
 *
 * 维护日期：2026年11月09日
 */
public class InitialActionPlanTest {
    /** 固定开关覆盖特殊随机种子；修心不读取无为开关。 */
    @Test public void disabledDynamicButtonsKeepContinueAboveExitAndRepairIndependent() {
        for (int index = 0; index < 200; index++) {
            String session = "fixed-" + index;
            InitialActionPlan fixed = InitialActionPlan.forSession(session, true, false);
            assertFalse(fixed.doubleExit);
            assertFalse(fixed.blindBox);
            assertEquals(2, fixed.actions.size());
            assertFalse(fixed.actions.get(0).exit);
            assertTrue(fixed.actions.get(1).exit);
            for (InitialActionPlan.Action action : fixed.actions) assertEquals(InitialActionPlan.Kind.BUTTON, action.kind);
            InitialActionPlan repair = InitialActionPlan.forSession(session, false, false);
            InitialActionPlan previous = InitialActionPlan.forSession(session, false);
            for (int slot = 0; slot < 2; slot++) {
                assertEquals(previous.actions.get(slot).kind, repair.actions.get(slot).kind);
                assertEquals(previous.actions.get(slot).exit, repair.actions.get(slot).exit);
            }
        }
    }
    /** 最终选择另抽普通双出口；即使原首屏是盲盒或双退出，也必须各有一个去向。 */
    @Test public void confirmationAlwaysHasBalancedRoutesAndDynamicCopySeeds() {
        int[] counts = new int[8];
        java.util.Set<Integer> variants = new java.util.HashSet<>();
        for (int index = 0; index < 16_000; index++) {
            InitialActionPlan plan = InitialActionPlan.forConfirmation("final-" + index);
            assertFalse(plan.doubleExit);
            assertFalse(plan.blindBox);
            assertEquals(2, plan.actions.size());
            InitialActionPlan.Action first = plan.actions.get(0), second = plan.actions.get(1);
            assertNotEquals(first.exit, second.exit);
            variants.add(first.copyVariant);
            int key = (first.kind == InitialActionPlan.Kind.SLIDER ? 4 : 0)
                    + (second.kind == InitialActionPlan.Kind.SLIDER ? 2 : 0) + (first.exit ? 1 : 0);
            counts[key]++;
        }
        for (int count : counts) assertTrue(count > 1_800 && count < 2_200);
        assertTrue(variants.size() > 15_000);
    }

    /** 修心始终一退一进，八种组合与路由排列保持均匀。 */
    @Test public void repairKeepsBalancedOrdinaryControlsWithoutSpecialEvents() {
        int[] counts = new int[8];
        for (int index = 0; index < 16_000; index++) {
            InitialActionPlan plan = InitialActionPlan.forSession("session-" + index, false);
            assertFalse(plan.doubleExit);
            assertFalse(plan.blindBox);
            assertEquals(2, plan.actions.size());
            InitialActionPlan.Action first = plan.actions.get(0), second = plan.actions.get(1);
            assertNotEquals(first.exit, second.exit);
            int key = (first.kind == InitialActionPlan.Kind.SLIDER ? 4 : 0)
                    + (second.kind == InitialActionPlan.Kind.SLIDER ? 2 : 0) + (first.exit ? 1 : 0);
            counts[key]++;
        }
        for (int count : counts) assertTrue("Joint count: " + count, count > 1_800 && count < 2_200);
    }

    /** 三类页面按 90%／5%／5% 互斥出现，普通控件分布与盲盒内部 70% 退出保持独立。 */
    @Test public void wuweiPagesAreExclusiveAndBlindBoxHasItsOwnOutcome() {
        int ordinary = 0, doubleExit = 0, blind = 0, blindExit = 0;
        int[] ordinaryControls = new int[8];
        int total = 100_000;
        for (int index = 0; index < total; index++) {
            InitialActionPlan plan = InitialActionPlan.forSession("probability-" + index, true);
            assertFalse(plan.doubleExit && plan.blindBox);
            if (plan.blindBox) {
                blind++;
                assertEquals(1, plan.actions.size());
                assertEquals(InitialActionPlan.Kind.SLIDER, plan.actions.get(0).kind);
                assertEquals(InitialActionPlan.Copy.BLIND_BOX, plan.actions.get(0).copy);
                if (plan.actions.get(0).exit) blindExit++;
            } else if (plan.doubleExit) {
                doubleExit++;
                assertEquals(2, plan.actions.size());
                for (InitialActionPlan.Action action : plan.actions) {
                    assertTrue(action.exit);
                    assertEquals(InitialActionPlan.Copy.EXIT_VARIANT, action.copy);
                }
            } else {
                ordinary++;
                assertEquals(2, plan.actions.size());
                InitialActionPlan.Action first = plan.actions.get(0), second = plan.actions.get(1);
                assertNotEquals(first.exit, second.exit);
                for (InitialActionPlan.Action action : plan.actions) {
                    assertEquals(action.exit ? InitialActionPlan.Copy.EXIT : InitialActionPlan.Copy.CONTINUE, action.copy);
                }
                int key = (first.kind == InitialActionPlan.Kind.SLIDER ? 4 : 0)
                        + (second.kind == InitialActionPlan.Kind.SLIDER ? 2 : 0) + (first.exit ? 1 : 0);
                ordinaryControls[key]++;
            }
        }
        assertEquals(0.90, ordinary / (double) total, 0.004);
        assertEquals(0.05, doubleExit / (double) total, 0.004);
        assertEquals(0.05, blind / (double) total, 0.004);
        assertEquals(0.70, blindExit / (double) blind, 0.02);
        for (int count : ordinaryControls) assertEquals(0.125, count / (double) ordinary, 0.005);
    }

    /** 重建不重抽控件、事件、盲盒结果及两个独立的文案种子。 */
    @Test public void recreatingSessionKeepsTheWholePlan() {
        for (int index = 0; index < 200; index++) {
            String session = "recreate-" + index;
            InitialActionPlan before = InitialActionPlan.forSession(session, true);
            InitialActionPlan after = InitialActionPlan.forSession(new String(session), true);
            assertEquals(before.doubleExit, after.doubleExit);
            assertEquals(before.blindBox, after.blindBox);
            assertEquals(before.actions.size(), after.actions.size());
            for (int slot = 0; slot < before.actions.size(); slot++) {
                InitialActionPlan.Action first = before.actions.get(slot), second = after.actions.get(slot);
                assertEquals(first.kind, second.kind);
                assertEquals(first.exit, second.exit);
                assertEquals(first.copy, second.copy);
                assertEquals(first.copyVariant, second.copyVariant);
            }
        }
    }

    /** 双退出继承基础控件组合；文案抽取允许相同索引，也允许不同索引。 */
    @Test public void doubleExitKeepsControlKindsAndAllowsRepeatedCopy() {
        boolean repeated = false, different = false;
        for (int index = 0; index < 1000; index++) {
            String session = "double-copy-" + index;
            InitialActionPlan plan = InitialActionPlan.forSession(session, true);
            if (!plan.doubleExit || plan.blindBox) continue;
            InitialActionPlan base = InitialActionPlan.forSession(session, false);
            for (int slot = 0; slot < 2; slot++) assertEquals(base.actions.get(slot).kind, plan.actions.get(slot).kind);
            boolean same = Math.floorMod(plan.actions.get(0).copyVariant, 6)
                    == Math.floorMod(plan.actions.get(1).copyVariant, 6);
            repeated |= same;
            different |= !same;
        }
        assertTrue(repeated);
        assertTrue(different);
    }
}
