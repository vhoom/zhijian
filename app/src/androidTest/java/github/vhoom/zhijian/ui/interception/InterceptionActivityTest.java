package github.vhoom.zhijian.ui.interception;

import android.content.Context;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;

import github.vhoom.zhijian.R;
import github.vhoom.zhijian.data.model.interception.InterceptionMode;
import github.vhoom.zhijian.data.model.interception.InterceptionStage;
import github.vhoom.zhijian.data.model.policy.ControlLevel;
import github.vhoom.zhijian.data.model.settings.SecondsRange;
import github.vhoom.zhijian.data.model.settings.WordChallengeSettings;
import github.vhoom.zhijian.data.store.ZhijianStorage;
import github.vhoom.zhijian.data.store.settings.GlobalSettings;
import github.vhoom.zhijian.data.store.settings.InteractionSettingsStore;
import github.vhoom.zhijian.domain.interception.InterceptionDecision;
import github.vhoom.zhijian.fixture.InterceptionAssertions;
import github.vhoom.zhijian.util.config.AppConfig;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 真机启动拦截页面，覆盖 ROM 对全屏 WindowInsets 初始化时机的差异。
 *
 * 维护日期：2026年11月14日
 */
public class InterceptionActivityTest {
    /** 两种摩擦的隐藏休息时钟持续刷新，归零不会跳过挑战。 */
    @Test
    public void restMessageUpdatesAndFinishesOnBothChallengePages() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        for (InterceptionStage stage : new InterceptionStage[]{
                InterceptionStage.HOLD, InterceptionStage.WORD_TRANSLATION}) {
            int messageId = stage == InterceptionStage.HOLD
                    ? R.id.interception_countdown : R.id.interception_countdown;
            int continueId = stage == InterceptionStage.HOLD
                    ? R.id.interception_hold_continue : R.id.interception_word_continue;
            String[] initial = new String[1];
            try (ActivityScenario<InterceptionActivity> scenario = ActivityScenario.launch(
                    InterceptionActivity.debugIntent(context, InterceptionDecision.Kind.INTERRUPT,
                            ControlLevel.REPAIR, stage, "dynamic-rest-" + stage.name())
                            .putExtra("interception.interrupt_pause", 10_000L))) {
                scenario.onActivity(activity -> {
                    TextView message = activity.findViewById(messageId);
                    assertFalse(message.isShown());
                    InterceptionAssertions.assertNoHeader(activity);
                    float baseSize = 13f;
                    assertEquals(android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,
                            baseSize, activity.getResources().getDisplayMetrics()), message.getTextSize(), 0.2f);
                    initial[0] = message.getText().toString();
                    assertTrue(initial[0].contains("秒"));
                    View action = activity.findViewById(continueId);
                    assertTrue(action == null || !action.isShown());
                });
                SystemClock.sleep(1_100L);
                scenario.onActivity(activity -> assertNotEquals(initial[0],
                        ((TextView) activity.findViewById(messageId)).getText().toString()));
                // 页面启动耗时随模拟器负载变化，按实际到期状态等待，避免固定延时误判。
                boolean[] ready = {false};
                long deadline = SystemClock.elapsedRealtime() + 12_000L;
                while (!ready[0] && SystemClock.elapsedRealtime() < deadline) {
                    scenario.onActivity(activity -> {
                        ready[0] = activity.getString(R.string.interception_rest_seconds, 0).equals(
                                ((TextView) activity.findViewById(messageId)).getText().toString());
                    });
                    SystemClock.sleep(50L);
                }
                scenario.onActivity(activity -> {
                    assertFalse(activity.isFinishing());
                    assertNull(activity.findViewById(continueId));
                    assertEquals(activity.getString(R.string.interception_rest_seconds, 0),
                            ((TextView) activity.findViewById(messageId)).getText().toString());
                });
            }
        }
    }

    /** 服务恢复携带的单调起点优先于已变动墙钟，防止重进时提前显示继续。 */
    @Test
    public void resumedPendingIntentUsesOriginalMonotonicPause() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        github.vhoom.zhijian.domain.interception.InterceptionDecision decision = new InterceptionDecision(
                "test.pending-ui", "global", 1L, InterceptionDecision.Kind.INTERRUPT,
                InterceptionDecision.Trigger.CONTINUOUS, ControlLevel.REPAIR, "", 1L, 1L,
                0L, 60_000L, 30_000L);
        github.vhoom.zhijian.data.model.runtime.PendingInterception pending =
                new github.vhoom.zhijian.data.model.runtime.PendingInterception(decision, "pending-ui",
                        github.vhoom.zhijian.util.time.BootIdentity.read(context),
                        System.currentTimeMillis() - 999_999L, SystemClock.elapsedRealtime());
        try (ActivityScenario<InterceptionActivity> scenario = ActivityScenario.launch(
                InterceptionActivity.intent(context, pending, "测试应用")
                        .putExtra("interception.debug_preview", true))) {
            scenario.onActivity(activity -> {
                View holdContinue = activity.findViewById(R.id.interception_hold_continue);
                View wordContinue = activity.findViewById(R.id.interception_word_continue);
                assertTrue(holdContinue == null || !holdContinue.isShown());
                assertTrue(wordContinue == null || !wordContinue.isShown());
            });
            scenario.recreate();
            scenario.onActivity(activity -> {
                View holdContinue = activity.findViewById(R.id.interception_hold_continue);
                View wordContinue = activity.findViewById(R.id.interception_word_continue);
                assertTrue(holdContinue == null || !holdContinue.isShown());
                assertTrue(wordContinue == null || !wordContinue.isShown());
            });
        }
    }
    /** 休息期限已经到达时仍保持当前摩擦页，等待用户主动继续。 */
    @Test
    public void expiredInterruptionKeepsCurrentPage() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (ActivityScenario<InterceptionActivity> scenario = ActivityScenario.launch(
                InterceptionActivity.debugIntent(context, InterceptionDecision.Kind.INTERRUPT,
                        ControlLevel.REPAIR, InterceptionStage.HOLD, "expired-interruption")
                        .putExtra("interception.occurred", System.currentTimeMillis() - 61_000L)
                        .putExtra("interception.started_elapsed", SystemClock.elapsedRealtime() - 61_000L)
                        .putExtra("interception.started_boot", github.vhoom.zhijian.util.time.BootIdentity.read(context)))) {
            scenario.onActivity(activity -> {
                assertFalse(activity.isFinishing());
                assertEquals(View.VISIBLE,
                        activity.findViewById(R.id.interception_page_hold).getVisibility());
                assertEquals(activity.getString(R.string.interception_rest_seconds, 0), ((TextView) activity.findViewById(
                        R.id.interception_countdown)).getText().toString());
                assertEquals(View.VISIBLE,
                        activity.findViewById(R.id.interception_hold_continue).getVisibility());
            });
            scenario.recreate();
            scenario.onActivity(activity -> {
                assertFalse(activity.isFinishing());
                assertEquals(View.VISIBLE,
                        activity.findViewById(R.id.interception_hold_continue).getVisibility());
            });
        }
    }

    /** 提醒按压完成后进入二次询问，重建保留阶段且不提前结束休息。 */
    @Test public void reminderHoldCompletionShowsConfirmation() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        InteractionSettingsStore interactions = new InteractionSettingsStore(context, new AppConfig(context));
        SecondsRange original = interactions.holdRange(InterceptionMode.REPAIR);
        interactions.setHoldRange(InterceptionMode.REPAIR, new SecondsRange(100L, 100L));
        try (ActivityScenario<InterceptionActivity> scenario = ActivityScenario.launch(
                InterceptionActivity.debugIntent(context, InterceptionDecision.Kind.INTERRUPT,
                        ControlLevel.REPAIR, InterceptionStage.HOLD, "reminder-hold-complete")
                        .putExtra("interception.interrupt_pause", 60_000L))) {
            scenario.onActivity(activity -> pressHold(activity, MotionEvent.ACTION_DOWN));
            SystemClock.sleep(700L);
            scenario.onActivity(activity -> {
                assertNotNull(activity.findViewById(R.id.interception_page_confirmation));
                assertNull(activity.findViewById(R.id.interception_hold_button));
                assertFalse(activity.isFinishing());
            });
            scenario.recreate();
            scenario.onActivity(activity -> {
                assertNotNull(activity.findViewById(R.id.interception_page_confirmation));
                assertNotEquals(activity.getString(R.string.interception_rest_seconds, 0),
                        ((TextView) activity.findViewById(R.id.interception_countdown)).getText().toString());
            });
        } finally {
            interactions.setHoldRange(InterceptionMode.REPAIR, original);
        }
    }

    /** 休息完成后点击继续才写入新连续起点，不签发跳过其他规则的通行证。 */
    @Test
    public void productionInterruptionContinuesWithoutBlanketGrant() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ZhijianStorage storage = ZhijianStorage.get(context);
        String packageName = "test.interruption-resume";
        String session = "resume-ui-" + java.util.UUID.randomUUID();
        try (ActivityScenario<InterceptionActivity> scenario = ActivityScenario.launch(
                InterceptionActivity.debugIntent(context, InterceptionDecision.Kind.INTERRUPT,
                        ControlLevel.REPAIR, InterceptionStage.HOLD, "before-resume"))) {
            scenario.onActivity(activity -> {
                InterceptionDecision decision = new InterceptionDecision(packageName, "global",
                        storage.snapshot().revisionOf("global"), InterceptionDecision.Kind.INTERRUPT,
                        InterceptionDecision.Trigger.CONTINUOUS, storage.snapshot().level(),
                        "", 60_000L, 60_000L, 0L, 180_000L, 60_000L);
                activity.onNewIntent(InterceptionActivity.intent(context, decision, "测试应用", session)
                        .putExtra("interception.occurred", System.currentTimeMillis() - 61_000L)
                        .putExtra("interception.started_elapsed", SystemClock.elapsedRealtime() - 61_000L)
                        .putExtra("interception.started_boot", github.vhoom.zhijian.util.time.BootIdentity.read(context)));
                int actionId = activity.findViewById(R.id.interception_page_hold) != null
                        ? R.id.interception_hold_continue : R.id.interception_word_continue;
                assertEquals(View.VISIBLE, activity.findViewById(actionId).getVisibility());
                assertTrue(activity.findViewById(actionId).performClick());
            });
            String key = github.vhoom.zhijian.data.store.RuntimeStateStore.interruptionResumeKey(packageName);
            long deadline = SystemClock.elapsedRealtime() + 5_000L;
            while ((storage.state().runtime.checkpoint(key) == null
                    || !session.equals(storage.state().runtime.checkpoint(key).sessionId))
                    && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20L);
            assertNotNull(storage.state().runtime.checkpoint(key));
            assertEquals(session, storage.state().runtime.checkpoint(key).sessionId);
            assertNull(storage.state().runtime.grantFor(packageName));
        }
    }

    /**
     * 向真实长按按钮发送按下或释放事件。
     *
     * @param activity 当前拦截页面
     * @param action 需要模拟的 MotionEvent 动作
     */
    private static void pressHold(InterceptionActivity activity, int action) {
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, 20f, 20f, 0);
        try {
            activity.findViewById(R.id.interception_hold_button).dispatchTouchEvent(event);
        } finally {
            event.recycle();
        }
    }

    /** singleTask 从预览切换为真实拦截后，点击继续必须实际写入临时放行。 */
    @Test
    public void previewCanReceiveProductionRequestAndPersistGrant() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String packageName = "test.preview-to-production";
        String session = github.vhoom.zhijian.fixture.InitialPageSessions.ordinary(
                "production-after-preview-" + java.util.UUID.randomUUID());
        ZhijianStorage storage = ZhijianStorage.get(context);
        storage.ready().join();
        ControlLevel originalLevel = storage.snapshot().level();
        storage.setLevel(ControlLevel.MORTAL).join();
        InteractionSettingsStore interactions = new InteractionSettingsStore(context, new AppConfig(context));
        boolean originalPass = interactions.readOptions(InterceptionMode.WUWEI).temporaryPassEnabled;
        interactions.setTemporaryPassEnabled(InterceptionMode.WUWEI, true);
        try (ActivityScenario<InterceptionActivity> scenario = ActivityScenario.launch(
                InterceptionActivity.debugIntent(context, InterceptionDecision.Kind.BLOCK,
                        ControlLevel.MORTAL, "preview-before-production"))) {
            scenario.onActivity(activity -> {
                InterceptionDecision decision = new InterceptionDecision(packageName, "global",
                        storage.snapshot().revisionOf("global"),
                        InterceptionDecision.Kind.BLOCK, InterceptionDecision.Trigger.WINDOW_BAN,
                        ControlLevel.MORTAL, "window", 1, 1,
                        System.currentTimeMillis() + 60_000L, 60_000L, 0L);
                activity.onNewIntent(InterceptionActivity.intent(context, decision,
                        "测试应用", session));
                assertNull(activity.findViewById(R.id.interception_preview_launcher));
                assertTrue(activity.findViewById(
                        R.id.interception_general_continue).performClick());
            });
            long deadline = SystemClock.elapsedRealtime() + 5_000L;
            while (storage.state().runtime.grantFor(packageName) == null
                    && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(20L);
            }
            assertNotNull(storage.state().runtime.grantFor(packageName));
        } finally {
            new github.vhoom.zhijian.data.store.RuntimeStateStore(context)
                    .revokeGrant(packageName).join();
            interactions.setTemporaryPassEnabled(InterceptionMode.WUWEI, originalPass);
            storage.setLevel(originalLevel).join();
        }
    }

    /** 验证真实拦截入口能完成布局创建。 */
    @Test
    public void interceptionActivityStartsWithoutCrashing() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        InterceptionDecision decision = new InterceptionDecision("test.target", "global", 1,
                InterceptionDecision.Kind.BLOCK, InterceptionDecision.Trigger.WINDOW_BAN,
                ControlLevel.MORTAL, "window", 1, 1,
                System.currentTimeMillis() + 60_000L, 60_000L, 0L);

        try (ActivityScenario<InterceptionActivity> scenario = ActivityScenario.launch(
                InterceptionActivity.intent(context, decision, "测试应用", "activity-start-test"))) {
            scenario.onActivity(activity ->
                    assertNotNull(activity.findViewById(R.id.interception_root)));
        }
    }

    /** 回归验证全宽操作顺序和文案在同一会话重建后保持一致。 */
    @Test
    public void interceptionActionsStayStableWithinSession() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        int[] orientation = new int[1];
        int[] firstId = new int[1];
        int[] secondId = new int[1];
        int[] firstWidth = new int[1];
        int[] secondWidth = new int[1];
        float[] firstWeight = new float[1];
        float[] secondWeight = new float[1];
        String[] firstText = new String[1];

        try (ActivityScenario<InterceptionActivity> scenario = ActivityScenario.launch(
                InterceptionActivity.debugIntent(context, InterceptionDecision.Kind.BLOCK,
                        ControlLevel.REPAIR, "action-layout-test"))) {
            scenario.onActivity(activity -> {
                LinearLayout actions = activity.findViewById(
                        R.id.interception_general_actions);
                assertEquals(2, actions.getChildCount());
                View first = actions.getChildAt(0);
                View second = actions.getChildAt(1);
                LinearLayout.LayoutParams firstParams =
                        (LinearLayout.LayoutParams) first.getLayoutParams();
                LinearLayout.LayoutParams secondParams =
                        (LinearLayout.LayoutParams) second.getLayoutParams();
                assertTrue(actions.getOrientation() == LinearLayout.HORIZONTAL
                        || actions.getOrientation() == LinearLayout.VERTICAL);
                assertEquals(LinearLayout.VERTICAL, actions.getOrientation());
                assertEquals(LinearLayout.LayoutParams.MATCH_PARENT, firstParams.width);
                assertEquals(LinearLayout.LayoutParams.MATCH_PARENT, secondParams.width);
                assertTrue(((Button) first).getText().length() > 0);
                assertTrue(((Button) second).getText().length() > 0);

                orientation[0] = actions.getOrientation();
                firstId[0] = first.getId();
                secondId[0] = second.getId();
                firstWidth[0] = firstParams.width;
                secondWidth[0] = secondParams.width;
                firstWeight[0] = firstParams.weight;
                secondWeight[0] = secondParams.weight;
                firstText[0] = ((Button) first).getText().toString();
            });

            scenario.recreate();
            scenario.onActivity(activity -> {
                LinearLayout actions = activity.findViewById(
                        R.id.interception_general_actions);
                LinearLayout.LayoutParams firstParams = (LinearLayout.LayoutParams)
                        actions.getChildAt(0).getLayoutParams();
                LinearLayout.LayoutParams secondParams = (LinearLayout.LayoutParams)
                        actions.getChildAt(1).getLayoutParams();
                assertEquals(orientation[0], actions.getOrientation());
                assertEquals(firstId[0], actions.getChildAt(0).getId());
                assertEquals(secondId[0], actions.getChildAt(1).getId());
                assertEquals(firstWidth[0], firstParams.width);
                assertEquals(secondWidth[0], secondParams.width);
                assertEquals(firstWeight[0], firstParams.weight, 0.001f);
                assertEquals(secondWeight[0], secondParams.weight, 0.001f);
                assertEquals(firstText[0], ((Button) actions.getChildAt(0)).getText().toString());
            });
        }
    }

    /** 提醒复用摩擦页，隐藏的休息状态仍保留准确秒数。 */
    @Test
    public void interruptionReusesFrictionPageWithRestCountdown() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

        try (ActivityScenario<InterceptionActivity> scenario = ActivityScenario.launch(
                InterceptionActivity.debugIntent(context, InterceptionDecision.Kind.INTERRUPT,
                        ControlLevel.REPAIR, InterceptionStage.HOLD,
                        "interruption-copy-test"))) {
            scenario.onActivity(activity -> {
                TextView title = activity.findViewById(R.id.interception_hold_title);
                TextView countdown = activity.findViewById(
                        R.id.interception_countdown);
                InterceptionAssertions.assertNoHeader(activity);
                assertTrue(title.getMaxLines() > 4);
                assertTrue(title.getText().length() > 0);
                assertEquals(View.GONE, countdown.getVisibility());
                assertTrue(countdown.getText().toString().contains("秒"));
                // Activity 创建及测试调度会消耗时间，读数应位于规则的剩余区间。
                java.util.regex.Matcher digits = java.util.regex.Pattern.compile("\\d+")
                        .matcher(countdown.getText().toString());
                assertTrue(digits.find());
                int remaining = Integer.parseInt(digits.group());
                assertTrue(remaining > 0 && remaining <= 60);
            });
        }
    }

    /** 修心首屏、挑战和二次询问均不显示顶部标签。 */
    @Test
    public void repairStagesHaveNoHeader() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        InterceptionStage[] stages = {InterceptionStage.INITIAL,
                InterceptionStage.HOLD, InterceptionStage.FINAL_CONFIRM};

        for (int index = 0; index < stages.length; index++) {
            try (ActivityScenario<InterceptionActivity> scenario = ActivityScenario.launch(
                    InterceptionActivity.debugIntent(context, InterceptionDecision.Kind.BLOCK,
                            ControlLevel.REPAIR, stages[index], "repair-label-" + index))) {
                scenario.onActivity(InterceptionAssertions::assertNoHeader);
            }
        }
    }

    /** 单词摩擦必须稳定提供左三右三的两列六选项布局。 */
    @Test
    public void wordChallengeUsesTwoByThreeGrid() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

        try (ActivityScenario<InterceptionActivity> scenario = ActivityScenario.launch(
                InterceptionActivity.debugIntent(context, InterceptionDecision.Kind.INTERRUPT,
                        ControlLevel.REPAIR, InterceptionStage.WORD_TRANSLATION,
                        "word-grid-test"))) {
            scenario.onActivity(activity -> {
                GridLayout choices = activity.findViewById(R.id.interception_word_choices);
                assertEquals(2, choices.getColumnCount());
                assertEquals(3, choices.getRowCount());
                assertEquals(6, choices.getChildCount());
                for (int index = 0; index < choices.getChildCount(); index++) {
                    assertEquals(View.VISIBLE, choices.getChildAt(index).getVisibility());
                }
            });
        }
    }

    /** 每题最低停留时间结束前，六个选项都不可点击。 */
    @Test
    public void wordChoicesStayLockedDuringMinimumDwell() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        InteractionSettingsStore settings = new InteractionSettingsStore(context, new AppConfig(context));
        WordChallengeSettings original = settings.wordSettings(InterceptionMode.REPAIR);
        settings.setWordSettings(InterceptionMode.REPAIR, new WordChallengeSettings(3, 60_000L));

        try (ActivityScenario<InterceptionActivity> scenario = ActivityScenario.launch(
                InterceptionActivity.debugIntent(context, InterceptionDecision.Kind.INTERRUPT,
                        ControlLevel.REPAIR, InterceptionStage.WORD_TRANSLATION,
                        "word-lock-test"))) {
            scenario.onActivity(activity -> {
                GridLayout choices = activity.findViewById(R.id.interception_word_choices);
                for (int index = 0; index < choices.getChildCount(); index++) {
                    assertFalse(choices.getChildAt(index).isEnabled());
                }
            });
        } finally {
            settings.setWordSettings(InterceptionMode.REPAIR, original);
        }
    }

    /** 摩擦拦截长按按钮的静止文案应直接告诉用户需要坚持多少秒。 */
    @Test
    public void holdButtonShowsRequiredSeconds() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        GlobalSettings settings = new GlobalSettings(context, new AppConfig(context));
        SecondsRange original = settings.buttonFrictionRange();
        settings.setButtonFrictionRange(new SecondsRange(85_000L, 85_000L));

        try (ActivityScenario<InterceptionActivity> scenario = ActivityScenario.launch(
                InterceptionActivity.debugIntent(context, InterceptionDecision.Kind.BLOCK,
                        ControlLevel.REPAIR, InterceptionStage.HOLD,
                        "hold-seconds-test"))) {
            scenario.onActivity(activity -> {
                Button hold = activity.findViewById(R.id.interception_hold_button);
                assertTrue(hold.getText().toString().matches(".*85.*秒.*"));
            });
        } finally {
            settings.setButtonFrictionRange(original);
        }
    }

    /** 中断按压按钮显示挑战剩余秒数，独立休息时间由公共页头展示。 */
    @Test
    public void interruptionHoldButtonHidesInternalCountdown() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

        try (ActivityScenario<InterceptionActivity> scenario = ActivityScenario.launch(
                InterceptionActivity.debugIntent(context, InterceptionDecision.Kind.INTERRUPT,
                        ControlLevel.REPAIR, InterceptionStage.HOLD,
                        "interrupt-hold-copy-test"))) {
            scenario.onActivity(activity -> {
                Button hold = activity.findViewById(R.id.interception_hold_button);
                assertTrue(hold.getText().toString().matches(".*\\d+.*秒.*"));
            });
        }
    }

    /** 相邻拦截更换主句并独立抽取路由文案，同一会话重建保持全部文字。 */
    @Test
    public void copyChangesOnEveryTriggerAndStaysStableOnRecreate() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ((github.vhoom.zhijian.ZhijianApplication) context.getApplicationContext()).ready().join();
        String[] first = new String[3];

        try (ActivityScenario<InterceptionActivity> scenario = ActivityScenario.launch(
                InterceptionActivity.debugIntent(context, InterceptionDecision.Kind.BLOCK,
                        ControlLevel.MORTAL, InterceptionStage.INITIAL,
                        github.vhoom.zhijian.fixture.InitialPageSessions.ordinary("copy-trigger-one")))) {
            scenario.onActivity(activity -> {
                InterceptionAssertions.assertNoHeader(activity);
                first[0] = ((TextView) activity.findViewById(
                        R.id.interception_general_title)).getText().toString();
                first[1] = ((Button) activity.findViewById(
                        R.id.interception_general_exit)).getText().toString();
                first[2] = ((Button) activity.findViewById(
                        R.id.interception_general_continue)).getText().toString();
            });
            scenario.recreate();
            scenario.onActivity(activity -> {
                assertEquals(first[0], ((TextView) activity.findViewById(
                        R.id.interception_general_title)).getText().toString());
                assertEquals(first[1], ((Button) activity.findViewById(
                        R.id.interception_general_exit)).getText().toString());
                assertEquals(first[2], ((Button) activity.findViewById(
                        R.id.interception_general_continue)).getText().toString());
            });
        }

        String[] second = new String[3];
        try (ActivityScenario<InterceptionActivity> scenario = ActivityScenario.launch(
                InterceptionActivity.debugIntent(context, InterceptionDecision.Kind.BLOCK,
                        ControlLevel.MORTAL, InterceptionStage.INITIAL,
                        github.vhoom.zhijian.fixture.InitialPageSessions.ordinary("copy-trigger-two")))) {
            scenario.onActivity(activity -> {
                second[0] = ((TextView) activity.findViewById(R.id.interception_general_title)).getText().toString();
                second[1] = ((Button) activity.findViewById(R.id.interception_general_exit)).getText().toString();
                second[2] = ((Button) activity.findViewById(R.id.interception_general_continue)).getText().toString();
                assertNotEquals(first[0], second[0]);
                assertTrue(java.util.Arrays.asList(activity.getResources().getStringArray(R.array.interception_exit_actions)).contains(second[1]));
                assertTrue(java.util.Arrays.asList(activity.getResources().getStringArray(
                        R.array.interception_continue_actions)).contains(second[2]));
            });
            scenario.recreate();
            scenario.onActivity(activity -> {
                assertEquals(second[0], ((TextView) activity.findViewById(R.id.interception_general_title)).getText().toString());
                assertEquals(second[1], ((Button) activity.findViewById(R.id.interception_general_exit)).getText().toString());
                assertEquals(second[2], ((Button) activity.findViewById(R.id.interception_general_continue)).getText().toString());
            });
        }
    }

    /** 禁止拦截只提供结束使用按钮，不得出现继续或临时放行入口。 */
    @Test
    public void forbiddenInterceptionOnlyShowsExitAction() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

        try (ActivityScenario<InterceptionActivity> scenario = ActivityScenario.launch(
                InterceptionActivity.debugIntent(context, InterceptionDecision.Kind.BLOCK,
                        ControlLevel.SEAL, InterceptionStage.LOCKED,
                        "forbidden-actions-test"))) {
            scenario.onActivity(activity -> {
                InterceptionAssertions.assertNoHeader(activity);
                LinearLayout actions = activity.findViewById(
                        R.id.interception_forbidden_actions);
                assertEquals(1, actions.getChildCount());
                assertEquals(R.id.interception_forbidden_exit,
                        actions.getChildAt(0).getId());
                View forbiddenPage = activity.findViewById(
                        R.id.interception_page_forbidden);
                assertNull(forbiddenPage.findViewById(
                        R.id.interception_general_continue));
            });
        }
    }

    /** Debug 图集应支持左右滑动，并循环浏览所有现用页面。 */
    @Test
    public void debugGallerySwipesBothDirectionsAcrossAllPages() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

        try (ActivityScenario<InterceptionActivity> scenario = ActivityScenario.launch(
                InterceptionActivity.debugGalleryIntent(context))) {
            scenario.onActivity(activity -> {
                InterceptionAssertions.assertNoHeader(activity);                assertEquals(0, activity.getIntent().getIntExtra("interception.debug_page", -1));
                swipe(activity, 800f, 100f);
                assertEquals(1, activity.getIntent().getIntExtra("interception.debug_page", -1));
                swipe(activity, 100f, 800f);
                assertEquals(0, activity.getIntent().getIntExtra("interception.debug_page", -1));
                swipe(activity, 100f, 800f);
                assertEquals(github.vhoom.zhijian.ui.interception.request.InterceptionIntents.PAGE_COUNT - 1,
                        activity.getIntent().getIntExtra("interception.debug_page", -1));
                assertTrue(activity.findViewById(R.id.interception_page_forbidden).isShown());
            });
        }
    }

    /**
     * 向 Activity 发送一组水平触摸事件，模拟用户左右滑动。
     *
     * @param activity 执行本次界面操作的 Activity
     * @param fromX 模拟水平滑动的开始横坐标
     * @param toX 模拟水平滑动的结束横坐标
     */
    private static void swipe(InterceptionActivity activity, float fromX, float toX) {
        long time = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN,
                fromX, 500f, 0);
        MotionEvent up = MotionEvent.obtain(time, time + 100L, MotionEvent.ACTION_UP,
                toX, 500f, 0);
        try {
            activity.dispatchTouchEvent(down);
            activity.dispatchTouchEvent(up);
        } finally {
            down.recycle();
            up.recycle();
        }
    }
}
