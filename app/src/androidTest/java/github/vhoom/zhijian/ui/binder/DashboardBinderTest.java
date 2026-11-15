package github.vhoom.zhijian.ui.binder;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import github.vhoom.zhijian.R;
import github.vhoom.zhijian.data.model.AppInfo;
import github.vhoom.zhijian.data.model.policy.ControlLevel;
import github.vhoom.zhijian.data.model.usage.ArchivedUsage;
import github.vhoom.zhijian.data.model.usage.DailyUsage;
import github.vhoom.zhijian.data.source.InstalledAppSource;
import github.vhoom.zhijian.data.store.settings.GlobalSettings;
import github.vhoom.zhijian.ui.activity.MainActivity;
import github.vhoom.zhijian.ui.component.usage.UsageListScrollView;
import github.vhoom.zhijian.util.config.AppConfig;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * 验证固定榜单行位、内部滚动上限以及历史选择与实时强度数据分离。
 *
 * 维护日期：2026年11月09日
 */
@RunWith(AndroidJUnit4.class)
public class DashboardBinderTest {
    /** 两条、六条和零条应用共享相同行位高度，历史数据不能改变上方实时剩余额度。 */
    @Test public void fewerAppsKeepCalendarPositionAndHistoryDoesNotChangeStrength() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                GlobalSettings settings = new GlobalSettings(activity, new AppConfig(activity));
                int previousLimit = settings.usageRowLimit();
                boolean previousOverride = settings.hasUsageRowLimitOverride();
                DashboardBinder binder = new DashboardBinder(activity,
                        new InstalledAppSource(activity, new AppConfig(activity)), settings, () -> { });
                try {
                    settings.setUsageRowLimit(6);
                    DailyUsage today = usage(LocalDate.now(), 2, 120_000);
                    binder.bind(ControlLevel.REPAIR, today, 600_000);
                    String remaining = ((TextView) activity.findViewById(R.id.strength_remaining)).getText().toString();
                    binder.bindDateUsage(LocalDate.now(), today);
                    int two = slotHeight(activity);
                    binder.bindDateUsage(LocalDate.now().minusDays(1),
                            usage(LocalDate.now().minusDays(1), 6, 500_000));
                    int six = slotHeight(activity);
                    assertEquals(two, six);
                    assertEquals(remaining,
                            ((TextView) activity.findViewById(R.id.strength_remaining)).getText().toString());
                    binder.bindDateUsage(LocalDate.now().minusDays(2),
                            usage(LocalDate.now().minusDays(2), 0, 0));
                    assertEquals(two, slotHeight(activity));
                } finally {
                    binder.close();
                    settings.setUsageRowLimit(previousOverride ? previousLimit : 0);
                }
            });
        }
    }

    /** 超过屏幕容量时列表内部滚动，容量边界不增加卡片高度，切换日期后回到顶部。 */
    @Test public void oversizedListKeepsCardHeightAndDateChangeResetsScroll() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                GlobalSettings settings = new GlobalSettings(activity, new AppConfig(activity));
                int previousLimit = settings.usageRowLimit();
                boolean previousOverride = settings.hasUsageRowLimitOverride();
                View guide = activity.findViewById(R.id.setup_card);
                int previousVisibility = guide.getVisibility();
                DashboardBinder binder = new DashboardBinder(activity,
                        new InstalledAppSource(activity, new AppConfig(activity)), settings, () -> { });
                try {
                    guide.setVisibility(View.GONE);
                    settings.setUsageRowLimit(80);
                    DailyUsage today = usage(LocalDate.now(), 80, 120_000);
                    binder.bind(ControlLevel.REPAIR, today, GlobalSettings.NO_QUOTA);
                    binder.bindDateUsage(LocalDate.now(), today);
                    layoutDashboard(activity);

                    UsageListScrollView scroll = activity.findViewById(R.id.usage_rows_scroll);
                    View card = activity.findViewById(R.id.usage_card);
                    View content = activity.findViewById(R.id.dashboard_content);
                    View dashboard = activity.findViewById(R.id.page_dashboard);
                    int cappedHeight = card.getHeight();
                    int cappedListHeight = scroll.getHeight();
                    assertTrue(scroll.canScrollVertically(1));
                    ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) card.getLayoutParams();
                    assertTrue(card.getBottom() + margins.bottomMargin + content.getPaddingBottom()
                            <= dashboard.getHeight());

                    scroll.scrollTo(0, scroll.getChildAt(0).getHeight());
                    assertTrue(scroll.getScrollY() > 0);
                    binder.showDateLoading(LocalDate.now().minusDays(1));
                    assertEquals(0, scroll.getScrollY());

                    // 默认数字即同一布局的完整行容量，手动填写它应与超量时的卡片边界一致。
                    settings.setUsageRowLimit(0);
                    int capacity = settings.usageRowLimit();
                    assertTrue(capacity < 80);
                    settings.setUsageRowLimit(capacity);
                    binder.bindDateUsage(LocalDate.now(), today);
                    layoutDashboard(activity);
                    assertEquals(cappedHeight, card.getHeight());
                    assertEquals(cappedListHeight, scroll.getHeight());
                    assertFalse(scroll.canScrollVertically(1));
                } finally {
                    binder.close();
                    guide.setVisibility(previousVisibility);
                    settings.setUsageRowLimit(previousOverride ? previousLimit : 0);
                }
            });
        }
    }

    /** @param activity 已创建的首页；重复测量使实测预算生效后的高度约束完成布局 */
    private void layoutDashboard(MainActivity activity) {
        View dashboard = activity.findViewById(R.id.page_dashboard);
        float density = activity.getResources().getDisplayMetrics().density;
        int width = (int) (320 * density);
        int height = (int) (800 * density);
        for (int pass = 0; pass < 3; pass++) {
            dashboard.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            dashboard.layout(0, 0, width, height);
            dashboard.getViewTreeObserver().dispatchOnGlobalLayout();
        }
    }

    /** @param activity 已创建的首页 @return 独立测量后的榜单预留高度 */
    private int slotHeight(MainActivity activity) {
        View slot = activity.findViewById(R.id.usage_rows_slot);
        int width = (int) (320 * activity.getResources().getDisplayMetrics().density);
        slot.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        return slot.getMeasuredHeight();
    }

    /** @param date 查询日期 @param count 达标应用数量 @param screen 独立屏幕累计值 @return 测试榜单 */
    private DailyUsage usage(LocalDate date, int count, long screen) {
        List<ArchivedUsage.Entry> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            entries.add(new ArchivedUsage.Entry(new AppInfo("fixture." + i, "示例应用 " + i), 90_000));
        }
        return DailyUsage.of(new ArchivedUsage(date, "Asia/Shanghai", screen, 1_800_000_000_000L, true, entries));
    }
}
