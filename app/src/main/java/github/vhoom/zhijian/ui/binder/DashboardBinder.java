package github.vhoom.zhijian.ui.binder;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.text.TextUtils;

import github.vhoom.zhijian.R;
import github.vhoom.zhijian.data.model.policy.ControlLevel;
import github.vhoom.zhijian.data.model.usage.DailyUsage;
import github.vhoom.zhijian.data.source.DailyPoem;
import github.vhoom.zhijian.data.source.InstalledAppSource;
import github.vhoom.zhijian.data.store.settings.GlobalSettings;
import github.vhoom.zhijian.ui.component.usage.UsageListScrollView;
import github.vhoom.zhijian.util.time.DurationFormat;
import github.vhoom.zhijian.domain.usage.DailyUsagePolicy;
import github.vhoom.zhijian.domain.usage.UsageRowBudget;
import github.vhoom.zhijian.util.permission.PermissionStatusReader;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 今日页两张动态卡片（执行强度、使用明细）的渲染与交互。
 *
 * 维护日期：2026年11月11日
 */
public final class DashboardBinder {

    /**
     * 首页需要向 Activity 转交的界面操作回调。
     */
    public interface Listener {
        /**
         * 打开使用情况访问授权的系统入口。
         */
        void onRequestUsageAccess();
    }

    private final Activity activity;
    private final Listener listener;
    private final LayoutInflater inflater;
    private final InstalledAppSource appSource;
    /** 用量榜显示几条由设置页决定，这里只认合并后的当前值。 */
    private final GlobalSettings settings;

    private final TextView strengthMode;
    private final TextView strengthPoem;
    private final TextView strengthRemaining;
    private final TextView strengthQuota;
    private final TextView strengthPercent;
    private final ProgressBar strengthProgress;
    private final View quotaBlock;

    private final LinearLayout usageRows;
    private final UsageListScrollView usageRowsScroll;
    private final View usageTotalRow;
    private final View usageTotalDivider;
    private final TextView usageTotalValue;
    private final TextView usageTotalLabel;
    private final TextView usageNotice;
    private final View usageAccessRequest;
    private final View root;
    private final View dashboardContent;
    private final View strengthCard;
    private final View usageCard;
    private final View usageRowsSlot;
    private final View calendarSpacer;
    private final ViewTreeObserver.OnGlobalLayoutListener rowLayoutObserver = this::updateRowBudget;

    private ControlLevel level = ControlLevel.REPAIR;
    private DailyUsage displayedUsage = DailyUsage.empty();
    private LocalDate displayedDate = LocalDate.now();
    private boolean displayInitialized;

    /**
     * 创建 DashboardBinder 实例，并初始化本次操作依赖或不可变字段。
     *
     * @param activity 执行本次界面操作的 Activity
     * @param appSource 读取已安装应用的系统数据源
     * @param settings 经过边界校验的全局或挑战设置
     * @param listener 状态发生变化后执行的监听回调
     */
    public DashboardBinder(Activity activity, InstalledAppSource appSource, GlobalSettings settings,
            Listener listener) {
        this.activity = activity;
        this.appSource = appSource;
        this.settings = settings;
        this.listener = listener;
        this.inflater = LayoutInflater.from(activity);

        root = activity.findViewById(R.id.page_dashboard);
        dashboardContent = root.findViewById(R.id.dashboard_content);
        strengthCard = root.findViewById(R.id.strength_card);
        usageCard = root.findViewById(R.id.usage_card);
        usageRowsSlot = root.findViewById(R.id.usage_rows_slot);
        calendarSpacer = root.findViewById(R.id.usage_calendar_spacer);
        strengthMode = root.findViewById(R.id.strength_mode);
        strengthPoem = root.findViewById(R.id.strength_poem);
        strengthRemaining = root.findViewById(R.id.strength_remaining);
        strengthQuota = root.findViewById(R.id.strength_quota);
        strengthPercent = root.findViewById(R.id.strength_percent);
        strengthProgress = root.findViewById(R.id.strength_progress);
        quotaBlock = root.findViewById(R.id.quota_block);
        usageRows = root.findViewById(R.id.usage_rows);
        usageRowsScroll = root.findViewById(R.id.usage_rows_scroll);
        usageTotalRow = root.findViewById(R.id.usage_total_row);
        usageTotalDivider = root.findViewById(R.id.usage_total_divider);
        usageTotalValue = root.findViewById(R.id.usage_screen_total_value);
        usageTotalLabel = root.findViewById(R.id.usage_screen_total_label);
        usageNotice = root.findViewById(R.id.usage_notice);
        usageAccessRequest = root.findViewById(R.id.btn_usage_access);

        usageAccessRequest.setOnClickListener(v -> listener.onRequestUsageAccess());
        root.getViewTreeObserver().addOnGlobalLayoutListener(rowLayoutObserver);
    }

