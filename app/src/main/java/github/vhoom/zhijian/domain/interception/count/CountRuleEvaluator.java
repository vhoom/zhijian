package github.vhoom.zhijian.domain.interception.count;

import github.vhoom.zhijian.data.model.policy.CountLimit;
import github.vhoom.zhijian.data.model.policy.RuleGroup;
import github.vhoom.zhijian.data.model.policy.RuleType;
import github.vhoom.zhijian.data.model.policy.RuleWindow;
import github.vhoom.zhijian.domain.interception.DailyResetSchedule;
import github.vhoom.zhijian.domain.interception.InterceptionDecision;
import github.vhoom.zhijian.domain.interception.InterceptionEvaluation;
import github.vhoom.zhijian.domain.interception.RuleWindowSchedule;
import github.vhoom.zhijian.domain.interception.UsageEvidence;
import github.vhoom.zhijian.domain.interception.WeekdayRuleSchedule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 限次规则模块：统计范围、次数阈值和额度释放由同一组策略生成。
 * 系统读取器只执行查询，引擎只合并结果，两者不重复解释规则类型。
 *
 * 维护日期：2026年11月12日
 */
public final class CountRuleEvaluator {
    private static final Map<RuleType, ScopeStrategy> SCOPES = createScopes();

    /** 规则模块不持有系统服务、存储或可变配置。 */
    private CountRuleEvaluator() { }

