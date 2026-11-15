package github.vhoom.zhijian.domain.interception;

import github.vhoom.zhijian.data.model.policy.ControlLevel;
import github.vhoom.zhijian.data.model.runtime.PendingInterception;
import github.vhoom.zhijian.domain.usage.ContinuousUsageCycle;
import java.util.List;

/**
 * 未启用临时放行的提醒按新增实际用量再次触发；不修改真实周期、休息或禁用额度。
 *
 * 维护日期：2026年11月13日
 */
public final class ReminderRepeatPolicy {
    /** 纯判定策略不持有系统状态。 */
    private ReminderRepeatPolicy() { }

    /** @param kind 拦截类型 @param level 当前强度 @param temporaryPassEnabled 是否启用限时放行
     * @return 是否只执行提醒导航并保存再次提醒起点 */
    public static boolean isDirectReminder(InterceptionDecision.Kind kind, ControlLevel level, boolean temporaryPassEnabled) {
        return kind == InterceptionDecision.Kind.INTERRUPT && level != ControlLevel.SEAL && !temporaryPassEnabled;
    }

    /**
     * 只为提醒阈值提供处理后的新增用量，禁用规则继续读取原始累计额度与次数。
     * @param evidence 真实用量证据，不修改原对象
     * @param rests 当前策略仍未完成的独立休息事实
     * @param level 当前强度，闭关不使用已处理提醒标记
     * @return 供规则判断使用的只读证据
     */
    public static UsageEvidence forEvaluation(UsageEvidence evidence, List<PendingInterception> rests,
            ControlLevel level) {
        UsageEvidence result = evidence;
        for (PendingInterception rest : rests) {
            if (!acknowledged(rest, level)) continue;
            if (rest.decision.trigger == InterceptionDecision.Trigger.CONTINUOUS) {
                result = result.withContinuousMillis(sinceAcknowledged(evidence.continuousMs, rest.acknowledgedUsageMs));
            } else if (rest.decision.trigger == InterceptionDecision.Trigger.WINDOW_CONTINUOUS) {
                String window = rest.decision.windowId;
                result = result.withWindowContinuousMillis(window,
                        sinceAcknowledged(evidence.windowContinuousMs(window), rest.acknowledgedUsageMs));
            }
        }
        return result;
    }

    /**
     * 原休息尚未满足时，判断同一提醒是否已累计足够的新用量。
     * @param rest 尚未完成的原休息事实
     * @param cycle 已验证的连续周期，缺失时不虚构新增用量
     * @param level 当前强度
     * @return 距离再次提醒尚需实际使用的毫秒数，零表示应展示
     */
    public static long remainingUsage(PendingInterception rest, ContinuousUsageCycle cycle,
            ControlLevel level) {
        if (!acknowledged(rest, level)) return 0L;
        long used = cycle == null ? rest.acknowledgedUsageMs : cycle.usedMs;
        return Math.max(0L, Math.max(1L, rest.decision.limitValue)
                - sinceAcknowledged(used, rest.acknowledgedUsageMs));
    }

    /** @param rest 原提醒 @param level 当前强度
     * @return 已按冻结选项保存的确认是否仍有效，之后的新会话开关不撤销原确认 */
    private static boolean acknowledged(PendingInterception rest, ControlLevel level) {
        return rest.decision.kind == InterceptionDecision.Kind.INTERRUPT && level != ControlLevel.SEAL
                && rest.acknowledgedUsageMs >= 0L;
    }

    /** @param used 真实总用量 @param acknowledged 已处理时用量 @return 不因恢复或计数上限溢出的新增用量 */
    private static long sinceAcknowledged(long used, long acknowledged) {
        return Math.max(0L, used - acknowledged);
    }
}
