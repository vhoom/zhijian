package github.vhoom.zhijian.data.model.settings;

import github.vhoom.zhijian.data.model.interception.InterceptionChallenge;
import github.vhoom.zhijian.data.model.interception.InterceptionMode;

import java.util.EnumSet;
import java.util.Set;

/**
 * 挑战候选策略：修心至少保留一种挑战，是否执行挑战由独立的阻碍页面开关决定。
 *
 * 维护日期：2026年11月14日
 */
public final class ChallengeSelectionPolicy {
    /** 策略工具不创建实例。 */
    private ChallengeSelectionPolicy() { }

    /**
     * 规范保存或读取的启用集合，不修改调用方的集合。
     * @param mode 开关所属模式
     * @param source 当前启用集合
     * @return 满足本模式约束的副本
     */
    public static EnumSet<InterceptionChallenge> normalize(InterceptionMode mode,
            Set<InterceptionChallenge> source) {
        EnumSet<InterceptionChallenge> result = source.isEmpty()
                ? EnumSet.noneOf(InterceptionChallenge.class) : EnumSet.copyOf(source);
        if (mode == InterceptionMode.REPAIR) {
            if (result.isEmpty()) result.add(InterceptionChallenge.BUTTON_HOLD);
        } else {
            result.clear();
        }
        return result;
    }

    /**
     * 执行一次挑战开关动作，拒绝关闭最后一种挑战，不替用户切换到另一种。
     * @param mode 开关所属模式
     * @param current 已规范的当前集合
     * @param challenge 用户操作的选项
     * @param enabled 本次请求的开关状态
     * @return 可以原子保存的新集合
     */
    public static EnumSet<InterceptionChallenge> change(InterceptionMode mode,
            Set<InterceptionChallenge> current, InterceptionChallenge challenge, boolean enabled) {
        EnumSet<InterceptionChallenge> result = normalize(mode, current);
        if (enabled) {
            result.add(challenge);
        } else {
            if (result.size() == 1 && result.contains(challenge)) return result;
            result.remove(challenge);
        }
        return normalize(mode, result);
    }
}
