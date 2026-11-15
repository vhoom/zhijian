package github.vhoom.zhijian.ui.activity;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.view.View;

import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;

import github.vhoom.zhijian.R;
import github.vhoom.zhijian.ZhijianApplication;
import github.vhoom.zhijian.ui.preview.DeveloperPreviewActivity;
import github.vhoom.zhijian.ui.component.input.InputFieldLayout;
import github.vhoom.zhijian.data.model.settings.SecondsRange;
import github.vhoom.zhijian.data.model.settings.ThemeMode;
import github.vhoom.zhijian.data.model.settings.WordChallengeSettings;
import github.vhoom.zhijian.data.model.settings.InterceptionOptions;
import github.vhoom.zhijian.data.model.interception.InterceptionMode;
import github.vhoom.zhijian.data.model.word.BuiltInWordCatalog;
import github.vhoom.zhijian.data.store.ZhijianStorage;
import github.vhoom.zhijian.data.store.settings.InteractionSettingsStore;
import github.vhoom.zhijian.data.store.settings.GlobalSettings;
import github.vhoom.zhijian.util.config.AppConfig;
import android.widget.EditText;

import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;

import org.junit.Test;
import org.junit.Before;
import org.junit.After;

import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.pressBack;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.matcher.ViewMatchers.withId;

/**
 * Debug 设置入口的真机行为测试。
 *
 * 维护日期：2026年11月14日
 */
public class SettingsActivityTest {
    private InteractionSettingsStore interactions;
    private InterceptionOptions originalWuwei, originalRepair;

