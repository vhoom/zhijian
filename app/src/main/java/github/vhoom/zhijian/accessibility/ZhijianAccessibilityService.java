package github.vhoom.zhijian.accessibility;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import java.util.Objects;
import java.security.SecureRandom;

import github.vhoom.zhijian.data.store.ZhijianStorage;
import github.vhoom.zhijian.data.store.PolicySnapshot;
import github.vhoom.zhijian.ZhijianApplication;
import github.vhoom.zhijian.data.model.runtime.PendingInterception;
import github.vhoom.zhijian.data.model.runtime.PassGrant;
import github.vhoom.zhijian.data.model.policy.ControlLevel;
import github.vhoom.zhijian.domain.interception.InterceptionDecision;
import github.vhoom.zhijian.domain.interception.InterceptionEvaluation;
import github.vhoom.zhijian.domain.interception.RetreatReentryPolicy;
import github.vhoom.zhijian.runtime.interception.InterceptionRuntime;
import github.vhoom.zhijian.ui.interception.InterceptionActivity;
import github.vhoom.zhijian.util.diagnostics.DiagnosticLog;
import github.vhoom.zhijian.util.diagnostics.InterceptionLaunchDiagnostics;
import github.vhoom.zhijian.util.permission.PermissionStatusReader;

import java.time.ZoneId;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 前台窗口事件的系统 Adapter；规则解释与交互流程分别交给运行模块和拦截页面。
 *
 * 维护日期：2026年11月14日
 */
public final class ZhijianAccessibilityService extends AccessibilityService {
    private static final String TAG = "ZhijianInterception";
    private static final long FAILED_RECHECK_MS = 30_000L;
    private static final long LAUNCH_RECHECK_MS = 1_500L;
    /** 留出系统处理返回事件的时间，再复查是否仍需显示闭关页面。 */
    private static final long BACK_PAGE_DELAY_MS = 250L;
    /** 连续使用期间也定期归档，独立于规则检查和首页可见状态。 */
    private static final long ARCHIVE_INTERVAL_MS = 60_000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final RetreatReentryPolicy retreatReentry = new RetreatReentryPolicy(new SecureRandom()::nextBoolean);
    private final ExecutorService evaluator = Executors.newSingleThreadExecutor(runnable ->
            new Thread(runnable, "zhijian-interception"));
    private final Runnable scheduledEvaluation = this::evaluateForeground;
    private final Runnable scheduledArchive = new Runnable() {
        /** 服务绑定期间周期提交；服务停止时统一取消 Handler 回调。 */
        @Override public void run() {
            if (stopped) return;
            github.vhoom.zhijian.runtime.usage.UsageArchiveCoordinator.synchronize(
                    ZhijianAccessibilityService.this, false);
            main.postDelayed(this, ARCHIVE_INTERVAL_MS);
        }
    };

    private ManagedPackageFilter packageFilter;
    private InterceptionRuntime runtime;
    private String foregroundPackage;
    private long foregroundObservedAtMs;
    private long foregroundVersion;
    private long evaluationVersion;
    private boolean evaluating;
    private long scheduledAtUptimeMs;
    private int failureCount;
    private volatile boolean stopped;
    private String lastLaunchKey = "";
    private long lastLaunchElapsed;
    /** 当前窗口请求的回执代次；服务解绑时取消等待。 */
    private long launchRequest;
    /** 返回后请求的页面允许从桌面等待真实窗口回执。 */
    private long backPageLaunchRequest = -1L;
    /** 每次真实前台访问最多模拟一次返回，定时复查不重复注入返回事件。 */
    private long backHandledVersion = -1L;
    /** 返回后的弹页代次；新的返回请求或服务停止使旧请求失效。 */
    private long backPageGeneration;

