package github.vhoom.zhijian.data.store.settings;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.platform.app.InstrumentationRegistry;

import github.vhoom.zhijian.data.model.settings.WordChallengeSettings;
import github.vhoom.zhijian.data.model.word.BuiltInWordCatalog;
import github.vhoom.zhijian.data.model.settings.SecondsRange;
import github.vhoom.zhijian.data.model.settings.SudokuDifficulty;
import github.vhoom.zhijian.data.model.interception.InterceptionMode;
import github.vhoom.zhijian.data.model.interception.InterceptionChallenge;
import github.vhoom.zhijian.util.config.AppConfig;
import github.vhoom.zhijian.data.store.ZhijianStorage;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 覆盖真实资源默认值、修心参数保存和损坏设置的读取行为。
 *
 * 维护日期：2026年11月14日
 */
public class GlobalSettingsTest {
    /** 修心默认阻碍开启且只选按压，时长 50 至 75 秒；单词参数和数独简单难度保留。 */
    @Test public void modeOptionsUseConfirmedDefaults() {
        assertFalse(interactions.readOptions(InterceptionMode.WUWEI).temporaryPassEnabled);
        assertFalse(interactions.readOptions(InterceptionMode.WUWEI).obstaclePagesEnabled);
        assertTrue(interactions.readOptions(InterceptionMode.REPAIR).temporaryPassEnabled);
        assertTrue(interactions.readOptions(InterceptionMode.REPAIR).obstaclePagesEnabled);
        assertTrue(interactions.readOptions(InterceptionMode.REPAIR).confirmationEnabled);
        assertEquals(java.util.EnumSet.of(InterceptionChallenge.BUTTON_HOLD),
                interactions.read(InterceptionMode.REPAIR).enabledChallenges);
        assertEquals(50_000L, settings.buttonFrictionRange().minMillis);
        assertEquals(75_000L, settings.buttonFrictionRange().maxMillis);
        assertFalse(interactions.isEnabled(InterceptionMode.REPAIR, InterceptionChallenge.WORD_TRANSLATION));
        assertFalse(interactions.readOptions(InterceptionMode.SEAL).temporaryPassEnabled);
        assertFalse(interactions.readOptions(InterceptionMode.SEAL).obstaclePagesEnabled);
        assertFalse(interactions.isEnabled(InterceptionMode.REPAIR, InterceptionChallenge.SUDOKU));
        assertEquals(SudokuDifficulty.EASY, interactions.sudokuDifficulty());
    }

    /** 模式独立持久化；只开单词时阻碍开关保留选择和参数，最后一个挑战不能关闭。 */
    @Test public void optionPersistenceKeepsModesAndChallengeParametersIndependent() {
        interactions.setHoldRange(InterceptionMode.REPAIR, new SecondsRange(400L, 800L));
        interactions.setWordSettings(InterceptionMode.REPAIR, new WordChallengeSettings(2, 300L));
        interactions.setEnabled(InterceptionMode.REPAIR, InterceptionChallenge.WORD_TRANSLATION, true);
        interactions.setEnabled(InterceptionMode.REPAIR, InterceptionChallenge.BUTTON_HOLD, false);
        interactions.setTemporaryPassEnabled(InterceptionMode.WUWEI, true);
        interactions.setTemporaryPassEnabled(InterceptionMode.REPAIR, false);
        interactions.setObstaclePagesEnabled(false);
        interactions.setSudokuDifficulty(SudokuDifficulty.HARD);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        InteractionSettingsStore reopened = new InteractionSettingsStore(context, new AppConfig(context));
        assertTrue(reopened.readOptions(InterceptionMode.WUWEI).temporaryPassEnabled);
        assertFalse(reopened.readOptions(InterceptionMode.REPAIR).temporaryPassEnabled);
        assertFalse(reopened.readOptions(InterceptionMode.REPAIR).obstaclePagesEnabled);
        assertEquals(SudokuDifficulty.HARD, reopened.read(InterceptionMode.REPAIR).sudokuDifficulty);
        assertNull(reopened.read(InterceptionMode.REPAIR).selectChallenge("disabled"));
        reopened.setObstaclePagesEnabled(true);
        reopened.setEnabled(InterceptionMode.REPAIR, InterceptionChallenge.WORD_TRANSLATION, false);
        assertEquals(InterceptionChallenge.WORD_TRANSLATION, reopened.read(InterceptionMode.REPAIR).selectChallenge("enabled"));
        assertEquals(800L, reopened.holdRange(InterceptionMode.REPAIR).maxMillis);
        assertEquals(2, reopened.wordSettings(InterceptionMode.REPAIR).questionCount);
        assertEquals(300L, reopened.wordSettings(InterceptionMode.REPAIR).minDwellMillis);
        assertFalse(reopened.isEnabled(InterceptionMode.REPAIR, InterceptionChallenge.BUTTON_HOLD));
        assertTrue(reopened.isEnabled(InterceptionMode.REPAIR, InterceptionChallenge.WORD_TRANSLATION));
    }

