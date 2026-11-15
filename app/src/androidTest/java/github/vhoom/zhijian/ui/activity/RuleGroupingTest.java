package github.vhoom.zhijian.ui.activity;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;

import github.vhoom.zhijian.R;
import github.vhoom.zhijian.ZhijianApplication;
import github.vhoom.zhijian.data.model.policy.RuleType;

import org.junit.Test;
import org.junit.Before;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.matcher.ViewMatchers.withTagValue;
import static org.hamcrest.Matchers.is;

/**
 * 两个规则编辑入口的统一标题、共享额度说明、排序及草稿重建回归。
 *
 * 维护日期：2026年11月14日
 */
public class RuleGroupingTest {
    /** 等待应用后台初始化完成，再验证真实编辑控件而非加载页面。 */
    @Before public void awaitApplicationReady() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ((ZhijianApplication) context.getApplicationContext()).ready().get(30, TimeUnit.SECONDS);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    /** 规则组统一显示八条规则，连续提醒与新限次参数重建后仍保留。 */
    @Test public void ruleGroupUnifiesRulesAndRestoresReminderDraft() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (ActivityScenario<RuleGroupEditActivity> scenario = ActivityScenario.launch(
                new Intent(context, RuleGroupEditActivity.class))) {
            scenario.onActivity(activity -> {
                assertGrouping(activity);
                editReminderParameters(activity);
                editCountParameters(activity);
            });
            captureReminderScreenshots();
            scenario.recreate();
            scenario.onActivity(activity -> {
                assertGrouping(activity);
                assertRestoredReminderParameters(activity);
                assertRestoredCountParameters(activity);
            });
        }
    }

    /**
     * 按运行参数导出实际设备截图供视觉验收，普通回归不生成文件。
     */
    private static void captureReminderScreenshots() throws Exception {
        if (!"true".equals(InstrumentationRegistry.getArguments().getString("ruleScreenshots"))) return;
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory = context.getExternalFilesDir("ui-tests");
        if (directory == null || (!directory.isDirectory() && !directory.mkdirs())) {
            throw new AssertionError("Cannot create screenshot directory");
        }
        for (RuleType type : new RuleType[] { RuleType.CONTINUOUS, RuleType.WINDOW_CONTINUOUS }) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            onView(withTagValue(is((Object) type))).perform(scrollTo());
            InstrumentationRegistry.getInstrumentation().getUiAutomation().waitForIdle(300, 5_000);
            Bitmap bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
            if (bitmap == null) throw new AssertionError("Cannot capture screenshot");
            try (FileOutputStream output = new FileOutputStream(new File(directory, type.name() + ".png"))) {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
            } finally {
                bitmap.recycle();
            }
        }
    }

    /** 单应用规则入口使用同一个规则标题与横向参数，重建保留未提交草稿。 */
    @Test public void appPolicyUsesTheSameGrouping() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (ActivityScenario<AppPolicyActivity> scenario = ActivityScenario.launch(
                new Intent(context, AppPolicyActivity.class)
                        .putExtra(AppPolicyActivity.EXTRA_PACKAGE, "github.vhoom.zhijian.grouping.test"))) {
            scenario.onActivity(activity -> {
                assertGrouping(activity);
                editReminderParameters(activity);
                editCountParameters(activity);
            });
            scenario.recreate();
            scenario.onActivity(activity -> {
                assertGrouping(activity);
                assertRestoredReminderParameters(activity);
                assertRestoredCountParameters(activity);
            });
        }
    }

    /**
     * 启用两种新限次并填写独立次数，频率始终只有一条配置。
     *
     * @param activity 单应用或规则组的真实编辑页面
     */
    private static void editCountParameters(Activity activity) {
        View frequency = ruleCard(activity, RuleType.ROLLING_COUNT);
        ((Switch) frequency.findViewById(R.id.type_check)).setChecked(true);
        ((EditText) frequency.findViewById(R.id.frequency_window_field)).setText("0.1");
        ((EditText) frequency.findViewById(R.id.frequency_count_field)).setText("99");
        View window = ruleCard(activity, RuleType.WINDOW_COUNT);
        ((Switch) window.findViewById(R.id.type_check)).setChecked(true);
        View row = ((LinearLayout) window.findViewById(R.id.condition_body)).getChildAt(0);
        setParameter(row, R.id.window_limit_row, "2");
    }

    /**
     * 核对重建后的频率窗口、两项独立次数以及时段次数单位。
     *
     * @param activity 重建后的单应用或规则组编辑页面
     */
    private static void assertRestoredCountParameters(Activity activity) {
        View frequency = ruleCard(activity, RuleType.ROLLING_COUNT);
        assertTrue(((Switch) frequency.findViewById(R.id.type_check)).isChecked());
        LinearLayout body = frequency.findViewById(R.id.condition_body);
        assertEquals(1, body.getChildCount());
        assertNull(body.findViewById(R.id.btn_add_window));
        assertEquals("0.1", ((EditText) body.findViewById(R.id.frequency_window_field)).getText().toString());
        assertEquals("99", ((EditText) body.findViewById(R.id.frequency_count_field)).getText().toString());
        View window = ruleCard(activity, RuleType.WINDOW_COUNT);
        assertTrue(((Switch) window.findViewById(R.id.type_check)).isChecked());
        View row = ((LinearLayout) window.findViewById(R.id.condition_body)).getChildAt(0);
        View limit = row.findViewById(R.id.window_limit_row);
        assertEquals("2", ((EditText) limit.findViewById(R.id.param_field)).getText().toString());
        assertEquals(activity.getString(R.string.rule_unit_times),
                ((TextView) limit.findViewById(R.id.param_unit)).getText().toString());
    }

    /** @param activity 规则编辑页面；启用两种连续提醒并填写不同小数参数 */
    private static void editReminderParameters(Activity activity) {
        View allDay = ruleCard(activity, RuleType.CONTINUOUS);
        ((Switch) allDay.findViewById(R.id.type_check)).setChecked(true);
        View allDayParameters = ((LinearLayout) allDay.findViewById(R.id.condition_body)).getChildAt(0);
        setParameter(allDayParameters, R.id.window_limit_row, "3.5");
        setParameter(allDayParameters, R.id.window_pause_row, "4.5");

        View window = ruleCard(activity, RuleType.WINDOW_CONTINUOUS);
        ((Switch) window.findViewById(R.id.type_check)).setChecked(true);
        View windowParameters = ((LinearLayout) window.findViewById(R.id.condition_body))
                .getChildAt(0).findViewById(R.id.window_parameters);
        setParameter(windowParameters, R.id.window_limit_row, "20.5");
        setParameter(windowParameters, R.id.window_pause_row, "5.5");
    }

    /** @param activity 重建后的规则编辑页面；核对独立值与窄屏横向排版 */
    private static void assertRestoredReminderParameters(Activity activity) {
        View allDay = ruleCard(activity, RuleType.CONTINUOUS);
        assertTrue(((Switch) allDay.findViewById(R.id.type_check)).isChecked());
        LinearLayout allDayBody = allDay.findViewById(R.id.condition_body);
        assertTrue(allDayBody.getBackground() != null);
        assertEquals(1, allDayBody.getChildCount());
        assertInlineParameters(allDayBody.getChildAt(0), "3.5", "4.5");

        View window = ruleCard(activity, RuleType.WINDOW_CONTINUOUS);
        assertTrue(((Switch) window.findViewById(R.id.type_check)).isChecked());
        View windowRow = ((LinearLayout) window.findViewById(R.id.condition_body)).getChildAt(0);
        View windowParameters = windowRow.findViewById(R.id.window_parameters);
        assertInlineParameters(windowParameters, "20.5", "5.5");
        assertWindowPanel(windowRow);
        LinearLayout windowBody = window.findViewById(R.id.condition_body);
        assertTrue(windowBody.getBackground() != null);
        assertSame(windowBody, windowBody.findViewById(R.id.btn_add_window).getParent());
    }

    /** @param parameters 使用休息横向组合 @param rowId 参数行身份 @param value 输入值 */
    private static void setParameter(View parameters, int rowId, String value) {
        ((EditText) parameters.findViewById(rowId).findViewById(R.id.param_field)).setText(value);
    }

    /** @param activity 规则页面 @param type 稳定规则身份 @return 对应规则卡片 */
    private static View ruleCard(Activity activity, RuleType type) {
        LinearLayout rules = activity.findViewById(R.id.type_rows);
        for (int i = 0; i < rules.getChildCount(); i++) {
            View card = rules.getChildAt(i);
            if (card.getTag() == type) return card;
        }
        throw new AssertionError("Missing rule: " + type);
    }

    /**
     * 验证窄屏下两项仍在同一行、框随数字伸缩且标签不截断。
     * @param parameters 可横向滚动的参数组合
     * @param usage 预期使用分钟数
     * @param rest 预期休息分钟数
     */
    private static void assertInlineParameters(View parameters, String usage, String rest) {
        View viewport = parameters instanceof HorizontalScrollView ? parameters
                : (View) parameters.getParent().getParent();
        assertTrue(viewport instanceof HorizontalScrollView);
        View usageRow = parameters.findViewById(R.id.window_limit_row);
        View restRow = parameters.findViewById(R.id.window_pause_row);
        assertSame(usageRow.getParent(), restRow.getParent());
        assertEquals(LinearLayout.HORIZONTAL, ((LinearLayout) usageRow.getParent()).getOrientation());
        EditText usageField = usageRow.findViewById(R.id.param_field);
        EditText restField = restRow.findViewById(R.id.param_field);
        assertEquals(usage, usageField.getText().toString());
        assertEquals(rest, restField.getText().toString());
        int width = Math.round(224f * parameters.getResources().getDisplayMetrics().density);
        viewport.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        viewport.layout(0, 0, viewport.getMeasuredWidth(), viewport.getMeasuredHeight());
        assertEquals(((View) usageField.getParent()).getWidth(), ((View) restField.getParent()).getWidth());
        // 等权列分配奇数像素时允许一像素取整差异，输入框仍须保持相同宽度。
        assertEquals(usageRow.getWidth(), restRow.getWidth(), 1.0);
        assertNull(usageRow.getBackground());
        assertNull(restRow.getBackground());
        assertEquals(usageRow.getTop(), restRow.getTop());
        assertTrue(restRow.getLeft() >= usageRow.getRight());
        for (View row : new View[] { usageRow, restRow }) {
            TextView label = row.findViewById(R.id.param_label);
            assertUniformParameterText(row);
            assertCompactNumber(row.findViewById(R.id.param_field));
            assertTrue(label.getMeasuredWidth() >= Math.ceil(label.getPaint().measureText(label.getText().toString())));
            assertEquals(1, label.getLineCount());
            assertEquals(0, label.getLayout().getEllipsisCount(0));
        }
        assertMinimumFieldAndTouchArea(usageField, viewport, width);
    }

    /**
     * 时间与删除操作共用白色行，使用休息参数排列在下一行。
     * @param windowRow 已测量的时段子块
     */
    private static void assertWindowPanel(View windowRow) {
        LinearLayout grid = (LinearLayout) ((HorizontalScrollView) windowRow
                .findViewById(R.id.window_grid)).getChildAt(0);
        View parameters = windowRow.findViewById(R.id.window_parameters);
        View timeRow = windowRow.findViewById(R.id.window_time_row);
        assertSame(timeRow, grid.getChildAt(0));
        assertSame(parameters, grid.getChildAt(1));
        assertTrue(timeRow.getBackground() != null);
        assertEquals(timeRow.getWidth(), parameters.getWidth());
        assertTrue(parameters.getTop() > timeRow.getBottom());
        assertSame(timeRow, windowRow.findViewById(R.id.window_remove).getParent());
        LinearLayout timeLabels = (LinearLayout) timeRow;
        for (int i = 0; i < timeLabels.getChildCount(); i++) {
            View child = timeLabels.getChildAt(i);
            if (child instanceof TextView && child.getId() != R.id.window_remove) {
                assertParameterTextSize((TextView) child);
            }
        }
        View[] times = { windowRow.findViewById(R.id.tv_start), windowRow.findViewById(R.id.tv_end) };
        for (int i = 0; i < times.length; i++) {
            View timeTarget = (View) times[i].getParent();
            assertSame(timeRow, timeTarget.getParent());
            if (i > 0) assertTrue(timeTarget.getLeft() >= ((View) times[i - 1].getParent()).getRight());
            assertCompactNumber((TextView) times[i]);
        }
    }

    /**
     * 数字保留12sp，矩形框尊重最小宽度，透明外层保留完整触控范围。
     * @param field 时间或时长数字框
     */
    private static void assertCompactNumber(TextView field) {
        float density = field.getResources().getDisplayMetrics().density;
        assertParameterTextSize(field);
        View target = (View) field.getParent();
        assertTrue(target.getHeight() >= Math.round(48f * density));
        assertTrue(target.getWidth() >= Math.round(48f * density));
        assertTrue(target.getTouchDelegate() != null);
        int content = (int) Math.ceil(field.getPaint().measureText(field.getText().toString()))
                + field.getCompoundPaddingStart() + field.getCompoundPaddingEnd();
        assertEquals(Math.max(field.getMinWidth(), content), field.getWidth());
        assertTrue(field.getHeight() <= target.getHeight());
    }

    /**
     * 参数标签、单位和输入值共用12sp字号。
     * @param row 包含独立标签、单位及数字框的参数行
     */
    private static void assertUniformParameterText(View row) {
        assertParameterTextSize(row.findViewById(R.id.param_label));
        assertParameterTextSize(row.findViewById(R.id.param_unit));
        assertParameterTextSize(row.findViewById(R.id.param_field));
    }

    /**
     * 按当前系统字体缩放核对参数文字的实际像素字号。
     * @param text 参数标签、单位或数字框
     */
    private static void assertParameterTextSize(TextView text) {
        float scaledDensity = text.getResources().getDisplayMetrics().scaledDensity;
        assertEquals(12f * scaledDensity, text.getTextSize(), 0.5f);
    }

    /**
     * 验证内容与最小宽度共同决定边框，透明边缘仍能聚焦原生输入框。
     * @param field 已绑定的分钟输入框
     * @param viewport 参数的横向滚动容器
     * @param width 当前测试可用宽度
     */
    private static void assertMinimumFieldAndTouchArea(EditText field, View viewport, int width) {
        String original = field.getText().toString();
        try {
            field.setText("1");
            viewport.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            viewport.layout(0, 0, viewport.getMeasuredWidth(), viewport.getMeasuredHeight());
            int narrow = field.getWidth();
            field.setText("9999");
            viewport.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            viewport.layout(0, 0, viewport.getMeasuredWidth(), viewport.getMeasuredHeight());
            int content = (int) Math.ceil(field.getPaint().measureText(field.getText().toString()))
                    + field.getCompoundPaddingStart() + field.getCompoundPaddingEnd();
            assertEquals(Math.max(field.getMinWidth(), content), field.getWidth());
            assertTrue(field.getWidth() >= narrow);
            field.setText("1");
            viewport.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            viewport.layout(0, 0, viewport.getMeasuredWidth(), viewport.getMeasuredHeight());
            assertEquals(narrow, field.getWidth());
            View target = (View) field.getParent();
            field.clearFocus();
            long now = SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 1, target.getHeight() / 2f, 0);
            MotionEvent up = MotionEvent.obtain(now, now + 10, MotionEvent.ACTION_UP, 1, target.getHeight() / 2f, 0);
            try {
                assertTrue(target.dispatchTouchEvent(down));
                assertTrue(target.dispatchTouchEvent(up));
                assertTrue(field.hasFocus());
            } finally {
                down.recycle();
                up.recycle();
            }
        } finally {
            field.setText(original);
            viewport.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            viewport.layout(0, 0, viewport.getMeasuredWidth(), viewport.getMeasuredHeight());
        }
    }

    /** 首次查看和程序重建不自动开启，用户首次启用规则后开启总开关并保留草稿状态。 */
    @Test public void independentPolicyEnablesOnlyAfterEditingARule() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (ActivityScenario<AppPolicyActivity> scenario = ActivityScenario.launch(
                new Intent(context, AppPolicyActivity.class).putExtra(AppPolicyActivity.EXTRA_PACKAGE,
                        "github.vhoom.zhijian.edit.test." + System.nanoTime()))) {
            scenario.onActivity(activity -> assertFalse(((Switch) activity.findViewById(R.id.switch_policy)).isChecked()));
            scenario.recreate();
            scenario.onActivity(activity -> {
                assertFalse(((Switch) activity.findViewById(R.id.switch_policy)).isChecked());
                View firstRule = ((LinearLayout) activity.findViewById(R.id.type_rows)).getChildAt(0);
                ((Switch) firstRule.findViewById(R.id.type_check)).setChecked(true);
                assertTrue(((Switch) activity.findViewById(R.id.switch_policy)).isChecked());
            });
            scenario.recreate();
            scenario.onActivity(activity -> assertTrue(((Switch) activity.findViewById(R.id.switch_policy)).isChecked()));
        }
    }

    /**
     * 验证两入口的统一标题及八条规则顺序，规则组共享说明位于标题与规则之间。
     * @param activity 当前规则编辑页面
     */
    private static void assertGrouping(Activity activity) {
        LinearLayout rules = activity.findViewById(R.id.type_rows);
        TextView title = activity.findViewById(R.id.rule_section_title);
        assertEquals("提醒规则", title.getText().toString());
        assertTrue(title.isAccessibilityHeading());
        RuleType[] expected = { RuleType.DAILY_LIMIT, RuleType.DAILY_COUNT, RuleType.ROLLING_COUNT,
                RuleType.CONTINUOUS, RuleType.WINDOW_LIMIT, RuleType.WINDOW_COUNT,
                RuleType.WINDOW_CONTINUOUS, RuleType.WINDOW_BAN };
        assertEquals(expected.length, rules.getChildCount());
        for (int i = 0; i < expected.length; i++) {
            View row = rules.getChildAt(i);
            assertEquals(expected[i], row.getTag());
            assertEquals(activity.getString(expected[i].nameRes),
                    ((TextView) row.findViewById(R.id.condition_title)).getText().toString());
            if (expected[i] == RuleType.DAILY_LIMIT || expected[i] == RuleType.DAILY_COUNT) {
                assertUniformParameterText(row);
            }
        }
        LinearLayout sections = (LinearLayout) rules.getParent();
        TextView sharedQuota = activity.findViewById(R.id.rule_shared_quota_hint);
        if (activity instanceof RuleGroupEditActivity) {
            assertNotNull(sharedQuota);
            assertEquals(activity.getString(R.string.rule_group_shared_quota_hint),
                    sharedQuota.getText().toString());
            assertEquals(View.VISIBLE, sharedQuota.getVisibility());
            assertParameterTextSize(sharedQuota);
            assertEquals(sections.indexOfChild(title) + 1, sections.indexOfChild(sharedQuota));
            assertEquals(sections.indexOfChild(sharedQuota) + 1, sections.indexOfChild(rules));
        } else {
            assertNull(sharedQuota);
            assertEquals(sections.indexOfChild(title) + 1, sections.indexOfChild(rules));
        }
        assertTrue(sections.indexOfChild(activity.findViewById(R.id.weekday_editor))
                < sections.indexOfChild(title));
        View pass = activity.findViewById(R.id.field_pass);
        assertParameterTextSize((TextView) pass);
        while (pass.getParent() != sections) pass = (View) pass.getParent();
        assertTrue(sections.indexOfChild(rules) < sections.indexOfChild(pass));
    }
}