    /**
     * 返回本次启用且处于活动范围的统计请求。
     * @param policy 已提交的规则配置 @param nowMs 当前墙钟 @param zoneId 设备时区
     * @param resetMinute 全天次数额度的恢复分钟
     * @return 查询键和起止时间均固定的不可变请求列表
     */
    public static List<Query> queries(RuleGroup policy, long nowMs, String zoneId, int resetMinute) {
        List<Query> result = new ArrayList<>();
        for (Limit limit : limits(policy, nowMs, zoneId, resetMinute)) {
            if (limit.query != null) result.add(limit.query);
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * 用相同范围策略判定所有限次，允许前 N 次，第 N+1 次才触发。
     * @param policy 当前规则 @param evidence 本次只读系统证据 @param resetMinute 全天恢复分钟
     * @return 全部超限结果及最迟复查间隔
     */
    public static Result evaluate(RuleGroup policy, UsageEvidence evidence, int resetMinute) {
        List<Match> matches = new ArrayList<>();
        long nextCheck = InterceptionEvaluation.DEFAULT_RECHECK_MS;
        for (Limit limit : limits(policy, evidence.nowMs, evidence.zoneId, resetMinute)) {
            nextCheck = Math.min(nextCheck, limit.nextCheckMs);
            if (!evidence.available || limit.query == null) continue;
            long count = limit.trigger == InterceptionDecision.Trigger.DAILY_COUNT
                    ? evidence.openCount : evidence.countSnapshot(limit.query.key).count();
            if (count <= limit.count) continue;
            long reset = limit.rollingMillis == 0L ? limit.resetAtMs
                    : evidence.countSnapshot(limit.query.key).resetAtMs(limit.count, limit.rollingMillis);
            matches.add(new Match(limit, count, reset));
            nextCheck = Math.min(nextCheck, Math.max(1L, reset - evidence.nowMs));
        }
        return new Result(matches, nextCheck);
    }

    /** @param policy 当前规则 @param nowMs 当前墙钟 @param zoneId 设备时区
     * @return 是否有活动限次必须依赖系统证据复查 */
    public static boolean needsUsage(RuleGroup policy, long nowMs, String zoneId) {
        return !queries(policy, nowMs, zoneId, 0).isEmpty();
    }

    /** @return 三种实际统计范围的策略工厂，不依赖枚举顺序解释业务 */
    private static Map<RuleType, ScopeStrategy> createScopes() {
        Map<RuleType, ScopeStrategy> scopes = new EnumMap<>(RuleType.class);
        scopes.put(RuleType.DAILY_COUNT, new DailyScope());
        scopes.put(RuleType.WINDOW_COUNT, new WindowScope());
        scopes.put(RuleType.ROLLING_COUNT, new RollingScope());
        return Collections.unmodifiableMap(scopes);
    }

    /** @param policy 完整规则 @param nowMs 当前墙钟 @param zoneId 时区 @param resetMinute 全天恢复分钟
     * @return 经星期解析后的本次额度，包含未开始时段的复查要求 */
    private static List<Limit> limits(RuleGroup policy, long nowMs, String zoneId, int resetMinute) {
        RuleGroup resolved = WeekdayRuleSchedule.resolve(policy, nowMs, zoneId);
        List<Limit> limits = new ArrayList<>();
        for (Map.Entry<RuleType, ScopeStrategy> entry : SCOPES.entrySet()) {
            if (resolved.has(entry.getKey())) entry.getValue().collect(resolved, nowMs, zoneId, resetMinute, limits);
        }
        return limits;
    }

    /** 统计范围策略只产生数据，不读取事件或发起拦截。 */
    private interface ScopeStrategy {
        /** @param policy 单次运行配置 @param nowMs 当前墙钟 @param zoneId 时区
         * @param resetMinute 全天恢复分钟 @param result 接收本次额度的列表 */
        void collect(RuleGroup policy, long nowMs, String zoneId, int resetMinute, List<Limit> result);
    }

    /** 全天次数按用户设置的日界线恢复。 */
    private static final class DailyScope implements ScopeStrategy {
        /** {@inheritDoc} */
        @Override public void collect(RuleGroup policy, long nowMs, String zoneId, int resetMinute, List<Limit> result) {
            if (!CountLimit.isValid(policy.count)) return;
            Query query = new Query("daily_count", DailyResetSchedule.periodStart(nowMs, zoneId, resetMinute), nowMs);
            result.add(new Limit(query, InterceptionDecision.Trigger.DAILY_COUNT, "", policy.count,
                    DailyResetSchedule.nextReset(nowMs, zoneId, resetMinute), 0L, 0L,
                    InterceptionEvaluation.DEFAULT_RECHECK_MS));
        }
    }

    /** 每个固定时段使用独立次数额度和结束边界。 */
    private static final class WindowScope implements ScopeStrategy {
        /** {@inheritDoc} */
        @Override public void collect(RuleGroup policy, long nowMs, String zoneId, int resetMinute, List<Limit> result) {
            for (RuleWindow window : policy.windowsFor(RuleType.WINDOW_COUNT)) {
                if (!CountLimit.isValid(window.limitCount)) continue;
                RuleWindowSchedule.Interval interval = RuleWindowSchedule.activeInterval(window, nowMs, zoneId);
                Query query = interval == null ? null : new Query("window_count:" + window.id, interval.startMs, nowMs);
                long next = interval == null ? RuleWindowSchedule.millisUntilNextStart(window, nowMs, zoneId)
                        : interval.endMs - nowMs;
                result.add(new Limit(query, InterceptionDecision.Trigger.WINDOW_COUNT, window.id, window.limitCount,
                        interval == null ? 0L : interval.endMs, 0L, window.scheduledPassDurationMillis, next));
            }
        }
    }

    /** 滚动范围使用 (当前时刻减窗口长度，当前时刻]，记录在到期毫秒立即移出。 */
    private static final class RollingScope implements ScopeStrategy {
        /** {@inheritDoc} */
        @Override public void collect(RuleGroup policy, long nowMs, String zoneId, int resetMinute, List<Limit> result) {
            if (!CountLimit.isValid(policy.frequencyCount) || policy.frequencyWindowMillis <= 0L) return;
            long floor = nowMs - policy.frequencyWindowMillis;
            Query query = new Query("rolling_count", floor < 0L ? 0L : floor + 1L, nowMs);
            result.add(new Limit(query, InterceptionDecision.Trigger.ROLLING_COUNT, "", policy.frequencyCount,
                    0L, policy.frequencyWindowMillis, 0L, InterceptionEvaluation.DEFAULT_RECHECK_MS));
        }
    }

    /** 限次统计请求；调用方不需要了解对应范围策略。 */
    public static final class Query {
        public final String key;
        public final long startMs, endMs;
        /** @param key 唯一统计键 @param startMs 包含的起点 @param endMs 包含的终点 */
        private Query(String key, long startMs, long endMs) { this.key = key; this.startMs = startMs; this.endMs = endMs; }
    }

    /** 内部额度描述，隔离固定范围和滚动范围的恢复方式。 */
    private static final class Limit {
        private final Query query;
        private final InterceptionDecision.Trigger trigger;
        private final String windowId;
        private final int count;
        private final long resetAtMs, rollingMillis, passDurationMs, nextCheckMs;
        /** @param query 活动统计范围，未开始时为空 @param trigger 触发类型 @param windowId 时段身份
         * @param count 允许次数 @param resetAtMs 固定恢复时刻 @param rollingMillis 滚动长度
         * @param passDurationMs 原开始星期的放行时长 @param nextCheckMs 最迟复查间隔 */
        private Limit(Query query, InterceptionDecision.Trigger trigger, String windowId, int count,
                long resetAtMs, long rollingMillis, long passDurationMs, long nextCheckMs) {
            this.query = query; this.trigger = trigger; this.windowId = windowId; this.count = count;
            this.resetAtMs = resetAtMs; this.rollingMillis = rollingMillis;
            this.passDurationMs = passDurationMs; this.nextCheckMs = nextCheckMs;
        }
    }

    /** 一条超限结果，供引擎沿用已有的三档拦截流程。 */
    public static final class Match {
        public final InterceptionDecision.Trigger trigger;
        public final String windowId;
        public final long count, limitCount, resetAtMs, passDurationMs;
        /** @param limit 本次额度 @param count 实际次数 @param resetAtMs 自然解除时刻 */
        private Match(Limit limit, long count, long resetAtMs) {
            this.trigger = limit.trigger; this.windowId = limit.windowId;
            this.count = count; this.limitCount = limit.count; this.resetAtMs = resetAtMs;
            this.passDurationMs = limit.passDurationMs;
        }
    }

    /** 全部限次结果与时段开始、结束、滚动释放所要求的复查时间。 */
    public static final class Result {
        public final List<Match> matches;
        public final long recheckAfterMs;
        /** @param matches 全部超限结果 @param recheckAfterMs 最迟复查间隔 */
        private Result(List<Match> matches, long recheckAfterMs) {
            this.matches = Collections.unmodifiableList(new ArrayList<>(matches));
            this.recheckAfterMs = Math.max(1L, recheckAfterMs);
        }
    }
}