    /** 页面销毁时解除实测布局观察，防止旧页面继续更新默认数。 */
    public void close() {
        ViewTreeObserver observer = root.getViewTreeObserver();
        if (observer.isAlive()) observer.removeOnGlobalLayoutListener(rowLayoutObserver);
    }

    /** 首页尺寸或卡片内容变化后，以不含配置引导的实测高度计算默认行数和内部滚动上限。 */
    private void updateRowBudget() {
        if (root.getHeight() <= 0 || usageRows.getChildCount() == 0) return;
        int rowHeight = usageRows.getChildAt(0).getMeasuredHeight();
        if (rowHeight <= 0 || usageCard.getMeasuredHeight() <= 0) return;
        int fixed = dashboardContent.getPaddingTop() + dashboardContent.getPaddingBottom()
                + strengthCard.getMeasuredHeight() + margins(strengthCard) + margins(usageCard)
                + usageCard.getMeasuredHeight() - usageRowsSlot.getMeasuredHeight()
                - calendarSpacer.getMeasuredHeight();
        int dividerHeight = activity.getResources().getDimensionPixelSize(R.dimen.divider_height);
        UsageRowBudget budget = UsageRowBudget.calculate(root.getHeight(), Math.max(0, fixed), rowHeight,
                dividerHeight);
        usageRowsScroll.setMaximumHeight(budget.rows * rowHeight + (budget.rows - 1) * dividerHeight);
        int previous = settings.usageRowLimit();
        settings.setCalculatedUsageRows(budget.rows);
        int remaining = settings.usageRowLimit() >= budget.rows ? budget.remainingPixels : 0;
        ViewGroup.LayoutParams params = calendarSpacer.getLayoutParams();
        if (params.height != remaining) {
            params.height = remaining;
            calendarSpacer.setLayoutParams(params);
        }
        if (previous != settings.usageRowLimit()) bindUsage(displayedUsage);
    }

    /** @param view 已测量的卡片 @return 布局中的上下外边距 */
    private int margins(View view) {
        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
        return params.topMargin + params.bottomMargin;
    }

    /**
     * 将当前数据连接到页面控件。
     *
     * @param level 全局管控档位
     * @param usage 当前今日用量的聚合与展示明细
     * @param quotaMillis 实际保存的毫秒额度，保留小数分钟精度
     */
    public void bind(ControlLevel level, DailyUsage usage, long quotaMillis) {
        this.level = level;
        bindStrength(usage, quotaMillis);
        // 档位与每日额度只使用今日统计，日期榜单独立持有自己的显示快照。
        if (!displayInitialized) {
            displayInitialized = true;
            displayedUsage = usage;
        }
        bindUsage(displayedUsage);
    }

    /** @param date 被选择的本地日期 @param usage 已提交的归档榜单，仅影响下方时长卡片 */
    public void bindDateUsage(LocalDate date, DailyUsage usage) {
        if (!date.equals(displayedDate)) usageRowsScroll.scrollTo(0, 0);
        displayedDate = date;
        displayedUsage = usage;
        displayInitialized = true;
        bindUsage(usage);
    }

    /** @param date 正在读取的日期，切换时立即清除上一天榜单避免错误日期和数据搭配 */
    public void showDateLoading(LocalDate date) {
        usageRowsScroll.scrollTo(0, 0);
        displayedDate = date;
        displayedUsage = DailyUsage.empty();
        displayInitialized = true;
        bindUsage(displayedUsage);
        usageAccessRequest.setVisibility(View.GONE);
        usageNotice.setText(R.string.usage_history_loading);
    }

    /** 日期查询失败时显示错误，不把失败呈现为当天用量为零。 */
    public void showDateFailure() {
        usageNotice.setVisibility(View.VISIBLE);
        usageNotice.setText(R.string.usage_history_failed);
        usageAccessRequest.setVisibility(View.GONE);
    }

    /** 同步首页古诗显隐，复用当天固定正文，跨日时由每日诗句入口重新选择。 */
    public void refreshAppearance() {
        boolean showPoem = settings.showDailyPoem();
        strengthPoem.setVisibility(showPoem ? View.VISIBLE : View.GONE);
        if (showPoem) strengthPoem.setText(DailyPoem.of(activity));
    }

