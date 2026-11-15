package github.vhoom.zhijian.ui.interception;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.view.View;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;

import github.vhoom.zhijian.R;
import github.vhoom.zhijian.data.model.interception.InterceptionMode;
import github.vhoom.zhijian.data.model.interception.InterceptionStage;
import github.vhoom.zhijian.data.model.policy.ControlLevel;
import github.vhoom.zhijian.data.model.settings.ThemeMode;
import github.vhoom.zhijian.data.model.settings.WordChallengeSettings;
import github.vhoom.zhijian.data.store.settings.GlobalSettings;
import github.vhoom.zhijian.data.store.settings.InteractionSettingsStore;
import github.vhoom.zhijian.domain.interception.InterceptionDecision;
import github.vhoom.zhijian.fixture.InterceptionAssertions;
import github.vhoom.zhijian.ui.interception.page.support.WordOptionsPresentation;
import github.vhoom.zhijian.util.config.AppConfig;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 单词页展示回归：真实等待权限、前后台恢复、富文本及浅深色布局。
 *
 * 维护日期：2026年11月14日
 */
public class WordPresentationTest {
    private Context context;
    private GlobalSettings global;
    private InteractionSettingsStore interactions;
    private ThemeMode originalTheme;
    private WordChallengeSettings originalWord;

