package github.vhoom.zhijian.ui.interception;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Toast;

import github.vhoom.zhijian.BuildConfig;
import github.vhoom.zhijian.R;
import github.vhoom.zhijian.accessibility.AccessibilityConnection;
import github.vhoom.zhijian.data.model.interception.InterceptionChallenge;
import github.vhoom.zhijian.data.model.interception.InterceptionEffect;
import github.vhoom.zhijian.data.model.interception.InterceptionEvent;
import github.vhoom.zhijian.data.model.interception.InterceptionMode;
import github.vhoom.zhijian.data.model.interception.InterceptionStage;
import github.vhoom.zhijian.data.model.policy.ControlLevel;
import github.vhoom.zhijian.data.model.runtime.PendingInterception;
import github.vhoom.zhijian.data.model.settings.InteractionSettings;
import github.vhoom.zhijian.data.model.settings.InterceptionOptions;
import github.vhoom.zhijian.data.model.settings.SecondsRange;
import github.vhoom.zhijian.data.store.ZhijianStorage;
import github.vhoom.zhijian.data.store.settings.InteractionSettingsStore;
import github.vhoom.zhijian.domain.interception.InterceptionDecision;
import github.vhoom.zhijian.domain.interception.InterceptionFlow;
import github.vhoom.zhijian.domain.interception.ModeFlowPlan;
import github.vhoom.zhijian.ui.activity.ThemedActivity;
import github.vhoom.zhijian.ui.interception.page.InterceptionPage;
import github.vhoom.zhijian.ui.interception.page.InterceptionPageRegistry;
import github.vhoom.zhijian.ui.interception.page.support.PageTransition;
import github.vhoom.zhijian.ui.interception.page.support.RestCountdownPresentation;
import github.vhoom.zhijian.ui.interception.preview.InterceptionPreviewCatalog;
import github.vhoom.zhijian.ui.interception.preview.InterceptionPreviewPanel;
import github.vhoom.zhijian.ui.interception.request.InterceptionIntents;
import github.vhoom.zhijian.ui.interception.session.InteractionSessionState;
import github.vhoom.zhijian.ui.interception.session.InterceptionOutcomeWriter;
import github.vhoom.zhijian.ui.interception.session.PageSession;
import github.vhoom.zhijian.util.config.AppConfig;
import github.vhoom.zhijian.util.diagnostics.DiagnosticLog;
import github.vhoom.zhijian.util.diagnostics.InterceptionLaunchDiagnostics;
import github.vhoom.zhijian.util.time.BootIdentity;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static github.vhoom.zhijian.ui.interception.request.InterceptionIntents.*;

/**
 * 全屏拦截协调层：接收请求、装配独立页面、执行状态机效果和保存结果。
 *
 * 维护日期：2026年11月16日
 */
public final class InterceptionActivity extends ThemedActivity {
    private static final String TAG = "ZhijianInterception";
    private static final String STATE_SESSION = "interception.state.session";
    private static final String STATE_STAGE = "interception.state.stage";
    private static final String STATE_COPY_VARIANT = "interception.state.copy_variant";
    private static final String STATE_INITIAL_ACTION_SEED = "interception.state.initial_action_seed";
    private static final String STATE_CHALLENGE_ENTRY = "interception.state.challenge_entry";
    private static final String STATE_DEBUG_PAGE = "interception.state.debug_page";
    private static final String STATE_PAUSE_DEADLINE = "interception.state.pause_deadline";
    private static final String STATE_PAUSE_BOOT = "interception.state.pause_boot";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable resetTick = this::updateReset;
    private final Runnable ruleTick = this::recheckVisibleRules;
    private final InterceptionPageRegistry pageRegistry = new InterceptionPageRegistry();
    private final PageTransition pageTransition = new PageTransition();
    private InteractionSettingsStore interactionStore;
    private InteractionSettings interactionSettings;
    private InterceptionPage activePage;
    private android.widget.FrameLayout pageContainer;
    private View savingPage;
    private RestCountdownPresentation countdown;
    private InterceptionDecision decision;
    private InterceptionFlow flow;
    private InterceptionStage stage;
    private InterceptionChallenge challenge;
    private String sessionId;
    /** 当前首屏的独立控件种子；保存到系统状态，不写入运行会话。 */
    private String initialActionSeed;
    /** 真实退出重进时更换；配置重建及规则复查保持，已开始进度由存储优先恢复。 */
    private String challengeEntryId;
    private boolean refreshChallengeOnResume;
    private long occurredAtMs;
    private long pauseDeadlineElapsedMs;
    /** 最近规则复查给出的单调时间期限，页面步骤变化不延后该期限。 */
    private long ruleRecheckDeadlineElapsedMs;
    private int copyVariant;
    private boolean busy;
    private boolean debugPreview;
    private boolean debugGallery;
    private int debugPageIndex;
    private InterceptionPreviewPanel previewPanel;
    private float debugSwipeStartX;
    private float debugSwipeStartY;
    private boolean debugSwipeTracking;
    private int requestGeneration;
    private boolean recoveryChecking;
    private boolean resumed;
    /** 下一次装配首屏时重抽控件；系统恢复沿用原种子。 */
    private boolean refreshInitialActionsOnResume;
    private int recoveryGeneration;
    private github.vhoom.zhijian.runtime.interception.InterceptionRuntime recoveryRuntime;

