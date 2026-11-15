package github.vhoom.zhijian.ui.interception;

import android.content.Context;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.lifecycle.Lifecycle;
import androidx.test.platform.app.InstrumentationRegistry;

import github.vhoom.zhijian.R;
import github.vhoom.zhijian.data.model.interception.InterceptionMode;
import github.vhoom.zhijian.data.model.interception.InterceptionStage;
import github.vhoom.zhijian.data.model.policy.ControlLevel;
import github.vhoom.zhijian.data.model.settings.SecondsRange;
import github.vhoom.zhijian.data.model.settings.WordChallengeSettings;
import github.vhoom.zhijian.data.store.settings.InteractionSettingsStore;
import github.vhoom.zhijian.domain.interception.InterceptionDecision;
import github.vhoom.zhijian.fixture.InterceptionAssertions;
import github.vhoom.zhijian.util.config.AppConfig;

import org.junit.Test;
import org.junit.Before;
import org.junit.After;

import static org.junit.Assert.*;

/**
 * 完成态的真实交互、继续门禁及系统重建回归。
 *
 * 维护日期：2026年11月14日
 */
public class ChallengeCompletionTest {
    private boolean originalConfirmation;

    /** 原有反问用例显式开启该选项，不依赖用户设置。 */
    @Before public void enableConfirmationForExistingScenarios() {
        InteractionSettingsStore store = new InteractionSettingsStore(context(), new AppConfig(context()));
        originalConfirmation = store.readOptions(InterceptionMode.REPAIR).confirmationEnabled;
        store.setConfirmationEnabled(true);
    }

    /** 还原测试前的再次确认选项。 */
    @After public void restoreConfirmation() {
        new InteractionSettingsStore(context(), new AppConfig(context())).setConfirmationEnabled(originalConfirmation);
    }

    /** 关闭反问后保留原页与退出操作，新增按钮等待点击，恢复后保留最后一题与冻结的放行去向。 */
    @Test public void disabledConfirmationUpdatesCurrentPageAndAddsContinueAction() {
        Context context = context();
        InteractionSettingsStore settings = new InteractionSettingsStore(context, new AppConfig(context));
        github.vhoom.zhijian.data.model.settings.InteractionSettings original = settings.read(InterceptionMode.REPAIR);
        try {
            settings.setHoldRange(InterceptionMode.REPAIR, new SecondsRange(200L, 200L));
            settings.setWordSettings(InterceptionMode.REPAIR, new WordChallengeSettings(2, 100L));
            for (boolean pass : new boolean[]{false, true}) {
                for (InterceptionDecision.Kind kind : InterceptionDecision.Kind.values()) {
                    for (InterceptionStage stage : new InterceptionStage[]{InterceptionStage.HOLD, InterceptionStage.WORD_TRANSLATION}) {
                        settings.setConfirmationEnabled(false);
                        settings.setTemporaryPassEnabled(InterceptionMode.REPAIR, pass);
                        boolean hold = stage == InterceptionStage.HOLD;
                        int continueId = hold ? R.id.interception_hold_continue : R.id.interception_word_continue;
                        int titleId = hold ? R.id.interception_hold_title : R.id.interception_word_title;
                        int[] preservedIds = hold
                                ? new int[]{R.id.interception_page_hold, R.id.interception_hold_button,
                                        R.id.interception_hold_progress, R.id.interception_hold_exit}
                                : new int[]{R.id.interception_page_word, R.id.interception_word_prompt,
                                        R.id.interception_word_progress, R.id.interception_word_exit};
                        View[] originalViews = new View[preservedIds.length];
                        String[] originalCopy = new String[2];
                        String[][] lastOptions = {null};
                        try (ActivityScenario<InterceptionActivity> scenario = ActivityScenario.launch(
                                InterceptionActivity.debugIntent(context, kind, ControlLevel.REPAIR, stage,
                                        "completed-inline-" + pass + kind + stage).putExtra("interception.interrupt_pause", 60_000L))) {
                            awaitPage(scenario, hold ? R.id.interception_hold_button : R.id.interception_word_choice_1);
                            scenario.onActivity(activity -> {
                                assertNull(activity.findViewById(continueId));
                                for (int index = 0; index < preservedIds.length; index++) {
                                    originalViews[index] = activity.findViewById(preservedIds[index]);
                                }
                                originalCopy[0] = ((TextView) activity.findViewById(titleId)).getText().toString();
                                originalCopy[1] = ((TextView) originalViews[3]).getText().toString();
                                if (hold) press(activity);
                            });
                            SystemClock.sleep(350L);
                            if (!hold) {
                                scenario.onActivity(ChallengeCompletionTest::answerWord);
                                SystemClock.sleep(150L);
                                scenario.onActivity(activity -> {
                                    lastOptions[0] = wordChoiceTexts(activity);
                                    answerWord(activity);
                                    View action = activity.findViewById(continueId);
                                    if (action.getAlpha() < 1f) {
                                        action.performClick();
                                        assertFalse(activity.isFinishing());
                                    }
                                });
                            }
                            awaitPage(scenario, continueId);
                            scenario.onActivity(activity -> {
                                for (int index = 0; index < preservedIds.length; index++) {
                                    assertSame(originalViews[index], activity.findViewById(preservedIds[index]));
                                }
                                assertEquals(1f, originalViews[0].getAlpha(), 0f);
                                assertEquals(0f, originalViews[0].getTranslationY(), 0f);
                                assertCompletedChallenge(activity, hold, lastOptions[0]);
                            });
                            scenario.moveToState(Lifecycle.State.STARTED);
                            scenario.moveToState(Lifecycle.State.RESUMED);
                            scenario.onActivity(activity -> {
                                assertSame(originalViews[0], activity.findViewById(preservedIds[0]));
                                assertCompletedChallenge(activity, hold, lastOptions[0]);
                            });
                            settings.setConfirmationEnabled(true);
                            settings.setTemporaryPassEnabled(InterceptionMode.REPAIR, !pass);
                            scenario.recreate();
                            scenario.onActivity(activity -> {
                                assertCompletedChallenge(activity, hold, lastOptions[0]);
                                assertEquals(originalCopy[0], ((TextView) activity.findViewById(titleId)).getText().toString());
                                assertEquals(originalCopy[1], ((TextView) activity.findViewById(preservedIds[3])).getText().toString());
                                activity.findViewById(continueId).performClick();
                                assertTrue(activity.isFinishing());
                            });
                        }
                    }
                }
            }
        } finally {
            settings.setHoldRange(InterceptionMode.REPAIR, original.holdRange);
            settings.setWordSettings(InterceptionMode.REPAIR, original.word);
            settings.setTemporaryPassEnabled(InterceptionMode.REPAIR, original.options.temporaryPassEnabled);
        }
    }

