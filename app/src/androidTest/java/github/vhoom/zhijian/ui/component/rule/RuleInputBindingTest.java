package github.vhoom.zhijian.ui.component.rule;

import android.content.Context;
import android.os.Bundle;
import android.widget.EditText;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import github.vhoom.zhijian.data.model.policy.RuleInputLimits;
import github.vhoom.zhijian.data.model.policy.RuleType;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * 原始规则输入的粘贴、保存校验和不触发用户通知的文本恢复回归。
 *
 * 维护日期：2026年11月13日
 */
@RunWith(AndroidJUnit4.class)
public class RuleInputBindingTest {
    /** 粘贴的额外小数位保留在原文中，直到保存校验时才报告格式无效。 */
    @Test
    public void pastedPrecisionIsPreservedInsteadOfBeingFilteredIntoAValidValue() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            EditText field = field();
            AtomicLong written = new AtomicLong();
            NumberRow number = RuleParameterFactory.bindDuration(field, 60_000L, written::set);
            RuleInputBinding input = new RuleInputBinding(field, number, RuleInputLimits::pass, () -> true);
            field.getText().replace(0, field.length(), "12.34");
            assertEquals("12.34", field.getText().toString());
            assertEquals(0L, written.get());
            assertNull(field.getError());
            assertEquals(RuleInputLimits.Status.INVALID, input.validate().status);
            field.getText().replace(0, field.length(), "719.9");
            assertEquals("719.9", field.getText().toString());
            assertEquals(43_194_000L, written.get());
            assertNull(input.validate());
        });
    }

    /** 大于整数解析能力的次数保留原文，保存错误仍归为数值过大。 */
    @Test
    public void countOverflowIsNotReplacedOrMisreportedAsMissingInput() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            EditText field = field();
            NumberRow number = RuleParameterFactory.bindCount(field, 1, ignored -> { });
            RuleInputBinding input = new RuleInputBinding(field, number, RuleInputLimits::count, () -> true);
            String huge = "999999999999999999999999999";
            field.getText().replace(0, field.length(), huge);
            assertEquals(huge, field.getText().toString());
            assertNull(field.getHint());
            assertNull(field.getError());
            assertEquals(RuleInputLimits.Status.TOO_LARGE, input.validate().status);
            field.getText().replace(0, field.length(), "1.5");
            assertEquals("1.5", field.getText().toString());
            assertEquals(RuleInputLimits.Status.INVALID, input.validate().status);
        });
    }

    /** 数值草稿同为零时，保存仍能区别明确零值与用户清空的必填字段。 */
    @Test
    public void explicitDailyZeroAndEmptyTextHaveDifferentSaveResults() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            EditText field = field();
            AtomicLong written = new AtomicLong();
            NumberRow number = RuleParameterFactory.bindDuration(field, 0L, written::set);
            RuleInputBinding input = new RuleInputBinding(field, number,
                    () -> RuleInputLimits.usage(RuleType.DAILY_LIMIT, 0L), () -> true);
            field.setText("0");
            assertEquals(0L, written.get());
            assertNull(input.validate());
            field.setText("");
            assertEquals(0L, written.get());
            assertEquals(RuleInputLimits.Status.INVALID, input.validate().status);
            assertNull(field.getError());
        });
    }

    /** 系统恢复保留非法原文而不修改数值草稿或触发用户编辑回调。 */
    @Test
    public void rawTextBundleRestoresWithoutWritingTheParsedDraft() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            EditText original = field();
            NumberRow originalNumber = RuleParameterFactory.bindDuration(original, 60_000L, ignored -> { });
            RuleInputBinding originalInput = new RuleInputBinding(original, originalNumber,
                    RuleInputLimits::rest, () -> true);
            original.setText("12.34");
            Bundle state = new Bundle();
            originalInput.save(state, "rest");
            EditText restored = field();
            AtomicInteger writes = new AtomicInteger();
            NumberRow restoredNumber = RuleParameterFactory.bindDuration(restored, 0L, ignored -> writes.incrementAndGet());
            RuleInputBinding restoredInput = new RuleInputBinding(restored, restoredNumber,
                    RuleInputLimits::rest, () -> true);
            restoredInput.restore(state, "rest");
            assertEquals("12.34", restored.getText().toString());
            assertEquals(0, writes.get());
            assertNotNull(restoredInput.validate());
            assertNull(restored.getError());
            restored.setText("1.5");
            assertEquals(1, writes.get());
            assertNull(restoredInput.validate());
        });
    }

    /** 保存校验和草稿解析共用去首尾空格后的文本，避免有效数字被保存成零。 */
    @Test
    public void paddedPasteUsesTheSameNormalizedValueForDraftAndValidation() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            EditText field = field();
            AtomicLong written = new AtomicLong();
            NumberRow number = RuleParameterFactory.bindDuration(field, 0L, written::set);
            RuleInputBinding input = new RuleInputBinding(field, number, RuleInputLimits::pass, () -> true);
            StringBuilder text = new StringBuilder();
            for (int index = 0; index < 70; index++) text.append(' ');
            text.append("12.3");
            field.setText(text);
            assertEquals(text.toString(), field.getText().toString());
            assertEquals(738_000L, written.get());
            assertNull(input.validate());
        });
    }

    /** @return 用应用上下文创建、由主线程操作的原生输入框 */
    private static EditText field() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        return new EditText(context);
    }
}
