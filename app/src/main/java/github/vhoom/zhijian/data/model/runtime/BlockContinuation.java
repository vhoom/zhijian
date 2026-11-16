package github.vhoom.zhijian.data.model.runtime;

import github.vhoom.zhijian.data.model.policy.ControlLevel;
import github.vhoom.zhijian.domain.interception.InterceptionDecision;

import java.util.Objects;

/**
 * 无为或修心完成继续后，本次应用访问中忽略全部禁用；连续提醒仍独立生效。
 *
 * 维护日期：2026年11月16日
 */
public final class BlockContinuation {
    public final String packageName, policyId, windowId, bootId;
    public final long policyRevision, startedAtMs;
    /** 用户确认时的管控档位；另一档不能继承本次确认。 */
    public final ControlLevel level;
    public final InterceptionDecision.Trigger trigger;

    /**
     * 保存本次应用访问与原禁用身份。
     * @param packageName 本次继续的应用，不与规则组其他成员共享
     * @param policyId 原生效策略
     * @param policyRevision 原策略版本
     * @param level 实际完成继续操作的管控档位，闭关不接受确认
     * @param trigger 被忽略的禁用类型
     * @param windowId 被忽略的具体时段，全天规则为空
     * @param bootId 原系统启动身份，重启后不延续
     * @param startedAtMs 本次继续确认的墙钟时间，复用旧页面不沿用原触发边界
     */
    public BlockContinuation(String packageName, String policyId, long policyRevision,
            ControlLevel level, InterceptionDecision.Trigger trigger, String windowId, String bootId, long startedAtMs) {
        this.packageName = Objects.requireNonNull(packageName);
        this.policyId = Objects.requireNonNull(policyId);
        this.trigger = Objects.requireNonNull(trigger);
        this.windowId = Objects.requireNonNull(windowId);
        this.bootId = Objects.requireNonNull(bootId);
        this.policyRevision = policyRevision;
        this.level = Objects.requireNonNull(level);
        this.startedAtMs = startedAtMs;
        if (packageName.isEmpty() || policyId.isEmpty() || bootId.isEmpty() || policyRevision < 1L
                || level == ControlLevel.SEAL || startedAtMs < 0L || trigger == InterceptionDecision.Trigger.CONTINUOUS
                || trigger == InterceptionDecision.Trigger.WINDOW_CONTINUOUS) {
            throw new IllegalArgumentException("Invalid block continuation");
        }
    }

    /** @param decision 本次候选规则 @return 是否属于同应用、同档位、同版本下本次访问的禁用 */
    public boolean matches(InterceptionDecision decision) {
        return decision.level == level && decision.kind == InterceptionDecision.Kind.BLOCK
                && packageName.equals(decision.packageName) && policyId.equals(decision.policyId)
                && policyRevision == decision.policyRevision;
    }

    /** @param other 待清理的原快照 @return 是否同一条规则的同次访问，旧复查不清新访问 */
    public boolean sameOccurrence(BlockContinuation other) {
        return packageName.equals(other.packageName) && policyId.equals(other.policyId)
                && policyRevision == other.policyRevision && level == other.level && trigger == other.trigger
                && windowId.equals(other.windowId) && bootId.equals(other.bootId) && startedAtMs == other.startedAtMs;
    }
}
