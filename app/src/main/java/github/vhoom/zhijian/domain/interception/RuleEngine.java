package github.vhoom.zhijian.domain.interception;

import github.vhoom.zhijian.data.model.policy.ControlLevel;
import github.vhoom.zhijian.data.model.policy.RuleGroup;
import github.vhoom.zhijian.data.model.policy.RuleType;
import github.vhoom.zhijian.data.model.policy.RuleWindow;
import github.vhoom.zhijian.domain.usage.ContinuousRestPolicy;
import github.vhoom.zhijian.domain.interception.count.CountRuleEvaluator;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 纯规则模块：同一证据始终得到同一决定，不读取 Android 状态也不产生副作用。
 *
 * 维护日期：2026年11月13日
 */
public final class RuleEngine {
    private static final long MINUTE_MS = 60_000L;

    /**
     * 工具类不允许实例化，调用方直接使用静态方法。
     */
    private RuleEngine() { }

    /**
     * 对已结束窗口补做一次连续提醒判断；证据必须已裁剪到该窗口结束。
     * @param packageName 目标应用 @param policyId 生效策略 @param revision 策略版本 @param level 当前强度
     * @param window 原窗口 @param interval 原窗口发生区间 @param usedMs 原窗口内尚未休息清零的连续用量
     * @param nowMs 当前检查时间 @param passDurationMs 缺省放行时长
     * @return 达到阈值时的提醒，未到结束或用量不足时为空
     */
    public static InterceptionDecision completedWindowReminder(String packageName, String policyId,
            long revision, ControlLevel level, RuleWindow window, RuleWindowSchedule.Interval interval,
            long usedMs, long nowMs, long passDurationMs) {
        if (interval == null || nowMs < interval.endMs || window.limitDurationMillis <= 0L
                || usedMs < window.limitDurationMillis) return null;
        return interrupt(packageName, policyId, revision, level,
                InterceptionDecision.Trigger.WINDOW_CONTINUOUS, window.id, usedMs, window.limitDurationMillis,
                window.scheduledPassDurationMillis > 0L ? window.scheduledPassDurationMillis : passDurationMs,
                window.interruptionPauseMillis).decision;
    }

    /**
     * 按条件优先级检查使用证据，并返回拦截或下次检查时间。
     *
     * @param packageName 需要查询或管控的应用包名
     * @param policyId 数据库中的稳定策略主键
     * @param revision 当前记录对应的策略修订号
     * @param policy 待判断或持久化的策略
     * @param level 全局管控档位
     * @param evidence 由系统用量事件汇总的规则判断证据
     * @param passDurationMs 完成拦截后可临时放行的毫秒数
     */
    public static InterceptionEvaluation evaluate(String packageName, String policyId,
            long revision, RuleGroup policy, ControlLevel level, UsageEvidence evidence,
            long passDurationMs) {
        return evaluate(packageName, policyId, revision, policy, level, evidence,
                passDurationMs, 0);
    }

    /**
     * 使用指定的全天规则恢复时间进行判定；时段规则仍只在各自时段结束时恢复。
     *
     * @param packageName 需要查询或管控的应用包名
     * @param policyId 数据库中的稳定策略主键
     * @param revision 当前记录对应的策略修订号
     * @param policy 待判断或持久化的策略
     * @param level 全局管控档位
     * @param evidence 由系统用量事件汇总的规则判断证据
     * @param passDurationMs 完成拦截后可临时放行的毫秒数
     * @param dailyResetMinute 每日额度恢复时刻相对零点的分钟数
     */
    public static InterceptionEvaluation evaluate(String packageName, String policyId,
            long revision, RuleGroup policy, ControlLevel level, UsageEvidence evidence,
            long passDurationMs, int dailyResetMinute) {
        return evaluate(packageName, policyId, revision, policy, level, evidence,
                passDurationMs, dailyResetMinute, block -> false);
    }