    /**
     * 创建生产拦截启动请求。
     * @param context 启动上下文
     * @param decision 原规则决定
     * @param appLabel 应用名称
     * @param sessionId 稳定会话标识
     * @return 拦截任务启动请求
     */
    public static Intent intent(Context context, InterceptionDecision decision, String appLabel, String sessionId) {
        return InterceptionIntents.intent(context, decision, appLabel, sessionId);
    }

    /**
     * 创建原会话恢复请求。
     * @param context 启动上下文
     * @param pending 持久化待处理会话
     * @param appLabel 应用名称
     * @return 保留原休息起点的请求
     */
    public static Intent intent(Context context, PendingInterception pending, String appLabel) {
        return InterceptionIntents.intent(context, pending, appLabel);
    }

    /**
     * 创建调试模式的初始页请求。
     * @param context 启动上下文
     * @param kind 拦截类型
     * @param level 管控档位
     * @param sessionId 稳定预览会话
     * @return 调试请求
     */
    public static Intent debugIntent(Context context, InterceptionDecision.Kind kind,
            ControlLevel level, String sessionId) {
        return InterceptionIntents.debugIntent(context, kind, level, sessionId);
    }

    /**
     * 创建指定独立页面的调试请求。
     * @param context 启动上下文
     * @param kind 拦截类型
     * @param level 管控档位
     * @param previewStage 指定页面阶段
     * @param sessionId 稳定预览会话
     * @return 指定页调试请求
     */
    public static Intent debugIntent(Context context, InterceptionDecision.Kind kind,
            ControlLevel level, InterceptionStage previewStage, String sessionId) {
        return InterceptionIntents.debugIntent(context, kind, level, previewStage, sessionId);
    }

    /**
     * 创建全部拦截页面的滑动预览请求。
     * @param context 启动上下文
     * @return 首张预览页请求
     */
    public static Intent debugGalleryIntent(Context context) { return InterceptionIntents.debugGalleryIntent(context); }

    /**
     * 创建页面、等待存储可用，并进入首次请求渲染。
     *
     * @param savedInstanceState 系统保存的上次页面状态，首次创建时为 null
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DiagnosticLog.info("activity", getIntent().getStringExtra(EXTRA_SESSION), "on_create");
        whenStorageReady(() -> initialize(savedInstanceState));
    }

    /**
     * 单任务复用时读取新请求，避免沿用上一轮拦截状态。
     *
     * @param intent 本次启动或复用页面的请求参数
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        DiagnosticLog.info("activity", intent.getStringExtra(EXTRA_SESSION), "on_new_intent");
        acceptRequest(intent, true);
    }

    /**
     * 接收启动或规则更新请求；重新打开时更换控件种子和挑战进入标识，保留已答进度。
     *
     * @param intent 本次完整拦截请求
     * @param reopened 是否由重新打开页面产生，后台规则更新为 false
     */
    private void acceptRequest(Intent intent, boolean reopened) {
        if (reopened && pageContainer == null) {
            refreshInitialActionsOnResume = true;
            refreshChallengeOnResume = true;
        }
        Bundle retained = null;
        if (pageContainer != null && sessionId != null && sessionId.equals(intent.getStringExtra(EXTRA_SESSION))
                && !debugPreview && !(BuildConfig.DEBUG && intent.getBooleanExtra(EXTRA_DEBUG_PREVIEW, false))) {
            retained = new Bundle();
            onSaveInstanceState(retained);
            if (reopened) retained.remove(STATE_CHALLENGE_ENTRY);
            if (reopened && stage == InterceptionStage.INITIAL) {
                retained.remove(STATE_INITIAL_ACTION_SEED);
            }
            if (intent.getLongExtra(EXTRA_STARTED_ELAPSED, 0L)
                    > getIntent().getLongExtra(EXTRA_STARTED_ELAPSED, 0L)
                    || !java.util.Objects.equals(intent.getStringExtra(EXTRA_STARTED_BOOT),
                            getIntent().getStringExtra(EXTRA_STARTED_BOOT))) {
                // 同组提前返回会重新开始休息，保留文案与完成阶段，使用新期限。
                retained.remove(STATE_PAUSE_DEADLINE);
                retained.remove(STATE_PAUSE_BOOT);
            }
        }
        setIntent(intent);
        if (pageContainer != null) loadRequest(retained);
    }

