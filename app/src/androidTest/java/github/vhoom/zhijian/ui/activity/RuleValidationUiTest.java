package github.vhoom.zhijian.ui.activity;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import github.vhoom.zhijian.ZhijianApplication;
import github.vhoom.zhijian.R;
import github.vhoom.zhijian.data.model.policy.RuleType;
import github.vhoom.zhijian.data.model.policy.RuleGroup;
import github.vhoom.zhijian.data.model.policy.RuleGroupValidator;
import github.vhoom.zhijian.data.model.policy.RuleWindow;
import github.vhoom.zhijian.ui.component.rule.RuleConditionsEditor;
import github.vhoom.zhijian.data.store.AppPolicyStore;
import github.vhoom.zhijian.ui.component.rule.RuleValidationFeedback;
import github.vhoom.zhijian.ui.component.input.InputFieldLayout;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static org.hamcrest.Matchers.allOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 两个真实规则入口的保存提示、错误定位、原文恢复和反馈生命周期回归。
 *
 * 维护日期：2026年11月14日
 */
@RunWith(AndroidJUnit4.class)
public class RuleValidationUiTest {
    /** 等待后台初始化完成，再启动真实规则配置页面。 */
    @Before
    public void awaitApplicationReady() throws Exception {
        ((ZhijianApplication) context().getApplicationContext()).ready().get(30, TimeUnit.SECONDS);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    /** 保存失败保留次数，提示位于原字段下方，系统重建释放旧提示。 */
    @Test
    public void countSaveFailureKeepsTextFocusAndAnUncommittedPolicy() {
        try (ActivityScenario<AppPolicyActivity> scenario = launchPolicy()) {
            scenario.onActivity(activity -> {
                View card = card(activity, RuleType.DAILY_COUNT);
                ((Switch) card.findViewById(R.id.type_check)).setChecked(true);
                EditText field = card.findViewById(R.id.param_field);
                field.setText("100");
                assertNull(field.getError());
                activity.findViewById(R.id.btn_save).performClick();
            });
            assertInlineError(R.string.rule_value_too_large);

            scenario.onActivity(activity -> {
                EditText field = card(activity, RuleType.DAILY_COUNT).findViewById(R.id.param_field);
                assertEquals("100", field.getText().toString());
                assertError(field, "数值过大");
                assertTrue(field.hasFocus());
                assertFalse(activity.isFinishing());
                String packageName = activity.getIntent().getStringExtra(AppPolicyActivity.EXTRA_PACKAGE);
                assertFalse(new AppPolicyStore(activity).hasOverride(packageName));
                activity.findViewById(R.id.btn_save).performClick();
            });
            assertInlineError(R.string.rule_value_too_large);
            scenario.recreate();
            onView(withText(R.string.rule_value_too_large)).check(doesNotExist());
            scenario.onActivity(activity -> assertEquals("100",
                    ((EditText) card(activity, RuleType.DAILY_COUNT).findViewById(R.id.param_field)).getText().toString()));
        }
    }

    /** 收起的规则仍校验已填上界，错误提示留在卡片外部且不重新启用。 */
    @Test
    public void collapsedInvalidInputUsesTheCardWithoutEnablingItsRule() {
        try (ActivityScenario<AppPolicyActivity> scenario = launchPolicy()) {
            scenario.onActivity(activity -> {
                View card = card(activity, RuleType.DAILY_COUNT);
                Switch toggle = card.findViewById(R.id.type_check);
                toggle.setChecked(true);
                ((EditText) card.findViewById(R.id.param_field)).setText("100");
                toggle.setChecked(false);
                activity.findViewById(R.id.btn_save).performClick();
            });
            assertInlineError(R.string.rule_value_too_large);

            scenario.onActivity(activity -> {
                View card = card(activity, RuleType.DAILY_COUNT);
                assertFalse(((Switch) card.findViewById(R.id.type_check)).isChecked());
                assertEquals(View.GONE, card.findViewById(R.id.condition_body).getVisibility());
                TextView error = card.findViewById(R.id.condition_error);
                assertEquals(View.VISIBLE, error.getVisibility());
                assertEquals("数值过大", error.getText().toString());
                assertEquals("100", ((EditText) card.findViewById(R.id.param_field)).getText().toString());
            });
        }
    }

    /** 连续休息与破例放行分别定位原字段，继续输入时清除旧错误。 */
    @Test
    public void restAndPassFailuresLocateTheirOwnFields() {
        try (ActivityScenario<AppPolicyActivity> scenario = launchPolicy()) {
            scenario.onActivity(activity -> {
                View card = card(activity, RuleType.CONTINUOUS);
                ((Switch) card.findViewById(R.id.type_check)).setChecked(true);
                parameter(card, R.id.window_limit_row).setText("10");
                parameter(card, R.id.window_pause_row).setText("720.1");
                activity.findViewById(R.id.btn_save).performClick();
            });
            assertInlineError(R.string.rule_value_too_large);

            scenario.onActivity(activity -> {
                View card = card(activity, RuleType.CONTINUOUS);
                EditText rest = parameter(card, R.id.window_pause_row);
                assertTrue(rest.hasFocus());
                assertEquals("720.1", rest.getText().toString());
                assertNull(parameter(card, R.id.window_limit_row).getError());
                rest.setText("1");
                assertNull(rest.getError());
                ((EditText) activity.findViewById(R.id.field_pass)).setText("720");
                activity.findViewById(R.id.btn_save).performClick();
            });
            assertInlineError(R.string.rule_value_too_large);

            scenario.onActivity(activity -> {
                EditText pass = activity.findViewById(R.id.field_pass);
                assertTrue(pass.hasFocus());
                assertEquals("720", pass.getText().toString());
                assertError(pass, "数值过大");
            });
        }
    }

    /** 时段增删、时间选择器和系统重建不丢失非法原文，也不截断合法的放行时长。 */
    @Test
    public void rawWindowTextSurvivesRedrawPickerAndRecreation() {
        try (ActivityScenario<AppPolicyActivity> scenario = launchPolicy()) {
            scenario.onActivity(activity -> {
                View card = card(activity, RuleType.WINDOW_LIMIT);
                ((Switch) card.findViewById(R.id.type_check)).setChecked(true);
                LinearLayout body = card.findViewById(R.id.condition_body);
                parameter(body.getChildAt(0), R.id.window_limit_row).setText("12.34");
                body.findViewById(R.id.btn_add_window).performClick();
                assertEquals("12.34", parameter(body.getChildAt(0), R.id.window_limit_row).getText().toString());
                body.getChildAt(1).findViewById(R.id.window_remove).performClick();
                assertEquals("12.34", parameter(body.getChildAt(0), R.id.window_limit_row).getText().toString());
                ((EditText) activity.findViewById(R.id.field_pass)).setText("719.9");
                body.getChildAt(0).findViewById(R.id.tv_start).performClick();
            });
            onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click());
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.recreate();
            scenario.onActivity(activity -> {
                View row = ((LinearLayout) card(activity, RuleType.WINDOW_LIMIT)
                        .findViewById(R.id.condition_body)).getChildAt(0);
                assertEquals("12.34", parameter(row, R.id.window_limit_row).getText().toString());
                assertEquals("719.9", ((EditText) activity.findViewById(R.id.field_pass)).getText().toString());
                assertNull(parameter(row, R.id.window_limit_row).getError());
                activity.findViewById(R.id.btn_save).performClick();
            });
            assertInlineError(R.string.rule_value_invalid);

            scenario.onActivity(activity -> {
                View row = ((LinearLayout) card(activity, RuleType.WINDOW_LIMIT)
                        .findViewById(R.id.condition_body)).getChildAt(0);
                assertError(parameter(row, R.id.window_limit_row), "无效，请修改");
                assertEquals("12.34", parameter(row, R.id.window_limit_row).getText().toString());
            });
        }
    }

