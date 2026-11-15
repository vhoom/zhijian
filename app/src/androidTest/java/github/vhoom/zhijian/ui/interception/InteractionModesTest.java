package github.vhoom.zhijian.ui.interception;

import android.content.Context;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Switch;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import github.vhoom.zhijian.R;
import github.vhoom.zhijian.data.model.interception.InterceptionChallenge;
import github.vhoom.zhijian.data.model.interception.InterceptionMode;
import github.vhoom.zhijian.data.model.policy.ControlLevel;
import github.vhoom.zhijian.data.model.settings.InteractionSettings;
import github.vhoom.zhijian.data.model.settings.WordChallengeSettings;
import github.vhoom.zhijian.data.model.word.BuiltInWordCatalog;
import github.vhoom.zhijian.data.store.ZhijianStorage;
import github.vhoom.zhijian.domain.interception.word.WordQuizSession;
import github.vhoom.zhijian.data.store.settings.InteractionSettingsStore;
import github.vhoom.zhijian.domain.interception.InterceptionDecision;
import github.vhoom.zhijian.ui.activity.SettingsActivity;
import github.vhoom.zhijian.util.config.AppConfig;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.matcher.ViewMatchers.withId;

/**
 * 通过设置接口与真实页面验证独立挑战、最后一个开关保护、空配置兜底和重建行为。
 *
 * 维护日期：2026年11月14日
 */
public class InteractionModesTest {
    /** 关闭动态按钮落盘并冻结在页面，设置改回开启后系统重建仍保留两个固定普通按钮。 */
    @Test public void fixedWuweiButtonsSurviveSettingsChangeAndRecreation() {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        InteractionSettingsStore store = new InteractionSettingsStore(app, new AppConfig(app));
        boolean original = store.readOptions(InterceptionMode.WUWEI).dynamicButtonsEnabled;
        try {
            store.setDynamicButtonsEnabled(false);
            assertFalse(new InteractionSettingsStore(app, new AppConfig(app)).readOptions(InterceptionMode.WUWEI).dynamicButtonsEnabled);
            try (ActivityScenario<InterceptionActivity> scenario = ActivityScenario.launch(
                    InterceptionActivity.debugIntent(app, InterceptionDecision.Kind.BLOCK, ControlLevel.MORTAL, "fixed-wuwei"))) {
                store.setDynamicButtonsEnabled(true);
                scenario.recreate();
                scenario.onActivity(activity -> {
                    android.widget.LinearLayout actions = activity.findViewById(R.id.interception_general_actions);
                    assertEquals(2, actions.getChildCount());
                    assertEquals(R.id.interception_general_continue, actions.getChildAt(0).getId());
                    assertEquals(R.id.interception_general_exit, actions.getChildAt(1).getId());
                    for (int slot = 0; slot < 2; slot++) {
                        assertTrue(actions.getChildAt(slot) instanceof android.widget.Button);
                        assertFalse(actions.getChildAt(slot) instanceof github.vhoom.zhijian.ui.interception.page.support.SlideActionView);
                    }
                });
            }
        } finally { store.setDynamicButtonsEnabled(original); }
    }
    private Context context;
    private InteractionSettingsStore settings;
    private InteractionSettings originalRepair;