    /**
     * 完成依赖创建、视图绑定与当前请求加载。
     *
     * @param state 待恢复或处理的状态快照
     */
    private void initialize(Bundle state) {
        setContentView(R.layout.activity_interception);
        hideSystemBars();
        debugPreview = BuildConfig.DEBUG && getIntent().getBooleanExtra(EXTRA_DEBUG_PREVIEW, false);
        debugGallery = BuildConfig.DEBUG && debugPreview && getIntent().getBooleanExtra(EXTRA_DEBUG_GALLERY, false);
        interactionStore = new InteractionSettingsStore(this, new AppConfig(this));
        recoveryRuntime = new github.vhoom.zhijian.runtime.interception.InterceptionRuntime(this);
        countdown = new RestCountdownPresentation(findViewById(R.id.interception_countdown));
        pageContainer = findViewById(R.id.interception_page_container);
        savingPage = findViewById(R.id.interception_page_saving);
        loadRequest(state);
        if (resumed) recheckVisibleRules();
    }

    /**
     * 校验 Intent、读取独立设置，并恢复或创建本次拦截状态机。
     *
     * @param state 待恢复或处理的状态快照
     */
    private void loadRequest(Bundle state) {
        requestGeneration++;
        recoveryChecking = false;
        ruleRecheckDeadlineElapsedMs = 0L;
        stopTimers();
        Intent intent = getIntent();
        debugPreview = BuildConfig.DEBUG && intent.getBooleanExtra(EXTRA_DEBUG_PREVIEW, false);
        debugGallery = BuildConfig.DEBUG && debugPreview && intent.getBooleanExtra(EXTRA_DEBUG_GALLERY, false);

        int requestedDebugPage = 0;
        if (BuildConfig.DEBUG) {
            requestedDebugPage = state != null && state.containsKey(STATE_DEBUG_PAGE)
                    ? state.getInt(STATE_DEBUG_PAGE) : intent.getIntExtra(EXTRA_DEBUG_PAGE, 0);
        }
        debugPageIndex = BuildConfig.DEBUG && debugGallery
                ? Math.floorMod(requestedDebugPage, InterceptionIntents.PAGE_COUNT) : 0;
        try {
            decision = new InterceptionDecision(
                    intent.getStringExtra(EXTRA_PACKAGE),
                    intent.getStringExtra(EXTRA_POLICY),
                    intent.getLongExtra(EXTRA_REVISION, -1),
                    InterceptionDecision.Kind.valueOf(intent.getStringExtra(EXTRA_KIND)),
                    InterceptionDecision.Trigger.valueOf(intent.getStringExtra(EXTRA_TRIGGER)),
                    ControlLevel.valueOf(intent.getStringExtra(EXTRA_LEVEL)),
                    intent.getStringExtra(EXTRA_WINDOW),
                    intent.getLongExtra(EXTRA_CURRENT, 0),
                    intent.getLongExtra(EXTRA_LIMIT, 0),
                    intent.getLongExtra(EXTRA_RESET, 0),
                    intent.getLongExtra(EXTRA_PASS, 0),
                    intent.getLongExtra(EXTRA_INTERRUPT_PAUSE, 0));
        } catch (RuntimeException invalidRequest) {
            Log.e(TAG, "Invalid interception request", invalidRequest);
            DiagnosticLog.error("activity", intent.getStringExtra(EXTRA_SESSION),
                    "invalid_interception_request", invalidRequest);
            finishAndRemoveTask();
            return;
        }
        sessionId = intent.getStringExtra(EXTRA_SESSION);
        occurredAtMs = intent.getLongExtra(EXTRA_OCCURRED, System.currentTimeMillis());
        boolean sameSession = state != null && sessionId.equals(state.getString(STATE_SESSION));
        copyVariant = sameSession && state.containsKey(STATE_COPY_VARIANT)
                ? state.getInt(STATE_COPY_VARIANT)
                : intent.hasExtra(EXTRA_COPY_VARIANT) ? intent.getIntExtra(EXTRA_COPY_VARIANT, 0) : sessionId.hashCode();
        String savedActionSeed = sameSession && !refreshInitialActionsOnResume
                ? state.getString(STATE_INITIAL_ACTION_SEED) : null;
        initialActionSeed = savedActionSeed == null ? createOpeningSeed() : savedActionSeed;
        refreshInitialActionsOnResume = false;
        String savedEntry = sameSession && !refreshChallengeOnResume ? state.getString(STATE_CHALLENGE_ENTRY) : null;
        challengeEntryId = savedEntry == null ? createOpeningSeed() : savedEntry;
        refreshChallengeOnResume = false;
        InterceptionMode mode = ModeFlowPlan.modeFor(decision.kind, decision.level);
        InteractionSettings currentSettings = interactionStore.read(mode);
        interactionSettings = sameSession
                ? InteractionSessionState.restore(state, currentSettings) : currentSettings;
        InterceptionStage previewStage = readDebugStage(intent);
        boolean previewObstacle = previewStage == InterceptionStage.HOLD
                || previewStage == InterceptionStage.WORD_TRANSLATION || previewStage == InterceptionStage.SUDOKU
                || previewStage == InterceptionStage.FINAL_CONFIRM
                || previewStage != null && previewStage.isCompletedChallenge();
        // 显式预览阻碍页面时保持其流程可操作；图集按压固定 10 秒，均不修改真实设置。
        if (BuildConfig.DEBUG && debugPreview && (debugGallery || previewObstacle)) {
            interactionSettings = new InteractionSettings(interactionSettings.enabledChallenges,
                    debugGallery ? new SecondsRange(10_000L, 10_000L) : interactionSettings.holdRange,
                    interactionSettings.word, new InterceptionOptions(interactionSettings.options.temporaryPassEnabled,
                            interactionSettings.options.obstaclePagesEnabled || previewObstacle,
                            interactionSettings.options.dynamicButtonsEnabled,
                            previewStage == InterceptionStage.FINAL_CONFIRM || (previewStage == null
                                    || previewStage == InterceptionStage.SUDOKU_COMPLETED || !previewStage.isCompletedChallenge())
                                    && interactionSettings.options.confirmationEnabled), interactionSettings.sudokuDifficulty);
        }
        challenge = sameSession ? InteractionSessionState.restoreChallenge(state, interactionSettings, sessionId)
                : interactionSettings.selectChallenge(sessionId);
        if (BuildConfig.DEBUG && previewStage != null && !sameSession) {
            for (InterceptionChallenge candidate : InterceptionChallenge.values()) {
                if (candidate.stage() == previewStage || candidate.stage().completedChallenge() == previewStage) challenge = candidate;
            }
        }
        flow = new InterceptionFlow(decision.kind, decision.level, challenge, interactionSettings.options);
        long nowElapsed = SystemClock.elapsedRealtime();
        long pauseRemaining = decision.minimumInterruptionPauseDurationMs;
        if (intent.hasExtra(EXTRA_STARTED_BOOT)
                && !BootIdentity.read(this).equals(intent.getStringExtra(EXTRA_STARTED_BOOT))) {
            pauseRemaining = Math.max(0L, pauseRemaining - nowElapsed);
        }
        pauseDeadlineElapsedMs = nowElapsed + pauseRemaining;
        if (intent.hasExtra(EXTRA_STARTED_ELAPSED)
                && BootIdentity.read(this).equals(intent.getStringExtra(EXTRA_STARTED_BOOT))) {
            pauseDeadlineElapsedMs = Math.min(nowElapsed, intent.getLongExtra(EXTRA_STARTED_ELAPSED, nowElapsed))
                    + decision.minimumInterruptionPauseDurationMs;
        }
        if (sameSession && state.containsKey(STATE_PAUSE_DEADLINE)
                && BootIdentity.read(this).equals(state.getString(STATE_PAUSE_BOOT))) {
            pauseDeadlineElapsedMs = state.getLong(STATE_PAUSE_DEADLINE);
        }
        if (sameSession) {
            stage = InterceptionStage.valueOf(state.getString(STATE_STAGE));
        } else if (BuildConfig.DEBUG && previewStage != null) {
            stage = previewStage;
        } else {
            stage = flow.initialStage();
        }
        if (!debugPreview && sameSession && !flow.containsStage(stage)) {
            stage = flow.initialStage();
        }
        busy = false;
        DiagnosticLog.info("activity", sessionId, "request package="
                + decision.packageName + " trigger=" + decision.trigger.code
                + " stage=" + stage.name());
        render(sameSession ? state : null);
    }

