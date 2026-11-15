package github.vhoom.zhijian.ui.component.rule;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;

import androidx.test.platform.app.InstrumentationRegistry;

import github.vhoom.zhijian.R;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * 验证长列表不会撑满外页，短列表不保留空白滚动区。
 *
 * 维护日期：2026年11月08日
 */
public class RuleAppListScrollViewTest {
    /** 内容较少时随内容收缩，无内部滚动。 */
    @Test public void shortListUsesOnlyItsContentHeight() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            RuleAppListScrollView scroll = measuredList(2);
            assertEquals(2 * rowHeight(scroll.getContext()), scroll.getMeasuredHeight());
            assertFalse(scroll.canScrollVertically(1));
        });
    }

    /** 很多应用时限制外部高度，剩余内容可在子块内到达。 */
    @Test public void longListCapsHeightAndKeepsEveryRowReachable() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            RuleAppListScrollView scroll = measuredList(20);
            int maximum = scroll.getResources().getDimensionPixelSize(R.dimen.rule_app_list_max_height);
            assertEquals(maximum, scroll.getMeasuredHeight());
            assertTrue(scroll.canScrollVertically(1));
            scroll.scrollTo(0, scroll.getChildAt(0).getHeight());
            assertEquals(20 * rowHeight(scroll.getContext()) - maximum, scroll.getScrollY());
            assertFalse(scroll.canScrollVertically(1));
            assertTrue(scroll.canScrollVertically(-1));
        });
    }

    /** @param rows 模拟应用数量 @return 在外页无高度约束下完成测量的应用子块 */
    private static RuleAppListScrollView measuredList(int rows) {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        RuleAppListScrollView scroll = new RuleAppListScrollView(context, null);
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content);
        for (int index = 0; index < rows; index++) {
            content.addView(new View(context), new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, rowHeight(context)));
        }
        scroll.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        scroll.layout(0, 0, scroll.getMeasuredWidth(), scroll.getMeasuredHeight());
        return scroll;
    }

    /** @param context 设备资源上下文 @return 模拟应用行高 */
    private static int rowHeight(Context context) {
        return context.getResources().getDimensionPixelSize(R.dimen.rule_row_height);
    }
}
