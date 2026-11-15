package github.vhoom.zhijian.fixture;

import github.vhoom.zhijian.ui.interception.page.support.InitialActionPlan;
import java.util.function.Predicate;

/**
 * 按真实方案选择可重复的设备测试会话，不往生产入口增加强制概率开关。
 *
 * 维护日期：2026年11月08日
 */
public final class InitialPageSessions {
    /** 测试工具不需要实例。 */
    private InitialPageSessions() { }

    /** @param prefix 本次用例的唯一前缀 @return 一退一进的普通无为会话 */
    public static String ordinary(String prefix) {
        return matching(prefix, plan -> !plan.blindBox && !plan.doubleExit);
    }

    /** @param prefix 本次用例前缀 @param condition 需要验证的方案条件 @return 满足条件的稳定会话 */
    public static String matching(String prefix, Predicate<InitialActionPlan> condition) {
        for (int index = 0; index < 10_000; index++) {
            String candidate = prefix + ':' + index;
            if (condition.test(InitialActionPlan.forSession(candidate, true))) return candidate;
        }
        throw new AssertionError("No initial page scenario for " + prefix);
    }
}