    /** 等待真实应用的启动资源加载，测试操作与用户可交互阶段保持一致。 */
    @Before
    public void waitForStartupResources() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ((ZhijianApplication) context.getApplicationContext()).ready().get(30L, TimeUnit.SECONDS);
        interactions = new InteractionSettingsStore(context, new AppConfig(context));
        originalWuwei = interactions.readOptions(InterceptionMode.WUWEI);
        originalRepair = interactions.readOptions(InterceptionMode.REPAIR);
        interactions.setObstaclePagesEnabled(true);
    }

    /** 还原进入用例前的模式选项，参数测试不依赖用户是否隐藏了阻碍设置。 */
    @After public void restoreModeOptions() {
        interactions.setTemporaryPassEnabled(InterceptionMode.WUWEI, originalWuwei.temporaryPassEnabled);
        interactions.setTemporaryPassEnabled(InterceptionMode.REPAIR, originalRepair.temporaryPassEnabled);
        interactions.setObstaclePagesEnabled(originalRepair.obstaclePagesEnabled);
        interactions.setDynamicButtonsEnabled(originalWuwei.dynamicButtonsEnabled);
        interactions.setConfirmationEnabled(originalRepair.confirmationEnabled);
    }

    /** 各主题下关闭阻碍同时隐藏两块配置；重建保持开关，重新开启保留原参数。 */
    @Test public void modeSwitchesPersistAndObstacleControlsRetainValuesAcrossThemes() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        GlobalSettings global = new GlobalSettings(context, new AppConfig(context));
        ThemeMode originalTheme = global.themeMode();
        SecondsRange hold = interactions.holdRange(InterceptionMode.REPAIR);
        WordChallengeSettings word = interactions.wordSettings(InterceptionMode.REPAIR);
        try {
            for (ThemeMode theme : ThemeMode.values()) {
                global.setThemeMode(theme);
                interactions.setTemporaryPassEnabled(InterceptionMode.WUWEI, false);
                interactions.setTemporaryPassEnabled(InterceptionMode.REPAIR, true);
                interactions.setObstaclePagesEnabled(true);
                interactions.setDynamicButtonsEnabled(true);
                interactions.setConfirmationEnabled(true);
                try (ActivityScenario<SettingsActivity> scenario = ActivityScenario.launch(new Intent(context, SettingsActivity.class))) {
                    scenario.onActivity(activity -> {
                        assertEquals(false, ((android.widget.Switch) activity.findViewById(R.id.switch_wuwei_temporary_pass)).isChecked());
                        assertEquals(true, ((android.widget.Switch) activity.findViewById(R.id.switch_repair_temporary_pass)).isChecked());
                        activity.findViewById(R.id.switch_wuwei_dynamic_buttons).performClick();
                        activity.findViewById(R.id.switch_repair_confirmation).performClick();
                        activity.findViewById(R.id.switch_repair_obstacle_pages).performClick();
                        assertEquals(View.GONE, activity.findViewById(R.id.settings_repair_hold_group).getVisibility());
                        activity.findViewById(R.id.switch_wuwei_temporary_pass).performClick();
                        activity.findViewById(R.id.switch_repair_temporary_pass).performClick();
                    });
                    scenario.recreate();
                    scenario.onActivity(activity -> {
                        assertEquals(true, ((android.widget.Switch) activity.findViewById(R.id.switch_wuwei_temporary_pass)).isChecked());
                        assertEquals(false, ((android.widget.Switch) activity.findViewById(R.id.switch_repair_temporary_pass)).isChecked());
                        assertEquals(false, ((android.widget.Switch) activity.findViewById(R.id.switch_repair_obstacle_pages)).isChecked());
                        assertEquals(false, ((android.widget.Switch) activity.findViewById(R.id.switch_wuwei_dynamic_buttons)).isChecked());
                        assertEquals(false, ((android.widget.Switch) activity.findViewById(R.id.switch_repair_confirmation)).isChecked());
                        assertEquals(View.GONE, activity.findViewById(R.id.settings_repair_hold_group).getVisibility());
                        activity.findViewById(R.id.switch_repair_obstacle_pages).performClick();
                        assertEquals(View.VISIBLE, activity.findViewById(R.id.settings_repair_hold_group).getVisibility());
                        assertEquals(false, ((android.widget.Switch) activity.findViewById(R.id.switch_repair_confirmation)).isChecked());
                    });
                    assertEquals(hold.minMillis, interactions.holdRange(InterceptionMode.REPAIR).minMillis);
                    assertEquals(hold.maxMillis, interactions.holdRange(InterceptionMode.REPAIR).maxMillis);
                    assertEquals(word.questionCount, interactions.wordSettings(InterceptionMode.REPAIR).questionCount);
                    assertEquals(word.catalog, interactions.wordSettings(InterceptionMode.REPAIR).catalog);
                }
            }
        } finally { global.setThemeMode(originalTheme); }
    }

    /** 加减题量实时刷新保底时间，返回取消草稿后原配置保持完整。 */
    @Test
    public void wordSheetStepperRecalculatesMinimumAndBackCancelsEditing() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        GlobalSettings settings = new GlobalSettings(context, new AppConfig(context));
        WordChallengeSettings original = settings.wordChallengeSettings();
        try (ActivityScenario<SettingsActivity> scenario = ActivityScenario.launch(
                new Intent(context, SettingsActivity.class))) {
            onView(withId(R.id.row_word_challenge)).perform(scrollTo(), click());
            onView(withId(R.id.field_word_question_count)).perform(replaceText("3"));
            onView(withId(R.id.field_word_dwell_seconds)).perform(replaceText("2"), closeSoftKeyboard());
            onView(withId(R.id.stepper_word_question_count_increase)).perform(click());
            onView(withId(R.id.field_word_question_count)).check((view, missing) ->
                    assertEquals("4", ((EditText) view).getText().toString()));
            onView(withId(R.id.word_minimum_duration_value)).check((view, missing) ->
                    assertEquals(context.getString(R.string.settings_seconds_value, "8"),
                            ((android.widget.TextView) view).getText().toString()));
            pressBack();
            assertEquals(original.questionCount, settings.wordChallengeSettings().questionCount);
            assertEquals(original.minDwellMillis, settings.wordChallengeSettings().minDwellMillis);
        }
    }

    /** 单词配置允许超过十五题，超出所选词库时保留草稿且拒绝保存。 */
    @Test
    public void wordCountsUseCatalogCapacityAndPreserveInvalidDraft() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        InteractionSettingsStore interactions = new InteractionSettingsStore(context, new AppConfig(context));
        WordChallengeSettings original = interactions.wordSettings(InterceptionMode.REPAIR);
        try (ActivityScenario<SettingsActivity> scenario = ActivityScenario.launch(
                new Intent(context, SettingsActivity.class))) {
            onView(withId(R.id.row_word_challenge)).perform(scrollTo(), click());
            selectCatalog(BuiltInWordCatalog.JUNIOR_HIGH);
            int capacity = ZhijianStorage.get(context).wordCatalog(BuiltInWordCatalog.JUNIOR_HIGH).size();
            String invalid = Long.toString((long) capacity + 1L);
            onView(withId(R.id.field_word_question_count)).perform(replaceText(invalid), closeSoftKeyboard());
            onView(errorBelow(R.id.field_word_question_count)).check((view, missing) -> {
                assertTrue(view.isShown());
                assertEquals("数量超出词库", ((android.widget.TextView) view).getText().toString());
            });
            onView(withId(R.id.settings_sheet_done)).perform(click());
            onView(withId(R.id.field_word_question_count)).check((view, missing) ->
                    assertEquals(invalid, ((EditText) view).getText().toString()));
            assertEquals(original.questionCount, interactions.wordSettings(InterceptionMode.REPAIR).questionCount);

            onView(withId(R.id.field_word_question_count)).perform(replaceText("20"));
            onView(withId(R.id.field_word_dwell_seconds)).perform(replaceText("1.5"), closeSoftKeyboard());
            onView(withId(R.id.stepper_word_dwell_seconds_increase)).perform(click());
            onView(withId(R.id.field_word_dwell_seconds)).check((view, missing) ->
                    assertEquals("2.5", ((EditText) view).getText().toString()));
            onView(withId(R.id.stepper_word_dwell_seconds_decrease)).perform(click());
            onView(withId(R.id.settings_sheet_done)).perform(click());
            assertEquals(20, interactions.wordSettings(InterceptionMode.REPAIR).questionCount);
            assertEquals(1_500L, interactions.wordSettings(InterceptionMode.REPAIR).minDwellMillis);
            scenario.recreate();
            onView(withId(R.id.row_word_challenge)).perform(scrollTo(), click());
            onView(withId(R.id.field_word_question_count)).check((view, missing) ->
                    assertEquals("20", ((EditText) view).getText().toString()));
            pressBack();
        } finally {
            interactions.setWordSettings(InterceptionMode.REPAIR, original);
        }
    }

    /** 切换到较小词库立即提示超限，切回足够大的词库清除提示且保持题量。 */
    @Test
    public void changingCatalogRevalidatesCountWithoutClamping() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (ActivityScenario<SettingsActivity> scenario = ActivityScenario.launch(
                new Intent(context, SettingsActivity.class))) {
            onView(withId(R.id.row_word_challenge)).perform(scrollTo(), click());
            BuiltInWordCatalog smaller = BuiltInWordCatalog.JUNIOR_HIGH;
            BuiltInWordCatalog larger = smaller;
            ZhijianStorage storage = ZhijianStorage.get(context);
            for (BuiltInWordCatalog catalog : BuiltInWordCatalog.values()) {
                if (storage.wordCatalog(catalog).size() < storage.wordCatalog(smaller).size()) smaller = catalog;
                if (storage.wordCatalog(catalog).size() > storage.wordCatalog(larger).size()) larger = catalog;
            }
            int count = storage.wordCatalog(smaller).size() + 1;
            int largerCapacity = storage.wordCatalog(larger).size();
            assertTrue(count <= storage.wordCatalog(larger).size());
            String draft = Integer.toString(count);
            selectCatalog(larger);
            onView(withId(R.id.field_word_question_count)).perform(replaceText(draft), closeSoftKeyboard());
            onView(errorBelow(R.id.field_word_question_count)).check((view, missing) ->
                    assertEquals(View.GONE, view.getVisibility()));
            selectCatalog(smaller);
            onView(errorBelow(R.id.field_word_question_count)).check((view, missing) ->
                    assertEquals("数量超出词库", ((android.widget.TextView) view).getText().toString()));
            onView(withId(R.id.field_word_question_count)).check((view, missing) ->
                    assertEquals(draft, ((EditText) view).getText().toString()));
            selectCatalog(larger);
            onView(errorBelow(R.id.field_word_question_count)).check((view, missing) ->
                    assertEquals(View.GONE, view.getVisibility()));
            onView(withId(R.id.field_word_question_count)).perform(
                    replaceText(Integer.toString(largerCapacity)), closeSoftKeyboard());
            onView(withId(R.id.stepper_word_question_count_increase)).check((view, missing) ->
                    assertEquals(false, view.isEnabled()));
            selectCatalog(smaller);
            onView(withId(R.id.stepper_word_question_count_increase)).check((view, missing) ->
                    assertEquals(false, view.isEnabled()));
            onView(withId(R.id.field_word_question_count)).check((view, missing) ->
                    assertEquals(Integer.toString(largerCapacity), ((EditText) view).getText().toString()));
            pressBack();
        }
    }

    /**
     * 在弹窗主线程选中指定内置词库，后续 Espresso 操作等待选择回调完成。
     * @param catalog 当前测试需要的词库
     */
    private static void selectCatalog(BuiltInWordCatalog catalog) {
        onView(withId(R.id.field_word_catalog)).check((view, missing) ->
                ((android.widget.Spinner) view).setSelection(catalog.ordinal()));
    }


    /** 设置页在跟随系统、浅色和深色三种模式下创建及重建均应保留关键设置控件。 */
    @Test
    public void allThemeModesOpenAndRecreateSettings() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        GlobalSettings settings = new GlobalSettings(context, new AppConfig(context));
        ThemeMode original = settings.themeMode();
        try {
            for (ThemeMode mode : ThemeMode.values()) {
                settings.setThemeMode(mode);
                try (ActivityScenario<SettingsActivity> scenario = ActivityScenario.launch(
                        new Intent(context, SettingsActivity.class))) {
                    scenario.onActivity(activity -> assertNotNull(
                            activity.findViewById(R.id.word_challenge_value)));
                    scenario.recreate();
                    scenario.onActivity(activity -> assertNotNull(
                            activity.findViewById(R.id.hold_range_value)));
                    assertEquals(mode, settings.themeMode());
                }
            }
        } finally {
            settings.setThemeMode(original);
        }
    }

    /** 不合法秒数保留弹窗和错误提示；一位小数区间按精确毫秒保存。 */
    @Test
    public void secondsDialogRejectsInvalidInputAndPersistsDecimalRange() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        GlobalSettings settings = new GlobalSettings(context, new AppConfig(context));
        SecondsRange original = settings.buttonFrictionRange();
        try (ActivityScenario<SettingsActivity> scenario = ActivityScenario.launch(
                new Intent(context, SettingsActivity.class))) {
            onView(withId(R.id.row_hold_range)).perform(scrollTo(), click());
            onView(withId(R.id.field_min_seconds)).perform(replaceText("0"));
            // 先关闭输入法，避免确认按钮坐标被键盘动画遮挡而漏掉点击。
            onView(withId(R.id.field_max_seconds)).perform(
                    replaceText("2.3"), closeSoftKeyboard());
            onView(withId(R.id.settings_sheet_done)).perform(click());
            onView(errorBelow(R.id.field_min_seconds)).check((view, missing) -> assertTrue(view.isShown()));
            onView(withId(R.id.field_min_seconds)).check((view, missing) ->
                    assertEquals(null, ((EditText) view).getError()));
            onView(withId(R.id.field_min_seconds)).perform(
                    replaceText("1.2"), closeSoftKeyboard());
            onView(errorBelow(R.id.field_min_seconds)).check((view, missing) ->
                    assertEquals(View.GONE, view.getVisibility()));
            onView(withId(R.id.settings_sheet_done)).perform(click());
            assertEquals(1_200L, settings.buttonFrictionRange().minMillis);
            assertEquals(2_300L, settings.buttonFrictionRange().maxMillis);
            onView(withId(R.id.hold_range_value)).check((view, missing) ->
                    assertTrue(((android.widget.TextView) view).getText().toString().contains("随机")));
        } finally {
            settings.setButtonFrictionRange(original);
        }
    }

    /** 连点设置页 Logo 七次打开包含配置状态和拦截图集入口的开发者页面。 */
    @Test
    public void sevenLogoTapsOpenDeveloperPreview() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(
                DeveloperPreviewActivity.class.getName(), null, false);
        Activity preview = null;
        try (ActivityScenario<SettingsActivity> scenario = ActivityScenario.launch(
                new Intent(context, SettingsActivity.class))) {
            scenario.onActivity(activity -> {
                View logo = activity.findViewById(R.id.settings_logo);
                for (int count = 0; count < 7; count++) assertTrue(logo.performClick());
            });
            preview = monitor.waitForActivityWithTimeout(5_000L);
            assertNotNull(preview);
            Activity launched = preview;
            instrumentation.runOnMainSync(() -> {
                assertNotNull(launched.findViewById(R.id.setup_preview_choose));
                assertNotNull(launched.findViewById(R.id.developer_interception_preview));
            });
        } finally {
            Activity launched = preview;
            if (launched != null) {
                instrumentation.runOnMainSync(launched::finish);
            }
            instrumentation.removeMonitor(monitor);
        }
    }
    /** @param fieldId 错误所属输入 @return 按所属输入容器匹配独立 ID 的下方提示 */
    private static Matcher<View> errorBelow(int fieldId) {
        return new TypeSafeMatcher<View>() {
            /** @param view 候选提示 @return 该提示属于指定控件 */
            @Override protected boolean matchesSafely(View view) {
                if (!(view.getParent() instanceof InputFieldLayout)) return false;
                EditText field = ((View) view.getParent()).findViewById(fieldId);
                if (field == null) return false;
                assertEquals(null, field.getError());
                return field.getTag(R.id.input_error_binding) == view;
            }

            /** @param description 匹配失败时的诊断文字 */
            @Override public void describeTo(Description description) {
                description.appendText("error caption belonging to input ").appendValue(fieldId);
            }
        };
    }
}