    /**
     * 检查完成后内容保留、满格进度、双出口及继续文案，不执行放行。
     * @param activity 当前完成阶段的拦截页面
     * @param hold 是否为按压挑战
     * @param expectedOptions 最后一题的选项文字，按压挑战时为空
     */
    private static void assertCompletedChallenge(InterceptionActivity activity, boolean hold,
            String[] expectedOptions) {
        int continueId = hold ? R.id.interception_hold_continue : R.id.interception_word_continue;
        int exitId = hold ? R.id.interception_hold_exit : R.id.interception_word_exit;
        int contentId = hold ? R.id.interception_hold_button : R.id.interception_word_prompt;
        int progressId = hold ? R.id.interception_hold_progress : R.id.interception_word_progress;
        assertNull(activity.findViewById(R.id.interception_page_confirmation));
        assertFalse(activity.isFinishing());
        TextView content = activity.findViewById(contentId);
        assertTrue(content.isShown());
        assertEquals(activity.getString(hold ? R.string.interception_hold_completed
                : R.string.interception_word_completed), content.getText().toString());
        android.widget.ProgressBar progress = activity.findViewById(progressId);
        assertTrue(progress.isShown());
        assertEquals(progress.getMax(), progress.getProgress());
        if (hold) assertFalse(content.isEnabled());
        else {
            assertTrue(activity.findViewById(R.id.interception_word_choices).isShown());
            assertArrayEquals(expectedOptions, wordChoiceTexts(activity));
            for (int id : wordChoiceIds()) assertFalse(activity.findViewById(id).isEnabled());
        }
        android.widget.LinearLayout actions = activity.findViewById(hold
                ? R.id.interception_hold_actions : R.id.interception_word_actions);
        assertEquals(2, actions.getChildCount());
        assertEquals(exitId, actions.getChildAt(0).getId());
        assertEquals(continueId, actions.getChildAt(1).getId());
        assertTrue(actions.getChildAt(0).isEnabled());
        assertTrue(actions.getChildAt(1).isEnabled());
        String[] pool = activity.getResources().getStringArray(R.array.interception_continue_actions);
        assertTrue(java.util.Arrays.asList(pool).contains(((TextView) actions.getChildAt(1)).getText().toString()));
    }

