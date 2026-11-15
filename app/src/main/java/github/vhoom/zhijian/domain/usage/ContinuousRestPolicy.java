package github.vhoom.zhijian.domain.usage;

import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * 三档共用离组休息策略；单应用短暂切换保留累计，较长休息配置继续生效。
 *
 * 维护日期：2026年11月11日
 */
public final class ContinuousRestPolicy {
    /** 单应用离开不超过两分钟再返回，仍属于原连续使用周期。 */
    private static final long SINGLE_APP_SWITCH_GRACE_MS = 120_000L;

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
     * 单应用清零须同时超过两分钟切换容错并满足配置；组和全局仍按原休息配置。
     * @param policyId 稳定策略主键，独立应用使用 app: 前缀，不传入时段周期键
     * @param configuredRestMs 本次周期或原提醒要求的连续休息毫秒数
     * @return 清零和完成提醒共用的实际休息时长，精确两分钟仍保留单应用累计
     */
    public static long requiredRestMillis(String policyId, long configuredRestMs) {
        return policyId.startsWith("app:")
                ? Math.max(configuredRestMs, SINGLE_APP_SWITCH_GRACE_MS + 1L) : configuredRestMs;
    }
}
