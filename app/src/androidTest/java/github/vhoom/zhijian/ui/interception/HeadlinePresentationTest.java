package github.vhoom.zhijian.ui.interception;

import android.content.Context;
import android.content.res.Configuration;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;

import github.vhoom.zhijian.R;
import github.vhoom.zhijian.data.model.policy.ControlLevel;
import github.vhoom.zhijian.domain.interception.InterceptionDecision;
import github.vhoom.zhijian.ui.interception.page.support.HeadlineScrollView;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * 所有拦截布局共享主句位置，调整屏幕或字体后仍居中偏上且保持长内容可达。
 *
 * 维护日期：2026年11月08日
 */
public class HeadlinePresentationTest {
    /** 普通选择、最终询问、闭关和休息主句保持左对齐及中心上移八 dp。 */
    @Test public void choiceHeadlinesStayEightDpAboveViewportCenter() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (ActivityScenario<InterceptionActivity> scenario = ActivityScenario.launch(
                InterceptionActivity.debugIntent(context, InterceptionDecision.Kind.BLOCK, ControlLevel.MORTAL, "headline-position"))) {
            scenario.onActivity(activity -> {
                int[] layouts = {R.layout.view_interception_initial,
                        R.layout.view_interception_confirmation, R.layout.view_interception_forbidden};
                for (int layout : layouts) {
                    View root = LayoutInflater.from(activity).inflate(layout, null);
                    HeadlineScrollView scroll = findScroll(root);
                    assertNotNull(scroll);
                    TextView title = (TextView) ((ViewGroup) scroll.getChildAt(0)).getChildAt(0);
                    title.setText("先停一下。\n看看自己现在需要什么。");
                    for (int height : new int[]{640, 480, 720}) {
                        measure(root, activity, height);
                        float expected = scroll.getHeight() / 2f
                                - activity.getResources().getDimensionPixelSize(R.dimen.interception_headline_offset);
                        assertEquals(expected, title.getTop() + title.getHeight() / 2f, 1f);
                        assertEquals(Gravity.START, title.getGravity() & Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK);
                        assertEquals(View.TEXT_ALIGNMENT_VIEW_START, title.getTextAlignment());
                    }
                }
            });
        }
    }

    /** 两种挑战按内容一起留位；浅深色、大字体和窄屏仍可到达挑战末尾。 */
    @Test public void challengeHeadlinesMoveUpAndKeepContentReachable() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (ActivityScenario<InterceptionActivity> scenario = ActivityScenario.launch(
                InterceptionActivity.debugIntent(context, InterceptionDecision.Kind.BLOCK, ControlLevel.REPAIR, "challenge-layout"))) {
            scenario.onActivity(activity -> {
                for (int night : new int[]{Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES}) {
                    for (float font : new float[]{1f, 2f}) {
                        Configuration configuration = new Configuration(activity.getResources().getConfiguration());
                        configuration.fontScale = font;
                        configuration.uiMode = (configuration.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | night;
                        Context themed = new ContextThemeWrapper(activity.createConfigurationContext(configuration), activity.getTheme());
                        for (int layout : new int[]{R.layout.view_interception_friction_hold, R.layout.view_interception_friction_word}) {
                            View root = LayoutInflater.from(themed).inflate(layout, null);
                            HeadlineScrollView scroll = findScroll(root);
                            ViewGroup content = (ViewGroup) scroll.getChildAt(0);
                            TextView title = (TextView) content.getChildAt(0);
                            title.setText("先慢下来。\n给自己一点空白。");
                            TextView prompt = root.findViewById(R.id.interception_word_prompt);
                            if (prompt != null) prompt.setText("选出 attention 的中文含义");
                            measure(root, themed, 640);
                            assertTrue(title.getTop() + title.getHeight() / 2f < scroll.getHeight() / 2f);
                            assertEquals(View.TEXT_ALIGNMENT_VIEW_START, title.getTextAlignment());
                            if (prompt != null) assertEquals(1, prompt.getLineCount());
                            measure(root, themed, 320);
                            scroll.scrollTo(0, content.getHeight());
                            assertTrue(scroll.getScrollY() + scroll.getHeight() >= content.getHeight());
                        }
                    }
                }
            });
        }
    }

    /** 大字体长主句从安全顶部开始，滚动到底可以到达整段内容的末尾。 */
    @Test public void oversizedHeadlineRemainsFullyScrollable() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (ActivityScenario<InterceptionActivity> scenario = ActivityScenario.launch(
                InterceptionActivity.debugIntent(context, InterceptionDecision.Kind.BLOCK, ControlLevel.MORTAL, "headline-long"))) {
            scenario.onActivity(activity -> {
                Configuration configuration = new Configuration(activity.getResources().getConfiguration());
                configuration.fontScale = 2f;
                Context themed = new ContextThemeWrapper(activity.createConfigurationContext(configuration), activity.getTheme());
                View root = LayoutInflater.from(themed).inflate(R.layout.view_interception_friction_hold, null);
                HeadlineScrollView scroll = findScroll(root);
                ViewGroup content = (ViewGroup) scroll.getChildAt(0);
                TextView title = (TextView) content.getChildAt(0);
                title.setText("先停一下。\n看看自己现在需要什么。\n给自己一段完整的休息。\n让注意力缓一缓。\n然后再决定接下来的安排。");
                measure(root, themed, 320);
                assertTrue(title.getTop() >= themed.getResources().getDimensionPixelSize(R.dimen.interception_headline_offset));
                assertEquals(title.getText().length(), title.getLayout().getLineEnd(title.getLineCount() - 1));
                assertTrue(content.getHeight() > scroll.getHeight());
                scroll.scrollTo(0, content.getHeight());
                assertEquals(content.getHeight(), scroll.getScrollY() + scroll.getHeight());
            });
        }
    }

    /** @param root 布局根 @return 当前独立页的共享主句滚动容器，未找到时为空 */
    private static HeadlineScrollView findScroll(View root) {
        if (root instanceof HeadlineScrollView) return (HeadlineScrollView) root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int index = 0; index < group.getChildCount(); index++) {
                HeadlineScrollView found = findScroll(group.getChildAt(index));
                if (found != null) return found;
            }
        }
        return null;
    }

    /** @param root 待测量布局 @param context 密度上下文 @param heightDp 测试视口高度 */
    private static void measure(View root, Context context, int heightDp) {
        float density = context.getResources().getDisplayMetrics().density;
        int width = Math.round(320 * density), height = Math.round(heightDp * density);
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
    }
}