    /** 过长名称保留原文，系统重建不截断；其他参数保存失败也不回填空名称。 */
    @Test
    public void invalidGroupNameRemainsIntactAfterSaveFailureAndRecreation() {
        String text = "  123456789012345678901  ";
        try (ActivityScenario<RuleGroupEditActivity> scenario = ActivityScenario.launch(
                RuleGroupEditActivity.newIntent(context()))) {
            scenario.onActivity(activity -> {
                EditText name = activity.findViewById(R.id.field_name);
                name.setText(text);
                assertEquals(text, name.getText().toString());
                assertNull(name.getError());
                activity.findViewById(R.id.btn_save).performClick();
            });
            assertInlineError(R.string.rule_value_invalid);

            scenario.onActivity(activity -> {
                EditText name = activity.findViewById(R.id.field_name);
                assertTrue(name.hasFocus());
                assertEquals(text, name.getText().toString());
                assertError(name, "无效，请修改");
            });
            scenario.recreate();
            scenario.onActivity(activity -> {
                EditText name = activity.findViewById(R.id.field_name);
                assertEquals(text, name.getText().toString());
                name.setText("");
                ((EditText) activity.findViewById(R.id.field_pass)).setText("720");
                activity.findViewById(R.id.btn_save).performClick();
            });
            assertInlineError(R.string.rule_value_too_large);

            scenario.onActivity(activity -> assertEquals("",
                    ((EditText) activity.findViewById(R.id.field_name)).getText().toString()));
        }
    }

