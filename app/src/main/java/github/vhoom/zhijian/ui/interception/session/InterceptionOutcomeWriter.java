package github.vhoom.zhijian.ui.interception.session;

import android.content.Context;
import android.os.SystemClock;

import github.vhoom.zhijian.data.model.interception.InterceptionChallenge;
import github.vhoom.zhijian.data.model.interception.InterceptionEffect;
import github.vhoom.zhijian.data.model.policy.ControlLevel;
import github.vhoom.zhijian.data.model.runtime.InterceptionRecord;
import github.vhoom.zhijian.data.model.runtime.PendingInterception;
import github.vhoom.zhijian.data.model.runtime.PassGrant;
import github.vhoom.zhijian.domain.usage.UsageTrackingStart;
import github.vhoom.zhijian.data.model.settings.InteractionSettings;
import github.vhoom.zhijian.data.store.RuntimeStateStore;
import github.vhoom.zhijian.data.store.ZhijianStorage;
import github.vhoom.zhijian.data.store.interception.PendingInterceptionStore;
import github.vhoom.zhijian.data.store.interception.BlockContinuationStore;
import github.vhoom.zhijian.data.store.usage.ContinuousUsageStore;
import github.vhoom.zhijian.data.source.ContinuousUsageReader;
import github.vhoom.zhijian.domain.interception.InterceptionDecision;
import github.vhoom.zhijian.domain.interception.ReminderRepeatPolicy;
import github.vhoom.zhijian.domain.usage.ContinuousUsageCycle;
import github.vhoom.zhijian.util.time.BootIdentity;
import github.vhoom.zhijian.util.diagnostics.DiagnosticLog;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 拦截结果提交实现：挑战只描述审计，允许行为由状态机决定。
 *
 * 维护日期：2026年11月16日
 */
public final class InterceptionOutcomeWriter {
    private final Context context;
    private final RuntimeStateStore runtime;

    /**
     * 创建不持有页面引用的结果写入器。
     * @param context 存储与启动身份读取上下文
     */
    public InterceptionOutcomeWriter(Context context) {
        this.context = context.getApplicationContext();
        runtime = new RuntimeStateStore(this.context);
    }

    /**
     * 按冻结选项保存结果；无临时放行的提醒仅在明确继续时保存再次提醒起点，退出保留原休息要求。
     * @param decision 原拦截决定
     * @param sessionId 稳定会话标识
     * @param occurredAtMs 原触发墙钟时间
     * @param challenge 本次独立挑战，未启用为 null
     * @param settings 原会话交互配置
     * @param effect 状态机要求的回桌面、直接返回或限时放行效果
     * @return 完整提交和清理的异步结果
     */
    public CompletableFuture<Void> save(InterceptionDecision decision, String sessionId,
            long occurredAtMs, InterceptionChallenge challenge, InteractionSettings settings, InterceptionEffect effect) {
        long nowWall = System.currentTimeMillis();
        long nowElapsed = SystemClock.elapsedRealtime();
        boolean directReminder = ReminderRepeatPolicy.isDirectReminder(
                decision.kind, decision.level, settings.options.temporaryPassEnabled);
        boolean issueGrant = effect == InterceptionEffect.ISSUE_GRANT;
        boolean permitsGrant = decision.level != ControlLevel.SEAL && settings.options.temporaryPassEnabled;
        if (effect != InterceptionEffect.RETURN_HOME && effect != InterceptionEffect.RETURN_TO_TARGET && !issueGrant) {
            throw new IllegalArgumentException("Unsupported completion effect");
        }
        if (effect == InterceptionEffect.RETURN_TO_TARGET && (permitsGrant || decision.level == ControlLevel.SEAL)
                || issueGrant && !permitsGrant) {
            throw new IllegalArgumentException("Completion does not match the session options");
        }
        PassGrant grant = issueGrant ? new PassGrant(decision.packageName, decision.policyId,
                decision.policyRevision, BootIdentity.read(context), nowWall, decision.passDurationMs,
                Math.addExact(nowElapsed, decision.passDurationMs)) : null;
        String challengeCode = "none";
        if (settings.options.obstaclePagesEnabled && challenge == InterceptionChallenge.WORD_TRANSLATION) {
            challengeCode = "word_" + settings.word.questionCount + "x" + settings.word.minDwellMillis;
        } else if (settings.options.obstaclePagesEnabled && challenge == InterceptionChallenge.BUTTON_HOLD) {
            challengeCode = "button_hold_" + Math.max(1L,
                    (settings.holdRange.valueFor(sessionId + ":button-friction") + 999L) / 1000L);
        } else if (settings.options.obstaclePagesEnabled && challenge != null) {
            challengeCode = challenge.name().toLowerCase(Locale.ROOT);
        }
        String outcome = issueGrant ? "allowed" : effect == InterceptionEffect.RETURN_TO_TARGET ? "continued" : "abandoned";
        InterceptionRecord record = new InterceptionRecord(issueGrant ? "pass:" + sessionId : UUID.randomUUID().toString(),
                decision.packageName, decision.policyId, decision.policyRevision,
                decision.kind.name().toLowerCase(Locale.ROOT), decision.level.code, decision.trigger.code,
                challengeCode, outcome, occurredAtMs, Math.max(occurredAtMs, nowWall));
        CompletableFuture<Void> saved = runtime.recordInterception(record, grant);
        if (directReminder && effect == InterceptionEffect.RETURN_TO_TARGET) {
            return saved.thenRun(() -> acknowledgeReminder(decision.packageName, sessionId));
        }
        if (effect == InterceptionEffect.RETURN_TO_TARGET && decision.level != ControlLevel.SEAL
                && decision.kind == InterceptionDecision.Kind.BLOCK) {
            return saved.thenRun(() -> acknowledgeBlock(decision, sessionId, nowWall));
        }
        return issueGrant || effect == InterceptionEffect.RETURN_TO_TARGET
                ? saved.thenRun(() -> new PendingInterceptionStore(context)
                .clearPresentation(sessionId)) : saved;
    }

