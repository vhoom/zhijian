package github.vhoom.zhijian.ui.component.dialog;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import github.vhoom.zhijian.R;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 在 Android 实际读取窗口属性的边界验证弹窗主题，防止百分比资源导致设置弹窗闪退。
 * 配置上下文仅在用例内切换深浅色，不修改用户的主题或设置数据。
 *
 * 维护日期：2026年11月14日
 */
@RunWith(AndroidJUnit4.class)
public class DialogThemeTest {
    /** 浅色主题下确认框、日期与时间选择器的遮罩均可被窗口按浮点数读取。 */
    @Test public void lightDialogThemesExposeFloatDimAmount() {
        assertDialogThemes(Configuration.UI_MODE_NIGHT_NO);
    }

    /** 深色主题的资源覆盖同样必须满足系统窗口的浮点数契约。 */
    @Test public void darkDialogThemesExposeFloatDimAmount() {
        assertDialogThemes(Configuration.UI_MODE_NIGHT_YES);
    }

    /**
     * 验证应用的原生弹窗主题入口以及底部弹窗直接使用的共用主题。
     * @param nightMode 当前用例的 UI_MODE_NIGHT_NO 或 UI_MODE_NIGHT_YES
     */
    private void assertDialogThemes(int nightMode) {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Configuration configuration = new Configuration(target.getResources().getConfiguration());
        configuration.uiMode = (configuration.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | nightMode;
        Context context = new ContextThemeWrapper(target.createConfigurationContext(configuration),
                R.style.Theme_Zhijian);
        TypedArray themes = context.obtainStyledAttributes(new int[] {
                android.R.attr.alertDialogTheme,
                android.R.attr.datePickerDialogTheme,
                android.R.attr.timePickerDialogTheme
        });
        try {
            for (int index = 0; index < themes.length(); index++) {
                int theme = themes.getResourceId(index, 0);
                assertTrue("原生弹窗入口必须提供主题", theme != 0);
                assertWindowDimAmount(context, theme);
            }
        } finally {
            themes.recycle();
        }
        assertWindowDimAmount(context, R.style.Theme_Zhijian_Dialog);
    }

    /**
     * 执行与 PhoneWindow 相同的 getFloat 调用；fraction 资源会在此抛出转换异常。
     * @param context 带当前用例深浅色配置的页面主题上下文
     * @param themeId 待验证的弹窗主题资源
     */
    private void assertWindowDimAmount(Context context, int themeId) {
        Context dialog = new ContextThemeWrapper(context, themeId);
        TypedArray attributes = dialog.obtainStyledAttributes(
                new int[] {android.R.attr.backgroundDimAmount});
        try {
            float amount = attributes.getFloat(0, -1f);
            assertEquals(TypedValue.TYPE_FLOAT, attributes.getType(0));
            assertEquals(0.4f, amount, 0.0001f);
        } finally {
            attributes.recycle();
        }
    }
}
