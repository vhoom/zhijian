package github.vhoom.zhijian.domain.interception;

import github.vhoom.zhijian.data.model.policy.RuleDayParameterAdapter;
import github.vhoom.zhijian.data.model.policy.RuleDayParameters;
import github.vhoom.zhijian.data.model.policy.RuleGroup;
import github.vhoom.zhijian.data.model.policy.RuleWindow;
import java.util.HashSet;

/**
 * 按实际七日规则比较配置行为，名称与等价的星期展开不使已完成挑战失效。
 *
 * 维护日期：2026年11月16日
 */
public final class RuleConfigurationComparison {
    /** 比较策略不持有配置或页面。 */
    private RuleConfigurationComparison() { }

    /** @param previous 已提交规则 @param proposed 待提交草稿 @return 是否保持规则、成员和启用行为 */
    public static boolean sameBehavior(RuleGroup previous, RuleGroup proposed) {
        if (previous == null || previous.enabled != proposed.enabled
                || !new HashSet<>(previous.apps).equals(new HashSet<>(proposed.apps))) return false;
        for (int day = 1; day <= 7; day++) {
            if (!sameDay(RuleDayParameterAdapter.forDay(previous, day),
                    RuleDayParameterAdapter.forDay(proposed, day))) return false;
        }
        return true;
    }

    /** @param previous 原单日参数 @param proposed 新单日参数 @return 是否相同，包含暂未启用的已保存参数 */
    private static boolean sameDay(RuleDayParameters previous, RuleDayParameters proposed) {
        if (!previous.types.equals(proposed.types) || previous.dailyLimitMillis != proposed.dailyLimitMillis
                || previous.count != proposed.count || previous.frequencyWindowMillis != proposed.frequencyWindowMillis
                || previous.frequencyCount != proposed.frequencyCount || previous.continuousLimitMillis != proposed.continuousLimitMillis
                || previous.interruptionPauseMillis != proposed.interruptionPauseMillis
                || previous.passDurationMillis != proposed.passDurationMillis
                || previous.windows.size() != proposed.windows.size()) return false;
        for (int i = 0; i < previous.windows.size(); i++) {
            RuleWindow old = previous.windows.get(i), next = proposed.windows.get(i);
            if (!old.id.equals(next.id) || old.type != next.type || old.startMinute != next.startMinute
                    || old.endMinute != next.endMinute || old.limitDurationMillis != next.limitDurationMillis
                    || old.limitCount != next.limitCount || old.interruptionPauseMillis != next.interruptionPauseMillis) return false;
        }
        return true;
    }
}