    /** @return 正式页面打开时独立生成的种子；显式 Debug 预览保持可重复的会话样例 */
    private String createOpeningSeed() {
        return debugPreview ? sessionId : UUID.randomUUID().toString();
    }

    /**
     * 通过页面目录装配当前页，协调层不访问页面内部控件。
     * @param state 同一会话的页面重建状态，首次为空
     */
    private void render(Bundle state) {
        hideAllPages();
        boolean sudoku = stage == InterceptionStage.SUDOKU || stage == InterceptionStage.SUDOKU_COMPLETED;
        View content = findViewById(R.id.interception_root);
        int horizontal = getResources().getDimensionPixelSize(sudoku ? R.dimen.space_sm : R.dimen.space_lg);
        content.setPaddingRelative(horizontal, content.getPaddingTop(), horizontal, content.getPaddingBottom());
        PageSession session = new PageSession(decision, sessionId, copyVariant, stage,
                interactionSettings, ZhijianStorage.get(this).wordCatalog(interactionSettings.word.catalog),
                initialActionSeed, !debugPreview, !debugPreview, challengeEntryId);
        activePage = pageRegistry.create(pageContainer, session, this::dispatch, state);
        activePage.render();
        updatePreviewPanel();
        configureReset();
        confirmVisibleRequest();
    }

    /** 已完成页面装配且真正获得窗口焦点后，才确认拦截页面可见。 */
    private void confirmVisibleRequest() {
        if (!debugPreview && activePage != null && sessionId != null && hasWindowFocus()) {
            InterceptionLaunchDiagnostics.shown(sessionId);
        }
    }

    /** 移除旧页面并释放其回调，保存页由任务协调层单独管理。 */
    private void hideAllPages() {
        pageTransition.cancel();
        if (activePage != null) activePage.pause();
        activePage = null;
        pageContainer.removeAllViews();
        savingPage.setVisibility(View.GONE);
    }

