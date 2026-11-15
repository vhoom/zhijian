package github.vhoom.zhijian.ui.preview;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;

import github.vhoom.zhijian.R;
import github.vhoom.zhijian.ui.preview.SetupPreviewCatalog.Scenario;
import github.vhoom.zhijian.ui.setup.SetupGuideDialogFactory.Guide;

import org.junit.Test;

import static androidx.test.espresso.Espresso.onData;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.anything;
import static org.junit.Assert.*;

/**
 * 共用配置界面的状态预览回归，核对条件警示、五项独立入口和重建恢复。
 *
 * 维护日期：2026年11月10日
 */
public class SetupGuidePreviewTest {
    /** 所有说明保留系统操作按钮；条件警示不覆盖检查步骤，预览操作留在当前页面。 */
    @Test public void everyScenarioKeepsInstructionsAndUsesPreviewActions() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (ActivityScenario<DeveloperPreviewActivity> activityScenario = ActivityScenario.launch(DeveloperPreviewActivity.class)) {
            for (Scenario sample : Scenario.values()) {
                selectScenario(sample);
                openDescription();
                onView(withId(android.R.id.message)).check((view, missing) ->
                        assertInstructions(context, sample, ((TextView) view).getText().toString()));
                onView(withId(android.R.id.button2)).check(matches(withText(android.R.string.cancel)));
                onView(withId(android.R.id.button1)).check(matches(withText(R.string.setup_go_settings)));
                if (sample.guide == Guide.ACCESSIBILITY) {
                    onView(withId(android.R.id.button3)).check(matches(withText(R.string.setup_open_app_details)))
                            .perform(click());
                    assertEquals(Lifecycle.State.RESUMED, activityScenario.getState());
                    openDescription();
                }
                onView(withId(android.R.id.button1)).perform(click());
                assertEquals(Lifecycle.State.RESUMED, activityScenario.getState());
            }
        }
    }

    /** 切换实际布局并重建后保留选中的状态、五项顺序和首页收起选择。 */
    @Test public void layoutsAndRecreationKeepFiveOrderedEntriesAndSelectedState() {
        try (ActivityScenario<DeveloperPreviewActivity> scenario = ActivityScenario.launch(DeveloperPreviewActivity.class)) {
            scenario.onActivity(activity -> assertFiveEntries(activity, false));
            selectScenario(Scenario.BACKGROUND_BOTH);
            scenario.onActivity(activity -> activity.findViewById(R.id.setup_header).performClick());
            scenario.recreate();
            scenario.onActivity(activity -> {
                assertSelected(activity, Scenario.BACKGROUND_BOTH);
                assertEquals(View.GONE, activity.findViewById(R.id.setup_body).getVisibility());
            });
            onView(withId(R.id.setup_preview_layout)).perform(scrollTo(), click());
            scenario.onActivity(activity -> assertFiveEntries(activity, true));
            scenario.recreate();
            scenario.onActivity(activity -> {
                assertSelected(activity, Scenario.BACKGROUND_BOTH);
                assertFiveEntries(activity, true);
            });
            onView(withId(R.id.setup_preview_layout)).perform(scrollTo(), click());
            scenario.onActivity(activity -> {
                assertFiveEntries(activity, false);
                assertEquals(View.GONE, activity.findViewById(R.id.setup_body).getVisibility());
                activity.findViewById(R.id.setup_header).performClick();
                assertEquals(View.VISIBLE, activity.findViewById(R.id.setup_body).getVisibility());
                TextView status = activity.findViewById(R.id.setup_background).findViewById(R.id.setup_status);
                assertEquals(activity.getColor(R.color.error_deep), status.getCurrentTextColor());
                TextView battery = activity.findViewById(R.id.setup_battery).findViewById(R.id.setup_status);
                assertEquals(activity.getColor(R.color.level_selected_text), battery.getCurrentTextColor());
            });
        }
    }

    /**
     * 使用真实状态选择列表，避免通过测试专用接口跳过界面操作。
     * @param sample 目录中需要选择的样例
     */
    private static void selectScenario(Scenario sample) {
        onView(withId(R.id.setup_preview_choose)).perform(scrollTo(), click());
        onData(anything()).atPosition(sample.ordinal()).perform(click());
    }

    /** 打开当前样例的共用原生说明。 */
    private static void openDescription() {
        onView(withId(R.id.setup_preview_description)).perform(scrollTo(), click());
    }

    /**
     * 核对版本提示与后台警示组合，所有前缀之后仍有完整操作正文。
     * @param context 读取当前应用文案资源的上下文
     * @param sample 用户通过目录选择的状态
     * @param message 实际弹窗展示的完整说明
     */
    private static void assertInstructions(Context context, Scenario sample, String message) {
        assertFalse(message.isEmpty());
        String appName = context.getString(R.string.app_name);
        if (sample.guide == Guide.ACCESSIBILITY) {
            assertEquals(sample.sdkLevel >= 33 && !sample.state.accessibility.isEnabled(),
                    message.contains(context.getString(R.string.setup_accessibility_restricted_detail)));
        } else if (sample.guide == Guide.BACKGROUND) {
            String base = context.getString(R.string.setup_background_detail, appName);
            String restricted = context.getString(R.string.setup_background_restricted_detail);
            String unconfirmed = context.getString(R.string.setup_display_unconfirmed_detail,
                    context.getString(R.string.settings_about_title), context.getString(R.string.diagnostics_title));
            assertTrue(message.endsWith(base));
            assertEquals(sample.state.backgroundRestricted, message.contains(restricted));
            assertEquals(sample.state.displayUnconfirmed, message.contains(unconfirmed));
            if (sample.state.backgroundRestricted && sample.state.displayUnconfirmed) {
                assertTrue(message.indexOf(restricted) < message.indexOf(unconfirmed));
                assertTrue(message.indexOf(unconfirmed) < message.indexOf(base));
            }
        } else if (sample.guide == Guide.BATTERY) {
            assertEquals(context.getString(sample.state.batteryExempt ? R.string.setup_battery_enabled_detail
                    : R.string.setup_battery_detail, appName), message);
        } else if (sample.guide == Guide.USAGE) {
            assertEquals(context.getString(R.string.setup_usage_detail, appName), message);
        } else if (sample.guide == Guide.AUTOSTART) {
            assertEquals(context.getString(R.string.setup_autostart_detail, appName), message);
        }
    }

    /**
     * 核对五项在实际网格或紧凑列表中的独立入口和顺序。
     * @param activity 当前开发者页面
     * @param settingsLayout 是否应当显示设置列表
     */
    private static void assertFiveEntries(DeveloperPreviewActivity activity, boolean settingsLayout) {
        int[] ids = {R.id.setup_accessibility, R.id.setup_usage, R.id.setup_battery,
                settingsLayout ? R.id.setup_background : R.id.setup_autostart,
                settingsLayout ? R.id.setup_autostart : R.id.setup_background};
        int[] titles = {R.string.setup_accessibility_title, R.string.setup_usage_title,
                R.string.setup_battery_title,
                settingsLayout ? R.string.setup_background_title : R.string.setup_autostart_title,
                settingsLayout ? R.string.setup_autostart_title : R.string.setup_background_title};
        ViewGroup body = activity.findViewById(R.id.setup_body);
        assertEquals(settingsLayout ? 9 : 3, body.getChildCount());
        for (int index = 0; index < ids.length; index++) {
            View entry = settingsLayout ? body.getChildAt(index * 2)
                    : ((ViewGroup) body.getChildAt(index / 2)).getChildAt(index % 2);
            assertEquals(ids[index], entry.getId());
            assertTrue(entry.isClickable());
            if (!settingsLayout && ids[index] == R.id.setup_background) {
                assertEquals(1, ((ViewGroup) entry.getParent()).getChildCount());
            }
            assertEquals(activity.getString(titles[index]), ((TextView) entry.findViewById(R.id.setup_title)).getText().toString());
        }
    }

    /**
     * 核对恢复的状态名称和目录索引，而非设备此刻的真实权限。
     * @param activity 重建后的开发者页面
     * @param sample 应当继续显示的样例
     */
    private static void assertSelected(DeveloperPreviewActivity activity, Scenario sample) {
        assertEquals(activity.getString(R.string.setup_preview_scenario_value, sample.ordinal() + 1,
                        SetupPreviewCatalog.count(), activity.getString(sample.labelRes)),
                ((TextView) activity.findViewById(R.id.setup_preview_scenario)).getText().toString());
    }
}