    /** 保存修心的原始配置，测试只更改自己的交互参数。 */
    @Before public void prepare() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ((github.vhoom.zhijian.ZhijianApplication) context.getApplicationContext()).ready().join();
        settings = new InteractionSettingsStore(context, new AppConfig(context));
        originalRepair = settings.read(InterceptionMode.REPAIR);
        settings.setSudokuDifficulty(github.vhoom.zhijian.data.model.settings.SudokuDifficulty.EASY);
        settings.setEnabled(InterceptionMode.REPAIR, InterceptionChallenge.BUTTON_HOLD, true);
        settings.setEnabled(InterceptionMode.REPAIR, InterceptionChallenge.WORD_TRANSLATION, true);
        settings.setEnabled(InterceptionMode.REPAIR, InterceptionChallenge.SUDOKU, false);
        settings.setObstaclePagesEnabled(true);
        settings.setTemporaryPassEnabled(InterceptionMode.REPAIR, true);
        settings.setConfirmationEnabled(true);
    }

    /** 恢复修心的开关和参数，避免影响后续测试。 */
    @After public void restore() {
        restoreMode(InterceptionMode.REPAIR, originalRepair);
    }

    /**
     * 恢复一种模式的独立配置。
     * @param mode 原配置所属模式
     * @param original 原配置快照
     */
    private void restoreMode(InterceptionMode mode, InteractionSettings original) {
        settings.setSudokuDifficulty(original.sudokuDifficulty);
        settings.setHoldRange(mode, original.holdRange);
        settings.setWordSettings(mode, original.word);
        settings.setTemporaryPassEnabled(mode, original.options.temporaryPassEnabled);
        settings.setObstaclePagesEnabled(original.options.obstaclePagesEnabled);
        settings.setConfirmationEnabled(original.options.confirmationEnabled);
        // 先开启原选择，确保跨越“仅按压”和“仅单词”时不会触发最后一个保护。
        for (InterceptionChallenge challenge : original.enabledChallenges) {
            settings.setEnabled(mode, challenge, true);
        }
        for (InterceptionChallenge challenge : InterceptionChallenge.values()) {
            settings.setEnabled(mode, challenge, original.enabledChallenges.contains(challenge));
        }
    }

    /**
     * 模拟全部关闭的持久化配置，验证按压挑战兜底。
     * @param mode 需要回退的模式
     */
    private void disableAll(InterceptionMode mode) {
        android.content.SharedPreferences.Editor editor = context.getSharedPreferences("global_settings", Context.MODE_PRIVATE).edit();
        for (InterceptionChallenge challenge : InterceptionChallenge.values()) {
            editor.putBoolean(mode.name() + ".challenge." + challenge.name() + ".enabled", false);
        }
        editor.commit();
    }

    /** 空快照及空挑战选择在系统重建时回退按压，原时长和题量不丢失。 */
    @Test public void emptySessionRestoresHoldChallenge() {
        InteractionSettings current = settings.read(InterceptionMode.REPAIR);
        InteractionSettings emptySettings = new InteractionSettings(java.util.EnumSet.noneOf(InterceptionChallenge.class),
                current.holdRange, current.word, current.options);
        android.os.Bundle state = new android.os.Bundle();
        github.vhoom.zhijian.ui.interception.session.InteractionSessionState.save(state, emptySettings, null);
        InteractionSettings restored = github.vhoom.zhijian.ui.interception.session.InteractionSessionState.restore(state, current);
        assertEquals(InterceptionChallenge.BUTTON_HOLD,
                github.vhoom.zhijian.ui.interception.session.InteractionSessionState.restoreChallenge(state, restored, "emptySettings"));
        assertEquals(current.holdRange.minMillis, restored.holdRange.minMillis);
        assertEquals(current.word.questionCount, restored.word.questionCount);
        state.putString("interception.interaction.challenge", InterceptionChallenge.WORD_TRANSLATION.name());
        assertEquals(InterceptionChallenge.BUTTON_HOLD,
                github.vhoom.zhijian.ui.interception.session.InteractionSessionState.restoreChallenge(state, restored, "emptySettings"));
    }

    /** 新设置不改写原会话；Bundle 恢复流程开关及挑战，损坏开关回退合法配置。 */
    @Test public void sessionRestoresItsOptionsAfterSettingsChange() {
        settings.setTemporaryPassEnabled(InterceptionMode.REPAIR, false);
        settings.setObstaclePagesEnabled(false);
        settings.setConfirmationEnabled(false);
        settings.setSudokuDifficulty(github.vhoom.zhijian.data.model.settings.SudokuDifficulty.HARD);
        InteractionSettings frozen = settings.read(InterceptionMode.REPAIR);
        android.os.Bundle state = new android.os.Bundle();
        github.vhoom.zhijian.ui.interception.session.InteractionSessionState.save(state, frozen, null);
        settings.setTemporaryPassEnabled(InterceptionMode.REPAIR, true);
        settings.setObstaclePagesEnabled(true);
        settings.setConfirmationEnabled(true);
        settings.setSudokuDifficulty(github.vhoom.zhijian.data.model.settings.SudokuDifficulty.EASY);
        InteractionSettings current = settings.read(InterceptionMode.REPAIR);
        InteractionSettings restored = github.vhoom.zhijian.ui.interception.session.InteractionSessionState.restore(state, current);
        assertFalse(restored.options.temporaryPassEnabled);
        assertFalse(restored.options.obstaclePagesEnabled);
        assertFalse(restored.options.confirmationEnabled);
        assertEquals(github.vhoom.zhijian.data.model.settings.SudokuDifficulty.HARD, restored.sudokuDifficulty);
        assertNull(github.vhoom.zhijian.ui.interception.session.InteractionSessionState.restoreChallenge(state, restored, "frozen"));
        state.putString("interception.interaction.temporaryPass", "corrupt");
        assertSame(current, github.vhoom.zhijian.ui.interception.session.InteractionSessionState.restore(state, current));
        state.putBoolean("interception.interaction.temporaryPass", false);
        state.putString("interception.interaction.confirmation", "corrupt");
        assertSame(current, github.vhoom.zhijian.ui.interception.session.InteractionSessionState.restore(state, current));
    }

    /** 实际页面重建保留关闭的阻碍开关，不因设置改回开启而重新插入挑战或反问。 */
    @Test public void disabledObstaclePageSurvivesRecreationAndContinuesDirectly() {
        settings.setObstaclePagesEnabled(false);
        settings.setTemporaryPassEnabled(InterceptionMode.REPAIR, false);
        try (ActivityScenario<InterceptionActivity> scenario = ActivityScenario.launch(
                InterceptionActivity.debugIntent(context, InterceptionDecision.Kind.INTERRUPT,
                        ControlLevel.REPAIR, "no-obstacles").putExtra("interception.interrupt_pause", 60_000L))) {
            settings.setObstaclePagesEnabled(true);
            settings.setTemporaryPassEnabled(InterceptionMode.REPAIR, true);
            scenario.recreate();
            scenario.onActivity(activity -> {
                assertNotNull(activity.findViewById(R.id.interception_page_general));
                activity.findViewById(R.id.interception_general_continue).performClick();
                assertTrue(activity.isFinishing());
                assertNull(activity.findViewById(R.id.interception_page_hold));
                assertNull(activity.findViewById(R.id.interception_page_confirmation));
            });
        }
    }

    /** 修心空摩擦配置进入按压挑战，不能直接跳到最终确认。 */
    @Test public void repairEmptyConfigurationUsesHoldChallenge() {
        disableAll(InterceptionMode.REPAIR);
        try (ActivityScenario<InterceptionActivity> scenario = ActivityScenario.launch(
                InterceptionActivity.debugIntent(context, InterceptionDecision.Kind.BLOCK, ControlLevel.REPAIR, "no-repair"))) {
            scenario.onActivity(activity -> {
                assertNotNull(activity.findViewById(R.id.interception_page_general));
                activity.findViewById(R.id.interception_general_continue).performClick();
                assertFalse(activity.isFinishing());
                assertNull(activity.findViewById(R.id.interception_page_confirmation));
                assertNotNull(activity.findViewById(R.id.interception_page_hold));
                assertNull(activity.findViewById(R.id.interception_page_word));
            });
            scenario.recreate();
            scenario.onActivity(activity -> assertNotNull(activity.findViewById(R.id.interception_page_hold)));
        }
    }

    /** 提醒同样使用修心首屏，进入挑战时保留原休息期限。 */
    @Test public void reminderUsesRepairFirstPageAndKeepsRestDeadline() {
        disableAll(InterceptionMode.REPAIR);
        try (ActivityScenario<InterceptionActivity> scenario = ActivityScenario.launch(
                InterceptionActivity.debugIntent(context, InterceptionDecision.Kind.INTERRUPT,
                        ControlLevel.REPAIR, "no-interrupt")
                        .putExtra("interception.interrupt_pause", 60_000L))) {
            scenario.onActivity(activity -> {
                assertNotNull(activity.findViewById(R.id.interception_page_general));
                assertTrue(activity.findViewById(R.id.interception_general_continue).isShown());
                activity.findViewById(R.id.interception_general_continue).performClick();
                assertNotNull(activity.findViewById(R.id.interception_page_hold));
                assertFalse(activity.isFinishing());
            });
            scenario.recreate();
            scenario.onActivity(activity -> {
                assertNotNull(activity.findViewById(R.id.interception_page_hold));
                assertNotEquals(activity.getString(R.string.interception_rest_seconds, 0),
                        ((TextView) activity.findViewById(R.id.interception_countdown)).getText().toString());
            });
        }
    }

    /** 禁用和提醒共用修心挑战设置，只开启单词时不会补回按压。 */
    @Test public void triggerKindsShareControlLevelChallengeSettings() {
        disableAll(InterceptionMode.REPAIR);
        settings.setEnabled(InterceptionMode.REPAIR, InterceptionChallenge.WORD_TRANSLATION, true);
        settings.setEnabled(InterceptionMode.REPAIR, InterceptionChallenge.BUTTON_HOLD, false);
        for (InterceptionDecision.Kind kind : InterceptionDecision.Kind.values()) {
            try (ActivityScenario<InterceptionActivity> scenario = ActivityScenario.launch(
                    InterceptionActivity.debugIntent(context, kind, ControlLevel.REPAIR,
                            wordSession(InterceptionMode.REPAIR, "own-" + kind)))) {
                scenario.onActivity(activity -> {
                    assertNotNull(activity.findViewById(R.id.interception_page_general));
                    activity.findViewById(R.id.interception_general_continue).performClick();
                    assertNotNull(activity.findViewById(R.id.interception_page_word));
                    assertNull(activity.findViewById(R.id.interception_page_hold));
                });
            }
        }
    }

    /** 设置变化只影响新会话，单词页面重建保留原题量、题目和挑战配置。 */
    @Test public void recreationKeepsActiveWordSessionDespiteSettingsChanges() {
        disableAll(InterceptionMode.REPAIR);
        settings.setEnabled(InterceptionMode.REPAIR, InterceptionChallenge.WORD_TRANSLATION, true);
        settings.setEnabled(InterceptionMode.REPAIR, InterceptionChallenge.BUTTON_HOLD, false);
        WordChallengeSettings wordSettings = new WordChallengeSettings(3, 60_000L, BuiltInWordCatalog.CET4);
        settings.setWordSettings(InterceptionMode.REPAIR, wordSettings);
        String sessionId = wordSession(InterceptionMode.REPAIR, "stable-config");
        String[] before = new String[2];
        try (ActivityScenario<InterceptionActivity> scenario = ActivityScenario.launch(
                InterceptionActivity.debugIntent(context, InterceptionDecision.Kind.INTERRUPT, ControlLevel.REPAIR, sessionId))) {
            scenario.onActivity(activity -> {
                activity.findViewById(R.id.interception_general_continue).performClick();
                before[0] = ((TextView) activity.findViewById(R.id.interception_word_prompt)).getText().toString();
                assertTrue(before[0].contains(WordQuizSession.start(
                        ZhijianStorage.get(context).wordCatalog(BuiltInWordCatalog.CET4),
                        wordSettings, sessionId, 0L).currentQuestion().prompt));
                before[1] = ((TextView) activity.findViewById(R.id.interception_word_status)).getText().toString();
            });
            disableAll(InterceptionMode.REPAIR);
            settings.setWordSettings(InterceptionMode.REPAIR, new WordChallengeSettings(1, 100L, BuiltInWordCatalog.SAT));
            scenario.recreate();
            scenario.onActivity(activity -> {
                assertNotNull(activity.findViewById(R.id.interception_page_word));
                assertEquals(before[0], ((TextView) activity.findViewById(R.id.interception_word_prompt)).getText().toString());
                assertTrue(((TextView) activity.findViewById(R.id.interception_word_status)).getText().toString().contains("3"));
            });
        }
    }

    /** 修心提醒执行随机按压区间，重建后仍按原区间执行，完成按压不缩短休息期限。 */
    @Test public void repairReminderHoldRangeSurvivesRecreationAndCompletesWithinMaximum() {
        settings.setEnabled(InterceptionMode.REPAIR, InterceptionChallenge.BUTTON_HOLD, true);
        settings.setEnabled(InterceptionMode.REPAIR, InterceptionChallenge.WORD_TRANSLATION, false);
        settings.setHoldRange(InterceptionMode.REPAIR,
                new github.vhoom.zhijian.data.model.settings.SecondsRange(1_200L, 2_200L));
        try (ActivityScenario<InterceptionActivity> scenario = ActivityScenario.launch(
                InterceptionActivity.debugIntent(context, InterceptionDecision.Kind.INTERRUPT,
                        ControlLevel.REPAIR, "range-recreation")
                        .putExtra("interception.interrupt_pause", 60_000L))) {
            scenario.onActivity(activity -> activity.findViewById(R.id.interception_general_continue).performClick());
            settings.setHoldRange(InterceptionMode.REPAIR,
                    new github.vhoom.zhijian.data.model.settings.SecondsRange(60_000L, 60_000L));
            scenario.recreate();
            scenario.onActivity(activity -> {
                View button = activity.findViewById(R.id.interception_hold_button);
                long now = SystemClock.uptimeMillis();
                MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN,
                        button.getWidth() / 2f, button.getHeight() / 2f, 0);
                try { button.dispatchTouchEvent(down); }
                finally { down.recycle(); }
            });
            SystemClock.sleep(400L);
            scenario.onActivity(activity -> assertTrue(activity.findViewById(R.id.interception_hold_button).isShown()));
            SystemClock.sleep(2_400L);
            scenario.onActivity(activity -> {
                assertNotNull(activity.findViewById(R.id.interception_page_confirmation));
                assertNull(activity.findViewById(R.id.interception_hold_button));
                assertNull(activity.findViewById(R.id.interception_hold_continue));
                assertFalse(activity.isFinishing());
            });
        }
    }

    /** 词库选择保存并重建恢复，返回不提交未完成的参数与词库草稿。 */
    @Test public void catalogSelectionPersistsAndBackCancelsDraft() {
        settings.setWordSettings(InterceptionMode.REPAIR,
                new WordChallengeSettings(3, 2_000L, BuiltInWordCatalog.JUNIOR_HIGH));
        try (ActivityScenario<SettingsActivity> scenario = ActivityScenario.launch(
                new android.content.Intent(context, SettingsActivity.class))) {
            scenario.onActivity(activity -> activity.findViewById(R.id.row_word_challenge).performClick());
            onView(withId(R.id.field_word_catalog)).perform(click());
            androidx.test.espresso.Espresso.onData(org.hamcrest.Matchers.is(
                    context.getString(R.string.word_catalog_cet4)))
                    .inRoot(androidx.test.espresso.matcher.RootMatchers.isPlatformPopup()).perform(click());
            onView(withId(R.id.settings_sheet_done)).perform(click());
            assertEquals(BuiltInWordCatalog.CET4, settings.wordSettings(InterceptionMode.REPAIR).catalog);
            scenario.recreate();
            scenario.onActivity(activity -> assertTrue(((TextView)
                    activity.findViewById(R.id.word_challenge_value)).getText().toString()
                    .contains(context.getString(R.string.word_catalog_cet4))));

            scenario.onActivity(activity -> activity.findViewById(R.id.row_word_challenge).performClick());
            onView(withId(R.id.field_word_question_count)).perform(replaceText("1"), closeSoftKeyboard());
            onView(withId(R.id.field_word_catalog)).perform(click());
            androidx.test.espresso.Espresso.onData(org.hamcrest.Matchers.is("SAT"))
                    .inRoot(androidx.test.espresso.matcher.RootMatchers.isPlatformPopup()).perform(click());
            onView(withId(R.id.field_word_question_count)).check((view, missing) ->
                    assertEquals("1", ((android.widget.EditText) view).getText().toString()));
            androidx.test.espresso.Espresso.pressBack();
            assertEquals(BuiltInWordCatalog.CET4, settings.wordSettings(InterceptionMode.REPAIR).catalog);
            assertEquals(3, settings.wordSettings(InterceptionMode.REPAIR).questionCount);
        }
    }

    /** 三种挑战独立开关、最后一个不可关闭，收起及重建保留选择，开启另一项后解除锁定。 */
    @Test public void repairChallengeSwitchesPersistAndProtectLastEnabled() {
        for (InterceptionChallenge only : InterceptionChallenge.values()) {
            for (InterceptionChallenge challenge : InterceptionChallenge.values()) {
                settings.setEnabled(InterceptionMode.REPAIR, challenge, true);
            }
            int otherId = only == InterceptionChallenge.BUTTON_HOLD
                    ? R.id.switch_repair_word : R.id.switch_repair_hold;
            try (ActivityScenario<SettingsActivity> scenario = ActivityScenario.launch(SettingsActivity.class)) {
                scenario.onActivity(activity -> {
                    assertTrue(activity.findViewById(R.id.switch_repair_hold).isEnabled());
                    assertTrue(activity.findViewById(R.id.switch_repair_word).isEnabled());
                    for (InterceptionChallenge candidate : InterceptionChallenge.values()) {
                        if (candidate != only) activity.findViewById(challengeSwitchId(candidate)).performClick();
                    }
                    assertOnlyChallengeEnabled(activity, only);
                });
                settings.setEnabled(InterceptionMode.REPAIR, only, false);
                InteractionSettings reopened = new InteractionSettingsStore(context, new AppConfig(context))
                        .read(InterceptionMode.REPAIR);
                assertEquals(java.util.EnumSet.of(only), reopened.enabledChallenges);
                assertEquals(only, reopened.selectChallenge("single-challenge"));
                scenario.onActivity(activity -> {
                    activity.findViewById(R.id.switch_repair_obstacle_pages).performClick();
                    assertEquals(View.GONE, activity.findViewById(R.id.settings_repair_hold_group).getVisibility());
                });
                scenario.recreate();
                scenario.onActivity(activity -> {
                    assertFalse(((Switch) activity.findViewById(R.id.switch_repair_obstacle_pages)).isChecked());
                    activity.findViewById(R.id.switch_repair_obstacle_pages).performClick();
                    assertEquals(View.VISIBLE, activity.findViewById(R.id.settings_repair_hold_group).getVisibility());
                    assertOnlyChallengeEnabled(activity, only);
                    activity.findViewById(otherId).performClick();
                    assertTrue(activity.findViewById(challengeSwitchId(only)).isEnabled());
                    assertTrue(activity.findViewById(otherId).isEnabled());
                    assertTrue(((Switch) activity.findViewById(challengeSwitchId(only))).isChecked());
                    assertTrue(((Switch) activity.findViewById(otherId)).isChecked());
                    activity.findViewById(otherId).performClick();
                });
                scenario.recreate();
                scenario.onActivity(activity -> assertOnlyChallengeEnabled(activity, only));
            }
        }
    }

    /**
     * 核对唯一启用项的状态、锁定说明以及参数入口；未启用项仍可被打开。
     * @param activity 当前设置页面
     * @param only 唯一启用的挑战
     */
    private static void assertOnlyChallengeEnabled(SettingsActivity activity, InterceptionChallenge only) {
        for (InterceptionChallenge challenge : InterceptionChallenge.values()) {
            Switch toggle = activity.findViewById(challengeSwitchId(challenge));
            assertEquals(challenge == only, toggle.isChecked());
            assertEquals(challenge != only, toggle.isEnabled());
        }
        Switch locked = activity.findViewById(challengeSwitchId(only));
        String title = activity.getString(only == InterceptionChallenge.BUTTON_HOLD ? R.string.settings_challenge_hold_title
                : only == InterceptionChallenge.WORD_TRANSLATION ? R.string.settings_challenge_word_title
                : R.string.settings_challenge_sudoku_title);
        String description = activity.getString(R.string.settings_mode_option_toggle,
                activity.getString(R.string.settings_repair_mode), title);
        assertEquals(activity.getString(R.string.settings_last_challenge_toggle, description),
                locked.getContentDescription().toString());
        assertEquals(activity.getColor(R.color.switch_enabled),
                locked.getTrackTintList().getColorForState(locked.getDrawableState(), 0));
        assertEquals(activity.getColor(R.color.switch_knob),
                locked.getThumbTintList().getColorForState(locked.getDrawableState(), 0));
        locked.performClick();
        assertTrue(locked.isChecked());
        assertTrue(activity.findViewById(R.id.row_hold_range).isEnabled());
        assertTrue(activity.findViewById(R.id.row_word_challenge).isEnabled());
    }

    /** @param challenge 独立挑战 @return 对应的设置开关 */
    private static int challengeSwitchId(InterceptionChallenge challenge) {
        switch (challenge) {
            case BUTTON_HOLD: return R.id.switch_repair_hold;
            case WORD_TRANSLATION: return R.id.switch_repair_word;
            case SUDOKU: return R.id.switch_repair_sudoku;
            default: throw new AssertionError(challenge);
        }
    }

    /** 难度选择取消不保存，三档确认后摘要只显示难度，重建保持所选值。 */
    @Test public void sudokuDifficultySheetSavesOnlyOnConfirmation() {
        try (ActivityScenario<SettingsActivity> scenario = ActivityScenario.launch(SettingsActivity.class)) {
            scenario.onActivity(activity -> activity.findViewById(R.id.row_sudoku_challenge).performClick());
            onView(withId(R.id.sudoku_difficulty_hard)).perform(click());
            androidx.test.espresso.Espresso.pressBack();
            assertEquals(github.vhoom.zhijian.data.model.settings.SudokuDifficulty.EASY, settings.sudokuDifficulty());
            int[] choices = {R.id.sudoku_difficulty_easy, R.id.sudoku_difficulty_medium, R.id.sudoku_difficulty_hard};
            int[] labels = {R.string.settings_sudoku_easy, R.string.settings_sudoku_medium, R.string.settings_sudoku_hard};
            github.vhoom.zhijian.data.model.settings.SudokuDifficulty[] difficulties =
                    github.vhoom.zhijian.data.model.settings.SudokuDifficulty.values();
            for (int index = 0; index < choices.length; index++) {
                scenario.onActivity(activity -> activity.findViewById(R.id.row_sudoku_challenge).performClick());
                onView(withId(choices[index])).perform(click());
                onView(withId(R.id.settings_sheet_done)).perform(click());
                assertEquals(difficulties[index], new InteractionSettingsStore(context, new AppConfig(context)).sudokuDifficulty());
                scenario.recreate();
                int label = labels[index];
                scenario.onActivity(activity -> assertEquals(activity.getString(label),
                        ((android.widget.TextView) activity.findViewById(R.id.sudoku_difficulty_value)).getText().toString()));
            }
        }
    }

    /**
     * 从当前模式自己的启用集合中选择稳定的单词会话标识。
     * @param mode 单词风暴所属模式
     * @param seed 测试种子
     * @return 选择单词风暴的会话标识
     */
    private String wordSession(InterceptionMode mode, String seed) {
        String session = seed;
        while (settings.read(mode).selectChallenge(session) != InterceptionChallenge.WORD_TRANSLATION) session += "a";
        return session;
    }

    /** 修心单词弹窗保存题量和停留，重建后摘要保持正确。 */
    @Test public void wordDialogPersistsRepairParameters() {
        try (ActivityScenario<SettingsActivity> scenario = ActivityScenario.launch(SettingsActivity.class)) {
            onView(withId(R.id.row_word_challenge)).perform(scrollTo(), click());
            onView(withId(R.id.field_word_question_count)).perform(replaceText("2"));
            onView(withId(R.id.field_word_dwell_seconds)).perform(replaceText("0.3"), closeSoftKeyboard());
            onView(withId(R.id.settings_sheet_done)).perform(click());
            assertEquals(2, settings.wordSettings(InterceptionMode.REPAIR).questionCount);
            assertEquals(300L, settings.wordSettings(InterceptionMode.REPAIR).minDwellMillis);
            scenario.recreate();
            scenario.onActivity(activity -> {
                assertTrue(((TextView) activity.findViewById(R.id.word_challenge_value)).getText().toString().contains("2"));
            });
        }
    }
}