    /**
     * 系统连接无障碍服务后创建规则判断依赖并发布连接状态。
     */
    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        packageFilter = new ManagedPackageFilter(this);
        runtime = new InterceptionRuntime(this);
        AccessibilityConnection.connected(this);
        DiagnosticLog.info("accessibility", "", "service_connected "
                + PermissionStatusReader.read(this).describe());
        initializeForeground();
        main.removeCallbacks(scheduledArchive);
        main.post(scheduledArchive);
    }

    /** 后台读取连接时的前台应用，新窗口事件优先于本次初始查询。 */
    private void initializeForeground() {
        long startingVersion = foregroundVersion;
        evaluator.execute(() -> {
            try {
                String packageName = runtime.currentForegroundPackage();
                main.post(() -> {
                    if (stopped || foregroundVersion != startingVersion
                            || !packageFilter.accepts(packageName)) return;
                    foregroundPackage = packageName;
                    foregroundObservedAtMs = System.currentTimeMillis();
                    foregroundVersion++;
                    recordForeground(packageName);
                    scheduleEvaluation(250L);
                });
            } catch (RuntimeException failure) {
                Log.e(TAG, "Could not read initial foreground", failure);
                DiagnosticLog.error("accessibility", "", "initial_foreground_failed", failure);
                // 系统暂时无法读取用量时，后续窗口事件仍可正常启动规则检查。
            }
        });
    }

    /**
     * 接收前台窗口变化，过滤不可管控包名后安排规则检查。
     *
     * @param event 系统事件或当前状态机收到的交互事件
     */
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || event.getPackageName() == null) return;
        String actualPackage = event.getPackageName().toString();
        if (stopped || packageFilter == null || packageFilter.isTransientWindow(actualPackage)) return;
        if (Objects.equals(actualPackage, foregroundPackage)) {
            // 桌面事件可能遗漏；同包恢复仍复查真实访问，保留前台代次且不推迟更早的阈值检查。
            if (packageFilter.accepts(actualPackage)) scheduleEvaluation(250L);
            return;
        }
        foregroundPackage = actualPackage;
        foregroundObservedAtMs = System.currentTimeMillis();
        foregroundVersion++;
        if (!actualPackage.equals(getPackageName()) && !isWaitingAfterBack(launchRequest)) {
            InterceptionLaunchDiagnostics.cancel(launchRequest);
        }
        recordForeground(actualPackage);
        if (!packageFilter.accepts(foregroundPackage)) {
            github.vhoom.zhijian.runtime.usage.UsageArchiveCoordinator.synchronize(this, false);
            main.removeCallbacks(scheduledEvaluation);
            scheduledAtUptimeMs = 0L;
            return;
        }
        DiagnosticLog.info("accessibility", "", "foreground=" + foregroundPackage);
        // 给 UsageEvents 极短时间落下刚发生的前台切换，限次规则不会慢一整轮才触发。
        scheduleEvaluation(250L);
    }

    /**
     * 处理系统反馈中断；服务仍绑定时不发布断开状态。
     */
    @Override
    public void onInterrupt() {
        // 中断反馈不等于服务解绑，不能在此把连接状态改成已断开。
    }

    /** @param packageName 已过滤瞬时窗口的真实前台；后台顺序落盘，止间拦截窗口不结束原访问 */
    private void recordForeground(String packageName) {
        evaluator.execute(() -> {
            try { runtime.observeForeground(packageName); }
            catch (RuntimeException failure) {
                DiagnosticLog.error("accessibility", "", "block_continuation_foreground_failed", failure);
            }
        });
    }

    /**
     * 服务解绑时停止规则检查并发布断开状态。
     *
     * @param intent 本次启动或复用页面的请求参数
     */
    @Override
    public boolean onUnbind(Intent intent) {
        stopEvaluation();
        AccessibilityConnection.disconnected(this);
        return super.onUnbind(intent);
    }

    /**
     * 释放本实例持有的监听、弹窗或后台回调。
     */
    @Override
    public void onDestroy() {
        stopEvaluation();
        AccessibilityConnection.disconnected(this);
        super.onDestroy();
    }

    /**
     * 取消旧检查并按指定延迟安排下一次前台规则判断。
     *
     * @param delayMs 安排下一次工作前等待的毫秒数
     */
    private void scheduleEvaluation(long delayMs) {
        long due = SystemClock.uptimeMillis() + Math.max(0L, delayMs);
        if (stopped || scheduledAtUptimeMs > 0L && scheduledAtUptimeMs <= due) return;
        main.removeCallbacks(scheduledEvaluation);
        scheduledAtUptimeMs = due;
        main.postDelayed(scheduledEvaluation, Math.max(0L, delayMs));
    }

    /**
     * 在后台等待存储初始化并检查当前前台应用。
     */
    private void evaluateForeground() {
        scheduledAtUptimeMs = 0L;
        String packageName = foregroundPackage;
        if (stopped || packageFilter == null || !packageFilter.accepts(packageName)) return;
        if (evaluating) {
            scheduleEvaluation(250L);
            return;
        }
        evaluating = true;
        evaluationVersion = foregroundVersion;
        long observedAtMs = foregroundObservedAtMs;
        ((ZhijianApplication) getApplication()).ready().whenComplete((ignored, readyFailure) -> {
            if (readyFailure != null) {
                main.post(() -> completeEvaluation(packageName, null, readyFailure, 0L, 0L));
                return;
            }
            if (stopped) return;
            try {
                evaluator.execute(() -> {
                    long evaluatedAtWallMs = System.currentTimeMillis();
                    long evaluatedAtElapsedMs = SystemClock.elapsedRealtime();
                    try {
                        InterceptionEvaluation result = runtime.evaluate(packageName, observedAtMs);
                        main.post(() -> completeEvaluation(packageName, result, null,
                                evaluatedAtWallMs, evaluatedAtElapsedMs));
                    } catch (Throwable failure) {
                        main.post(() -> completeEvaluation(packageName, null, failure,
                                evaluatedAtWallMs, evaluatedAtElapsedMs));
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException shuttingDown) {
                // 解绑可能发生在检查排队之前，关闭中的线程不再接收任务。
                main.post(() -> completeEvaluation(packageName, null, shuttingDown, 0L, 0L));
            }
        });
    }

    /**
     * 回到主线程处理规则结果；过期前台结果不再执行。
     *
     * @param packageName 需要查询或管控的应用包名
     * @param result 异步规则检查或模型校验的结果
     * @param failure 本次异步工作失败的异常；成功时为 null
     * @param evaluatedAtWallMs 本轮计算开始时的墙上时钟；计算尚未开始就失败时为零
     * @param evaluatedAtElapsedMs 同次计算开始时的单调时钟；失败分支不使用此值
     */
    private void completeEvaluation(String packageName, InterceptionEvaluation result, Throwable failure,
            long evaluatedAtWallMs, long evaluatedAtElapsedMs) {
        evaluating = false;
        if (stopped) return;
        if (!packageName.equals(foregroundPackage) || evaluationVersion != foregroundVersion) {
            if (result != null && result.pending != null && packageFilter.accepts(foregroundPackage)) {
                PendingInterception redirected = redirectSharedPending(result.pending);
                if (redirected != null) {
                    launchInterception(redirected, packageFilter.labelOf(foregroundPackage));
                    return;
                }
            }
            if (packageFilter != null && packageFilter.accepts(foregroundPackage)) {
                boolean reminder = result != null && result.pending != null
                        && result.pending.decision.kind == InterceptionDecision.Kind.INTERRUPT;
                scheduleEvaluation(reminder ? 1L : 250L);
            }
            return;
        }
        if (failure != null) {
            Log.e(TAG, "Rule evaluation failed", failure);
            DiagnosticLog.error("evaluation", "", "failed package=" + packageName, failure);
            scheduleEvaluation(Math.min(FAILED_RECHECK_MS, 1_000L << Math.min(failureCount++, 5)));
            return;
        }
        if (result == null) {
            scheduleEvaluation(1_000L);
            return;
        }
        failureCount = 0;
        if (result.decision == null) {
            retreatReentry.clear(ZhijianStorage.get(this).snapshot().effectivePolicyId(packageName), packageName);
            DiagnosticLog.sample("allow:" + packageName, "evaluation", "allow package=" + packageName
                    + " recheckMs=" + result.recheckAfterMs);
            scheduleEvaluation(result.recheckDelayMs(evaluatedAtWallMs, evaluatedAtElapsedMs,
                    SystemClock.elapsedRealtime(), ZoneId.systemDefault().getId()));
            return;
        }
        DiagnosticLog.info("evaluation", result.pending.sessionId, "block package=" + packageName
                + " trigger=" + result.decision.trigger.code
                + " level=" + result.decision.level.code
                + " current=" + result.decision.currentValue + " limit=" + result.decision.limitValue);
        handleInterception(result.pending, packageFilter.labelOf(packageName));
    }

    /**
     * 同组共享额度结果转交当前组员；失效策略或可能已完成自然休息的结果仍须重新检查。
     * @param pending 原应用已经持久化的拦截事实
     * @return 能继续使用的同组会话，跨组、过期或版本改变时为空
     */
    private PendingInterception redirectSharedPending(PendingInterception pending) {
        ZhijianStorage.State state = ZhijianStorage.get(this).state();
        PolicySnapshot policies = state.policies;
        String policyId = policies.effectivePolicyId(foregroundPackage);
        long wall = System.currentTimeMillis();
        if (!pending.isCurrent(policyId, policies.revisionOf(policyId), policies.level(), wall)) return null;
        // 闭关的返回动作必须使用本次真实进入的完整复查，旧组员结果不能代替。
        if (policies.level() == ControlLevel.SEAL) return null;
        PendingInterception selected = pending.forTarget(foregroundPackage);
        if (runtime.isContinuedBlock(selected.decision)) return null;
        // 旧提醒结果必须在当前应用复查禁用边界，不能直接转交后盖过新禁用。
        if (pending.decision.kind == InterceptionDecision.Kind.INTERRUPT) return null;
        PassGrant grant = state.runtime.grantFor(foregroundPackage, policyId, runtime.bootId(),
                wall, SystemClock.elapsedRealtime());
        if (grant != null && grant.covers(foregroundPackage, policyId)
                && grant.remainingMs(runtime.bootId(), wall, SystemClock.elapsedRealtime()) > 0L) return null;
        return policyId.startsWith("group:") ? selected : null;
    }

    /**
     * 首次正常显示闭关页，后续访问按共享策略选择系统返回或返回后显示。
     * @param pending 本次前台已复查的限制 @param appLabel 当前目标的显示名称
     */
    private void handleInterception(PendingInterception pending, String appLabel) {
        PolicySnapshot policies = ZhijianStorage.get(this).snapshot();
        if (!pending.isCurrent(policies.effectivePolicyId(pending.decision.packageName),
                policies.revisionOf(pending.decision.policyId), policies.level(), System.currentTimeMillis())) {
            scheduleEvaluation(1L);
            return;
        }
        RetreatReentryPolicy.Action action = retreatReentry.actionFor(pending, foregroundVersion);
        if (action == RetreatReentryPolicy.Action.SHOW_PAGE) {
            launchInterception(pending, appLabel);
            return;
        }
        if (backHandledVersion == foregroundVersion) {
            // 完整复查仍命中且目标仍在前台，说明返回未退出；两种动作都以页面兜底。
            launchInterception(pending, appLabel, true);
            scheduleEvaluation(LAUNCH_RECHECK_MS);
            return;
        }
        long visit = foregroundVersion;
        long request = ++backPageGeneration;
        // 新访问不沿用上一轮的短时弹页去重，否则快速返回桌面后会丢失弹页。
        lastLaunchKey = "";
        InterceptionLaunchDiagnostics.cancel(launchRequest);
        boolean accepted = performGlobalAction(GLOBAL_ACTION_BACK);
        DiagnosticLog.info("retreat-reentry", pending.sessionId, "action=" + action.name()
                + " back_accepted=" + accepted + " target=" + pending.decision.packageName
                + " policy=" + pending.decision.policyId + " visit=" + visit);
        if (!accepted) {
            launchInterception(pending, appLabel);
            return;
        }
        backHandledVersion = visit;
        main.postDelayed(() -> recheckAfterBack(request, visit, pending, appLabel, action), BACK_PAGE_DELAY_MS);
        // 返回请求被接受不等于应用已经退出，仍复查真实用量及未解除规则。
        scheduleEvaluation(LAUNCH_RECHECK_MS);
    }

    /**
     * 返回事件之后后台复查，休息完成、规则变化或用户切到其他应用时不显示旧页面。
     * @param request 返回后弹页的有效代次 @param visit 触发返回的前台代次
     * @param pending 原本已确认的闭关限制 @param appLabel 原目标的显示名称
     * @param action 本次访问选定的返回动作；只返回时仅为仍在前台的目标兜底
     */
    private void recheckAfterBack(long request, long visit, PendingInterception pending, String appLabel,
            RetreatReentryPolicy.Action action) {
        if (!canShowAfterBack(request, visit, pending, action)) return;
        long observedAtMs = Objects.equals(pending.decision.packageName, foregroundPackage)
                ? foregroundObservedAtMs : 0L;
        evaluator.execute(() -> {
            try {
                InterceptionEvaluation updated = runtime.evaluate(pending.decision.packageName, observedAtMs);
                main.post(() -> {
                    if (!canShowAfterBack(request, visit, pending, action)) return;
                    if (updated.decision == null) {
                        retreatReentry.clear(pending.decision.policyId, pending.decision.packageName);
                        return;
                    }
                    if (updated.pending == null || !canShowAfterBack(request, visit, updated.pending, action)) return;
                    // 新命中原因建立自身首轮；同一原因沿用本次抽样，不再次模拟返回。
                    retreatReentry.actionFor(updated.pending, visit);
                    launchInterception(updated.pending, appLabel, true);
                });
            } catch (RuntimeException failure) {
                DiagnosticLog.error("retreat-reentry", pending.sessionId, "back_page_recheck_failed", failure);
                main.post(() -> {
                    if (canShowAfterBack(request, visit, pending, action)) launchInterception(pending, appLabel, true);
                });
            }
        });
    }

    /**
     * 只返回时仅为原应用兜底；返回后弹页允许落到桌面或 止间，其他访问使旧请求失效。
     * @param request 本次弹页代次 @param visit 原前台代次 @param pending 本次待显示的限制
     * @param action 本次访问固定采用的返回动作
     * @return 是否仍可以显示返回后的闭关页面
     */
    private boolean canShowAfterBack(long request, long visit, PendingInterception pending,
            RetreatReentryPolicy.Action action) {
        if (stopped || request != backPageGeneration || foregroundVersion > visit + 1L) return false;
        String target = pending.decision.packageName;
        if (!Objects.equals(target, foregroundPackage) && (action == RetreatReentryPolicy.Action.BACK_ONLY
                || !Objects.equals(getPackageName(), foregroundPackage)
                && !packageFilter.isHome(foregroundPackage))) return false;
        PolicySnapshot policies = ZhijianStorage.get(this).snapshot();
        return policies.level() == ControlLevel.SEAL && pending.isCurrent(policies.effectivePolicyId(target),
                policies.revisionOf(pending.decision.policyId), policies.level(), System.currentTimeMillis());
    }

    /**
     * 保留本次返回造成的桌面切换期间的弹页回执，后续真实访问不沿用旧等待。
     * @param request 当前待确认的页面请求代次
     * @return 是否仍在等待同次返回后的页面实际显示
     */
    private boolean isWaitingAfterBack(long request) {
        return request == backPageLaunchRequest && foregroundVersion == backHandledVersion + 1L
                && packageFilter.isHome(foregroundPackage);
    }

    /**
     * 对重复触发短暂去重，并启动覆盖目标应用的独立拦截任务。
     *
     * @param pending 已持久化的完整拦截会话
     * @param appLabel 供界面和审计显示的应用名称
     */
    private void launchInterception(PendingInterception pending, String appLabel) {
        launchInterception(pending, appLabel, false);
    }

    /**
     * 启动页面并等待实际窗口；返回后落到桌面时仍保留本次弹页回执检查。
     * @param pending 已持久化的限制 @param appLabel 目标显示名称 @param afterBack 是否为返回后弹页
     */
    private void launchInterception(PendingInterception pending, String appLabel, boolean afterBack) {
        InterceptionDecision decision = pending.decision;
        if (runtime.isPresentationSuppressed(decision)) {
            DiagnosticLog.info("launch", pending.sessionId, "suppressed_after_continuation_or_grant");
            scheduleEvaluation(1L);
            return;
        }
        String key = decision.packageName + ':' + decision.policyId + ':' + decision.policyRevision
                + ':' + decision.trigger.code + ':' + decision.windowId;
        long now = SystemClock.elapsedRealtime();
        if (key.equals(lastLaunchKey) && now - lastLaunchElapsed < LAUNCH_RECHECK_MS) {
            scheduleEvaluation(LAUNCH_RECHECK_MS - (now - lastLaunchElapsed));
            return;
        }
        lastLaunchKey = key;
        lastLaunchElapsed = now;
        DiagnosticLog.info("launch", pending.sessionId, "requested key=" + key);
        Intent intent = InterceptionActivity.intent(this, pending, appLabel);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        long request = InterceptionLaunchDiagnostics.requested(pending.sessionId, now);
        launchRequest = request;
        backPageLaunchRequest = afterBack ? request : -1L;
        main.postDelayed(() -> verifyLaunch(request, pending), InterceptionLaunchDiagnostics.TIMEOUT_MS);
        try {
            startActivity(intent);
            // 请求被系统接受不代表已经覆盖应用；真实 止间窗口事件到达后才取消复查。
            scheduleEvaluation(LAUNCH_RECHECK_MS);
        } catch (RuntimeException rejected) {
            DiagnosticLog.error("launch", pending.sessionId, "start_failed target="
                    + decision.packageName, rejected);
            scheduleEvaluation(LAUNCH_RECHECK_MS);
        }
    }

    /**
     * 只在目标仍相关时报告缺少窗口回执，保留系统权限快照帮助后续排查。
     * @param request 等待中的请求代次
     * @param pending 发起请求时的不可变会话
     */
    private void verifyLaunch(long request, PendingInterception pending) {
        boolean waitingAfterBack = isWaitingAfterBack(request);
        if (stopped || !pending.decision.packageName.equals(foregroundPackage) && !waitingAfterBack) {
            InterceptionLaunchDiagnostics.cancel(request);
            return;
        }
        if (!InterceptionLaunchDiagnostics.expired(request, SystemClock.elapsedRealtime())) return;
        DiagnosticLog.warn("launch", pending.sessionId, "window_unconfirmed target="
                + pending.decision.packageName + " timeout_ms=" + InterceptionLaunchDiagnostics.TIMEOUT_MS
                + " " + PermissionStatusReader.read(this).describe());
        if (packageFilter.accepts(foregroundPackage)) scheduleEvaluation(10_000L);
    }

    /**
     * 移除待执行检查并停止后台规则线程。
     */
    private void stopEvaluation() {
        stopped = true;
        backPageGeneration++;
        InterceptionLaunchDiagnostics.cancel(launchRequest);
        DiagnosticLog.info("accessibility", "", "service_stopped");
        main.removeCallbacksAndMessages(null);
        scheduledAtUptimeMs = 0L;
        evaluator.shutdownNow();
    }

    /**
     * 拦截任务关闭后补查真实前台，避免丢失恢复窗口事件或覆盖其他应用的前台身份。
     *
     * @param packageName 本次导航希望恢复的目标，仍须由系统证据确认
     */
    void resumeTarget(String packageName) {
        if (stopped || packageFilter == null || !packageFilter.accepts(packageName)) return;
        lastLaunchKey = "";
        if (Objects.equals(packageName, foregroundPackage)) {
            scheduleEvaluation(500L);
        } else if (Objects.equals(getPackageName(), foregroundPackage)) {
            // 关闭任务只是导航请求；用真实事件补查，不能覆盖已经切到桌面或其他应用的前台。
            main.postDelayed(() -> {
                if (!stopped && Objects.equals(getPackageName(), foregroundPackage)) initializeForeground();
            }, 500L);
        }
    }

}
