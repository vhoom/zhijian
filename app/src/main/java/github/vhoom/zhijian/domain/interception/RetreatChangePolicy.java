package github.vhoom.zhijian.domain.interception;

import github.vhoom.zhijian.data.model.policy.ControlLevel;
import github.vhoom.zhijian.data.model.policy.RetreatRestrictionException;
import github.vhoom.zhijian.data.model.policy.RuleGroup;
import github.vhoom.zhijian.data.model.runtime.PendingInterception;
import github.vhoom.zhijian.data.store.PolicySnapshot;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 对已复查的约束执行统一变更保护；不查询系统或依赖页面。
 *
 * 维护日期：2026年11月08日
 */
public final class RetreatChangePolicy {
    /** 纯策略不创建实例。 */
    private RetreatChangePolicy() { }

    /**
     * 闭关触发前允许修改，触发期间保护档位、原规则及实际成员归属。
     * @param previous 原已提交配置 @param proposed 事务中的候选配置
     * @param restrictions 尚未解除的触发事实
     * @throws RetreatRestrictionException 候选变更会绕过正在执行的约束
     */
    public static void requireAllowed(PolicySnapshot previous, PolicySnapshot proposed,
            List<PendingInterception> restrictions) {
        if (previous.level() != ControlLevel.SEAL || restrictions.isEmpty()) return;
        if (previous.level() != proposed.level()) throw new RetreatRestrictionException(true);
        Set<String> packages = new HashSet<>();
        for (RuleGroup policy : previous.configuredPolicies().values()) packages.addAll(policy.apps);
        for (RuleGroup policy : proposed.configuredPolicies().values()) packages.addAll(policy.apps);
        for (PendingInterception restriction : restrictions) {
            String policyId = restriction.decision.policyId;
            packages.add(restriction.decision.packageName);
            if (previous.revisionOf(policyId) != proposed.revisionOf(policyId)) {
                throw new RetreatRestrictionException(false);
            }
            for (String pkg : packages) {
                if (policyId.equals(previous.effectivePolicyId(pkg))
                        && !policyId.equals(proposed.effectivePolicyId(pkg))) {
                    throw new RetreatRestrictionException(false);
                }
            }
        }
    }
}