    /** 返回固定六选一的控件标识，供内容保留和实际答题共用。 */
    private static int[] wordChoiceIds() {
        return new int[]{R.id.interception_word_choice_1, R.id.interception_word_choice_2,
                R.id.interception_word_choice_3, R.id.interception_word_choice_4,
                R.id.interception_word_choice_5, R.id.interception_word_choice_6};
    }

    /**
     * 记录当前一题的选项，用于完成后和系统重建后的内容核对。
     * @param activity 当前单词挑战页面
     * @return 当前六个选项的文字
     */
    private static String[] wordChoiceTexts(InterceptionActivity activity) {
        int[] ids = wordChoiceIds();
        String[] texts = new String[ids.length];
        for (int index = 0; index < ids.length; index++) {
            texts[index] = ((TextView) activity.findViewById(ids[index])).getText().toString();
        }
        return texts;
    }

    /** 两类触发、两种挑战完成后直接二次询问；动画中重建仍保持双出口，主动选择才放行。 */
    @Test public void repairCompletionLeadsDirectlyToFinalChoiceAndSurvivesRecreation() {
        Context context = context();
        InteractionSettingsStore settings = new InteractionSettingsStore(context, new AppConfig(context));
        SecondsRange oldRange = settings.holdRange(InterceptionMode.REPAIR);
        WordChallengeSettings oldWord = settings.wordSettings(InterceptionMode.REPAIR);
        try {
            settings.setHoldRange(InterceptionMode.REPAIR, new SecondsRange(200L, 200L));
            settings.setWordSettings(InterceptionMode.REPAIR, new WordChallengeSettings(1, 100L));
            for (InterceptionDecision.Kind kind : InterceptionDecision.Kind.values()) {
                for (InterceptionStage stage : new InterceptionStage[]{InterceptionStage.HOLD, InterceptionStage.WORD_TRANSLATION}) {
                    boolean hold = stage == InterceptionStage.HOLD;
                    int titleId = hold ? R.id.interception_hold_title : R.id.interception_word_title;
                    int continueId = R.id.interception_confirmation_continue;
                    String[] text = new String[3];
                    try (ActivityScenario<InterceptionActivity> scenario = ActivityScenario.launch(
                            InterceptionActivity.debugIntent(context, kind,
                                    ControlLevel.REPAIR, stage, "completion-" + kind + stage))) {
                        awaitPage(scenario, hold ? R.id.interception_hold_button : R.id.interception_word_choice_1);
                        scenario.onActivity(activity -> {
                            text[0] = ((TextView) activity.findViewById(titleId)).getText().toString();
                            assertNull(activity.findViewById(continueId));
                            InterceptionAssertions.assertNoHeader(activity);
                            android.widget.LinearLayout actions = activity.findViewById(hold
                                    ? R.id.interception_hold_actions : R.id.interception_word_actions);
                            assertEquals(1, actions.getChildCount());
                            if (hold) press(activity);
                        });
                        SystemClock.sleep(350L);
                        if (!hold) scenario.onActivity(ChallengeCompletionTest::answerWord);
                        awaitPage(scenario, continueId);
                        scenario.onActivity(activity -> {
                            assertNotNull(activity.findViewById(R.id.interception_page_confirmation));
                            assertNull(activity.findViewById(titleId));
                            assertTrue(activity.findViewById(continueId).isShown());
                            text[1] = ((TextView) activity.findViewById(R.id.interception_confirmation_title)).getText().toString();
                            text[2] = ((TextView) activity.findViewById(continueId)).getText().toString();
                            assertNotEquals(text[0], text[1]);
                            assertFalse(activity.isFinishing());
                        });
                        scenario.recreate();
                        scenario.onActivity(activity -> {
                            assertEquals(text[1], ((TextView) activity.findViewById(R.id.interception_confirmation_title)).getText().toString());
                            assertEquals(text[2], ((TextView) activity.findViewById(continueId)).getText().toString());
                            assertNotNull(activity.findViewById(R.id.interception_page_confirmation));
                            assertEquals(1f, activity.findViewById(R.id.interception_page_confirmation).getAlpha(), 0f);
                            assertEquals(2, ((android.widget.LinearLayout) activity.findViewById(R.id.interception_confirmation_actions)).getChildCount());
                            assertFalse(activity.isFinishing());
                            activity.findViewById(continueId).performClick();
                            assertTrue(activity.isFinishing());
                        });
                    }
                }
            }
        } finally {
            settings.setHoldRange(InterceptionMode.REPAIR, oldRange);
            settings.setWordSettings(InterceptionMode.REPAIR, oldWord);
        }
    }

