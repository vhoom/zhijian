package github.vhoom.zhijian.ui.component.usage;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import github.vhoom.zhijian.fixture.TouchEvents;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.LocalDate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 直接测量与触摸日期组件，验证日期跟手、星期固定和查询日期稳定。
 *
 * 维护日期：2026年11月09日
 */
@RunWith(AndroidJUnit4.class)
public class UsageDateSelectorTest {
    /** 日期在释放之前真实跟随手指移动，星期标题不移动，也不会提前切换查询日期。 */
    @Test
    public void dateNumbersFollowFingerWhileWeekdayHeadingStaysFixed() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            UsageDateSelector selector = new UsageDateSelector(context, null);
            selector.measure(View.MeasureSpec.makeMeasureSpec(280, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            selector.layout(0, 0, 280, selector.getMeasuredHeight());
            LocalDate selected = selector.selectedDate();
            ViewGroup viewport = (ViewGroup) selector.getChildAt(1);
            View track = viewport.getChildAt(0);
            float original = track.getTranslationX();
            TouchEvents.dispatch(selector, MotionEvent.ACTION_DOWN, 120, selector.getHeight() - 20);
            TouchEvents.dispatch(selector, MotionEvent.ACTION_MOVE, 155, selector.getHeight() - 20);
            assertTrue(track.getTranslationX() > original);
            assertEquals(0f, selector.getChildAt(0).getTranslationX(), 0f);
            assertEquals(selected, selector.selectedDate());
            TouchEvents.dispatch(selector, MotionEvent.ACTION_CANCEL, 155, selector.getHeight() - 20);
        });
    }
}
