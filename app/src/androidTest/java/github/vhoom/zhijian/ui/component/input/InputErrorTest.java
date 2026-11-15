package github.vhoom.zhijian.ui.component.input;

import android.content.Context;
import android.content.res.Configuration;
import android.text.InputType;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import github.vhoom.zhijian.R;
import github.vhoom.zhijian.ui.component.settings.SettingsNumberStepper;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 共用错误容器的所有权、深浅色、尺寸及原始输入回归。
 *
 * 维护日期：2026年11月14日
 */
@RunWith(AndroidJUnit4.class)
public class InputErrorTest {
    /** 独立 ID 的提示各归其主；修改仅清除自己的错误，表单清理同时清除全部提示。 */
    @Test public void editingClearsOnlyTheOwningCaptionInBothThemes() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (int mode : new int[]{Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES}) {
                Context context = themed(mode);
                View root = LayoutInflater.from(context).inflate(R.layout.view_rule_frequency_parameters, null, false);
                EditText duration = root.findViewById(R.id.frequency_window_field);
                EditText count = root.findViewById(R.id.frequency_count_field);
                ManualInput.configure(duration, InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
                ManualInput.configure(count, InputType.TYPE_CLASS_NUMBER);
                InputError.show(duration, context.getString(R.string.rule_value_invalid));
                InputError.show(count, context.getString(R.string.rule_value_too_large));
                TextView own = caption(duration);
                TextView other = caption(count);
                assertTrue(own != other);
                assertEquals(context.getColor(R.color.error_deep), own.getCurrentTextColor());
                assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, own.getAccessibilityLiveRegion());
                duration.setText("1");
                assertEquals(View.GONE, own.getVisibility());
                assertEquals(View.VISIBLE, other.getVisibility());
                InputError.show(duration, context.getString(R.string.rule_value_invalid));
                InputError.clearAll(root);
                assertEquals(View.GONE, own.getVisibility());
                assertEquals(View.GONE, other.getVisibility());
                assertEquals("", own.getText().toString());
                assertEquals("", other.getText().toString());
                assertEquals("1", duration.getText().toString());
                assertNull(duration.getError());
                assertNull(count.getError());
            }
        });
    }

    /** 提示按既有紧凑触控宽度换行，不能撑宽数字框或进入输入正文。 */
    @Test public void longCaptionWrapsWithoutExpandingCompactControl() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = themed(Configuration.UI_MODE_NIGHT_NO);
            View root = LayoutInflater.from(context).inflate(R.layout.item_rule_value, null, false);
            EditText field = root.findViewById(R.id.param_field);
            InputError.bind(field);
            InputFieldLayout column = (InputFieldLayout) field.getParent().getParent();
            int width = View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.AT_MOST);
            int height = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
            column.measure(width, height);
            int initialWidth = column.getMeasuredWidth();
            InputError.show(field, context.getString(R.string.settings_rows_scan_unreadable));
            column.measure(width, height);
            column.layout(0, 0, column.getMeasuredWidth(), column.getMeasuredHeight());
            assertEquals(initialWidth, column.getMeasuredWidth());
            assertTrue(caption(field).getLineCount() > 1);
            assertTrue(caption(field).getTop() >= ((View) field.getParent()).getBottom());
            assertNull(field.getError());
        });
    }

    /** 容量缩减只更新按钮，符号、小数及字母原文不被过滤或夹成合法值。 */
    @Test public void dynamicCountCapacityKeepsInvalidRawDraftAndDisablesStepping() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = themed(Configuration.UI_MODE_NIGHT_NO);
            View root = LayoutInflater.from(context).inflate(R.layout.dialog_word_challenge, null, false);
            EditText field = root.findViewById(R.id.field_word_question_count);
            AtomicInteger capacity = new AtomicInteger(10);
            SettingsNumberStepper stepper = SettingsNumberStepper.bindCount(field, 10, 1, capacity::get, null);
            View increase = root.findViewById(R.id.stepper_word_question_count_increase);
            View decrease = root.findViewById(R.id.stepper_word_question_count_decrease);
            assertFalse(increase.isEnabled());
            capacity.set(5);
            stepper.refreshBounds();
            assertEquals("10", field.getText().toString());
            assertFalse(increase.isEnabled());
            assertFalse(decrease.isEnabled());
            for (String raw : new String[]{"+2", "2.0", "2e1", "2abc", "999999999999999999999999"}) {
                field.getText().replace(0, field.length(), raw);
                assertEquals(raw, field.getText().toString());
                assertFalse(increase.isEnabled());
                assertFalse(decrease.isEnabled());
                assertNull(field.getError());
            }
        });
    }

    /** @param field 已绑定输入 @return 所属下方提示 */
    private static TextView caption(EditText field) {
        return (TextView) field.getTag(R.id.input_error_binding);
    }

    /** @param nightMode 深浅配置 @return 正式规则主题上下文 */
    private static Context themed(int nightMode) {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Configuration configuration = new Configuration(context.getResources().getConfiguration());
        configuration.uiMode = (configuration.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | nightMode;
        return new ContextThemeWrapper(context.createConfigurationContext(configuration), R.style.Theme_Zhijian_RuleEditor);
    }
}