    /** 错误类型回落到每个模式自己的默认值，闭关即使保存了 true 也不开放行。 */
    @Test public void corruptOptionTypesUseModeDefaultsAndSealRemainsClosed() {
        preferences.edit().putString("WUWEI.temporary_pass.enabled", "true")
                .putInt("REPAIR.temporary_pass.enabled", 0)
                .putString("REPAIR.obstacle_pages.enabled", "false")
                .putBoolean("SEAL.temporary_pass.enabled", true)
                .putBoolean("SEAL.obstacle_pages.enabled", true).putInt("sudoku_difficulty", 9)
                .putString("REPAIR.challenge.BUTTON_HOLD.enabled", "broken")
                .putString("REPAIR.challenge.WORD_TRANSLATION.enabled", "broken")
                .putString("REPAIR.challenge.SUDOKU.enabled", "broken").commit();
        assertEquals(SudokuDifficulty.EASY, interactions.sudokuDifficulty());
        assertEquals(java.util.EnumSet.of(InterceptionChallenge.BUTTON_HOLD),
                interactions.read(InterceptionMode.REPAIR).enabledChallenges);
        assertFalse(interactions.readOptions(InterceptionMode.WUWEI).temporaryPassEnabled);
        assertTrue(interactions.readOptions(InterceptionMode.REPAIR).temporaryPassEnabled);
        assertTrue(interactions.readOptions(InterceptionMode.REPAIR).obstaclePagesEnabled);
        assertFalse(interactions.readOptions(InterceptionMode.SEAL).temporaryPassEnabled);
        assertFalse(interactions.readOptions(InterceptionMode.SEAL).obstaclePagesEnabled);
    }