    /** 二次询问不重置提醒的休息时钟；归零仍留页，隐藏倒计时和稳定双出口不变。 */
    @Test public void reminderCompletionKeepsOriginalRestDeadline() {
        Context context = context();
        InteractionSettingsStore settings = new InteractionSettingsStore(context, new AppConfig(context));
        SecondsRange old = settings.holdRange(InterceptionMode.REPAIR);
        settings.setHoldRange(InterceptionMode.REPAIR, new SecondsRange(100L, 100L));
        try (ActivityScenario<InterceptionActivity> scenario = ActivityScenario.launch(
                InterceptionActivity.debugIntent(context, InterceptionDecision.Kind.INTERRUPT,
                        ControlLevel.REPAIR, InterceptionStage.HOLD, "completion-rest")
                        .putExtra("interception.interrupt_pause", 4_000L))) {
            awaitPage(scenario, R.id.interception_hold_button);
            scenario.onActivity(ChallengeCompletionTest::press);
            awaitPage(scenario, R.id.interception_confirmation_title);
            String[] headline = new String[1];
            scenario.onActivity(activity -> {
                assertNotNull(activity.findViewById(R.id.interception_confirmation_continue));
                assertNull(activity.findViewById(R.id.interception_hold_continue));
                assertFalse(activity.findViewById(R.id.interception_countdown).isShown());
                headline[0] = ((TextView) activity.findViewById(R.id.interception_confirmation_title)).getText().toString();
            });
            scenario.recreate();
            scenario.onActivity(activity -> {
                assertEquals(headline[0], ((TextView) activity.findViewById(R.id.interception_confirmation_title)).getText().toString());
                assertNull(activity.findViewById(R.id.interception_hold_button));
            });
            boolean[] ready = {false};
            long deadline = SystemClock.elapsedRealtime() + 5_000L;
            while (!ready[0] && SystemClock.elapsedRealtime() < deadline) {
                scenario.onActivity(activity -> ready[0] = "0 秒".contentEquals(
                        ((TextView) activity.findViewById(R.id.interception_countdown)).getText()));
                SystemClock.sleep(50L);
            }
            assertTrue(ready[0]);
            scenario.onActivity(activity -> {
                assertFalse(activity.isFinishing());
                assertEquals("0 秒", ((TextView) activity.findViewById(R.id.interception_countdown)).getText().toString());
                assertEquals(headline[0], ((TextView) activity.findViewById(R.id.interception_confirmation_title)).getText().toString());
                assertFalse(activity.findViewById(R.id.interception_countdown).isShown());
                assertNotNull(activity.findViewById(R.id.interception_confirmation_continue));
            });
        } finally {
            settings.setHoldRange(InterceptionMode.REPAIR, old);
        }
    }