    /**
     * 把 dp 数值转换为当前屏幕密度下的像素值。
     *
     * @param value 本次需要读取、转换或写入的值
     */
    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /**
     * 在 Debug 预览中识别横向滑动并切换到相邻真实拦截状态。
     *
     * @param event 系统事件或当前状态机收到的交互事件
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (pageTransition.isRunning()) return true;
        if (!BuildConfig.DEBUG || !debugGallery || pageContainer == null) return super.dispatchTouchEvent(event);
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            debugSwipeStartX = event.getX();
            debugSwipeStartY = event.getY();
            debugSwipeTracking = true;
        } else if (action == MotionEvent.ACTION_UP && debugSwipeTracking) {
            debugSwipeTracking = false;
            if (activePage != null && activePage.isHandlingHorizontalGesture()) return super.dispatchTouchEvent(event);
            float deltaX = event.getX() - debugSwipeStartX;
            float deltaY = event.getY() - debugSwipeStartY;
            if (Math.abs(deltaX) >= dp(64) && Math.abs(deltaX) > Math.abs(deltaY) * 1.2f) {
                MotionEvent cancel = MotionEvent.obtain(event);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                super.dispatchTouchEvent(cancel);
                cancel.recycle();
                showDebugPage(debugPageIndex + (deltaX < 0 ? 1 : -1));
                return true;
            }
        } else if (action == MotionEvent.ACTION_CANCEL) {
            debugSwipeTracking = false;
        }
        return super.dispatchTouchEvent(event);
    }

    /**
     * 循环切换预览页，并完全复用该页的正常加载与渲染流程。
     *
     * @param pageIndex 调试图集目标页索引，允许首尾循环
     */
    private void showDebugPage(int pageIndex) {
        if (!BuildConfig.DEBUG) return;
        Intent request = getIntent();
        openDebugRequest(debugPageIntent(this, pageIndex,
                request.getIntExtra(EXTRA_DEBUG_SAMPLE, 0),
                InterceptionPreviewCatalog.Presentation.parse(request.getStringExtra(EXTRA_DEBUG_PRESENTATION))));
    }

    /** @param request 调试面板生成的新预览请求 */
    private void openDebugRequest(Intent request) {
        if (!BuildConfig.DEBUG || !debugPreview) return;
        if (previewPanel != null) previewPanel.dismiss();
        setIntent(request);
        loadRequest(null);
    }

    /** 跟随实际流程更新调试菜单；生产请求切入时彻底移除入口。 */
    private void updatePreviewPanel() {
        if (BuildConfig.DEBUG && debugPreview) {
            debugPageIndex = InterceptionPreviewCatalog.indexOf(decision.level, stage, decision.kind);
            if (previewPanel == null) previewPanel = new InterceptionPreviewPanel(this, this::openDebugRequest);
            previewPanel.update(debugPageIndex, getIntent());
        } else if (previewPanel != null) {
            previewPanel.close();
            previewPanel = null;
        }
    }

    /**
     * 仅在 Debug 预览中解析目标状态，无效值则回退到真实初始状态。
     *
     * @param intent 本次启动或复用页面的请求参数
     */
    private InterceptionStage readDebugStage(Intent intent) {
        if (!BuildConfig.DEBUG || !debugPreview) return null;
        String value = intent.getStringExtra(EXTRA_DEBUG_STAGE);
        if (value == null) return null;
        try {
            InterceptionStage requested = InterceptionStage.valueOf(value);
            return requested == InterceptionStage.COMPLETE ? null : requested;
        } catch (IllegalArgumentException invalidStage) {
            Log.w(TAG, "Ignoring invalid debug stage: " + value);
            return null;
        }
    }

    /** 启动规则恢复或中断休息结束检查；中断倒计时独立于摩擦操作。 */
    private void configureReset() {
        main.removeCallbacks(resetTick);
        if (!resumed || busy) return;
        scheduleRuleRecheck();
        if (automaticReleaseAtMs() > 0) updateReset();
    }

    /** 可见页面按最早额度释放和星期切换边界复查，页面重绘不延后已有期限。 */
    private void scheduleRuleRecheck() {
        main.removeCallbacks(ruleTick);
        if (!resumed || busy || debugPreview || decision == null) return;
        long now = System.currentTimeMillis();
        long midnight = github.vhoom.zhijian.domain.interception.RuleWindowSchedule.nextDayStart(
                now, java.time.ZoneId.systemDefault().getId());
        long remaining = ruleRecheckDeadlineElapsedMs == 0L
                ? github.vhoom.zhijian.domain.interception.InterceptionEvaluation.DEFAULT_RECHECK_MS
                : Math.max(1L, ruleRecheckDeadlineElapsedMs - SystemClock.elapsedRealtime());
        main.postDelayed(ruleTick, Math.max(1L, Math.min(
                Math.min(github.vhoom.zhijian.domain.interception.InterceptionEvaluation.DEFAULT_RECHECK_MS,
                        remaining), midnight - now)));
    }

