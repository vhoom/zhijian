package github.vhoom.zhijian.data.model.runtime;

import github.vhoom.zhijian.data.model.policy.ControlLevel;
import github.vhoom.zhijian.domain.interception.InterceptionDecision;

import java.util.Objects;

/**
 * 已触发的拦截与休息事实；无为或修心处理提醒不等于完成真实休息。
 *
 * 维护日期：2026年11月13日
 */
public final class PendingInterception {
    public final InterceptionDecision decision;
    public final String sessionId;
    public final String bootId;
    public final long startedAtMs;
    public final long startedElapsedMs;
    public final long cycleGeneration;
    public final long occurredAtMs;
    /** 独立提醒周期身份及原始起点，修改时段端点不改变已经触发的休息。 */
    public final String continuousKey;
    public final long continuousFloorMs;
    /** 无为或修心按冻结选项处理该提醒时的真实连续用量；-1 表示未处理，不作为休息完成或禁用放行依据。 */
    public final long acknowledgedUsageMs;

    /**
     * 保存拦截决定、会话身份与双时钟起点。
     *
     * @param decision 原规则决定，恢复时无需重新累计连续使用量
     * @param sessionId 本次拦截的稳定身份
     * @param bootId 起点所属的系统启动身份
     * @param startedAtMs 开始拦截的墙钟时间
     * @param startedElapsedMs 开始拦截的单调时间
     */
    public PendingInterception(InterceptionDecision decision, String sessionId, String bootId,
            long startedAtMs, long startedElapsedMs) {
        this(decision, sessionId, bootId, startedAtMs, startedElapsedMs, -1L);
    }

    /**
     * 保存同组周期身份；未关联连续周期时使用 -1，恢复时以真实使用证据为准。
     * @param decision 原规则决定 @param sessionId 会话身份 @param bootId 启动身份
     * @param startedAtMs 休息起点墙钟 @param startedElapsedMs 休息起点单调时间
     * @param cycleGeneration 触发时已完成的休息周期代次
     */
    public PendingInterception(InterceptionDecision decision, String sessionId, String bootId,
            long startedAtMs, long startedElapsedMs, long cycleGeneration) {
        this(decision, sessionId, bootId, startedAtMs, startedElapsedMs, cycleGeneration, startedAtMs);
    }

    /**
     * 休息重启与原触发事实分别保存，重进不能更改原审计起点。
     * @param decision 原决定 @param sessionId 原会话 @param bootId 休息启动身份
     * @param startedAtMs 当前休息起点墙钟 @param startedElapsedMs 当前休息起点单调时间
     * @param cycleGeneration 当前周期代次 @param occurredAtMs 原触发时间
     */
    public PendingInterception(InterceptionDecision decision, String sessionId, String bootId,
            long startedAtMs, long startedElapsedMs, long cycleGeneration, long occurredAtMs) {
        this(decision, sessionId, bootId, startedAtMs, startedElapsedMs, cycleGeneration, occurredAtMs, "", 0L);
    }

    /** @param decision 原决定 @param sessionId 会话 @param bootId 启动身份 @param startedAtMs 休息墙钟
     * @param startedElapsedMs 休息单调时间 @param cycleGeneration 周期代次 @param occurredAtMs 原触发
     * @param continuousKey 独立周期身份 @param continuousFloorMs 原始窗口起点 */
    public PendingInterception(InterceptionDecision decision, String sessionId, String bootId,
            long startedAtMs, long startedElapsedMs, long cycleGeneration, long occurredAtMs,
            String continuousKey, long continuousFloorMs) {
        this(decision, sessionId, bootId, startedAtMs, startedElapsedMs, cycleGeneration, occurredAtMs,
                continuousKey, continuousFloorMs, -1L);
    }

    /** @param decision 原决定 @param sessionId 会话 @param bootId 启动身份 @param startedAtMs 休息墙钟
     * @param startedElapsedMs 休息单调时间 @param cycleGeneration 周期代次 @param occurredAtMs 原触发
     * @param continuousKey 独立周期身份 @param continuousFloorMs 原始窗口起点
     * @param acknowledgedUsageMs 按冻结选项处理提醒时的真实用量，未处理为 -1 */
    public PendingInterception(InterceptionDecision decision, String sessionId, String bootId,
            long startedAtMs, long startedElapsedMs, long cycleGeneration, long occurredAtMs,
            String continuousKey, long continuousFloorMs, long acknowledgedUsageMs) {
        this.decision = Objects.requireNonNull(decision);
        this.sessionId = Objects.requireNonNull(sessionId);
        this.bootId = Objects.requireNonNull(bootId);
        this.startedAtMs = startedAtMs;
        this.startedElapsedMs = startedElapsedMs;
        this.cycleGeneration = cycleGeneration;
        this.occurredAtMs = occurredAtMs;
        this.continuousKey = Objects.requireNonNull(continuousKey);
        this.continuousFloorMs = continuousFloorMs;
        this.acknowledgedUsageMs = acknowledgedUsageMs;
        if (sessionId.isEmpty() || bootId.isEmpty() || startedAtMs < 0 || startedElapsedMs < 0 || occurredAtMs < 0
                || continuousFloorMs < 0 || acknowledgedUsageMs < -1L) {
            throw new IllegalArgumentException("Invalid pending interception");
        }
    }

    /**
     * 检查事实是否仍属于当前策略；普通规则恢复时释放，中断保留起点供恢复使用时结算。
     *
     * @param policyId 当前有效策略身份
     * @param revision 当前策略修订号
     * @param level 当前管控档位
     * @param nowWallMs 当前墙钟时间
     */
    public boolean isCurrent(String policyId, long revision, ControlLevel level, long nowWallMs) {
        return decision.policyId.equals(policyId) && decision.policyRevision == revision
                && decision.level == level && (decision.kind == InterceptionDecision.Kind.INTERRUPT
                || decision.resetAtMs == 0L || nowWallMs < decision.resetAtMs);
    }

    /**
     * 同一次启动使用单调时钟；跨启动只承认新启动的单调时间，不信任可修改的关机墙钟。
     *
     * @param currentBootId 当前系统启动身份
     * @param nowWallMs 当前墙钟时间
     * @param nowElapsedMs 当前单调时间
     */
    public long remainingPauseMs(String currentBootId, long nowWallMs, long nowElapsedMs) {
        long elapsed = bootId.equals(currentBootId)
                ? nowElapsedMs - startedElapsedMs : nowElapsedMs;
        return Math.max(0L, decision.minimumInterruptionPauseDurationMs - Math.max(0L, elapsed));
    }

    /** @param target 同组当前前台应用 @return 保留会话身份和时间事实的目标副本 */
    public PendingInterception forTarget(String target) {
        InterceptionDecision old = decision;
        InterceptionDecision selected = new InterceptionDecision(target, old.policyId, old.policyRevision,
                old.kind, old.trigger, old.level, old.windowId, old.currentValue, old.limitValue,
                old.resetAtMs, old.passDurationMs, old.minimumInterruptionPauseDurationMs);
        return new PendingInterception(selected, sessionId, bootId, startedAtMs, startedElapsedMs,
                cycleGeneration, occurredAtMs, continuousKey, continuousFloorMs, acknowledgedUsageMs);
    }
}