    /** 未设置词库或保存了无效标识时默认为初中，修心选择保存后可重读。 */
    @Test public void catalogDefaultsAndPersists() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        InteractionSettingsStore store = new InteractionSettingsStore(context, new AppConfig(context));
        assertEquals(BuiltInWordCatalog.JUNIOR_HIGH, store.wordSettings(InterceptionMode.REPAIR).catalog);
        store.setWordSettings(InterceptionMode.REPAIR, new WordChallengeSettings(2, 300L, BuiltInWordCatalog.CET4));
        InteractionSettingsStore reopened = new InteractionSettingsStore(context, new AppConfig(context));
        assertEquals(BuiltInWordCatalog.CET4, reopened.wordSettings(InterceptionMode.REPAIR).catalog);
        preferences.edit().putString("word_catalog", "removed").commit();
        assertEquals(BuiltInWordCatalog.JUNIOR_HIGH, reopened.wordSettings(InterceptionMode.REPAIR).catalog);
    }

    /** 修心空配置保留按压挑战。 */
    @Test public void emptyChallengesFollowTheirModePolicy() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        preferences.edit().putBoolean(InterceptionMode.REPAIR.name() + ".challenge.BUTTON_HOLD.enabled", false)
                .putBoolean(InterceptionMode.REPAIR.name() + ".challenge.WORD_TRANSLATION.enabled", false).commit();
        InteractionSettingsStore store = new InteractionSettingsStore(context, new AppConfig(context));
        assertEquals(true, store.isEnabled(InterceptionMode.REPAIR, InterceptionChallenge.BUTTON_HOLD));
        assertEquals(false, store.isEnabled(InterceptionMode.REPAIR, InterceptionChallenge.WORD_TRANSLATION));
        InterceptionChallenge fallback = InterceptionChallenge.BUTTON_HOLD;
        assertEquals(java.util.EnumSet.of(fallback), store.read(InterceptionMode.REPAIR).enabledChallenges);
        assertEquals(fallback, store.read(InterceptionMode.REPAIR).selectChallenge("empty"));
        store.setEnabled(InterceptionMode.REPAIR, InterceptionChallenge.BUTTON_HOLD, false);
        assertEquals(true, new InteractionSettingsStore(context, new AppConfig(context))
                .isEnabled(InterceptionMode.REPAIR, InterceptionChallenge.BUTTON_HOLD));
    }

    /** 修心的开关、题量、停留与长按设置在重新读取后保留。 */
    @Test
    public void repairSettingsPersist() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        InteractionSettingsStore store = new InteractionSettingsStore(context, new AppConfig(context));
        store.setEnabled(InterceptionMode.REPAIR, InterceptionChallenge.WORD_TRANSLATION, true);
        store.setEnabled(InterceptionMode.REPAIR, InterceptionChallenge.BUTTON_HOLD, false);
        store.setWordSettings(InterceptionMode.REPAIR, new WordChallengeSettings(2, 300L));
        store.setHoldRange(InterceptionMode.REPAIR, new SecondsRange(400L, 800L));
        InteractionSettingsStore reopened = new InteractionSettingsStore(context, new AppConfig(context));
        assertFalse(reopened.isEnabled(InterceptionMode.REPAIR, InterceptionChallenge.BUTTON_HOLD));
        assertEquals(true, reopened.isEnabled(InterceptionMode.REPAIR, InterceptionChallenge.WORD_TRANSLATION));
        assertEquals(2, reopened.wordSettings(InterceptionMode.REPAIR).questionCount);
        assertEquals(300L, reopened.wordSettings(InterceptionMode.REPAIR).minDwellMillis);
        assertEquals(800L, reopened.holdRange(InterceptionMode.REPAIR).maxMillis);
    }

    /** 大于十五的合法题量重读后保持原值。 */
    @Test
    public void largerWordCountsPersist() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        InteractionSettingsStore store = new InteractionSettingsStore(context, new AppConfig(context));
        store.setWordSettings(InterceptionMode.REPAIR, new WordChallengeSettings(50, 1_500L));
        InteractionSettingsStore reopened = new InteractionSettingsStore(context, new AppConfig(context));
        assertEquals(50, reopened.wordSettings(InterceptionMode.REPAIR).questionCount);
        assertEquals(1_500L, reopened.wordSettings(InterceptionMode.REPAIR).minDwellMillis);
    }


    private SharedPreferences preferences;
    private Map<String, ?> original;
    private GlobalSettings settings;
    private InteractionSettingsStore interactions;

    /** 每个测试使用空设置，结束后恢复设备原始值。 */
    @Before
    public void prepareSettings() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ZhijianStorage.get(context).ready().join();
        preferences = context.getSharedPreferences("global_settings", Context.MODE_PRIVATE);
        original = new HashMap<>(preferences.getAll());
        preferences.edit().clear().commit();
        settings = new GlobalSettings(context, new AppConfig(context));
        interactions = new InteractionSettingsStore(context, new AppConfig(context));
    }

    /** 完整恢复原始设置，避免测试改变其他用例的配置。 */
    @After
    public void restoreSettings() {
        SharedPreferences.Editor editor = preferences.edit().clear();
        for (Map.Entry<String, ?> entry : original.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Integer) editor.putInt(entry.getKey(), (Integer) value);
            else if (value instanceof Long) editor.putLong(entry.getKey(), (Long) value);
            else if (value instanceof Boolean) editor.putBoolean(entry.getKey(), (Boolean) value);
            else if (value instanceof String) editor.putString(entry.getKey(), (String) value);
            else if (value instanceof Float) editor.putFloat(entry.getKey(), (Float) value);
        }
        editor.commit();
    }

    /** 未设置覆盖值时，真实 XML 默认值必须构造成功。 */
    @Test
    public void factoryWordSettingsAreValid() {
        WordChallengeSettings value = settings.wordChallengeSettings();
        assertEquals(17, value.questionCount);
        assertEquals(2_000L, value.minDwellMillis);
        assertEquals(BuiltInWordCatalog.JUNIOR_HIGH, value.catalog);
    }

    /** 超界题量和停留时间必须回落到合法出厂配置。 */
    @Test
    public void invalidWordSettingsFallBackToDefaults() {
        preferences.edit().putInt("word_question_count", 99)
                .putLong("word_min_dwell_millis", -1L).commit();
        assertEquals(34_000L, settings.wordChallengeSettings().minimumDurationMillis());
    }

    /** 类型错误不得让设置页或拦截页抛出 ClassCastException。 */
    @Test
    public void wrongPreferenceTypesFallBackToDefaults() {
        preferences.edit().putString("word_question_count", "broken")
                .putString("word_min_dwell_millis", "broken")
                .putString("hold_min_millis", "broken")
                .putString("hold_max_millis", "broken")
                .putString("daily_reset_minute", "broken")
                .putString("theme_mode", "broken")
                .putString("usage_row_limit", "broken")
                .putString("show_setup_guide", "broken")
                .putString("show_daily_poem", "broken").commit();
        assertEquals(34_000L, settings.wordChallengeSettings().minimumDurationMillis());
        assertEquals(50_000L, settings.buttonFrictionRange().minMillis);
        assertEquals(75_000L, settings.buttonFrictionRange().maxMillis);
        assertEquals(0, settings.dailyResetMinute());
        assertEquals(6, settings.usageRowLimit());
        assertEquals(true, settings.showSetupGuide());
        assertEquals(true, settings.showDailyPoem());
        assertEquals(github.vhoom.zhijian.data.model.settings.ThemeMode.SYSTEM, settings.themeMode());
    }

    /** 设置始终返回实际数字，手动覆盖优先；恢复默认使用新计算值而非固定六条。 */
    @Test public void calculatedRowsStayNumericAndManualOverrideSurvivesRemeasurement() {
        settings.setCalculatedUsageRows(4);
        assertEquals(4, settings.usageRowLimit());
        settings.setUsageRowLimit(8);
        settings.setCalculatedUsageRows(5);
        assertEquals(8, settings.usageRowLimit());
        settings.setUsageRowLimit(0);
        assertEquals(5, settings.usageRowLimit());
    }

    /** 古诗默认显示，独立保存后重新创建设置对象仍保留开关，不影响引导和榜数。 */
    @Test public void dailyPoemVisibilityDefaultsToVisibleAndPersistsIndependently() {
        assertEquals(true, settings.showDailyPoem());
        settings.setShowDailyPoem(false);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        GlobalSettings reopened = new GlobalSettings(context, new AppConfig(context));
        assertEquals(false, reopened.showDailyPoem());
        assertEquals(true, reopened.showSetupGuide());
        assertEquals(6, reopened.usageRowLimit());
        reopened.setShowDailyPoem(true);
        assertEquals(true, settings.showDailyPoem());
    }
}
