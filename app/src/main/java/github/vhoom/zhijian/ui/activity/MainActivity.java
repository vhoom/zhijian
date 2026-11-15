package github.vhoom.zhijian.ui.activity;

import android.app.Dialog;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import github.vhoom.zhijian.R;
import github.vhoom.zhijian.data.model.AppInfo;
import github.vhoom.zhijian.data.model.policy.ControlLevel;
import github.vhoom.zhijian.data.model.usage.DailyUsage;
import github.vhoom.zhijian.data.source.CachedAppCatalog;
import github.vhoom.zhijian.data.source.InstalledAppSource;
import github.vhoom.zhijian.data.source.UsageStatsReader;
import github.vhoom.zhijian.data.store.AppPolicyStore;
import github.vhoom.zhijian.data.store.UsageArchiveStore;
import github.vhoom.zhijian.data.store.apps.AppCatalogFileStore;
import github.vhoom.zhijian.runtime.usage.UsageArchiveCoordinator;
import github.vhoom.zhijian.ui.component.usage.UsageDateSelector;

import java.time.LocalDate;
import github.vhoom.zhijian.data.store.settings.GlobalSettings;
import github.vhoom.zhijian.ui.binder.DashboardBinder;
import github.vhoom.zhijian.ui.binder.SetupGuideBinder;
import github.vhoom.zhijian.ui.component.apps.AppListAdapter;
import github.vhoom.zhijian.ui.component.apps.AppListRefreshButton;
import github.vhoom.zhijian.ui.component.rule.LevelConfirmDialog;
import github.vhoom.zhijian.ui.component.navigation.PullRefreshLayout;
import github.vhoom.zhijian.ui.component.navigation.SegmentSelector;
import github.vhoom.zhijian.ui.component.navigation.SwipePager;
import github.vhoom.zhijian.util.config.AppConfig;
import github.vhoom.zhijian.util.permission.PermissionIntents;
import github.vhoom.zhijian.util.permission.PermissionStatusReader;
import github.vhoom.zhijian.util.ui.SystemBars;

import java.util.Collections;
import java.util.List;
import github.vhoom.zhijian.util.concurrent.TaskQueue;

/**
 * 今日 / 管控中心 两个页面的宿主，只做装配与刷新，数据来源全部在 data 包。
 *
 * 维护日期：2026年11月14日
 */
public final class MainActivity extends ThemedActivity implements DashboardBinder.Listener {

    /** 数据来自系统服务，出问题时界面上只看得到「一动不动」，日志是唯一的线索。 */
    private static final String TAG = "ZhijianUsage";
    private static final String STATE_PAGE = "main_page";
    private static final String STATE_GUIDE_EXPANDED = "guide_expanded";
    private static final String STATE_USAGE_DATE = "usage_date";

    private InstalledAppSource appSource;
    private CachedAppCatalog appCatalog;
    private UsageStatsReader usageReader;
    private UsageArchiveStore usageArchive;
    private UsageDateSelector usageDates;
    /** 日期读取代次；快速切换、重建和返回时旧查询不得覆盖新日期。 */
    private long usageDateGeneration;
    private AppPolicyStore policies;
    private GlobalSettings settings;
    private AppConfig config;

    private AppListAdapter appAdapter;
    private AppListRefreshButton appsRefresh;
    private DashboardBinder dashboard;
    private SetupGuideBinder setupGuide;
    private PullRefreshLayout todayPull;
    private SwipePager pager;

    private TextView dashboardTitle;
    private TextView controlTitle;
    private TextView btnAction;
    private TextView btnSettings;
    private TextView appsCount;
    private EditText searchField;
    private SegmentSelector levelSelector;
    /** 无障碍已开启但缺少用量权限时持续显示，不受首页引导卡显隐设置影响。 */
    private View usageAccessWarning;

