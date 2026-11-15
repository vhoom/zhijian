package github.vhoom.zhijian.ui.interception.preview;

import github.vhoom.zhijian.ui.interception.page.support.InitialActionPlan;
import github.vhoom.zhijian.domain.interception.InterceptionDecision;
import github.vhoom.zhijian.ui.interception.preview.InterceptionPreviewCatalog.Page;
import github.vhoom.zhijian.ui.interception.preview.InterceptionPreviewCatalog.Presentation;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * 预览样例必须来自真实方案，并保持修心与闭关的路由边界。
 *
 * 维护日期：2026年11月10日
 */
public class InterceptionPreviewCatalogTest {
    /** 每种预设、两种触发和不同抽样均能选中真实控件组合。 */
    @Test public void presetsSelectRealPlansWithoutChangingTheirRoutes() {
        for (int index = 0; index < InterceptionPreviewCatalog.PAGE_COUNT; index++) {
            Page page = InterceptionPreviewCatalog.page(index);
            for (Presentation requested : Presentation.values()) {
                for (int sample = 0; sample < 16; sample++) {
                    String session = InterceptionPreviewCatalog.session(index, sample, requested);
                    assertEquals(session, InterceptionPreviewCatalog.session(index, sample, requested));
                    InitialActionPlan plan = page == Page.CONFIRMATION ? InitialActionPlan.forConfirmation(session)
                            : InitialActionPlan.forSession(session, page == Page.WUWEI);
                    Presentation selected = requested.forPage(page);
                    if (page != Page.WUWEI) {
                        assertFalse(plan.blindBox);
                        assertFalse(plan.doubleExit);
                        assertNotEquals(plan.actions.get(0).exit, plan.actions.get(1).exit);
                    }
                    if (selected == Presentation.BLIND_EXIT || selected == Presentation.BLIND_PASS) {
                        assertTrue(plan.blindBox);
                        assertEquals(1, plan.actions.size());
                        assertEquals(selected == Presentation.BLIND_EXIT, plan.actions.get(0).exit);
                    } else if (selected == Presentation.DOUBLE_EXIT) {
                        assertFalse(plan.blindBox);
                        assertTrue(plan.doubleExit);
                        for (InitialActionPlan.Action action : plan.actions) assertTrue(action.exit);
                    } else if (selected != Presentation.RANDOM) {
                        assertFalse(plan.doubleExit);
                        assertFalse(plan.blindBox);
                        assertNotEquals(plan.actions.get(0).exit, plan.actions.get(1).exit);
                        switch (selected) {
                            case BUTTONS: assertKinds(plan, false, false); break;
                            case SLIDERS: assertKinds(plan, true, true); break;
                            case BUTTON_SLIDER: assertKinds(plan, false, true); break;
                            case SLIDER_BUTTON: assertKinds(plan, true, false); break;
                            default: fail("Unexpected preset");
                        }
                    }
                }
            }
        }
    }

    /** 各阶段含挑战完成页能准确定位，翻页循环、触发切换与重抽保留身份边界。 */
    @Test public void navigationAndSamplesAreStable() {
        assertEquals(InterceptionPreviewCatalog.PAGE_COUNT - 1, InterceptionPreviewCatalog.normalize(-1));
        for (int index = 0; index < InterceptionPreviewCatalog.PAGE_COUNT; index++) {
            Page page = InterceptionPreviewCatalog.page(index);
            assertEquals(index, InterceptionPreviewCatalog.indexOf(page.level, page.stage,
                    InterceptionPreviewCatalog.reminder(index)
                            ? InterceptionDecision.Kind.INTERRUPT : InterceptionDecision.Kind.BLOCK));
            int toggled = InterceptionPreviewCatalog.toggleKind(index);
            assertSame(InterceptionPreviewCatalog.page(index), InterceptionPreviewCatalog.page(toggled));
            assertNotEquals(InterceptionPreviewCatalog.reminder(index), InterceptionPreviewCatalog.reminder(toggled));
            assertNotEquals(InterceptionPreviewCatalog.session(index, 0, Presentation.RANDOM),
                    InterceptionPreviewCatalog.session(index, 1, Presentation.RANDOM));
        }
        assertSame(Presentation.RANDOM, Presentation.parse("unknown"));
        assertSame(Presentation.RANDOM, Presentation.BLIND_PASS.forPage(Page.CONFIRMATION));
    }

    /** @param plan 真实方案 @param firstSlider 第一控件是否滑块 @param secondSlider 第二控件是否滑块 */
    private static void assertKinds(InitialActionPlan plan, boolean firstSlider, boolean secondSlider) {
        assertEquals(firstSlider, plan.actions.get(0).kind == InitialActionPlan.Kind.SLIDER);
        assertEquals(secondSlider, plan.actions.get(1).kind == InitialActionPlan.Kind.SLIDER);
    }
}