    /** 等待存储就绪后保存模式参数与配色，避免测试依赖执行顺序。 */
    @Before public void prepare() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ((github.vhoom.zhijian.ZhijianApplication) context.getApplicationContext())
                .ready().get(10, java.util.concurrent.TimeUnit.SECONDS);
        AppConfig config = new AppConfig(context);
        global = new GlobalSettings(context, config);
        interactions = new InteractionSettingsStore(context, config);
        originalTheme = global.themeMode();
        originalWord = interactions.wordSettings(InterceptionMode.REPAIR);
    }

    /** 恢复测试前的独立单词配置和主题。 */
    @After public void restore() {
        interactions.setWordSettings(InterceptionMode.REPAIR, originalWord);
        global.setThemeMode(originalTheme);
    }

    /** 重建保留原解锁时刻；到期只改变答题权限，外观不变且进度线填满。 */
    @Test public void realWaitControlsAvailabilityAcrossPause() {
        interactions.setWordSettings(InterceptionMode.REPAIR, new WordChallengeSettings(3, 2_500L));
        int[] originalColor = {0};
        try (ActivityScenario<InterceptionActivity> scenario = launchWord("word-ui-wait")) {
            scenario.onActivity(activity -> {
                GridLayout options = activity.findViewById(R.id.interception_word_choices);
                for (int i = 0; i < options.getChildCount(); i++) assertFalse(options.getChildAt(i).isEnabled());
                originalColor[0] = ((Button) options.getChildAt(0)).getCurrentTextColor();
                assertEquals(1f, options.getAlpha(), 0.01f);
                assertEquals(1f, activity.findViewById(R.id.interception_word_progress).getAlpha(), 0.01f);
            });
            SystemClock.sleep(100L);
            scenario.recreate();
            boolean[] unlocked = {false};
            long deadline = SystemClock.elapsedRealtime() + 5_000L;
            while (!unlocked[0] && SystemClock.elapsedRealtime() < deadline) {
                scenario.onActivity(activity -> unlocked[0] = activity.findViewById(
                        R.id.interception_word_choice_1).isEnabled());
                SystemClock.sleep(50L);
            }
            assertTrue(unlocked[0]);
            SystemClock.sleep(250L);
            scenario.onActivity(activity -> {
                GridLayout options = activity.findViewById(R.id.interception_word_choices);
                for (int i = 0; i < options.getChildCount(); i++) assertTrue(options.getChildAt(i).isEnabled());
                assertEquals(originalColor[0], ((Button) options.getChildAt(0)).getCurrentTextColor());
                assertEquals(1f, options.getAlpha(), 0.01f);
                ProgressBar line = activity.findViewById(R.id.interception_word_progress);
                assertEquals(1000, line.getProgress());
                assertEquals(1f, line.getAlpha(), 0.01f);
            });
        }
    }

    /** 词性富文本保留完整义项，副词不会被误识别成动词或截断。 */
    @Test public void partOfSpeechFormattingPreservesLabels() {
        try (ActivityScenario<InterceptionActivity> scenario = launchWord("word-ui-spans")) {
            scenario.onActivity(activity -> {
                View root = activity.findViewById(R.id.interception_page_word);
                GridLayout grid = root.findViewById(R.id.interception_word_choices);
                Button[] buttons = new Button[grid.getChildCount()];
                for (int i = 0; i < buttons.length; i++) buttons[i] = (Button) grid.getChildAt(i);
                String label = "n. 片刻；adv. 暂时";
                WordOptionsPresentation visuals = new WordOptionsPresentation(root, buttons);
                Spanned styled = (Spanned) visuals.formatOption(label);
                assertEquals(label, styled.toString());
                assertEquals(2, styled.getSpans(0, styled.length(), RelativeSizeSpan.class).length);
                assertEquals(2, styled.getSpans(0, styled.length(), StyleSpan.class).length);
                assertEquals(2, styled.getSpans(0, styled.length(), TypefaceSpan.class).length);
                String rest = "60 秒";
                TextView countdownView = activity.findViewById(R.id.interception_countdown);
                new github.vhoom.zhijian.ui.interception.page.support.RestCountdownPresentation(countdownView).update(60_000L);
                Spanned countdown = (Spanned) countdownView.getText();
                assertEquals(rest, countdown.toString());
                android.text.style.AbsoluteSizeSpan[] numbers = countdown.getSpans(0, rest.length(), android.text.style.AbsoluteSizeSpan.class);
                assertEquals(1, numbers.length);
                assertEquals(Math.round(20f * activity.getResources().getDisplayMetrics().scaledDensity), numbers[0].getSize());
            });
        }
    }

    /** 浅深色页面使用各自背景且没有页头，重建保持锁定和底部操作。 */
    @Test public void themesAndRecreationKeepUsableWordLayout() throws IOException {
        interactions.setWordSettings(InterceptionMode.REPAIR, new WordChallengeSettings(3, 60_000L));
        for (ThemeMode theme : new ThemeMode[]{ThemeMode.DARK, ThemeMode.LIGHT}) {
            global.setThemeMode(theme);
            try (ActivityScenario<InterceptionActivity> scenario = launchWord("word-ui-" + theme.name())) {
                SystemClock.sleep(750L);
                scenario.onActivity(activity -> {
                    TextView countdown = activity.findViewById(R.id.interception_countdown);
                    TextView title = activity.findViewById(R.id.interception_word_title);
                    InterceptionAssertions.assertNoHeader(activity);
                    assertFalse(countdown.isShown());
                    assertTrue(title.isShown());
                    assertTrue(activity.findViewById(R.id.interception_word_exit).isShown());
                    Bitmap sample = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
                    activity.findViewById(R.id.interception_root).getBackground().draw(new Canvas(sample));
                    assertEquals(activity.getColor(R.color.canvas), sample.getPixel(10, 10));
                    sample.recycle();
                });
                captureIfRequested("word-" + theme.name().toLowerCase(java.util.Locale.ROOT));
                scenario.recreate();
                scenario.onActivity(activity -> {
                    assertFalse(activity.findViewById(R.id.interception_word_choice_1).isEnabled());
                    assertEquals(1f, activity.findViewById(R.id.interception_word_choices).getAlpha(), 0.01f);
                });
            }
        }
    }

    /**
     * 启动真实单词页面并等待异步存储初始化完成，强制阶段仅用于测试入口。
     * @param session 稳定测试会话标识
     * @return 负责关闭页面的场景句柄
     */
    private ActivityScenario<InterceptionActivity> launchWord(String session) {
        ActivityScenario<InterceptionActivity> scenario = ActivityScenario.launch(InterceptionActivity.debugIntent(context,
                InterceptionDecision.Kind.INTERRUPT, ControlLevel.REPAIR,
                InterceptionStage.WORD_TRANSLATION, session));
        boolean[] ready = {false};
        long deadline = SystemClock.elapsedRealtime() + 5_000L;
        while (!ready[0] && SystemClock.elapsedRealtime() < deadline) {
            scenario.onActivity(activity -> ready[0] = activity.findViewById(R.id.interception_page_word) != null);
            if (!ready[0]) SystemClock.sleep(25L);
        }
        if (!ready[0]) {
            scenario.close();
            throw new AssertionError("单词页面未在限定时间内完成初始化");
        }
        return scenario;
    }

    /**
     * 仅在显式请求时导出截图，供浅深色和大字体的人工视觉核对。
     * @param name 截图文件名，不包含扩展名
     * @throws IOException 截图无法保存时报告测试失败
     */
    private void captureIfRequested(String name) throws IOException {
        if (!"true".equals(InstrumentationRegistry.getArguments().getString("zhijian.captureUi"))) return;
        Bitmap screenshot = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertTrue(screenshot != null);
        File target = new File(context.getExternalFilesDir(null), name + ".png");
        try (FileOutputStream output = new FileOutputStream(target)) {
            assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally {
            screenshot.recycle();
        }
    }
}
