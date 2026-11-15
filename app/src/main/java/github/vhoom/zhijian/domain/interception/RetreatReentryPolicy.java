package github.vhoom.zhijian.domain.interception;

import github.vhoom.zhijian.data.model.policy.ControlLevel;
import github.vhoom.zhijian.data.model.runtime.PendingInterception;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * 闭关再次进入的动作策略；同组共享一轮限制，同次前台访问只抽样一次。
 * 由无障碍服务在主线程持有，不改写真实用量、休息事实或放行授权。
 *
 * 维护日期：2026年11月11日
 */
public final class RetreatReentryPolicy {
    /** 首次显示页面；后续只返回，或先返回再显示页面。 */
    public enum Action { SHOW_PAGE, BACK_ONLY, BACK_AND_SHOW_PAGE }

    private final BooleanSupplier backOnly;
    private final Map<String, Round> rounds = new HashMap<>();

    /** @param backOnly 后续访问的随机策略，真值选择只返回，假值选择返回后显示页面 */
    public RetreatReentryPolicy(BooleanSupplier backOnly) {
        this.backOnly = Objects.requireNonNull(backOnly);
    }

    /**
     * 首次触发正常显示，后续真实访问各自随机；定时复查沿用本次选择。
     * @param pending 已经重新检查并持久化的当前限制
     * @param visitId 服务确认的前台访问代次，同包页面切换不改变此值
     * @return 本次访问固定采用的动作
     */
    public Action actionFor(PendingInterception pending, long visitId) {
        InterceptionDecision decision = pending.decision;
        String scope = scopeKey(decision.policyId, decision.packageName);
        if (decision.level != ControlLevel.SEAL) {
            rounds.remove(scope);
            return Action.SHOW_PAGE;
        }
        Round round = rounds.get(scope);
        if (round == null || !round.matches(pending)) {
            rounds.put(scope, new Round(pending, visitId));
            return Action.SHOW_PAGE;
        }
        if (round.visitId != visitId) {
            round.visitId = visitId;
            round.action = backOnly.getAsBoolean() ? Action.BACK_ONLY : Action.BACK_AND_SHOW_PAGE;
        }
        return round.action;
    }

    /** @param policyId 已解除或不再闭关的实际策略 @param packageName 本次复查目标 */
    public void clear(String policyId, String packageName) {
        rounds.remove(scopeKey(policyId, packageName));
    }

    /** @param policyId 生效策略 @param packageName 目标应用 @return 组级共享、其他配置按目标隔离的键 */
    private static String scopeKey(String policyId, String packageName) {
        return policyId.startsWith("group:") ? policyId : policyId + ":" + packageName;
    }

    /** 一轮限制的稳定身份及最近真实访问的选择，不依赖可变化的页面会话或休息起点。 */
    private static final class Round {
        private final long revision, identity;
        private final InterceptionDecision.Kind kind;
        private final InterceptionDecision.Trigger trigger;
        private final String windowId;
        private long visitId;
        private Action action = Action.SHOW_PAGE;

        /** @param pending 首次确认的限制 @param visitId 本次真实前台访问 */
        private Round(PendingInterception pending, long visitId) {
            InterceptionDecision decision = pending.decision;
            revision = decision.policyRevision;
            kind = decision.kind;
            trigger = decision.trigger;
            windowId = decision.windowId;
            identity = identityOf(pending);
            this.visitId = visitId;
        }

        /** @param pending 当前复查结果 @return 是否仍是同一轮限制，同组更换目标不改变身份 */
        private boolean matches(PendingInterception pending) {
            InterceptionDecision decision = pending.decision;
            return revision == decision.policyRevision && kind == decision.kind && trigger == decision.trigger
                    && windowId.equals(decision.windowId) && identity == identityOf(pending);
        }

        /** @param pending 已验证限制 @return 提醒使用原触发时刻，禁用使用原恢复边界 */
        private static long identityOf(PendingInterception pending) {
            return pending.decision.kind == InterceptionDecision.Kind.INTERRUPT
                    ? pending.occurredAtMs : pending.decision.resetAtMs;
        }
    }
}
