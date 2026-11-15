package github.vhoom.zhijian.ui.activity;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.ViewAction;
import androidx.test.espresso.action.GeneralClickAction;
import androidx.test.espresso.action.Press;
import androidx.test.espresso.action.Tap;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import github.vhoom.zhijian.R;
import github.vhoom.zhijian.data.model.settings.ThemeMode;
import github.vhoom.zhijian.data.store.ZhijianStorage;
import github.vhoom.zhijian.data.store.settings.GlobalSettings;
import github.vhoom.zhijian.ui.component.navigation.SwipePager;
import github.vhoom.zhijian.util.config.AppConfig;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.TimeUnit;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.pressBack;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.action.ViewActions.swipeLeft;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isChecked;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.*;

/**
 * 验证首页引导、翻页导航、主题切换与偏好恢复的页面集成行为。
 *
 * 维护日期：2026年11月14日
 */
@RunWith(AndroidJUnit4.class)
public class SetupNavigationTest {
    /** 首页状态在展开、折叠与重建后均可见，颜色与实际授权信号一致。 */
    @Test
    public void homeFunctionStatusRemainsVisibleWhenCollapsed() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(this::assertFunctionStatus);
            scenario.onActivity(activity -> activity.findViewById(R.id.setup_header).performClick());
            scenario.onActivity(this::assertFunctionStatus);
            scenario.recreate();
            scenario.onActivity(this::assertFunctionStatus);
        }
    }

    /**
     * 根据系统当前可验证状态断言文案、颜色和无障碍描述。
     *
     * @param activity 当前首页实例
     */
    private void assertFunctionStatus(MainActivity activity) {
        github.vhoom.zhijian.util.permission.PermissionStatusReader.Snapshot state =
                github.vhoom.zhijian.util.permission.PermissionStatusReader.read(activity);
        github.vhoom.zhijian.util.permission.ProtectionStatus status =
                github.vhoom.zhijian.util.permission.ProtectionStatus.resolve(state.usageAccess,
                        state.accessibilityInstalled, state.accessibilityEnabled,
                        state.accessibilityConnected, state.backgroundRestricted, state.displayUnconfirmed);
        int text = status == github.vhoom.zhijian.util.permission.ProtectionStatus.MISSING_PERMISSION
                ? R.string.setup_function_missing_permission
                : status == github.vhoom.zhijian.util.permission.ProtectionStatus.WAITING_CONNECTION
                ? R.string.setup_function_waiting_connection
                : status == github.vhoom.zhijian.util.permission.ProtectionStatus.BACKGROUND_RESTRICTED
                ? R.string.setup_function_background_restricted
                : status == github.vhoom.zhijian.util.permission.ProtectionStatus.DISPLAY_UNCONFIRMED
                ? R.string.setup_function_display_unconfirmed : R.string.setup_function_running;
        int color = status == github.vhoom.zhijian.util.permission.ProtectionStatus.MISSING_PERMISSION
                ? R.color.error_deep : status == github.vhoom.zhijian.util.permission.ProtectionStatus.RUNNING
                ? R.color.level_selected_text : R.color.warning_deep;
        TextView label = activity.findViewById(R.id.setup_function_status);
        assertTrue(label.isShown());
        assertEquals(activity.getString(text), label.getText().toString());
        assertEquals(activity.getColor(color), label.getCurrentTextColor());
        assertTrue(activity.findViewById(R.id.setup_header).getContentDescription()
                .toString().contains(label.getText()));
    }
    private SharedPreferences preferences;
    private boolean hadGuidePreference;
    private boolean originalGuidePreference;
    private boolean hadPoemPreference;
    private boolean originalPoemPreference;
    private boolean hadThemePreference;
    private int originalThemePreference;

    /**
     * 保存原偏好并准备可见指导卡片供导航测试。
     */
    @Before
    public void prepareVisibleGuideAndPreserveUserPreference() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ZhijianStorage.get(context).ready().get(30, TimeUnit.SECONDS);
        preferences = context.getSharedPreferences("global_settings", Context.MODE_PRIVATE);
        hadGuidePreference = preferences.contains("show_setup_guide");
        originalGuidePreference = preferences.getBoolean("show_setup_guide", true);
        hadPoemPreference = preferences.contains("show_daily_poem");
        originalPoemPreference = preferences.getBoolean("show_daily_poem", true);
        hadThemePreference = preferences.contains("theme_mode");
        originalThemePreference = preferences.getInt("theme_mode", ThemeMode.SYSTEM.ordinal());
        preferences.edit().putBoolean("show_setup_guide", true).putBoolean("show_daily_poem", true).commit();
    }

    /**
     * 只恢复测试改变的偏好，不清除其他设置。
     */
    @After
    public void restoreOnlyChangedPreference() {
        SharedPreferences.Editor editor = preferences.edit();
        if (hadGuidePreference) editor.putBoolean("show_setup_guide", originalGuidePreference);
        else editor.remove("show_setup_guide");
        if (hadPoemPreference) editor.putBoolean("show_daily_poem", originalPoemPreference);
        else editor.remove("show_daily_poem");
        if (hadThemePreference) editor.putInt("theme_mode", originalThemePreference);
        else editor.remove("theme_mode");
        editor.commit();
    }

    /** 两种主题下，标题左右和顶部留白均能切换展开状态，重建后仍可点击。 */
    @Test
    public void guideHeaderEdgesToggleInBothThemesAndAfterRecreation() {
        for (ThemeMode mode : new ThemeMode[]{ThemeMode.LIGHT, ThemeMode.DARK}) {
            preferences.edit().putInt("theme_mode", mode.ordinal()).commit();
            try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
                onView(withId(R.id.setup_card)).perform(scrollTo(),
                        clickCardEdge(Gravity.LEFT, R.id.setup_header));
                assertGuideExpanded(scenario, false);
                onView(withId(R.id.setup_card)).perform(clickCardEdge(Gravity.RIGHT, R.id.setup_header));
                assertGuideExpanded(scenario, true);
                onView(withId(R.id.setup_card)).perform(clickCardEdge(Gravity.TOP, R.id.setup_header));
                assertGuideExpanded(scenario, false);
                scenario.recreate();
                assertGuideExpanded(scenario, false);
                onView(withId(R.id.setup_toggle)).perform(click());
                assertGuideExpanded(scenario, true);
            }
        }
    }

    /** 后台入口侧边和底部直接打开说明，电源入口提供独立说明且不改变系统权限。 */
    @Test
    public void backgroundAndBatteryGuidesOpenDirectlyInBothThemes() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String background = context.getString(R.string.setup_background_detail, context.getString(R.string.app_name));
        for (ThemeMode mode : new ThemeMode[]{ThemeMode.LIGHT, ThemeMode.DARK}) {
            preferences.edit().putInt("theme_mode", mode.ordinal()).commit();
            try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
                onView(withId(R.id.setup_background)).perform(scrollTo(),
                        clickCardEdge(Gravity.LEFT, R.id.setup_background));
                onView(withId(android.R.id.message)).check((view, missing) ->
                        assertTrue(((TextView) view).getText().toString().endsWith(background)));
                pressBack();
                onView(withId(R.id.setup_background)).perform(
                        clickCardEdge(Gravity.BOTTOM, R.id.setup_background));
                onView(withId(android.R.id.message)).check((view, missing) ->
                        assertTrue(((TextView) view).getText().toString().endsWith(background)));
                pressBack();
                onView(withId(R.id.setup_battery)).perform(scrollTo(), click());
                onView(withId(android.R.id.message)).check((view, missing) -> {
                    boolean exempt = github.vhoom.zhijian.util.permission.PermissionStatusReader.read(context).batteryExempt;
                    assertEquals(context.getString(exempt ? R.string.setup_battery_enabled_detail
                                    : R.string.setup_battery_detail, context.getString(R.string.app_name)),
                            ((TextView) view).getText().toString());
                });
                pressBack();
            }
        }
    }

    /**
     * 在卡片边缘注入真实点击，避免 performClick 跳过触摸区域命中检查。
     *
     * @param edge 点击的几何边缘，使用 Gravity.LEFT、RIGHT、TOP 或 BOTTOM
     * @param rowId 左右边缘点击需要对齐的行资源 ID
     * @return 点击卡片留白区域的 Espresso 操作
     */
    private static ViewAction clickCardEdge(int edge, int rowId) {
        return new GeneralClickAction(Tap.SINGLE, card -> {
            int[] cardPosition = new int[2];
            card.getLocationOnScreen(cardPosition);
            View row = card.findViewById(rowId);
            int[] rowPosition = new int[2];
            row.getLocationOnScreen(rowPosition);
            int inset = card.getResources().getDimensionPixelSize(R.dimen.space_xxs);
            float x = cardPosition[0] + card.getWidth() / 2f;
            float y = rowPosition[1] + row.getHeight() / 2f;
            switch (edge) {
                case Gravity.LEFT:
                    x = cardPosition[0] + inset;
                    break;
                case Gravity.RIGHT:
                    x = cardPosition[0] + card.getWidth() - inset;
                    break;
                case Gravity.TOP:
                    y = cardPosition[1] + inset;
                    break;
                case Gravity.BOTTOM:
                    y = cardPosition[1] + card.getHeight() - inset;
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported card edge: " + edge);
            }
            return new float[]{x, y};
        }, Press.FINGER);
    }

    /**
     * 检查点击后的引导展开状态。
     *
     * @param scenario 正在运行的首页测试场景
     * @param expanded 引导内容是否应该可见
     */
    private static void assertGuideExpanded(ActivityScenario<MainActivity> scenario, boolean expanded) {
        scenario.onActivity(activity -> assertEquals(expanded ? View.VISIBLE : View.GONE,
                activity.findViewById(R.id.setup_body).getVisibility()));
    }

    /**
     * 验证滑动、标题点击及页面重建后的选择保持同步。
     */
    @Test
    public void swipeAndTitleTapStayInSyncAcrossRecreation() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            onView(withId(R.id.pager)).perform(swipeLeft());
            scenario.onActivity(this::assertControlPage);
            scenario.recreate();
            scenario.onActivity(this::assertControlPage);
            onView(withId(R.id.title_dashboard)).perform(click());
            scenario.onActivity(activity -> {
                assertEquals(0, ((SwipePager) activity.findViewById(R.id.pager)).getDisplayedChild());
                assertTrue(activity.findViewById(R.id.title_dashboard).isSelected());
                assertFalse(activity.findViewById(R.id.title_control).isSelected());
                assertTrue(((TextView) activity.findViewById(R.id.title_dashboard)).getTextSize()
                        > ((TextView) activity.findViewById(R.id.title_control)).getTextSize());
            });
            scenario.onActivity(activity -> activity.findViewById(R.id.setup_header).performClick());
            scenario.recreate();
            scenario.onActivity(activity -> {
                assertEquals(View.GONE, activity.findViewById(R.id.setup_body).getVisibility());
                View card = activity.findViewById(R.id.setup_card);
                View header = activity.findViewById(R.id.setup_header);
                assertEquals(card.getPaddingTop() + header.getHeight() + card.getPaddingBottom(), card.getHeight());
            });
        }
    }

    /**
     * 验证返回首页后隐藏指导生效，设置页仍保留入口。
     */
    @Test
    public void hidingHomeGuideTakesEffectOnReturnAndKeepsSettingsEntry() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            onView(withId(R.id.btn_settings)).perform(click());
            onView(withId(R.id.switch_show_guide)).perform(scrollTo(), click());
            onView(withId(R.id.setup_card)).perform(scrollTo()).check(matches(isDisplayed()));
            onView(withId(R.id.setup_autostart)).perform(scrollTo()).check(matches(isDisplayed()));
            onView(withId(R.id.setup_battery)).perform(scrollTo()).check(matches(isDisplayed()));
            onView(withId(R.id.setup_background)).perform(scrollTo()).check(matches(isDisplayed()));
            pressBack();
            scenario.onActivity(activity -> {
                assertFalse(new GlobalSettings(activity, new AppConfig(activity)).showSetupGuide());
                assertEquals(View.GONE, activity.findViewById(R.id.setup_card).getVisibility());
            });
            scenario.recreate();
            scenario.onActivity(activity -> assertEquals(View.GONE,
                    activity.findViewById(R.id.setup_card).getVisibility()));
            onView(withId(R.id.btn_settings)).perform(click());
            onView(withId(R.id.switch_show_guide)).check(matches(not(isChecked())));
            onView(withId(R.id.setup_card)).perform(scrollTo()).check(matches(isDisplayed()));
            pressBack();
        }
    }

    /** 两种主题下古诗开关位于引导开关下方，返回与重建均保持显隐设置。 */
    @Test public void poemSwitchUpdatesHomeAndSurvivesRecreationInBothThemes() {
        for (ThemeMode mode : new ThemeMode[]{ThemeMode.LIGHT, ThemeMode.DARK}) {
            preferences.edit().putInt("theme_mode", mode.ordinal()).commit();
            try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
                scenario.onActivity(activity -> assertEquals(View.VISIBLE,
                        activity.findViewById(R.id.strength_poem).getVisibility()));
                onView(withId(R.id.btn_settings)).perform(click());
                onView(withId(R.id.switch_show_daily_poem)).perform(scrollTo()).check((view, missing) -> {
                    View guide = view.getRootView().findViewById(R.id.switch_show_guide);
                    assertSame(guide.getParent(), view.getParent());
                    assertTrue(view.getTop() >= guide.getBottom());
                });
                onView(withId(R.id.switch_show_daily_poem)).check(matches(isChecked())).perform(click());
                onView(withId(R.id.switch_show_guide)).check(matches(isChecked()));
                pressBack();
                scenario.onActivity(activity -> assertEquals(View.GONE,
                        activity.findViewById(R.id.strength_poem).getVisibility()));
                scenario.recreate();
                scenario.onActivity(activity -> assertEquals(View.GONE,
                        activity.findViewById(R.id.strength_poem).getVisibility()));
                onView(withId(R.id.btn_settings)).perform(click());
                onView(withId(R.id.switch_show_daily_poem)).perform(scrollTo())
                        .check(matches(not(isChecked()))).perform(click());
                pressBack();
                scenario.onActivity(activity -> assertEquals(View.VISIBLE,
                        activity.findViewById(R.id.strength_poem).getVisibility()));
            }
        }
    }

    /**
     * 断言系统指导卡片及其控制入口位于正确页面。
     *
     * @param activity 执行本次界面操作的 Activity
     */
    private void assertControlPage(MainActivity activity) {
        TextView home = activity.findViewById(R.id.title_dashboard);
        TextView control = activity.findViewById(R.id.title_control);
        assertEquals(1, ((SwipePager) activity.findViewById(R.id.pager)).getDisplayedChild());
        assertTrue(control.isSelected());
        assertFalse(home.isSelected());
        assertTrue(control.getTextSize() > home.getTextSize());
        assertTrue(home.getLeft() < control.getLeft());
    }
}