    /**
     * 保存本次复查要求的单调时间期限，供挑战步骤切换和页面刷新共用。
     *
     * @param recheckAfterMs 本次运行结果要求的最迟复查间隔，单位为毫秒
     * @param evaluatedAtElapsedMs 本次异步复查发起时的单调时间，耗时不推迟额度释放
     */
    private void scheduleRuleRecheck(long recheckAfterMs, long evaluatedAtElapsedMs) {
        long delay = Math.max(1L, Math.min(
                github.vhoom.zhijian.domain.interception.InterceptionEvaluation.DEFAULT_RECHECK_MS,
                recheckAfterMs));
        ruleRecheckDeadlineElapsedMs = evaluatedAtElapsedMs + delay;
        scheduleRuleRecheck();
    }

    /** 按当前规则刷新页面；普通禁用解除时返回，提醒自然休息完成后仍等待主动选择。 */
    private void recheckVisibleRules() {
        if (!debugPreview && decision != null) {
            recheckForRecovery(decision.kind == InterceptionDecision.Kind.BLOCK);
        }
    }

    /** 返回当前倒计时在墙钟中的期限；中断归零保持页面并允许正常返回。 */
    private long automaticReleaseAtMs() {
        if (decision == null) return 0L;
        if (decision.kind == InterceptionDecision.Kind.INTERRUPT) {
            return Math.addExact(occurredAtMs, decision.minimumInterruptionPauseDurationMs);
        }
        return decision.resetAtMs;
    }

    /** 检查规则是否已经恢复或休息是否结束，并持续刷新独立倒计时。 */
    private void updateReset() {
        if (!resumed || busy) return;
        long releaseAtMs = automaticReleaseAtMs();
        if (releaseAtMs <= 0) return;
        long remaining = decision.kind == InterceptionDecision.Kind.INTERRUPT
                ? pauseDeadlineElapsedMs - SystemClock.elapsedRealtime()
                : releaseAtMs - System.currentTimeMillis();
        if (decision.kind == InterceptionDecision.Kind.INTERRUPT) {
            countdown.update(remaining);
            if (activePage != null) activePage.updateRest(remaining);
        }
        if (remaining <= 0) {
            recheckForRecovery(decision.kind != InterceptionDecision.Kind.INTERRUPT);
            return;
        }
        main.postDelayed(resetTick, Math.min(1_000L, remaining));
    }

    /**
     * 将用户事件交给状态机并执行返回、放行或页面切换效果。
     *
     * @param event 系统事件或当前状态机收到的交互事件
     */
    private void dispatch(InterceptionEvent event) {
        if (busy || flow == null || pageTransition.isRunning()) return;
        InterceptionFlow.Transition transition = flow.dispatch(stage, event,
                decision.kind == InterceptionDecision.Kind.INTERRUPT
                        && SystemClock.elapsedRealtime() >= pauseDeadlineElapsedMs);
        DiagnosticLog.info("flow", sessionId, "stage=" + stage.name() + " event=" + event.name()
                + " next=" + transition.stage.name() + " effect=" + transition.effect.name());
        switch (transition.effect) {
            case RETURN_HOME:
            case ISSUE_GRANT:
            case RETURN_TO_TARGET:
                complete(transition.effect);
                break;
            case RESUME_USAGE:
            case DISMISS:
                recheckForRecovery(true);
                break;
            default:
                if (transition.stage != stage) {
                    View previous = activePage == null ? null : activePage.view();
                    stage = transition.stage;
                    if (event == InterceptionEvent.CHALLENGE_COMPLETED && stage.isCompletedChallenge()
                            && activePage != null && activePage.completeChallenge()) {
                        updatePreviewPanel();
                        configureReset();
                    } else {
                        render(null);
                        if (event == InterceptionEvent.CHALLENGE_COMPLETED && activePage != null && !busy && !isFinishing()) {
                            pageTransition.start(pageContainer, previous, activePage.view());
                        }
                    }
                }
                break;
        }
    }

