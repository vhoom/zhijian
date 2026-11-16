package github.vhoom.zhijian.ui.interception.session;

import android.content.Context;
import android.os.Bundle;
import github.vhoom.zhijian.data.model.interception.InterceptionChallenge;
import github.vhoom.zhijian.data.model.interception.InterceptionEffect;
import github.vhoom.zhijian.data.model.settings.InteractionSettings;
import github.vhoom.zhijian.domain.interception.InterceptionDecision;
import java.util.concurrent.CompletableFuture;
import java.util.Objects;

/**
 * 原生生命周期保留的结果提交，不持有页面；进程恢复以相同身份和确认时钟幂等重放。
 *
 * 维护日期：2026年11月16日
 */
public final class InterceptionSubmission {
    private static final String PREFIX = "interception.submission.";
    public final String id, sessionId;
    public final InterceptionEffect effect;
    private final long wallMs, elapsedMs, occurredAtMs;
    private final String bootId;
    private final InterceptionDecision decision;
    private final InterceptionChallenge challenge;
    private final InteractionSettings settings;
    private CompletableFuture<Void> task;

    /** @param id 稳定提交身份 @param decision 用户确认的决定 @param sessionId 页面会话
     * @param occurredAtMs 原触发时间 @param challenge 已完成挑战 @param settings 冻结设置
     * @param effect 用户确认的出口 @param wallMs 确认墙钟 @param elapsedMs 确认单调时钟 @param bootId 确认启动身份 */
    public InterceptionSubmission(String id, InterceptionDecision decision, String sessionId, long occurredAtMs,
            InterceptionChallenge challenge, InteractionSettings settings, InterceptionEffect effect,
            long wallMs, long elapsedMs, String bootId) {
        this.id = Objects.requireNonNull(id);
        this.decision = Objects.requireNonNull(decision);
        this.sessionId = Objects.requireNonNull(sessionId);
        this.occurredAtMs = occurredAtMs;
        this.challenge = challenge;
        this.settings = Objects.requireNonNull(settings);
        this.effect = Objects.requireNonNull(effect);
        this.wallMs = wallMs;
        this.elapsedMs = elapsedMs;
        this.bootId = Objects.requireNonNull(bootId);
        if (id.isEmpty() || sessionId.isEmpty() || bootId.isEmpty() || wallMs < 0L || elapsedMs < 0L
                || effect != InterceptionEffect.RETURN_HOME && effect != InterceptionEffect.RETURN_TO_TARGET
                && effect != InterceptionEffect.ISSUE_GRANT) throw new IllegalArgumentException("Invalid submission");
    }

    /** @param context 应用存储上下文 @return 所有页面实例观察同一个后台结果，不重复结束挑战 */
    public synchronized CompletableFuture<Void> save(Context context) {
        if (task != null) return task;
        Context app = context.getApplicationContext();
        try {
            task = new InterceptionOutcomeWriter(app).save(decision, sessionId, occurredAtMs, challenge,
                    settings, effect, id, wallMs, elapsedMs, bootId);
            if (challenge == InterceptionChallenge.SUDOKU) {
                task = task.thenCompose(ignored -> new github.vhoom.zhijian.data.store.interception.SudokuProgressStore(app)
                        .finishSession(sessionId));
            } else if (challenge == InterceptionChallenge.WORD_TRANSLATION) {
                task = task.thenCompose(ignored -> new github.vhoom.zhijian.data.store.interception.WordProgressStore(app)
                        .finishSession(sessionId));
            }
        } catch (RuntimeException failure) {
            task = new CompletableFuture<>();
            task.completeExceptionally(failure);
        }
        return task;
    }

    /** @param state 系统恢复状态，保留提交身份、出口和原始确认时钟 */
    public void saveState(Bundle state) {
        state.putString(PREFIX + "id", id);
        state.putString(PREFIX + "target", decision.packageName);
        state.putString(PREFIX + "effect", effect.name());
        state.putLong(PREFIX + "wall", wallMs);
        state.putLong(PREFIX + "elapsed", elapsedMs);
        state.putString(PREFIX + "boot", bootId);
    }

    /** @param state 系统快照 @param retained 原生配置重建保留的任务 @param decision 恢复决定
     * @param sessionId 当前会话 @param occurredAtMs 原触发 @param challenge 冻结挑战 @param settings 冻结设置
     * @return 原任务或进程恢复后的同身份提交，没有待提交出口时为空 */
    public static InterceptionSubmission restore(Bundle state, InterceptionSubmission retained,
            InterceptionDecision decision, String sessionId, long occurredAtMs,
            InterceptionChallenge challenge, InteractionSettings settings) {
        if (state == null || !state.containsKey(PREFIX + "id")) return null;
        if (!decision.packageName.equals(state.getString(PREFIX + "target"))) return null;
        String id = state.getString(PREFIX + "id");
        if (retained != null && retained.id.equals(id) && retained.sessionId.equals(sessionId)) return retained;
        return new InterceptionSubmission(id, decision, sessionId, occurredAtMs, challenge, settings,
                InterceptionEffect.valueOf(state.getString(PREFIX + "effect")), state.getLong(PREFIX + "wall"),
                state.getLong(PREFIX + "elapsed"), state.getString(PREFIX + "boot"));
    }
}
