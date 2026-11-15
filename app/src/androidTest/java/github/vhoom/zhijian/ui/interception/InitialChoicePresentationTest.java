package github.vhoom.zhijian.ui.interception;

import android.content.Context;
import android.content.res.Configuration;
import android.os.SystemClock;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;

import github.vhoom.zhijian.R;
import github.vhoom.zhijian.data.model.interception.InterceptionEvent;
import github.vhoom.zhijian.data.model.interception.InterceptionStage;
import github.vhoom.zhijian.data.model.policy.ControlLevel;
import github.vhoom.zhijian.data.model.settings.InteractionSettings;
import github.vhoom.zhijian.data.model.settings.InterceptionOptions;
import github.vhoom.zhijian.data.store.settings.InteractionSettingsStore;
import github.vhoom.zhijian.domain.interception.InterceptionDecision;
import github.vhoom.zhijian.domain.interception.ModeFlowPlan;
import github.vhoom.zhijian.fixture.InitialPageSessions;
import github.vhoom.zhijian.fixture.InterceptionAssertions;
import github.vhoom.zhijian.ui.interception.copy.ActionCopy;
import github.vhoom.zhijian.ui.interception.copy.InterceptionCopyCatalog;
import github.vhoom.zhijian.ui.interception.copy.InterceptionCopyCatalog.Page;
import github.vhoom.zhijian.ui.interception.page.InterceptionPage;
import github.vhoom.zhijian.ui.interception.page.InterceptionPageRegistry;
import github.vhoom.zhijian.ui.interception.page.support.InitialActionPlan;
import github.vhoom.zhijian.ui.interception.page.support.SlideActionView;
import github.vhoom.zhijian.ui.interception.session.PageSession;
import github.vhoom.zhijian.util.config.AppConfig;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * 共用首屏回归：控件独立抽样、主句保留、无为特殊事件、滑动取消和系统重建。
 *
 * 维护日期：2026年11月14日
 */