    /**
     * 后台复查全部规则；休息归零仅更新事实和页面，主动返回或禁用结束才关闭任务。
     *
     * @param returnToTarget 规则已经解除时，是否允许本次复查关闭页面并返回目标应用
     */
    private void recheckForRecovery(boolean returnToTarget) {
        if (!resumed || busy || recoveryChecking || decision == null) return;
        if (debugPreview) {
            if (returnToTarget) {
                if (debugGallery) Toast.makeText(this, R.string.interception_preview_result_resume, Toast.LENGTH_SHORT).show();
                else finishToTarget();
            }
            return;
        }
        recoveryChecking = true;
        main.removeCallbacks(ruleTick);
        int checkedGeneration = requestGeneration;
        int checkedRecovery = ++recoveryGeneration;
        String target = decision.packageName;
        long checkedAtElapsedMs = SystemClock.elapsedRealtime();
        CompletableFuture.supplyAsync(() -> recoveryRuntime.evaluate(target)).whenComplete((result, failure) ->
                runOnUiThread(() -> {
                    if (isDestroyed() || !resumed || checkedGeneration != requestGeneration
                            || checkedRecovery != recoveryGeneration) return;
                    recoveryChecking = false;
                    if (busy) return; // 放行尚未落盘时，自然恢复的旧回调不能提前返回目标。
                    if (failure != null) {
                        DiagnosticLog.error("recovery", sessionId, "rule_recheck_failed", failure);
                        scheduleRuleRecheck(1_000L, SystemClock.elapsedRealtime());
                        return;
                    }
                    scheduleRuleRecheck(result.recheckAfterMs, checkedAtElapsedMs);
                    if (result.decision == null) {
                        if (returnToTarget) finishToTarget();
                        else {
                            pauseDeadlineElapsedMs = SystemClock.elapsedRealtime();
                            countdown.update(0L);
                            if (activePage != null) activePage.updateRest(0L);
                        }
                        return;
                    }
                    PendingInterception updated = result.pending;
                    if (updated == null) { main.postDelayed(resetTick, 1_000L); return; }
                    boolean sameReason = updated.sessionId.equals(sessionId) && updated.decision.kind == decision.kind
                            && updated.decision.policyId.equals(decision.policyId)
                            && updated.decision.policyRevision == decision.policyRevision
                            && updated.decision.trigger == decision.trigger
                            && updated.decision.windowId.equals(decision.windowId)
                            && updated.decision.level == decision.level
                            && updated.decision.resetAtMs == decision.resetAtMs
                            && updated.decision.minimumInterruptionPauseDurationMs == decision.minimumInterruptionPauseDurationMs;
                    if (sameReason) {
                        if (decision.kind == InterceptionDecision.Kind.INTERRUPT) {
                            pauseDeadlineElapsedMs = updated.startedElapsedMs
                                    + updated.decision.minimumInterruptionPauseDurationMs;
                        }
                        main.removeCallbacks(resetTick);
                        main.postDelayed(resetTick, 1_000L);
                    } else {
                        acceptRequest(InterceptionIntents.intent(this, updated, target), false);
                        scheduleRuleRecheck(result.recheckAfterMs, checkedAtElapsedMs);
                    }
                }));
    }

    /**
     * 按会话开关持久化导航或特殊放行结果；所有选择都不清零真实连续用量。
     *
     * @param effect 状态机确定的导航或限时放行效果
     */
    private void complete(InterceptionEffect effect) {
        boolean allow = effect != InterceptionEffect.RETURN_HOME;
        boolean issueGrant = effect == InterceptionEffect.ISSUE_GRANT;
        if (BuildConfig.DEBUG && debugGallery) {
            DiagnosticLog.debug("debug-preview", allow ? "gallery allow" : "gallery abandon");
            Toast.makeText(this, allow ? issueGrant ? R.string.interception_preview_result_pass
                    : R.string.interception_preview_result_resume
                    : R.string.interception_preview_result_exit, Toast.LENGTH_SHORT).show();
            showDebugPage(debugPageIndex);
            return;
        }
        if (BuildConfig.DEBUG && debugPreview) {
            DiagnosticLog.debug("debug-preview", "preview effect=" + effect.name());
            if (allow) finishToTarget();
            else goHome();
            return;
        }
        busy = true;
        int completedGeneration = requestGeneration;
        Bundle retainedPage = new Bundle();
        if (activePage != null) activePage.save(retainedPage);
        hideAllPages();
        savingPage.setVisibility(View.VISIBLE);
        String completedSession = sessionId;
        CompletableFuture<Void> saved = new InterceptionOutcomeWriter(this).save(
                decision, sessionId, occurredAtMs, challenge, interactionSettings, effect);
        if (challenge == InterceptionChallenge.SUDOKU) {
            saved = saved.thenCompose(ignored ->
                    new github.vhoom.zhijian.data.store.interception.SudokuProgressStore(this)
                            .finishSession(completedSession));
        } else if (challenge == InterceptionChallenge.WORD_TRANSLATION) {
            saved = saved.thenCompose(ignored ->
                    new github.vhoom.zhijian.data.store.interception.WordProgressStore(this)
                            .finishSession(completedSession));
        }
        saved.whenComplete((ignored, failure) -> {
            if (failure != null) DiagnosticLog.error("outcome", completedSession,
                    "save_failed effect=" + effect.name(), failure);
            else DiagnosticLog.info("outcome", completedSession, "saved effect=" + effect.name());
            runOnUiThread(() -> {
                    if (isDestroyed() || completedGeneration != requestGeneration) return;
                    if (failure != null && allow) {
                        Log.e(TAG, "Could not persist interception outcome", failure);
                        busy = false;
                        Toast.makeText(this, R.string.storage_save_failed, Toast.LENGTH_LONG).show();
                        render(retainedPage);
                        return;
                    }
                    if (failure != null) Log.e(TAG, "Could not record abandoned interception", failure);
                    if (allow) finishToTarget();
                    else goHome();
            });
        });
    }

    /** 返回系统桌面并移除拦截任务。 */
    private void goHome() {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (resumed) startActivity(home);
        finishAndRemoveTask();
        overridePendingTransition(0, 0);
    }