    /** 遍历应用清单和使用统计都是 binder 调用，放后台线程避免首帧卡顿。 */
    private final TaskQueue io = new TaskQueue("zhijian-dashboard", 1, 2);
    /** 主线程合并重复刷新，系统查询变慢时不积压周期任务。 */
    private boolean usageLoading;
    private boolean appsLoading;
    private final Handler main = new Handler(Looper.getMainLooper());

    private DailyUsage usage = DailyUsage.empty();
    /** 待确认的档位切换框：切后台时要收掉，否则回到前台会残留一个压在旧档位上的模态框。 */
    private Dialog pendingSwitch;
    private boolean resumed;
    /** 应用清单变化不频繁，缓存供定时刷新复用，避免每次遍历包名。刷新任务从主线程读，用 volatile 保证可见性。 */
    private volatile List<AppInfo> apps = Collections.emptyList();

    private final Runnable refreshUsage = new Runnable() {
        /**
         * 执行当前回调规定的数据库工作或周期界面刷新。
         */
        @Override
        public void run() {
            loadUsage();
            main.postDelayed(this, config.usageRefreshIntervalMs());
        }
    };

    /**
     * 创建页面或进程依赖，并绑定本次启动所需的状态。
     *
     * @param savedInstanceState 系统保存的上次页面状态，首次创建时为 null
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        whenStorageReady(() -> initialize(savedInstanceState));
    }

    /**
     * 存储就绪后绑定当前页面依赖、控件和数据。
     *
     * @param savedInstanceState 系统保存的上次页面状态，首次创建时为 null
     */
    private void initialize(Bundle savedInstanceState) {
        setContentView(R.layout.activity_main);
        SystemBars.applyPadding(findViewById(R.id.root));

        config = new AppConfig(this);
        appSource = new InstalledAppSource(this, config);
        appCatalog = new CachedAppCatalog(new AppCatalogFileStore(
                getNoBackupFilesDir().toPath().resolve("launchable_apps.cache")), appSource::loadLaunchableApps);
        usageReader = new UsageStatsReader(this);
        usageArchive = new UsageArchiveStore(this);
        policies = new AppPolicyStore(this);
        settings = new GlobalSettings(this, config);

        dashboardTitle = findViewById(R.id.title_dashboard);
        controlTitle = findViewById(R.id.title_control);
        btnAction = findViewById(R.id.btn_action);
        btnSettings = findViewById(R.id.btn_settings);
        appsCount = findViewById(R.id.apps_count);
        searchField = findViewById(R.id.search_field);
        appsRefresh = new AppListRefreshButton(findViewById(R.id.apps_refresh), () -> reload(true));
        levelSelector = new SegmentSelector(this,
                findViewById(R.id.seg_mortal),
                findViewById(R.id.seg_repair),
                findViewById(R.id.seg_seal));

        pager = findViewById(R.id.pager);
        pager.setPageListener(index -> showPage(index == 0));
        dashboardTitle.setOnClickListener(v -> pager.showPage(0, true));
        controlTitle.setOnClickListener(v -> pager.showPage(1, true));

        todayPull = findViewById(R.id.page_dashboard);
        todayPull.setOnRefresh(this::reload);

        dashboard = new DashboardBinder(this, appSource, settings, this);
        usageDates = findViewById(R.id.usage_date_selector);
        if (savedInstanceState != null && savedInstanceState.containsKey(STATE_USAGE_DATE)) {
            usageDates.restoreDate(LocalDate.ofEpochDay(savedInstanceState.getLong(STATE_USAGE_DATE)));
        }
        usageDates.setListener(this::loadDateUsage);
        dashboard.showDateLoading(usageDates.selectedDate());
        loadDateUsage(usageDates.selectedDate(), false);
        setupGuide = new SetupGuideBinder(this, findViewById(R.id.setup_card), true);
        usageAccessWarning = findViewById(R.id.usage_access_warning);
        View.OnClickListener openUsageAccess = view -> PermissionIntents.openUsageAccess(this);
        usageAccessWarning.setOnClickListener(openUsageAccess);
        usageAccessWarning.findViewById(R.id.usage_access_warning_action)
                .setOnClickListener(openUsageAccess);
        if (savedInstanceState != null) {
            setupGuide.setExpanded(savedInstanceState.getBoolean(STATE_GUIDE_EXPANDED, true));
        }
        appAdapter = new AppListAdapter(policies, appSource);
        ListView appList = findViewById(R.id.app_list);
        appList.setAdapter(appAdapter);
        appList.setOnItemClickListener((parent, view, position, id) -> openPolicy(appAdapter.itemAt(position)));

        btnAction.setOnClickListener(v -> startActivity(new Intent(this, RulesGroupActivity.class)));
        btnSettings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));

        ControlLevel[] levels = ControlLevel.values();
        for (int i = 0; i < levels.length; i++) {
            final ControlLevel level = levels[i];
            levelSelector.segmentAt(i).setOnClickListener(v -> confirmLevel(level));
        }

        searchField.addTextChangedListener(new TextWatcher() {
            /**
             * 接收文本修改前通知；实际变更由修改完成回调处理。
             *
             * @param s 编辑器当前文本
             * @param start 变化起点或区间开始值
             * @param count 处理的条目数或本次变更的字符数量
             * @param after 变更后的文本数量或导出起始行号
             */
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            /**
             * 接收文本修改通知，具体保存由编辑完成回调处理。
             *
             * @param s 编辑器当前文本
             * @param start 变化起点或区间开始值
             * @param before 变更前的文本数量或待比较的旧记录
             * @param count 处理的条目数或本次变更的字符数量
             */
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            /**
             * 文本修改完成后更新草稿或筛选结果。
             *
             * @param s 编辑器当前文本
             */
            @Override
            public void afterTextChanged(Editable s) {
                refreshFilteredCount();
            }
        });

        pager.showPage(savedInstanceState == null ? 0 : savedInstanceState.getInt(STATE_PAGE, 0), false);
        if (resumed) resumeContent();
    }

    /**
     * 页面恢复可见后刷新配置和界面状态。
     */
    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (settings != null) resumeContent();
    }

    /**
     * 页面恢复可见时重新启动用量和授权状态刷新。
     */
    private void resumeContent() {
        appsRefresh.setActive(pager.getDisplayedChild() == 1);
        refreshUsageAccessWarning();
        dashboard.refreshAppearance();
        setupGuide.setVisible(settings.showSetupGuide());
        if (settings.showSetupGuide()) setupGuide.start();
        else setupGuide.stop();
        selectLevel(settings.level());
        refreshFilteredCount();
        reload();
        main.removeCallbacks(refreshUsage);
        main.postDelayed(refreshUsage, config.usageRefreshIntervalMs());
    }

    /**
     * 仅在无障碍已开启而使用情况访问仍缺失时显示醒目入口。
     * 两项权限必须配合，单独开启无障碍无法为时长规则提供系统用量证据。
     */
    private void refreshUsageAccessWarning() {
        PermissionStatusReader.Snapshot permissions = PermissionStatusReader.read(this);
        boolean shouldWarn = PermissionStatusReader.needsUsageAccessWarning(
                permissions.accessibilityEnabled, permissions.usageAccess);
        usageAccessWarning.setVisibility(shouldWarn ? View.VISIBLE : View.GONE);
    }

    /**
     * 页面离开前台时停止仅在可见期间运行的工作。
     */
    @Override
    protected void onPause() {
        resumed = false;
        if (appsRefresh != null) appsRefresh.setActive(false);
        if (setupGuide != null) setupGuide.stop();
        main.removeCallbacks(refreshUsage);
        dismissPendingSwitch();
        super.onPause();
    }

    /**
     * 释放本实例持有的监听、弹窗或后台回调。
     */
    @Override
    protected void onDestroy() {
        if (appsRefresh != null) appsRefresh.close();
        if (setupGuide != null) setupGuide.close();
        if (dashboard != null) dashboard.close();
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
        io.shutdown();
    }

    /**
     * 保存当前草稿和交互进度，供页面重建恢复。
     *
     * @param outState 写入页面重建所需数据的状态容器
     */
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (pager != null) outState.putInt(STATE_PAGE, pager.getDisplayedChild());
        if (setupGuide != null) outState.putBoolean(STATE_GUIDE_EXPANDED, setupGuide.isExpanded());
        if (usageDates != null) outState.putLong(STATE_USAGE_DATE, usageDates.selectedDate().toEpochDay());
    }

    /**
     * 打开使用情况访问授权的系统入口。
     */
    @Override
    public void onRequestUsageAccess() {
        if (setupGuide != null) setupGuide.showUsageGuide();
    }

    /** 启动、返回和首页下拉只读已有本地清单，首次尚未保存时由缓存代理读取系统。 */
    private void reload() { reload(false); }

    /**
     * 在后台读取清单，只有图标操作主动扫描系统并更新快照。
     * @param forceRefresh 是否由应用列表刷新图标触发一次真实读取
     */
    private void reload(boolean forceRefresh) {
        if (isDestroyed() || appsLoading) return;
        appsLoading = true;
        appsRefresh.setLoading(true);
        io.submit(() -> appCatalog.load(forceRefresh)).whenComplete((loadedApps, failure) -> main.post(() -> {
            appsLoading = false;
            if (isDestroyed() || isFinishing()) return;
            appsRefresh.setLoading(false);
            if (failure != null) {
                Log.e(TAG, "load app list failed", failure);
                Toast.makeText(this, R.string.apps_refresh_failed, Toast.LENGTH_LONG).show();
                finishPull();
                return;
            }
            apps = loadedApps;
            appAdapter.submit(loadedApps);
            appsCount.setText(getString(R.string.apps_count, appAdapter.visibleCount()));
            loadUsage(loadedApps);
        }));
    }

    /** 定时刷新只重查时长，不重新遍历应用清单。 */
    private void loadUsage() {
        loadUsage(apps);
    }

    /**
     * 异步读取用量，并在页面仍有效时更新展示。
     *
     * @param launchableApps 有启动入口的可管控应用列表
     */
    private void loadUsage(List<AppInfo> launchableApps) {
        if (isDestroyed() || usageLoading) return;
        usageLoading = true;
        refreshDateUsage();
        io.submit(() -> DailyUsage.of(usageReader.loadToday(), launchableApps))
                .whenComplete((loaded, failure) -> main.post(() -> {
            usageLoading = false;
            if (isDestroyed() || isFinishing()) return;
            if (failure != null) {
                Log.e(TAG, "load usage failed", failure);
                finishPull();
                return;
            }
            usage = loaded;
            dashboard.bind(settings.level(), loaded, settings.dailyQuotaMillis());
            finishPull();
        }));
    }

    /** 今日实时额度独立刷新后，同步归档并读取目前选择的日期。 */
    private void refreshDateUsage() {
        LocalDate selected = usageDates.refreshToday();
        UsageArchiveCoordinator.synchronize(this, true).whenComplete((ignored, failure) -> main.post(() -> {
            if (isDestroyed() || isFinishing()) return;
            // 历史记录可离线读取，权限关闭或采集失败不能擦掉已有归档。
            if (selected.equals(usageDates.selectedDate())) loadDateUsage(selected, false);
        }));
    }

    /** @param date 用户点击的日期，立即进入该日期的读取状态 */
    private void loadDateUsage(LocalDate date) { loadDateUsage(date, true); }

    /**
     * @param date 要读取的日期
     * @param showLoading 主动切换时清理旧日期榜单，周期刷新保持已显示内容
     */
    private void loadDateUsage(LocalDate date, boolean showLoading) {
        long generation = ++usageDateGeneration;
        if (showLoading) dashboard.showDateLoading(date);
        usageArchive.load(date).whenComplete((archive, failure) -> main.post(() -> {
            if (isDestroyed() || isFinishing() || generation != usageDateGeneration
                    || !date.equals(usageDates.selectedDate())) return;
            if (failure != null) {
                Log.e(TAG, "load archived date failed", failure);
                dashboard.showDateFailure();
                return;
            }
            dashboard.bindDateUsage(date, DailyUsage.of(archive));
        }));
    }

    /** 头部收起只认主线程，且 activity 已销毁时不能碰任何 View。 */
    private void finishPull() {
        if (!isDestroyed())
            todayPull.finishRefresh();
    }

    /**
     * 打开指定应用的独立策略编辑页。
     *
     * @param app 当前应用数据
     */
    private void openPolicy(AppInfo app) {
        Intent intent = new Intent(this, AppPolicyActivity.class);
        intent.putExtra(AppPolicyActivity.EXTRA_PACKAGE, app.packageName);
        intent.putExtra(AppPolicyActivity.EXTRA_LABEL, app.label);
        startActivity(intent);
    }

    /**
     * 确认并保存新的全局管控档位。
     *
     * @param level 全局管控档位
     */
    private void selectLevel(ControlLevel level) {
        levelSelector.select(level.ordinal(), R.drawable.bg_pill_level, R.color.level_selected_text);
        dashboard.bind(level, usage, settings.dailyQuotaMillis());
    }

    /**
     * 点段位不直接切档：先弹模态框把细则讲清楚，确认后才写入设置。
     *
     * @param target 本次需要切换到的档位、页索引或像素偏移
     */
    private void confirmLevel(ControlLevel target) {
        if (target == settings.level())
            return;
        dismissPendingSwitch();
        pendingSwitch = LevelConfirmDialog.show(this, target,
                level -> persist(settings.setLevel(level), () -> selectLevel(level)));
    }

    /**
     * 关闭待确认的档位切换弹窗。
     */
    private void dismissPendingSwitch() {
        if (pendingSwitch == null)
            return;
        pendingSwitch.dismiss();
        pendingSwitch = null;
    }

    /**
     * 更新筛选结果数量和当前列表状态。
     */
    private void refreshFilteredCount() {
        appAdapter.applyQuery(searchField.getText().toString());
        appsCount.setText(getString(R.string.apps_count, appAdapter.visibleCount()));
    }

    /**
     * 顶栏右侧的操作位跟着页面走：今日页给设置，管控页给时间管理规则入口。
     *
     * @param today 是否切换到今日用量页面
     */
    private void showPage(boolean today) {
        if (appsRefresh != null) appsRefresh.setActive(resumed && !today);
        bindPageTitle(dashboardTitle, today, R.string.title_today);
        bindPageTitle(controlTitle, !today, R.string.title_rules_center);
        btnAction.setVisibility(today ? View.GONE : View.VISIBLE);
        btnSettings.setVisibility(today ? View.VISIBLE : View.GONE);
    }

    /**
     * 将页标题和分页选择状态保持一致。
     *
     * @param title 需要显示的标题文本
     * @param selected 当前选中的规则或档位
     * @param label 界面显示的可读名称
     */
    private void bindPageTitle(TextView title, boolean selected, int label) {
        title.setSelected(selected);
        title.setAccessibilityHeading(selected);
        title.setTextAppearance(selected ? R.style.TextAppearance_App_DisplayMd : R.style.TextAppearance_App_BodySm);
        title.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
        title.setTextColor(getColor(selected ? R.color.ink : R.color.body));
        title.setContentDescription(getString(selected ? R.string.page_current : R.string.page_switch_to,
                getString(label)));
        float alpha = selected ? 1f : 0.82f;
        float offset = selected ? 0f : getResources().getDimension(R.dimen.page_title_recess);
        title.animate().cancel();
        if (title.isLaidOut() && ValueAnimator.areAnimatorsEnabled()) {
            title.animate().alpha(alpha).translationY(offset)
                    .setDuration(getResources().getInteger(R.integer.page_settle_duration_ms))
                    .setInterpolator(new DecelerateInterpolator()).start();
        } else {
            title.setAlpha(alpha);
            title.setTranslationY(offset);
        }
    }
}
