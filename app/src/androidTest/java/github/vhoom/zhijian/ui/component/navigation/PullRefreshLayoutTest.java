package github.vhoom.zhijian.ui.component.navigation;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import github.vhoom.zhijian.fixture.TouchEvents;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 直接测量与触摸下拉刷新组件，验证初始提示隐藏与取消手势。
 *
 * 维护日期：2026年11月09日
 */
@RunWith(AndroidJUnit4.class)
public class PullRefreshLayoutTest {
    /** 刷新提示在初始透明页面中也不可见，取消大幅下拉不会触发刷新。 */
    @Test
    public void refreshHintStartsHiddenAndCancelledDragDoesNotRefresh() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            PullRefreshLayout layout = new PullRefreshLayout(context, null);
            ScrollView scroll = new ScrollView(context);
            scroll.addView(new View(context), new ViewGroup.LayoutParams(280, 1000));
            layout.addView(scroll);
            layout.measure(View.MeasureSpec.makeMeasureSpec(280, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY));
            layout.layout(0, 0, 280, 600);
            assertEquals(View.INVISIBLE, layout.getChildAt(0).getVisibility());
            assertTrue(layout.getChildAt(0).getTranslationY() < 0f);
            int[] refreshes = {0};
            layout.setOnRefresh(() -> refreshes[0]++);
            TouchEvents.dispatch(layout, MotionEvent.ACTION_DOWN, 100, 100);
            TouchEvents.dispatch(layout, MotionEvent.ACTION_MOVE, 100, 500);
            assertTrue(scroll.getTranslationY() > 0f);
            TouchEvents.dispatch(layout, MotionEvent.ACTION_CANCEL, 100, 500);
            assertEquals(0, refreshes[0]);
        });
    }
}