    /** @param packageName 用户处理的目标 @param sessionId 原页面身份；周期锁避免与新展示或真实休息结算交错 */
    private void acknowledgeReminder(String packageName, String sessionId) {
        ContinuousUsageReader.withCycleLock(() -> {
            PendingInterceptionStore pending = new PendingInterceptionStore(context);
            PendingInterception shown = pending.read(packageName);
            if (shown == null || !sessionId.equals(shown.sessionId)) return null;
            UsageTrackingStart tracking = ZhijianStorage.get(context)
                    .snapshot().usageStart(shown.decision.policyId);
            ContinuousUsageCycle cycle = new ContinuousUsageStore(context).read(shown.continuousKey,
                    tracking == null ? "" : tracking.id);
            long used = cycle == null ? shown.decision.currentValue : Math.max(shown.decision.currentValue, cycle.usedMs);
            pending.acknowledgeReminder(shown, used);
            return null;
        });
    }

    /**
     * 保存确认时刻和实际确认的规则；周期锁避免与新展示交错，身份变化不清理新页面。
     * @param decision 用户确认的冻结规则
     * @param sessionId 已提交结果的页面身份
     * @param confirmedAtMs 本次提交时间，异步保存完成不刷新访问边界
     */
    private void acknowledgeBlock(InterceptionDecision decision, String sessionId, long confirmedAtMs) {
        ContinuousUsageReader.withCycleLock(() -> {
            PendingInterceptionStore pending = new PendingInterceptionStore(context);
            PendingInterception shown = pending.read(decision.packageName);
            if (shown == null || !sessionId.equals(shown.sessionId)) {
                DiagnosticLog.warn("outcome", sessionId, "block_continuation_skipped reason=stale_session");
                return null;
            }
            if (new BlockContinuationStore(context).ignore(shown, decision, confirmedAtMs)) {
                pending.clearPresentation(sessionId);
                DiagnosticLog.info("outcome", sessionId, "block_continuation_applied level=" + decision.level.code
                        + " trigger=" + decision.trigger.code + " confirmed_at_ms=" + confirmedAtMs);
            } else {
                DiagnosticLog.warn("outcome", sessionId, "block_continuation_skipped reason=changed_rule_or_visit");
            }
            return null;
        });
    }
}