    /**
     * 按本次访问的忽略策略跳过指定无为禁用，其他禁用与连续提醒继续使用原证据判断。
     * @param packageName 当前应用 @param policyId 生效策略 @param revision 已提交修订
     * @param policy 本次规则 @param level 执行强度 @param evidence 真实用量证据
     * @param passDurationMs 原规则放行时长 @param dailyResetMinute 全天恢复分钟
     * @param ignoredBlock 对已确认单条禁用的只读策略；闭关不采用
     * @return 剩余规则的拦截或准确下次检查时间
     */
    public static InterceptionEvaluation evaluate(String packageName, String policyId,
            long revision, RuleGroup policy, ControlLevel level, UsageEvidence evidence,
            long passDurationMs, int dailyResetMinute, Predicate<InterceptionDecision> ignoredBlock) {
        boolean usesWeekdays = !policy.weekdayRules.isEmpty();
        policy = WeekdayRuleSchedule.resolve(policy, evidence.nowMs, evidence.zoneId);
        if (usesWeekdays && policy.passDurationMillis > 0) passDurationMs = policy.passDurationMillis;
        long nextCheck = InterceptionEvaluation.DEFAULT_RECHECK_MS;
        CountRuleEvaluator.Result countRules = CountRuleEvaluator.evaluate(policy, evidence, dailyResetMinute);
        nextCheck = Math.min(nextCheck, countRules.recheckAfterMs);
        List<InterceptionDecision> blocks = new ArrayList<>();

        if (policy.has(RuleType.WINDOW_BAN)) {
            for (RuleWindow window : policy.windowsFor(RuleType.WINDOW_BAN)) {
                RuleWindowSchedule.Interval interval = RuleWindowSchedule.activeInterval(
                        window, evidence.nowMs, evidence.zoneId);
                if (interval != null) {
                    blocks.add(block(packageName, policyId, revision, level,
                            InterceptionDecision.Trigger.WINDOW_BAN, window.id, 1, 1,
                            interval.endMs, window.scheduledPassDurationMillis > 0
                                    ? window.scheduledPassDurationMillis : passDurationMs).decision);
                    continue;
                }
                nextCheck = Math.min(nextCheck, RuleWindowSchedule.millisUntilNextStart(
                        window, evidence.nowMs, evidence.zoneId));
            }
        }

        if (evidence.available) {
            if (policy.has(RuleType.WINDOW_LIMIT)) {
                for (RuleWindow window : policy.windowsFor(RuleType.WINDOW_LIMIT)) {
                    RuleWindowSchedule.Interval interval = RuleWindowSchedule.activeInterval(
                            window, evidence.nowMs, evidence.zoneId);
                    if (interval == null) {
                        nextCheck = Math.min(nextCheck, RuleWindowSchedule.millisUntilNextStart(
                                window, evidence.nowMs, evidence.zoneId));
                        continue;
                    }
                    long current = evidence.windowForegroundMs(window.id);
                    long limit = window.limitDurationMillis;
                    if (current >= limit) {
                        blocks.add(block(packageName, policyId, revision, level,
                                InterceptionDecision.Trigger.WINDOW_LIMIT, window.id, current, limit,
                                interval.endMs, window.scheduledPassDurationMillis > 0
                                        ? window.scheduledPassDurationMillis : passDurationMs).decision);
                        continue;
                    }
                    nextCheck = Math.min(nextCheck, limit - current);
                }
            }

            if (policy.has(RuleType.DAILY_LIMIT)) {
                long current = "global".equals(policyId)
                        ? evidence.screenForegroundMs : evidence.appForegroundMs;
                long limit = policy.dailyLimitMillis;
                if (current >= limit) {
                    blocks.add(block(packageName, policyId, revision, level,
                            InterceptionDecision.Trigger.DAILY_LIMIT, "", current, limit,
                            DailyResetSchedule.nextReset(
                                    evidence.nowMs, evidence.zoneId, dailyResetMinute),
                            passDurationMs).decision);
                }
                if (current < limit) nextCheck = Math.min(nextCheck, limit - current);
            }

            for (CountRuleEvaluator.Match match : countRules.matches) {
                blocks.add(block(packageName, policyId, revision, level,
                        match.trigger, match.windowId, match.count, match.limitCount, match.resetAtMs,
                        match.passDurationMs > 0L ? match.passDurationMs : passDurationMs).decision);
            }

            blocks.removeIf(block -> level != ControlLevel.SEAL && ignoredBlock.test(block));
            if (!blocks.isEmpty()) return combineBlocks(blocks, evidence.nowMs, nextCheck);

            RuleWindow reminder = ReminderSchedule.activeWindow(policy, evidence.nowMs, evidence.zoneId);
            for (RuleWindow window : policy.windowsFor(RuleType.WINDOW_CONTINUOUS)) {
                RuleWindowSchedule.Interval interval = RuleWindowSchedule.activeInterval(window, evidence.nowMs, evidence.zoneId);
                nextCheck = Math.min(nextCheck, interval == null ? RuleWindowSchedule.millisUntilNextStart(
                        window, evidence.nowMs, evidence.zoneId) : interval.endMs - evidence.nowMs);
            }
            if (reminder != null) {
                long used = evidence.windowContinuousMs(reminder.id);
                if (used >= reminder.limitDurationMillis) {
                    return interrupt(packageName, policyId, revision, level,
                            InterceptionDecision.Trigger.WINDOW_CONTINUOUS, reminder.id, used,
                            reminder.limitDurationMillis, reminder.scheduledPassDurationMillis > 0L
                                    ? reminder.scheduledPassDurationMillis : passDurationMs,
                            reminder.interruptionPauseMillis);
                }
                return InterceptionEvaluation.allow(Math.min(nextCheck, reminder.limitDurationMillis - used));
            }

            if (policy.has(RuleType.CONTINUOUS)) {
                long limit = policy.continuousLimitMillis;
                if (evidence.continuousMs >= limit) {
                    return interrupt(packageName, policyId, revision, level,
                            InterceptionDecision.Trigger.CONTINUOUS, "", evidence.continuousMs,
                            limit, passDurationMs,
                            policy.interruptionPauseMillis);
                }
                nextCheck = Math.min(nextCheck, limit - evidence.continuousMs);
            }
        }

        blocks.removeIf(block -> level != ControlLevel.SEAL && ignoredBlock.test(block));
        if (!blocks.isEmpty()) return combineBlocks(blocks, evidence.nowMs, nextCheck);
        return InterceptionEvaluation.allow(nextCheck);
    }

