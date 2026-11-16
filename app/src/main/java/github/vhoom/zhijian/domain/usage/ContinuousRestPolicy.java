package github.vhoom.zhijian.domain.usage;

import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * 三档和全部作用域共用离组休息策略，足额休息或非使用超过五分钟结束周期。
 *
 * 维护日期：2026年11月16日
 */
public final class ContinuousRestPolicy {
    /** 全局连续非使用边界；恰好五分钟仍须检查本轮休息要求。 */
    public static final long NON_USE_RESET_MS = 300_000L;

    /** 策略工厂不持有界面或系统对象。 */
    private ContinuousRestPolicy() { }

    /**
     * 无为、修心、闭关统一判断离组休息，组内换应用仍继续累计使用。
     * @param belongs 当前策略的使用归属
     * @return 接收前台包名及 Activity 类名的休息策略
     */
    public static BiPredicate<String, String> forScope(Predicate<String> belongs) {
        return (pkg, activity) -> pkg == null || !belongs.test(pkg);
    }

    /**
     * 满足本轮休息或连续非使用超过五分钟即可清零，各作用域使用相同边界。
     * @param policyId 稳定策略主键，不影响全局非使用边界
     * @param configuredRestMs 本次周期或原提醒要求的连续休息毫秒数
     * @return 清零和完成提醒共用的毫秒阈值，未配置休息时只采用非使用边界
     */
    public static long requiredRestMillis(String policyId, long configuredRestMs) {
        return configuredRestMs > 0L ? Math.min(configuredRestMs, NON_USE_RESET_MS + 1L)
                : NON_USE_RESET_MS + 1L;
    }
}