public class InitialChoicePresentationTest {
    /** 冷启动先在测试线程等待存储就绪，避免读取尚未装配的异步页面。 */
    @Before public void awaitStorageReady() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ((github.vhoom.zhijian.ZhijianApplication) context.getApplicationContext()).ready().get(10, TimeUnit.SECONDS);
    }

    /** 禁用和提醒都没有顶部内容；重建仍保持标题、控件类型、顺序和路由。 */
    @Test public void bothKindsHideHeaderAndKeepTheirPresentationAfterRecreation() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        for (ControlLevel level : new ControlLevel[]{ControlLevel.MORTAL, ControlLevel.REPAIR}) {
            for (InterceptionDecision.Kind kind : InterceptionDecision.Kind.values()) {
                String[] before = new String[1];
                try (ActivityScenario<InterceptionActivity> scenario = ActivityScenario.launch(
                        InterceptionActivity.debugIntent(context, kind, level, "initial-recreate-" + level + kind))) {
                    scenario.onActivity(activity -> {
                        InterceptionAssertions.assertNoHeader(activity);
                        assertFalse(activity.findViewById(R.id.interception_countdown).isShown());
                        before[0] = signature(activity);
                    });
                    scenario.recreate();
                    scenario.onActivity(activity -> {
                        InterceptionAssertions.assertNoHeader(activity);
                        assertEquals(before[0], signature(activity));
                    });
                }
            }
        }
    }

    /** 同一运行会话更换打开种子可离开双退出或盲盒；主句不变，同一种子重建保持完整方案。 */
    @Test public void newOpeningChangesControlsAndKeepsTheOriginalHeadline() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String doubleExitSeed = InitialPageSessions.matching("opening-double", plan -> plan.doubleExit);
        String blindSeed = InitialPageSessions.matching("opening-blind", plan -> plan.blindBox);
        String buttonsSeed = InitialPageSessions.matching("opening-buttons", plan -> !plan.doubleExit && !plan.blindBox
                && plan.actions.get(0).kind == InitialActionPlan.Kind.BUTTON
                && plan.actions.get(1).kind == InitialActionPlan.Kind.BUTTON);
        String slidersSeed = InitialPageSessions.matching("opening-sliders", plan -> !plan.doubleExit && !plan.blindBox
                && plan.actions.get(0).kind == InitialActionPlan.Kind.SLIDER
                && plan.actions.get(1).kind == InitialActionPlan.Kind.SLIDER);
        try (ActivityScenario<InterceptionActivity> scenario = ActivityScenario.launch(
                InterceptionActivity.debugIntent(context, InterceptionDecision.Kind.BLOCK, ControlLevel.MORTAL, "opening-headline"))) {
            scenario.onActivity(activity -> {
                for (ControlLevel level : new ControlLevel[]{ControlLevel.MORTAL, ControlLevel.REPAIR}) {
                    for (InterceptionDecision.Kind kind : InterceptionDecision.Kind.values()) {
                        String[] previousSeeds = level == ControlLevel.MORTAL
                                ? new String[]{doubleExitSeed, blindSeed} : new String[]{buttonsSeed};
                        for (String previousSeed : previousSeeds) {
                            InterceptionOptions options = InterceptionOptions.defaultsFor(ModeFlowPlan.modeFor(kind, level));
                            String sessionId = "unchanged-runtime-session";
                            InterceptionPage before = page(activity, kind, level, sessionId, new ArrayList<>(),
                                    InterceptionStage.INITIAL, options, previousSeed);
                            InterceptionPage restored = page(activity, kind, level, sessionId, new ArrayList<>(),
                                    InterceptionStage.INITIAL, options, previousSeed);
                            InterceptionPage reopened = page(activity, kind, level, sessionId, new ArrayList<>(),
                                    InterceptionStage.INITIAL, options, slidersSeed);
                            TextView headline = before.view().findViewById(R.id.interception_general_title);
                            assertFalse(headline.getText().toString().isEmpty());
                            assertEquals(headline.getText().toString(),
                                    ((TextView) reopened.view().findViewById(R.id.interception_general_title)).getText().toString());
                            assertEquals(signature(before.view()), signature(restored.view()));
                            assertNotEquals(signature(before.view()), signature(reopened.view()));
                            assertNotNull(reopened.view().findViewById(R.id.interception_general_continue));
                            assertNotNull(reopened.view().findViewById(R.id.interception_general_exit));
                            before.pause();
                            restored.pause();
                            reopened.pause();
                        }
                    }
                }
            });
        }
    }

    /** 八种联合结果在两类触发中都保持一退一进，真实手势只触发控件承担的路由。 */
    @Test public void everyCombinationExecutesItsAssignedRoutes() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (ActivityScenario<InterceptionActivity> scenario = ActivityScenario.launch(
                InterceptionActivity.debugIntent(context, InterceptionDecision.Kind.BLOCK, ControlLevel.MORTAL, "wuwei-actions"))) {
            scenario.onActivity(activity -> {
                for (ControlLevel level : new ControlLevel[]{ControlLevel.MORTAL, ControlLevel.REPAIR}) {
                    for (InterceptionDecision.Kind kind : InterceptionDecision.Kind.values()) {
                        boolean[] checked = new boolean[8];
                        int remaining = checked.length;
                        for (int index = 0; index < 1000 && remaining > 0; index++) {
                            String sessionId = "route-case-" + index;
                            InitialActionPlan plan = InitialActionPlan.forSession(sessionId, level == ControlLevel.MORTAL);
                            if (plan.blindBox || plan.doubleExit) continue;
                            InitialActionPlan.Action first = plan.actions.get(0), second = plan.actions.get(1);
                            int key = (first.kind == InitialActionPlan.Kind.SLIDER ? 4 : 0)
                                    + (second.kind == InitialActionPlan.Kind.SLIDER ? 2 : 0) + (first.exit ? 1 : 0);
                            if (checked[key]) continue;
                            checked[key] = true;
                            remaining--;
                            List<InterceptionEvent> events = new ArrayList<>();
                            InterceptionPage page = page(activity, kind, level, sessionId, events);
                            measure(page.view(), activity);
                            LinearLayout actions = page.view().findViewById(R.id.interception_general_actions);
                            assertEquals(2, actions.getChildCount());
                            assertEquals(first.kind == InitialActionPlan.Kind.SLIDER, actions.getChildAt(0) instanceof SlideActionView);
                            assertEquals(second.kind == InitialActionPlan.Kind.SLIDER, actions.getChildAt(1) instanceof SlideActionView);
                            assertEquals(first.exit ? R.id.interception_general_exit : R.id.interception_general_continue,
                                    actions.getChildAt(0).getId());
                            for (int slot = 0; slot < 2; slot++) {
                                Button control = (Button) actions.getChildAt(slot);
                                assertEquals(Gravity.CENTER, control.getGravity());
                                InitialActionPlan.Action spec = plan.actions.get(slot);
                                assertEquals(ActionCopy.text(activity, spec.exit ? ActionCopy.Route.REST
                                                : level == ControlLevel.REPAIR ? ActionCopy.Route.CHALLENGE
                                                : ActionCopy.Route.CONTINUE,
                                        spec.copyVariant), control.getText().toString());
                                events.clear();
                                if (control instanceof SlideActionView) {
                                    verifySlide((SlideActionView) control, events);
                                } else {
                                    control.performClick();
                                }
                                assertEquals(Collections.singletonList(control.getId() == R.id.interception_general_exit
                                        ? InterceptionEvent.ABANDON : InterceptionEvent.CONTINUE), events);
                            }
                        }
                        assertEquals("All eight outcomes must be reachable", 0, remaining);
                    }
                }
            });
        }
    }

    /** 无为和修心共享首次主句；闭关按自身禁用、提醒仓库读取且只有退出。 */
    @Test public void levelsReadTheCorrectHeadlineBank() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (ActivityScenario<InterceptionActivity> scenario = ActivityScenario.launch(
                InterceptionActivity.debugIntent(context, InterceptionDecision.Kind.BLOCK, ControlLevel.MORTAL, "headline-banks"))) {
            scenario.onActivity(activity -> {
                InterceptionCopyCatalog catalog = new InterceptionCopyCatalog(activity);
                for (InterceptionDecision.Kind kind : InterceptionDecision.Kind.values()) {
                    for (ControlLevel level : ControlLevel.values()) {
                        InterceptionPage page = page(activity, kind, level, "shared-copy", new ArrayList<>());
                        boolean seal = level == ControlLevel.SEAL;
                        TextView headline = page.view().findViewById(seal
                                ? R.id.interception_forbidden_title : R.id.interception_general_title);
                        assertEquals(catalog.scenario(seal ? Page.FORBIDDEN : Page.GENERAL,
                                InterceptionCopyCatalog.State.IDLE, 0).headline, headline.getText().toString());
                        if (seal) {
                            assertEquals(1, ((LinearLayout) page.view().findViewById(R.id.interception_forbidden_actions)).getChildCount());
                        } else if (level == ControlLevel.REPAIR) {
                            assertTrue(java.util.Arrays.asList(context.getResources().getStringArray(R.array.interception_exit_actions))
                                    .contains(((Button) page.view().findViewById(R.id.interception_general_exit)).getText().toString()));
                            assertTrue(java.util.Arrays.asList(context.getResources().getStringArray(R.array.interception_challenge_actions))
                                    .contains(((Button) page.view().findViewById(R.id.interception_general_continue)).getText().toString()));
                        }
                    }
                }
            });
        }
    }

    /** 闭关的禁用与提醒均只有主句和一个退出按钮，重建不恢复顶部内容。 */
    @Test public void sealOnlyShowsHeadlineAndExitForBothKinds() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        for (InterceptionDecision.Kind kind : InterceptionDecision.Kind.values()) {
            String[] before = new String[2];
            try (ActivityScenario<InterceptionActivity> scenario = ActivityScenario.launch(
                    InterceptionActivity.debugIntent(context, kind, ControlLevel.SEAL, "seal-minimal-" + kind))) {
                scenario.onActivity(activity -> {
                    InterceptionAssertions.assertNoHeader(activity);
                    assertFalse(activity.findViewById(R.id.interception_countdown).isShown());
                    LinearLayout actions = activity.findViewById(R.id.interception_forbidden_actions);
                    assertEquals(1, actions.getChildCount());
                    assertEquals(R.id.interception_forbidden_exit, actions.getChildAt(0).getId());
                    before[0] = ((TextView) activity.findViewById(R.id.interception_forbidden_title)).getText().toString();
                    before[1] = ((Button) actions.getChildAt(0)).getText().toString();
                    assertTrue(java.util.Arrays.asList(activity.getResources().getStringArray(R.array.interception_exit_actions)).contains(before[1]));
                    List<InterceptionEvent> events = new ArrayList<>();
                    InterceptionPage page = page(activity, kind, ControlLevel.SEAL, "seal-route", events);
                    page.view().findViewById(R.id.interception_forbidden_exit).performClick();
                    assertEquals(Collections.singletonList(InterceptionEvent.ABANDON), events);
                });
                scenario.recreate();
                scenario.onActivity(activity -> {
                    InterceptionAssertions.assertNoHeader(activity);
                    assertEquals(before[0], ((TextView) activity.findViewById(R.id.interception_forbidden_title)).getText().toString());
                    assertEquals(before[1], ((Button) activity.findViewById(R.id.interception_forbidden_exit)).getText().toString());
                    activity.findViewById(R.id.interception_forbidden_exit).performClick();
                    assertTrue(activity.isFinishing());
                });
            }
        }
    }

    /** 窄屏、大字体下保留完整文案；浅深色资源均能构建控件且文字不被固定高度截断。 */
    @Test public void smallScreensAndLargeFontsKeepBothControlsReachable() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (ActivityScenario<InterceptionActivity> scenario = ActivityScenario.launch(
                InterceptionActivity.debugIntent(context, InterceptionDecision.Kind.BLOCK, ControlLevel.MORTAL, "wuwei-large-font"))) {
            scenario.onActivity(activity -> {
                for (int night : new int[]{Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES}) {
                    Configuration configuration = new Configuration(activity.getResources().getConfiguration());
                    configuration.fontScale = 2f;
                    configuration.uiMode = (configuration.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | night;
                    Context themed = new ContextThemeWrapper(activity.createConfigurationContext(configuration), activity.getTheme());
                    InterceptionPage page = page(themed, InterceptionDecision.Kind.INTERRUPT, ControlLevel.MORTAL,
                            "large-font-case", new ArrayList<>());
                    measure(page.view(), themed);
                    LinearLayout actions = page.view().findViewById(R.id.interception_general_actions);
                    for (int index = 0; index < actions.getChildCount(); index++) {
                        Button action = (Button) actions.getChildAt(index);
                        assertTrue(action.getWidth() > 0);
                        assertTrue(action.getHeight() >= action.getLayout().getHeight() + action.getCompoundPaddingTop()
                                + action.getCompoundPaddingBottom());
                        assertEquals(action.getText().length(), action.getLayout().getLineEnd(action.getLineCount() - 1));
                    }
                }
            });
        }
    }

    /** @param context 主题上下文 @param kind 触发类型 @param level 强度 @param sessionId 稳定身份
     * @param events 收集页面事件 @return 已装配并渲染的独立页面 */
    private static InterceptionPage page(Context context, InterceptionDecision.Kind kind, ControlLevel level,
            String sessionId, List<InterceptionEvent> events) {
        return page(context, kind, level, sessionId, events,
                level == ControlLevel.SEAL ? InterceptionStage.LOCKED : InterceptionStage.INITIAL);
    }

    /** @param context 主题 @param kind 触发 @param level 强度 @param sessionId 身份 @param events 事件出口
     * @param stage 选择页阶段 @return 已装配的独立选择页 */
    private static InterceptionPage page(Context context, InterceptionDecision.Kind kind, ControlLevel level,
            String sessionId, List<InterceptionEvent> events, InterceptionStage stage) {
        return page(context, kind, level, sessionId, events, stage,
                InterceptionOptions.defaultsFor(ModeFlowPlan.modeFor(kind, level)));
    }

    /** @param context 主题 @param kind 触发 @param level 强度 @param sessionId 身份 @param events 事件出口
     * @param stage 页面阶段 @param options 冻结模式选项 @return 已装配并渲染的页面 */
    private static InterceptionPage page(Context context, InterceptionDecision.Kind kind, ControlLevel level,
            String sessionId, List<InterceptionEvent> events, InterceptionStage stage, InterceptionOptions options) {
        return page(context, kind, level, sessionId, events, stage, options, sessionId);
    }

    /** @param context 主题 @param kind 触发 @param level 强度 @param sessionId 稳定运行会话
     * @param events 事件出口 @param stage 页面阶段 @param options 冻结模式选项
     * @param initialActionSeed 本次打开的独立控件种子 @return 已装配并渲染的页面 */
    private static InterceptionPage page(Context context, InterceptionDecision.Kind kind, ControlLevel level,
            String sessionId, List<InterceptionEvent> events, InterceptionStage stage, InterceptionOptions options,
            String initialActionSeed) {
        InterceptionDecision decision = new InterceptionDecision("test.wuwei", "global", 1L, kind,
                kind == InterceptionDecision.Kind.INTERRUPT ? InterceptionDecision.Trigger.CONTINUOUS : InterceptionDecision.Trigger.WINDOW_BAN,
                level, "", 1L, 1L, 0L, 60_000L, kind == InterceptionDecision.Kind.INTERRUPT ? 60_000L : 0L);
        InteractionSettings current = new InteractionSettingsStore(context, new AppConfig(context))
                .read(ModeFlowPlan.modeFor(kind, level));
        InteractionSettings settings = new InteractionSettings(current.enabledChallenges, current.holdRange, current.word, options);
        PageSession session = new PageSession(decision, sessionId, 0,
                stage, settings, Collections.emptyList(), initialActionSeed);
        InterceptionPage page = new InterceptionPageRegistry().create(new FrameLayout(context), session, events::add, null);
        page.render();
        return page;
    }

    /** 初始和最终选择的继续文字与开关对应，不在关闭放行后继续承诺临时使用。 */
    @Test public void actionCopyFollowsFrozenModeOptions() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (ActivityScenario<InterceptionActivity> scenario = ActivityScenario.launch(
                InterceptionActivity.debugIntent(context, InterceptionDecision.Kind.BLOCK, ControlLevel.REPAIR, "option-copy"))) {
            scenario.onActivity(activity -> {
                for (boolean pass : new boolean[]{false, true}) {
                    for (boolean obstacles : new boolean[]{false, true}) {
                        for (InterceptionStage stage : new InterceptionStage[]{InterceptionStage.INITIAL, InterceptionStage.FINAL_CONFIRM}) {
                            if (stage == InterceptionStage.FINAL_CONFIRM && !obstacles) continue;
                            InterceptionPage page = page(activity, InterceptionDecision.Kind.INTERRUPT, ControlLevel.REPAIR,
                                    "option-copy", new ArrayList<>(), stage, new InterceptionOptions(pass, obstacles));
                            int id = stage == InterceptionStage.INITIAL
                                    ? R.id.interception_general_continue : R.id.interception_confirmation_continue;
                            int pool = stage == InterceptionStage.INITIAL && obstacles
                                    ? R.array.interception_challenge_actions
                                    : R.array.interception_continue_actions;
                            String text = ((Button) page.view().findViewById(id)).getText().toString();
                            assertTrue(java.util.Arrays.asList(activity.getResources().getStringArray(pool)).contains(text));
                        }
                    }
                }
            });
        }
    }

    /** 二次询问覆盖全部组合，动态文案与真实路由相符，始终一退一进且没有特殊事件。 */
    @Test public void finalChoicesUseDynamicCopyAndAlwaysKeepBothRoutes() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (ActivityScenario<InterceptionActivity> scenario = ActivityScenario.launch(
                InterceptionActivity.debugIntent(context, InterceptionDecision.Kind.BLOCK, ControlLevel.REPAIR, "final-actions"))) {
            scenario.onActivity(activity -> {
                boolean[] combinations = new boolean[8];
                java.util.Set<String> exitTexts = new java.util.HashSet<>(), passTexts = new java.util.HashSet<>();
                for (InterceptionDecision.Kind kind : InterceptionDecision.Kind.values()) {
                    for (int index = 0; index < 80; index++) {
                        String session = "final-actions-" + index;
                        InitialActionPlan plan = InitialActionPlan.forConfirmation(session);
                        List<InterceptionEvent> events = new ArrayList<>();
                        InterceptionPage page = page(activity, kind, ControlLevel.REPAIR, session, events, InterceptionStage.FINAL_CONFIRM);
                        measure(page.view(), activity);
                        LinearLayout actions = page.view().findViewById(R.id.interception_confirmation_actions);
                        assertEquals(2, actions.getChildCount());
                        assertNotNull(page.view().findViewById(R.id.interception_confirmation_exit));
                        assertNotNull(page.view().findViewById(R.id.interception_confirmation_continue));
                        int key = (plan.actions.get(0).kind == InitialActionPlan.Kind.SLIDER ? 4 : 0)
                                + (plan.actions.get(1).kind == InitialActionPlan.Kind.SLIDER ? 2 : 0)
                                + (plan.actions.get(0).exit ? 1 : 0);
                        combinations[key] = true;
                        for (int slot = 0; slot < 2; slot++) {
                            Button control = (Button) actions.getChildAt(slot);
                            InitialActionPlan.Action spec = plan.actions.get(slot);
                            String label = control.getText().toString();
                            assertEquals(ActionCopy.text(activity, spec.exit ? ActionCopy.Route.REST : ActionCopy.Route.PASS,
                                    spec.copyVariant), label);
                            (spec.exit ? exitTexts : passTexts).add(label);
                            events.clear();
                            if (control instanceof SlideActionView) verifySlide((SlideActionView) control, events);
                            else control.performClick();
                            assertEquals(Collections.singletonList(spec.exit ? InterceptionEvent.ABANDON : InterceptionEvent.CONTINUE), events);
                        }
                    }
                }
                for (boolean checked : combinations) assertTrue(checked);
                assertTrue(exitTexts.size() > 1 && passTexts.size() > 1);
            });
        }
    }

    /** @param view 独立页面 @param context 用于换算测试屏幕尺寸的主题上下文 */
    private static void measure(View view, Context context) {
        float density = context.getResources().getDisplayMetrics().density;
        int width = Math.round(272 * density), height = Math.round(480 * density);
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
    }

    /** @param activity 可见页面 @return 标题、控件类型、路由顺序及操作文案的联合签名 */
    private static String signature(InterceptionActivity activity) {
        return signature(activity.findViewById(R.id.interception_page_general));
    }

    /** @param view 独立首屏根视图 @return 主句、控件类型、路由顺序及操作文案的联合签名 */
    private static String signature(View view) {
        StringBuilder result = new StringBuilder(((TextView) view.findViewById(R.id.interception_general_title)).getText());
        LinearLayout actions = view.findViewById(R.id.interception_general_actions);
        assertTrue(actions.getChildCount() == 1 || actions.getChildCount() == 2);
        for (int index = 0; index < actions.getChildCount(); index++) {
            Button action = (Button) actions.getChildAt(index);
            result.append('|').append(action.getId()).append('|').append(action.getClass().getName()).append('|').append(action.getText());
        }
        return result.toString();
    }

    /** 两种布局方向均允许在控件外松手，仍按松手位置检查门槛并只执行一次。 */
    @Test public void slidesCompleteOutsideBoundsInBothLayoutDirections() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (ActivityScenario<InterceptionActivity> scenario = ActivityScenario.launch(
                InterceptionActivity.debugIntent(context, InterceptionDecision.Kind.BLOCK,
                        ControlLevel.MORTAL, "slide-release-outside"))) {
            scenario.onActivity(activity -> {
                for (int direction : new int[]{View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL}) {
                    SlideActionView slider = new SlideActionView(activity);
                    slider.setLayoutDirection(direction);
                    List<InterceptionEvent> events = new ArrayList<>();
                    slider.setOnClickListener(view -> events.add(InterceptionEvent.CONTINUE));
                    float density = activity.getResources().getDisplayMetrics().density;
                    int width = Math.round(272 * density), height = Math.round(64 * density);
                    slider.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                    slider.layout(0, 0, width, height);
                    verifySlide(slider, events);
                }
            });
        }
    }

    /** @param slider 被测滑块 @param events 收集执行次数；控件内外达到 90% 松手均只执行一次 */
    private static void verifySlide(SlideActionView slider, List<InterceptionEvent> events) {
        float thumbCenter = slider.getResources().getDimension(R.dimen.space_xs)
                + slider.getResources().getDimension(R.dimen.wuwei_slider_thumb) / 2f;
        boolean rtl = slider.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        float start = rtl ? slider.getWidth() - thumbCenter : thumbCenter;
        float end = rtl ? thumbCenter : slider.getWidth() - thumbCenter;
        float beyondEnd = rtl ? -1f : slider.getWidth() + 1f;
        float belowThreshold = start + (end - start) * 0.899f;
        float threshold = start + (end - start) * 0.90f;
        touch(slider, MotionEvent.ACTION_DOWN, end);
        touch(slider, MotionEvent.ACTION_UP, end);
        assertTrue(events.isEmpty());
        touch(slider, MotionEvent.ACTION_DOWN, start);
        touch(slider, MotionEvent.ACTION_MOVE, (start + end) / 2f);
        assertTrue(slider.isDragging());
        touch(slider, MotionEvent.ACTION_UP, (start + end) / 2f);
        assertFalse(slider.isDragging());
        assertTrue(events.isEmpty());
        touch(slider, MotionEvent.ACTION_DOWN, start);
        touch(slider, MotionEvent.ACTION_MOVE, belowThreshold);
        touch(slider, MotionEvent.ACTION_UP, belowThreshold);
        assertFalse(slider.isDragging());
        assertTrue(events.isEmpty());
        touch(slider, MotionEvent.ACTION_DOWN, start);
        touch(slider, MotionEvent.ACTION_MOVE, threshold);
        assertTrue(slider.isDragging());
        assertTrue(events.isEmpty());
        touch(slider, MotionEvent.ACTION_CANCEL, threshold);
        assertFalse(slider.isDragging());
        assertTrue(events.isEmpty());
        touch(slider, MotionEvent.ACTION_DOWN, start);
        touch(slider, MotionEvent.ACTION_MOVE, end);
        touch(slider, MotionEvent.ACTION_CANCEL, end);
        assertFalse(slider.isDragging());
        assertTrue(events.isEmpty());
        touch(slider, MotionEvent.ACTION_DOWN, start);
        touch(slider, MotionEvent.ACTION_MOVE, end);
        touch(slider, MotionEvent.ACTION_MOVE, belowThreshold);
        touch(slider, MotionEvent.ACTION_UP, belowThreshold);
        assertFalse(slider.isDragging());
        assertTrue(events.isEmpty());
        for (float releaseY : new float[]{-1f, slider.getHeight() + 1f}) {
            touch(slider, MotionEvent.ACTION_DOWN, start);
            touch(slider, MotionEvent.ACTION_MOVE, end);
            touch(slider, MotionEvent.ACTION_UP, belowThreshold, releaseY);
            assertFalse(slider.isDragging());
            assertTrue(events.isEmpty());
        }
        for (float completion : new float[]{end, threshold, beyondEnd}) {
            for (float releaseY : new float[]{slider.getHeight() / 2f, -1f, slider.getHeight() + 1f}) {
                events.clear();
                touch(slider, MotionEvent.ACTION_DOWN, start);
                touch(slider, MotionEvent.ACTION_MOVE, completion, releaseY);
                assertTrue(slider.isDragging());
                assertTrue(events.isEmpty());
                touch(slider, MotionEvent.ACTION_UP, completion, releaseY);
                assertFalse(slider.isDragging());
                assertEquals(1, events.size());
            }
        }
    }

    /** @param view 目标控件 @param action 触摸事件类型 @param x 控件局部水平坐标，可超出边界 */
    private static void touch(View view, int action, float x) {
        touch(view, action, x, view.getHeight() / 2f);
    }

    /** @param view 目标控件 @param action 触摸事件类型 @param x 局部水平坐标 @param y 局部纵向坐标，均可超出边界 */
    private static void touch(View view, int action, float x, float y) {
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, x, y, 0);
        try { view.dispatchTouchEvent(event); }
        finally { event.recycle(); }
    }

    /** 双退出和盲盒的实际手势使用对应路由；相同退出文案可以重复且控件 ID 唯一。 */
    @Test public void specialControlsUseDynamicCopyAndTheirAssignedOutcome() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (ActivityScenario<InterceptionActivity> scenario = ActivityScenario.launch(
                InterceptionActivity.debugIntent(context, InterceptionDecision.Kind.BLOCK, ControlLevel.MORTAL, "special-actions"))) {
            scenario.onActivity(activity -> {
                int[] doubleKinds = new int[4];
                boolean repeated = false;
                boolean[] blindResults = new boolean[2];
                java.util.Set<String> blindTexts = new java.util.HashSet<>();
                // 筛选足够会话，覆盖四种双控件组合、两种盲盒结果和重复文案。
                for (int index = 0; index < 1000; index++) {
                    String session = "special-actions-" + index;
                    InitialActionPlan plan = InitialActionPlan.forSession(session, true);
                    if (!plan.blindBox && !plan.doubleExit) continue;
                    List<InterceptionEvent> events = new ArrayList<>();
                    InterceptionPage page = page(activity, InterceptionDecision.Kind.BLOCK, ControlLevel.MORTAL, session, events);
                    measure(page.view(), activity);
                    LinearLayout actions = page.view().findViewById(R.id.interception_general_actions);
                    assertEquals(plan.actions.size(), actions.getChildCount());
                    assertNull(page.view().findViewById(R.id.interception_general_continue));
                    if (plan.blindBox) {
                        assertEquals(1, actions.getChildCount());
                        assertEquals(R.id.interception_general_blind_box, actions.getChildAt(0).getId());
                        assertTrue(actions.getChildAt(0) instanceof SlideActionView);
                        blindResults[plan.actions.get(0).exit ? 0 : 1] = true;
                        Button control = (Button) actions.getChildAt(0);
                        // 两种结果都用同一种描边文字颜色，不提前泄露去留。
                        assertEquals(activity.getColor(R.color.ink), control.getCurrentTextColor());
                        blindTexts.add(control.getText().toString());
                    } else {
                        assertEquals(2, actions.getChildCount());
                        assertNotEquals(actions.getChildAt(0).getId(), actions.getChildAt(1).getId());
                        repeated |= ((Button) actions.getChildAt(0)).getText().toString()
                                .equals(((Button) actions.getChildAt(1)).getText().toString());
                        int key = (actions.getChildAt(0) instanceof SlideActionView ? 2 : 0)
                                + (actions.getChildAt(1) instanceof SlideActionView ? 1 : 0);
                        doubleKinds[key]++;
                    }
                    for (int slot = 0; slot < actions.getChildCount(); slot++) {
                        Button control = (Button) actions.getChildAt(slot);
                        String[] pool = activity.getResources().getStringArray(plan.blindBox
                                ? R.array.interception_blind_box_actions : R.array.interception_exit_actions);
                        assertTrue(java.util.Arrays.asList(pool).contains(control.getText().toString()));
                        events.clear();
                        if (control instanceof SlideActionView) verifySlide((SlideActionView) control, events);
                        else control.performClick();
                        assertEquals(Collections.singletonList(plan.actions.get(slot).exit
                                ? InterceptionEvent.ABANDON : InterceptionEvent.CONTINUE), events);
                    }
                    page.pause();
                }
                for (int count : doubleKinds) assertTrue(count > 0);
                assertTrue(repeated);
                assertTrue(blindResults[0] && blindResults[1]);
                assertTrue(blindTexts.size() > 1);
            });
        }
    }

    /** 重建保留普通选择、双退出和盲盒文案；修心中同一会话仍然只出现一退一进。 */
    @Test public void specialPresentationSurvivesRecreationAndRepairNeverGetsIt() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String[] sessions = {
                InitialPageSessions.matching("double-recreate", plan -> plan.doubleExit && !plan.blindBox),
                InitialPageSessions.matching("blind-exit-recreate", plan -> plan.blindBox && plan.actions.get(0).exit),
                InitialPageSessions.matching("blind-pass-recreate", plan -> plan.blindBox && !plan.actions.get(0).exit),
                InitialPageSessions.ordinary("ordinary-recreate")
        };
        for (String session : sessions) {
            for (ControlLevel level : new ControlLevel[]{ControlLevel.MORTAL, ControlLevel.REPAIR}) {
                String[] before = new String[1];
                try (ActivityScenario<InterceptionActivity> scenario = ActivityScenario.launch(
                        InterceptionActivity.debugIntent(context, InterceptionDecision.Kind.BLOCK, level, session))) {
                    scenario.onActivity(activity -> before[0] = signature(activity));
                    scenario.recreate();
                    scenario.onActivity(activity -> {
                        assertEquals(before[0], signature(activity));
                        if (level == ControlLevel.REPAIR) {
                            assertNotNull(activity.findViewById(R.id.interception_general_exit));
                            assertNotNull(activity.findViewById(R.id.interception_general_continue));
                            assertNull(activity.findViewById(R.id.interception_general_blind_box));
                            activity.findViewById(R.id.interception_general_continue).performClick();
                            assertFalse(activity.isFinishing());
                            assertNull(activity.findViewById(R.id.interception_page_general));
                            assertTrue(activity.findViewById(R.id.interception_page_hold) != null
                                    || activity.findViewById(R.id.interception_page_word) != null);
                        }
                    });
                }
            }
        }
    }
}