    /** @param blocks 同次命中的全部禁用 @param nowMs 当前墙钟 @param nextCheck 最迟复查间隔
     * @return 保持原因优先级并等待全部禁用解除，同时在最早额度释放时复查的决定 */
    private static InterceptionEvaluation combineBlocks(List<InterceptionDecision> blocks, long nowMs, long nextCheck) {
        InterceptionDecision first = blocks.get(0);
        long reset = first.resetAtMs;
        for (InterceptionDecision block : blocks) {
            reset = Math.max(reset, block.resetAtMs);
            if (block.resetAtMs > nowMs) nextCheck = Math.min(nextCheck, block.resetAtMs - nowMs);
        }
        return InterceptionEvaluation.block(new InterceptionDecision(first.packageName, first.policyId,
                first.policyRevision, first.kind, first.trigger, first.level, first.windowId,
                first.currentValue, first.limitValue, reset, first.passDurationMs, 0L), nextCheck);
    }

    /** @param pkg 目标应用 @param policyId 策略身份 @param revision 修订号 @param level 强度
     * @param trigger 提醒类型 @param windowId 窗口身份 @param used 已用量 @param limit 连续阈值
     * @param pass 可申请临时时长 @param rest 必须完成的休息 @return 独立提醒决定 */
    private static InterceptionEvaluation interrupt(String pkg, String policyId, long revision,
            ControlLevel level, InterceptionDecision.Trigger trigger, String windowId, long used,
            long limit, long pass, long rest) {
        return InterceptionEvaluation.block(new InterceptionDecision(pkg, policyId, revision,
                InterceptionDecision.Kind.INTERRUPT, trigger, level, windowId, used, limit, 0L, pass,
                ContinuousRestPolicy.requiredRestMillis(policyId, rest)));
    }

    /**
     * 构造携带拦截决定和下一次检查时间的判断结果。
     *
     * @param packageName 需要查询或管控的应用包名
     * @param policyId 数据库中的稳定策略主键
     * @param revision 当前记录对应的策略修订号
     * @param level 全局管控档位
     * @param trigger 本次拦截的具体触发条件
     * @param windowId 当前时段的稳定标识
     * @param current 当前时长区间、用量或待比较的记录
     * @param limit 当前额度阈值或查询允许的最大条目数
     * @param resetAtMs 本次规则恢复的墙钟时间戳
     * @param passDurationMs 完成拦截后可临时放行的毫秒数
     */
    private static InterceptionEvaluation block(String packageName, String policyId, long revision,
            ControlLevel level, InterceptionDecision.Trigger trigger, String windowId,
            long current, long limit, long resetAtMs, long passDurationMs) {
        return InterceptionEvaluation.block(new InterceptionDecision(packageName, policyId, revision,
                InterceptionDecision.Kind.BLOCK, trigger, level, windowId, current, limit,
                resetAtMs, passDurationMs, 0L));
    }
}