    /** 关闭拦截页，让原目标应用重新成为前台页面。 */
    private void finishToTarget() {
        finishAndRemoveTask();
        overridePendingTransition(0, 0);
        if (resumed && !debugPreview && decision != null) {
            AccessibilityConnection.resumeTarget(decision.packageName);
        }
    }

    /** 中断休息结束后返回原应用；归零前返回视为离开，仍保留剩余休息限制。 */
    @Override
    protected void onBackRequested() {
        if (BuildConfig.DEBUG && debugGallery) {
            finishAndRemoveTask();
            return;
        }
        if (decision != null && decision.kind == InterceptionDecision.Kind.INTERRUPT
                && SystemClock.elapsedRealtime() >= pauseDeadlineElapsedMs) {
            dispatch(InterceptionEvent.CONTINUE);
            return;
        }
        if (stage == InterceptionStage.LOCKED) goHome();
        else dispatch(InterceptionEvent.ABANDON);
    }

    /** 回到前台恢复当前页；实际重进更换进入标识，存储仅刷新未开始的挑战。 */
    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (activePage != null && !busy) {
            boolean reopenedInitial = refreshInitialActionsOnResume;
            boolean reopenedChallenge = refreshChallengeOnResume;
            refreshInitialActionsOnResume = false;
            refreshChallengeOnResume = false;
            if (reopenedChallenge) challengeEntryId = createOpeningSeed();
            if (reopenedInitial && stage == InterceptionStage.INITIAL) {
                initialActionSeed = createOpeningSeed();
                render(null);
            } else if (reopenedChallenge && (stage == InterceptionStage.WORD_TRANSLATION
                    || stage == InterceptionStage.SUDOKU)) {
                Bundle retained = new Bundle();
                activePage.save(retained);
                render(retained);
            } else {
                activePage.render();
                configureReset();
            }
            recheckVisibleRules();
        }
    }

    /** 页面失去前台时取消未完成长按，防止后台累计。 */
    @Override
    protected void onPause() {
        resumed = false;
        if (previewPanel != null) previewPanel.dismiss();
        stopTimers();
        super.onPause();
    }

    /** 完整离开前台后标记下一次首屏打开；旋转与新实例恢复继续使用已保存种子。 */
    @Override
    protected void onStop() {
        if (!debugPreview && activePage != null && !busy && !isChangingConfigurations()) {
            refreshChallengeOnResume = true;
        }
        if (!debugPreview && activePage != null && !busy
                && stage == InterceptionStage.INITIAL && !isChangingConfigurations()) {
            refreshInitialActionsOnResume = true;
        }
        super.onStop();
    }

    /**
     * 保存状态机与单词题解锁时刻，供旋转或进程重建恢复。
     *
     * @param outState 写入页面重建所需数据的状态容器
     */
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (sessionId == null || stage == null) return;
        outState.putString(STATE_SESSION, sessionId);
        outState.putString(STATE_STAGE, stage.name());
        outState.putInt(STATE_COPY_VARIANT, copyVariant);
        outState.putString(STATE_INITIAL_ACTION_SEED, initialActionSeed);
        // Android 10+ 在 onStop 后保存后台状态；实际离开不沿用进入标识，旋转仍保留。
        if (!refreshChallengeOnResume) outState.putString(STATE_CHALLENGE_ENTRY, challengeEntryId);
        else outState.remove(STATE_CHALLENGE_ENTRY);
        outState.putLong(STATE_PAUSE_DEADLINE, pauseDeadlineElapsedMs);
        outState.putString(STATE_PAUSE_BOOT, BootIdentity.read(this));
        if (BuildConfig.DEBUG && debugGallery) outState.putInt(STATE_DEBUG_PAGE, debugPageIndex);
        InteractionSessionState.save(outState, interactionSettings, challenge);
        if (activePage != null) activePage.save(outState);
    }

    /** 销毁页面前移除所有 Handler 回调。 */
    @Override
    protected void onDestroy() {
        if (previewPanel != null) previewPanel.close();
        stopTimers();
        super.onDestroy();
    }

    /** 集中取消摩擦、规则恢复和单词解锁计时任务。 */
    private void stopTimers() {
        pageTransition.cancel();
        main.removeCallbacks(resetTick);
        main.removeCallbacks(ruleTick);
        recoveryGeneration++;
        recoveryChecking = false;
        if (activePage != null) activePage.pause();
    }

    /** 进入沉浸式全屏，并允许用户通过边缘滑动临时查看系统栏。 */
    private void hideSystemBars() {
        View decor = getWindow().getDecorView();
        decor.post(() -> {
            if (isDestroyed()) return;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                WindowInsetsController controller = decor.getWindowInsetsController();
                if (controller == null) {
                    DiagnosticLog.debug("window", "insets controller unavailable; keep theme fullscreen");
                    return;
                }
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            } else {
                decor.setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            }
            DiagnosticLog.debug("window", "system bars hidden");
        });
    }

    /**
     * 窗口重新获得焦点时恢复沉浸式系统栏状态。
     *
     * @param hasFocus 当前窗口是否获得焦点
     */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && pageContainer != null) {
            hideSystemBars();
            confirmVisibleRequest();
        }
    }

}