    /** @param activity 当前按压页，发送真实按下输入 */
    private static void press(InterceptionActivity activity) {
        View button = activity.findViewById(R.id.interception_hold_button);
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 20f, 20f, 0);
        try { button.dispatchTouchEvent(event); } finally { event.recycle(); }
    }

    /** @param activity 单题挑战，遍历直到正确答案推进为完成态 */
    private static void answerWord(InterceptionActivity activity) {
        for (int id : wordChoiceIds()) {
            View option = activity.findViewById(id);
            if (option != null && option.isShown() && option.isEnabled()) option.performClick();
        }
    }

    /** 最后一题触发真实过渡，动画期间不误触放行，结束后新操作正常生效。 */
    @Test public void challengeAnimationBlocksAccidentalGrantUntilFinalChoiceAppears() {
        Context context = context();
        InteractionSettingsStore settings = new InteractionSettingsStore(context, new AppConfig(context));
        WordChallengeSettings original = settings.wordSettings(InterceptionMode.REPAIR);
        settings.setWordSettings(InterceptionMode.REPAIR, new WordChallengeSettings(1, 100L));
        try (ActivityScenario<InterceptionActivity> scenario = ActivityScenario.launch(
                InterceptionActivity.debugIntent(context, InterceptionDecision.Kind.BLOCK,
                        ControlLevel.REPAIR, InterceptionStage.WORD_TRANSLATION, "animated-choice"))) {
            awaitPage(scenario, R.id.interception_word_choice_1);
            SystemClock.sleep(200L);
            scenario.onActivity(activity -> {
                answerWord(activity);
                View confirmation = activity.findViewById(R.id.interception_page_confirmation);
                assertNotNull(confirmation);
                if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
                    assertEquals(0f, confirmation.getAlpha(), 0f);
                    activity.findViewById(R.id.interception_confirmation_continue).performClick();
                    assertFalse(activity.isFinishing());
                }
            });
            boolean[] visible = {false};
            long deadline = SystemClock.elapsedRealtime() + 5_000L;
            while (!visible[0] && SystemClock.elapsedRealtime() < deadline) {
                scenario.onActivity(activity -> visible[0] = activity.findViewById(R.id.interception_page_confirmation).getAlpha() == 1f);
                SystemClock.sleep(20L);
            }
            assertTrue(visible[0]);
            scenario.onActivity(activity -> {
                activity.findViewById(R.id.interception_confirmation_continue).performClick();
                assertTrue(activity.isFinishing());
            });
        } finally {
            settings.setWordSettings(InterceptionMode.REPAIR, original);
        }
    }

    /** 返回真实目标应用上下文。 */
    private static Context context() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ((github.vhoom.zhijian.ZhijianApplication) context.getApplicationContext()).ready().join();
        return context;
    }

    /** 显式预览请求重新选页，系统重建仍保留当前完成态。 */
    @Test public void newPreviewRequestChangesPageAndRecreationKeepsCompletion() {
        Context context = context();
        try (ActivityScenario<InterceptionActivity> scenario = ActivityScenario.launch(
                InterceptionActivity.debugIntent(context, InterceptionDecision.Kind.BLOCK,
                        ControlLevel.REPAIR, InterceptionStage.INITIAL, "preview-switch"))) {
            awaitPage(scenario, R.id.interception_general_title);
            scenario.onActivity(activity -> {
                activity.onNewIntent(InterceptionActivity.debugIntent(context, InterceptionDecision.Kind.BLOCK,
                        ControlLevel.REPAIR, InterceptionStage.FINAL_CONFIRM, "preview-switch"));
                assertNotNull(activity.findViewById(R.id.interception_page_confirmation));
                assertNull(activity.findViewById(R.id.interception_hold_button));
            });
            scenario.recreate();
            scenario.onActivity(activity -> assertNotNull(activity.findViewById(R.id.interception_page_confirmation)));
        }
    }

    /** 服务重发同会话请求时保留原文案编号，主句和按钮一起保持稳定。 */
    @Test public void productionReentryPreservesGroupedCopyVariant() {
        Context context = context();
        InterceptionDecision decision = new InterceptionDecision("test.stable-copy", "test", 1L,
                InterceptionDecision.Kind.BLOCK, InterceptionDecision.Trigger.WINDOW_BAN,
                ControlLevel.MORTAL, "window", 1L, 1L, System.currentTimeMillis() + 60_000L, 60_000L, 0L);
        String[] copy = new String[3];
        String session = github.vhoom.zhijian.fixture.InitialPageSessions.ordinary("stable-copy");
        try (ActivityScenario<InterceptionActivity> scenario = ActivityScenario.launch(
                InterceptionActivity.intent(context, decision, "测试应用", session)
                        .putExtra("interception.copy_variant", 101))) {
            awaitPage(scenario, R.id.interception_general_title);
            scenario.onActivity(activity -> {
                copy[0] = ((TextView) activity.findViewById(R.id.interception_general_title)).getText().toString();
                copy[1] = ((TextView) activity.findViewById(R.id.interception_general_exit)).getText().toString();
                copy[2] = ((TextView) activity.findViewById(R.id.interception_general_continue)).getText().toString();
                activity.onNewIntent(InterceptionActivity.intent(context, decision, "测试应用", session)
                        .putExtra("interception.copy_variant", 102));
                assertEquals(copy[0], ((TextView) activity.findViewById(R.id.interception_general_title)).getText().toString());
                assertEquals(copy[1], ((TextView) activity.findViewById(R.id.interception_general_exit)).getText().toString());
                assertEquals(copy[2], ((TextView) activity.findViewById(R.id.interception_general_continue)).getText().toString());
            });
        }
    }

    /** @param scenario 运行场景 @param id 页面就绪控件；等待后台资源检查结束 */
    private static void awaitPage(ActivityScenario<InterceptionActivity> scenario, int id) {
        boolean[] ready = {false};
        long deadline = SystemClock.elapsedRealtime() + 10_000L;
        while (!ready[0] && SystemClock.elapsedRealtime() < deadline) {
            scenario.onActivity(activity -> {
                View view = activity.findViewById(id);
                ready[0] = view != null && view.isShown() && view.getAlpha() == 1f;
            });
            SystemClock.sleep(20L);
        }
        assertTrue("页面初始化未完成", ready[0]);
    }
}
