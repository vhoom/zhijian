package github.vhoom.zhijian.ui.interception.preview;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;

import github.vhoom.zhijian.R;
import github.vhoom.zhijian.data.model.interception.InterceptionMode;
import github.vhoom.zhijian.data.model.policy.ControlLevel;
import github.vhoom.zhijian.data.model.settings.SecondsRange;
import github.vhoom.zhijian.data.store.settings.InteractionSettingsStore;
import github.vhoom.zhijian.domain.interception.InterceptionDecision;
import github.vhoom.zhijian.ui.interception.InterceptionActivity;
import github.vhoom.zhijian.ui.interception.preview.InterceptionPreviewCatalog.Page;
import github.vhoom.zhijian.ui.interception.preview.InterceptionPreviewCatalog.Presentation;
import github.vhoom.zhijian.ui.interception.request.InterceptionIntents;
import github.vhoom.zhijian.util.config.AppConfig;

import org.junit.Before;
import org.junit.Test;

import java.util.Map;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assert.*;

/**
 * Debug 菜单、预设重建及按压主句的真实页面回归。
 *
 * 维护日期：2026年11月14日
 */
public class InterceptionPreviewTest {
    private Context context;

    /** 等待异步存储加载，使测试可独立运行。 */
    @Before public void prepare() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ((github.vhoom.zhijian.ZhijianApplication) context.getApplicationContext())
                .ready().get(10, java.util.concurrent.TimeUnit.SECONDS);
    }

    /** 创建任意预览不会推进真实拦截的持久文案序号。 */
    @Test public void previewRequestsDoNotAdvanceProductionCopy() {
        Map<String, ?> before = context.getSharedPreferences("interception_copy", Context.MODE_PRIVATE).getAll();
        for (int index = 0; index < InterceptionPreviewCatalog.PAGE_COUNT; index++) {
            InterceptionIntents.debugPageIntent(context, index, 17, Presentation.BLIND_PASS);
            InterceptionIntents.debugIntent(context, InterceptionDecision.Kind.BLOCK, ControlLevel.REPAIR, "isolated-preview-" + index);
        }
        assertEquals(before, context.getSharedPreferences("interception_copy", Context.MODE_PRIVATE).getAll());
    }

    /** 面板可直接选页、切换触发类型、重抽和前后翻页。 */
    @Test public void menuSelectsPageKindAndSample() {
        try (ActivityScenario<InterceptionActivity> scenario = ActivityScenario.launch(InterceptionIntents.debugGalleryIntent(context))) {
            openMenu();
            onView(withText(R.string.interception_preview_pages)).perform(click());
            onView(withText(R.string.interception_preview_hold)).perform(click());
            scenario.onActivity(activity -> assertNotNull(activity.findViewById(R.id.interception_hold_title)));
            openMenu();
            onView(withText(context.getString(R.string.interception_preview_kind,
                    context.getString(R.string.interception_preview_reminder)))).perform(click());
            scenario.onActivity(activity -> {
                assertEquals("INTERRUPT", activity.getIntent().getStringExtra(InterceptionIntents.EXTRA_KIND));
                assertNotNull(activity.findViewById(R.id.interception_hold_title));
            });
            openMenu();
            onView(withText(R.string.interception_preview_resample)).perform(click());
            scenario.onActivity(activity -> assertEquals(1, activity.getIntent().getIntExtra(InterceptionIntents.EXTRA_DEBUG_SAMPLE, -1)));
            openMenu();
            onView(withText(R.string.interception_preview_next)).perform(click());
            scenario.onActivity(activity -> assertNotNull(activity.findViewById(R.id.interception_word_title)));
            openMenu();
            onView(withText(R.string.interception_preview_previous)).perform(click());
            scenario.onActivity(activity -> assertNotNull(activity.findViewById(R.id.interception_hold_title)));
        }
    }

    /** 图集内两个出口只反馈结果并留页，不修改真实放行记录。 */
    @Test public void galleryOutcomesKeepSampleAndDoNotPersistGrant() {
        github.vhoom.zhijian.data.store.ZhijianStorage storage = github.vhoom.zhijian.data.store.ZhijianStorage.get(context);
        Object before = storage.state().runtime.grantFor("debug.preview");
        Intent request = InterceptionIntents.debugPageIntent(context, Page.WUWEI.ordinal(), 4, Presentation.BUTTONS);
        try (ActivityScenario<InterceptionActivity> scenario = ActivityScenario.launch(request)) {
            scenario.onActivity(activity -> {
                String session = activity.getIntent().getStringExtra(InterceptionIntents.EXTRA_SESSION);
                activity.findViewById(R.id.interception_general_exit).performClick();
                assertFalse(activity.isFinishing());
                assertEquals(session, activity.getIntent().getStringExtra(InterceptionIntents.EXTRA_SESSION));
                activity.findViewById(R.id.interception_general_continue).performClick();
                assertFalse(activity.isFinishing());
                assertEquals(session, activity.getIntent().getStringExtra(InterceptionIntents.EXTRA_SESSION));
                assertNotNull(activity.findViewById(R.id.interception_general_title));
            });
        }
        assertEquals(before, storage.state().runtime.grantFor("debug.preview"));
    }

    /** 特殊样例可直接选择且重建保持，盲盒仍只有一个真实滑块。 */
    @Test public void menuSelectsSpecialsAndRecreationKeepsSample() {
        try (ActivityScenario<InterceptionActivity> scenario = ActivityScenario.launch(InterceptionIntents.debugGalleryIntent(context))) {
            openMenu();
            onView(withText(context.getString(R.string.interception_preview_presentation,
                    context.getString(R.string.interception_preview_random)))).perform(click());
            onView(withText(R.string.interception_preview_blind_pass)).perform(click());
            String[] session = new String[1];
            scenario.onActivity(activity -> {
                session[0] = activity.getIntent().getStringExtra(InterceptionIntents.EXTRA_SESSION);
                assertNotNull(activity.findViewById(R.id.interception_general_blind_box));
                assertEquals(1, ((LinearLayout) activity.findViewById(R.id.interception_general_actions)).getChildCount());
            });
            scenario.recreate();
            scenario.onActivity(activity -> {
                assertEquals(session[0], activity.getIntent().getStringExtra(InterceptionIntents.EXTRA_SESSION));
                assertNotNull(activity.findViewById(R.id.interception_general_blind_box));
            });
            openMenu();
            onView(withText(context.getString(R.string.interception_preview_presentation,
                    context.getString(R.string.interception_preview_blind_pass)))).perform(click());
            onView(withText(R.string.interception_preview_double_exit)).perform(click());
            scenario.onActivity(activity -> {
                assertNotNull(activity.findViewById(R.id.interception_general_exit));
                assertNotNull(activity.findViewById(R.id.interception_general_exit_secondary));
                assertNull(activity.findViewById(R.id.interception_general_continue));
            });
        }
    }

    /** 按压主句保持不变；松开后保留剩余进度，倒退到零时恢复完整秒数。 */
    @Test public void holdKeepsHeadlineWhileCountdownChanges() {
        InteractionSettingsStore settings = new InteractionSettingsStore(context, new AppConfig(context));
        SecondsRange original = settings.holdRange(InterceptionMode.REPAIR);
        settings.setHoldRange(InterceptionMode.REPAIR, new SecondsRange(5_000L, 5_000L));
        try (ActivityScenario<InterceptionActivity> scenario = ActivityScenario.launch(
                InterceptionIntents.debugPageIntent(context, Page.HOLD.ordinal()))) {
            String[] originalText = new String[2];
            scenario.onActivity(activity -> {
                originalText[0] = ((TextView) activity.findViewById(R.id.interception_hold_title)).getText().toString();
                originalText[1] = ((TextView) activity.findViewById(R.id.interception_hold_button)).getText().toString();
                touch(activity.findViewById(R.id.interception_hold_button), MotionEvent.ACTION_DOWN);
            });
            SystemClock.sleep(1_150L);
            scenario.onActivity(activity -> {
                assertEquals(originalText[0], ((TextView) activity.findViewById(R.id.interception_hold_title)).getText().toString());
                TextView button = activity.findViewById(R.id.interception_hold_button);
                assertNotEquals(originalText[1], button.getText().toString());
                assertTrue(button.getText().toString().startsWith("还需按住"));
                touch(button, MotionEvent.ACTION_UP);
                assertNotEquals(originalText[1], button.getText().toString());
                assertTrue(((android.widget.ProgressBar) activity.findViewById(R.id.interception_hold_progress)).getProgress() > 0);
                assertEquals(originalText[0], ((TextView) activity.findViewById(R.id.interception_hold_title)).getText().toString());
            });
            SystemClock.sleep(1_800L);
            scenario.onActivity(activity -> {
                assertEquals(0, ((android.widget.ProgressBar) activity.findViewById(R.id.interception_hold_progress)).getProgress());
                assertEquals(originalText[1], ((TextView) activity.findViewById(R.id.interception_hold_button)).getText().toString());
                assertEquals(originalText[0], ((TextView) activity.findViewById(R.id.interception_hold_title)).getText().toString());
            });
        } finally {
            settings.setHoldRange(InterceptionMode.REPAIR, original);
        }
    }

    /** 打开实际浮动预览入口。 */
    private static void openMenu() { onView(withId(R.id.interception_preview_launcher)).perform(click()); }

    /** @param view 按压控件 @param action 触摸动作 */
    private static void touch(View view, int action) {
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, view.getWidth() / 2f, view.getHeight() / 2f, 0);
        try { view.dispatchTouchEvent(event); } finally { event.recycle(); }
    }
}