    /** 相同端点的错误放在结束框下方，不污染开始时间的输入正文。 */
    @Test public void identicalWindowEndpointsMarkOnlyTheEndCaption() {
        try (ActivityScenario<AppPolicyActivity> scenario = launchPolicy()) {
            scenario.onActivity(activity -> {
                RuleGroup draft = new RuleGroup(0L, "时段端点");
                draft.passDurationMillis = 60_000L;
                draft.types.add(RuleType.WINDOW_BAN);
                draft.windows.add(new RuleWindow("equal-endpoints", RuleType.WINDOW_BAN, 60, 60, 0L));
                RuleConditionsEditor editor = new RuleConditionsEditor(activity,
                        activity.findViewById(R.id.type_rows), draft, () -> { });
                try {
                    RuleGroupValidator.Result result = RuleGroupValidator.validateConfiguration(draft);
                    assertEquals(RuleGroupValidator.Error.WINDOW_INVALID, result.error);
                    View target = editor.showError(result);
                    TextView start = card(activity, RuleType.WINDOW_BAN).findViewById(R.id.tv_start);
                    TextView end = card(activity, RuleType.WINDOW_BAN).findViewById(R.id.tv_end);
                    assertTrue(target == end);
                    assertEquals(View.GONE, ((TextView) start.getTag(R.id.input_error_binding)).getVisibility());
                    assertEquals(View.VISIBLE, ((TextView) end.getTag(R.id.input_error_binding)).getVisibility());
                    assertNull(start.getError());
                    assertNull(end.getError());
                } finally { editor.close(); }
            });
        }
    }

    /** 生命周期关闭不会运行纠错回调或重新定位旧输入。 */
    @Test
    public void closingFeedbackSuppressesItsCorrectionCallback() {
        AtomicInteger corrections = new AtomicInteger();
        try (ActivityScenario<AppPolicyActivity> scenario = launchPolicy()) {
            scenario.onActivity(activity -> {
                RuleValidationFeedback feedback = new RuleValidationFeedback(activity);
                feedback.close();
                feedback.show(false, () -> {
                    corrections.incrementAndGet();
                    return activity.findViewById(R.id.field_pass);
                });
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertEquals(0, corrections.get());
            onView(withText(R.string.rule_value_invalid)).check(doesNotExist());
        }
    }

    /** @return 使用独立临时包名、不会覆盖实际应用配置的编辑页面 */
    private static ActivityScenario<AppPolicyActivity> launchPolicy() {
        Intent intent = new Intent(context(), AppPolicyActivity.class)
                .putExtra(AppPolicyActivity.EXTRA_PACKAGE, "github.vhoom.zhijian.validation." + System.nanoTime());
        return ActivityScenario.launch(intent);
    }

    /** @return 被测应用上下文 */
    private static Context context() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    /** @param activity 当前真实编辑页面 @param type 需要查找的规则类型 @return 对应规则卡片 */
    private static View card(Activity activity, RuleType type) {
        LinearLayout rules = activity.findViewById(R.id.type_rows);
        for (int index = 0; index < rules.getChildCount(); index++) {
            View card = rules.getChildAt(index);
            if (card.getTag() == type) return card;
        }
        throw new AssertionError("Missing rule card: " + type);
    }

    /** @param host 规则卡片或时段行 @param rowId 使用或休息行的 ID @return 原生数字输入框 */
    private static EditText parameter(View host, int rowId) {
        return host.findViewById(rowId).findViewById(R.id.param_field);
    }

    /** @param message 预期提示；规则保存失败不打开独立校验弹窗 */
    private static void assertInlineError(int message) {
        onView(allOf(withText(message), isDisplayed())).check((view, missing) -> {
            if (missing != null) throw missing;
            assertNotNull(view);
            boolean ownCaption = view.getParent() instanceof InputFieldLayout
                    && ((InputFieldLayout) view.getParent()).errorCaption() == view;
            assertTrue(ownCaption || view.getId() == R.id.condition_error);
            assertFalse(view.getRootView().findViewById(android.R.id.button1) != null);
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    /** @param field 错误所属输入 @param message 预期下方文字；原生错误保持为空 */
    private static void assertError(EditText field, String message) {
        assertNull(field.getError());
        TextView caption = (TextView) field.getTag(R.id.input_error_binding);
        assertNotNull(caption);
        assertTrue(caption.getParent() instanceof InputFieldLayout);
        assertEquals(message, caption.getText().toString());
        assertTrue(caption.isShown());
    }
}