    /**
     * 刷新管控档位的名称、说明和状态。
     *
     * @param usage 当前今日用量的聚合与展示明细
     * @param quotaMillis 实际保存的毫秒额度
     */
    private void bindStrength(DailyUsage usage, long quotaMillis) {
        strengthMode.setText(level.nameRes);
        refreshAppearance();

        if (quotaMillis <= GlobalSettings.NO_QUOTA) {
            quotaBlock.setVisibility(View.GONE);
            return;
        }

        int percent = (int) Math.min(100.0, 100.0 * usage.totalMillis / quotaMillis);
        quotaBlock.setVisibility(View.VISIBLE);
        strengthRemaining.setText(activity.getString(R.string.strength_remaining_fmt,
                DurationFormat.of(activity, Math.max(0L, quotaMillis - usage.totalMillis))));
        strengthQuota
                .setText(activity.getString(R.string.strength_quota_fmt, DurationFormat.of(activity, quotaMillis)));
        strengthPercent.setText(activity.getString(R.string.strength_percent_fmt, percent));
        strengthProgress.setProgress(percent);
    }

    /**
     * 刷新今日用量与额度进度。
     *
     * @param usage 当前今日用量的聚合与展示明细
     */
    private void bindUsage(DailyUsage usage) {
        usageRows.removeAllViews();
        List<DailyUsage.Row> eligible = new ArrayList<>();
        for (DailyUsage.Row row : usage.rows) {
            if (DailyUsagePolicy.shouldRecord(row.millis)) eligible.add(row);
        }
        int limit = settings.usageRowLimit();
        int shown = Math.min(limit, eligible.size());
        // 每次都创建完整行位；空位不可见但参与测量，大字体下仍和真实行保持相同高度。
        for (int i = 0; i < limit; i++) {
            if (i > 0) {
                View divider = inflater.inflate(R.layout.divider_row, usageRows, false);
                divider.setVisibility(i < shown ? View.VISIBLE : View.INVISIBLE);
                usageRows.addView(divider);
            }
            View row = inflater.inflate(R.layout.item_usage, usageRows, false);
            TextView name = row.findViewById(R.id.usage_name);
            TextView duration = row.findViewById(R.id.usage_duration);
            name.setMaxLines(1);
            name.setEllipsize(TextUtils.TruncateAt.END);
            duration.setMaxLines(1);
            duration.setEllipsize(TextUtils.TruncateAt.END);
            if (i < shown) {
                DailyUsage.Row data = eligible.get(i);
                android.graphics.drawable.Drawable icon = appSource.iconOf(data.app.packageName);
                ImageView image = row.findViewById(R.id.usage_icon);
                if (icon == null) image.setImageResource(R.drawable.ic_app_placeholder);
                else image.setImageDrawable(icon);
                name.setText(data.app.label);
                duration.setText(DurationFormat.of(activity, data.millis));
            } else {
                name.setText(" ");
                duration.setText(" ");
                row.setVisibility(View.INVISIBLE);
                row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            }
            usageRows.addView(row);
        }
        boolean today = displayedDate.equals(LocalDate.now());
        usageTotalLabel.setText(today ? activity.getString(R.string.usage_screen_total)
                : activity.getString(R.string.usage_history_total,
                        displayedDate.format(DateTimeFormatter.ofPattern("M月d日"))));
        boolean granted = usage.accessGranted;
        boolean needsPermission = today && !PermissionStatusReader.hasUsageAccess(activity);
        usageAccessRequest.setVisibility(needsPermission ? View.VISIBLE : View.GONE);
        // 无数据和切换加载期间仍保留总时长行位，日期组件不能因读取状态上下跳动。
        usageTotalRow.setVisibility(granted ? View.VISIBLE : View.INVISIBLE);
        usageTotalDivider.setVisibility(View.VISIBLE);
        if (granted)
            usageTotalValue.setText(DurationFormat.of(activity, usage.totalMillis));
        if (!granted) {
            usageNotice.setText(needsPermission ? R.string.usage_grant_hint : R.string.usage_history_missing);
            usageNotice.setVisibility(View.VISIBLE);
            return;
        }
        if (eligible.isEmpty()) {
            usageNotice.setText(today ? R.string.usage_today_filtered_empty : R.string.usage_history_empty);
            usageNotice.setVisibility(View.VISIBLE);
            return;
        }
        usageNotice.setVisibility(View.GONE);

    }

}
