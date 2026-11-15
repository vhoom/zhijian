package github.vhoom.zhijian.ui.component.rule;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.ContextThemeWrapper;
import android.widget.TextView;
import android.view.View;
import android.widget.EditText;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import github.vhoom.zhijian.R;
import github.vhoom.zhijian.ui.component.input.InputError;
import github.vhoom.zhijian.data.model.policy.RuleDayParameterAdapter;
import github.vhoom.zhijian.data.model.policy.RuleDayParameters;
import github.vhoom.zhijian.data.model.policy.RuleGroup;
import github.vhoom.zhijian.data.model.policy.RuleGroupValidator;
import github.vhoom.zhijian.data.model.policy.RuleType;
import github.vhoom.zhijian.data.model.policy.RuleWindow;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 两个规则编辑入口共用的原生次数输入和 Bundle 草稿恢复回归。
 *
 * 维护日期：2026年11月14日
 */
@RunWith(AndroidJUnit4.class)
public class RuleCountInputTest {
    /** 回填 99 次不误触发草稿写入，实际输入仍只通知一次。 */
    @Test
    public void countBackfillDoesNotBecomeAUserEdit() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            EditText field = field();
            AtomicInteger writes = new AtomicInteger();
            RuleParameterFactory.bindCount(field, 99, value -> writes.incrementAndGet());
            assertEquals("99", field.getText().toString());
            assertNull(field.getHint());
            assertEquals(0, writes.get());
            field.setText("1");
            assertEquals(1, writes.get());
            assertNull(field.getError());
        });
    }

    /** 三种限次编辑时保留原输入且不报错，保存候选才检查空值与 1–99 范围。 */
    @Test
    public void countRangeErrorsAreDeferredUntilSavingAllThreeRuleTypes() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (RuleType type : new RuleType[]{RuleType.DAILY_COUNT, RuleType.WINDOW_COUNT, RuleType.ROLLING_COUNT}) {
                RuleGroup draft = new RuleGroup(0L, "次数校验");
                draft.types.add(type);
                draft.passDurationMillis = 60_000L;
                draft.frequencyWindowMillis = 60_000L;
                RuleWindow window = new RuleWindow("count-window", RuleType.WINDOW_COUNT, 60, 120, 0L);
                if (type == RuleType.WINDOW_COUNT) draft.windows.add(window);
                EditText field = field();
                RuleParameterFactory.bindCount(field, 0, value -> {
                    if (type == RuleType.DAILY_COUNT) draft.count = Math.toIntExact(value);
                    else if (type == RuleType.ROLLING_COUNT) draft.frequencyCount = Math.toIntExact(value);
                    else window.limitCount = Math.toIntExact(value);
                });
                assertNull(field.getHint());
                for (String value : new String[]{"1", "99"}) {
                    field.setText(value);
                    assertNull(field.getError());
                    assertTrue(RuleGroupValidator.validateConfiguration(draft).isValid());
                }
                for (String value : new String[]{"0", "100", "9999", ""}) {
                    field.setText(value);
                    assertNull(field.getError());
                    RuleGroupValidator.Result result = RuleGroupValidator.validateConfiguration(draft);
                    assertEquals(RuleGroupValidator.Error.COUNT_OUT_OF_RANGE, result.error);
                    assertEquals(type, result.type);
                    assertEquals(value, field.getText().toString());
                }
            }
        });
    }

    /** 保存留下的下方错误在继续编辑时清除，清空输入仍回到未填写的零值。 */
    @Test
    public void clearingCountInputKeepsAnUnsetDraftAndClearsTheError() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            EditText field = field();
            AtomicLong written = new AtomicLong();
            RuleParameterFactory.bindCount(field, 0, written::set);
            field.setText("100");
            assertNull(field.getError());
            InputError.show(field, field.getContext().getString(R.string.rule_value_invalid));
            TextView error = (TextView) field.getTag(R.id.input_error_binding);
            assertNotNull(error);
            assertEquals(View.VISIBLE, error.getVisibility());
            assertNull(field.getError());
            field.setText("");
            assertEquals(0L, written.get());
            assertEquals(View.GONE, error.getVisibility());
            assertNull(field.getError());
            field.setText("99");
            assertEquals(99L, written.get());
            assertNull(field.getError());
        });
    }

    /** 页面重建同时恢复当前输入和已保存七日次数，源草稿的后续修改不能覆盖保存状态。 */
    @Test
    public void draftBundleRestoresCurrentAndSavedWeekdayCountsIndependently() {
        RuleGroup group = new RuleGroup(1L, "限次草稿");
        group.types.add(RuleType.ROLLING_COUNT);
        group.types.add(RuleType.WINDOW_COUNT);
        group.frequencyWindowMillis = 6_000L;
        group.frequencyCount = 2;
        RuleWindow window = new RuleWindow("count-window", RuleType.WINDOW_COUNT, 1320, 120, 0L);
        window.limitCount = 3;
        group.windows.add(window);
        RuleDayParameters initial = RuleDayParameterAdapter.capture(group);
        RuleDayParameters monday = initial.copy();
        monday.frequencyCount = 99;
        monday.windows.get(0).limitCount = 99;
        RuleDayParameterAdapter.applyToDays(group, 1, monday, initial);
        Bundle state = new Bundle();
        RuleDraftState.write(state, "count-draft", group);
        group.frequencyCount = 99;
        group.windows.get(0).limitCount = 99;
        group.weekdayRules.get(1).frequencyCount = 1;
        RuleGroup restored = RuleDraftState.read(state, "count-draft");
        assertEquals(6_000L, restored.frequencyWindowMillis);
        assertEquals(2, restored.frequencyCount);
        assertEquals(3, restored.windows.get(0).limitCount);
        assertEquals(99, restored.weekdayRules.get(1).frequencyCount);
        assertEquals(99, restored.weekdayRules.get(1).windows.get(0).limitCount);
        assertEquals(2, restored.weekdayRules.get(2).frequencyCount);
        assertEquals(3, restored.weekdayRules.get(2).windows.get(0).limitCount);
    }

    /** @return 使用正式带错误容器的布局创建、供主线程绑定的次数输入框 */
    private static EditText field() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Context themed = new ContextThemeWrapper(context, R.style.Theme_Zhijian);
        return LayoutInflater.from(themed).inflate(R.layout.item_rule_value, null, false).findViewById(R.id.param_field);
    }
}
