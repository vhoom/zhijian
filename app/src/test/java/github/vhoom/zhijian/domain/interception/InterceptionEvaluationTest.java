package github.vhoom.zhijian.domain.interception;

import github.vhoom.zhijian.data.model.policy.ControlLevel;
import github.vhoom.zhijian.data.model.policy.RuleDayParameters;
import github.vhoom.zhijian.data.model.policy.RuleGroup;
import github.vhoom.zhijian.data.model.policy.RuleType;

import org.junit.Test;

import java.time.ZonedDateTime;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * 验证允许结果的复查期限，防止星期切换和后台计算推迟下一次规则判断。
 *
 * 维护日期：2026年11月14日
 */
public class InterceptionEvaluationTest {
    private static final String ZONE = "Asia/Shanghai";
    private static final long START_ELAPSED_MS = 100_000L;

    /** 星期日当前没有规则时，也要在十秒后的星期一重新解析配置。 */
    @Test public void unrestrictedDayRechecksAtNextWeekdayBoundary() {
        RuleGroup policy = new RuleGroup(1L, "weekday");
        policy.weekdayRules.put(7, new RuleDayParameters());
        RuleDayParameters monday = new RuleDayParameters();
        monday.types.add(RuleType.DAILY_LIMIT);
        monday.dailyLimitMillis = 0L;
        policy.weekdayRules.put(1, monday);
        long wall = time("2026-11-15T23:59:50+08:00[Asia/Shanghai]");
        InterceptionEvaluation result = evaluate(policy, wall);
        assertNull(result.decision);
        assertEquals(10_000L, result.recheckDelayMs(wall,
                START_ELAPSED_MS, START_ELAPSED_MS, ZONE));
        assertEquals(InterceptionDecision.Kind.BLOCK, evaluate(policy, wall + 10_000L).decision.kind);
    }

    /** 短额度先于午夜到期时，日界线不能把原检查期限推迟。 */
    @Test public void earlierQuotaDeadlineWinsBeforeMidnight() {
        InterceptionEvaluation result = InterceptionEvaluation.allow(6_000L);
        assertEquals(6_000L, result.recheckDelayMs(
                time("2026-11-15T23:59:50+08:00[Asia/Shanghai]"),
                START_ELAPSED_MS, START_ELAPSED_MS, ZONE));
    }

    /** 六秒剩余额度经过两秒计算和排队后，只能再等待四秒。 */
    @Test public void computationAndDeliveryTimeConsumeTheRemainingQuota() {
        InterceptionEvaluation result = InterceptionEvaluation.allow(6_000L);
        assertEquals(4_000L, result.recheckDelayMs(
                time("2026-11-15T12:00:00+08:00[Asia/Shanghai]"),
                START_ELAPSED_MS, START_ELAPSED_MS + 2_000L, ZONE));
    }

    /** 计算返回时已跨日，不能以新一天的午夜重新开始等待。 */
    @Test public void resultDeliveredAfterMidnightRechecksImmediately() {
        InterceptionEvaluation result = InterceptionEvaluation.allow(60_000L);
        assertEquals(1L, result.recheckDelayMs(
                time("2026-11-15T23:59:50+08:00[Asia/Shanghai]"),
                START_ELAPSED_MS, START_ELAPSED_MS + 11_000L, ZONE));
    }

    /** 计算返回时额度恰好到期或已经过期，都应尽快复查而不重启六秒计时。 */
    @Test public void exhaustedQuotaRechecksAtAndAfterItsDeadline() {
        InterceptionEvaluation result = InterceptionEvaluation.allow(6_000L);
        long wall = time("2026-11-15T12:00:00+08:00[Asia/Shanghai]");
        assertEquals(1L, result.recheckDelayMs(wall,
                START_ELAPSED_MS, START_ELAPSED_MS + 6_000L, ZONE));
        assertEquals(1L, result.recheckDelayMs(wall,
                START_ELAPSED_MS, START_ELAPSED_MS + 9_000L, ZONE));
    }

    /** @param value 含时区的固定时间文本 @return 对应墙上时钟毫秒值 */
    private static long time(String value) {
        return ZonedDateTime.parse(value).toInstant().toEpochMilli();
    }

    /** @param policy 星期规则配置 @param nowMs 本轮固定墙上时间 @return 正式引擎的额度判定 */
    private static InterceptionEvaluation evaluate(RuleGroup policy, long nowMs) {
        UsageEvidence evidence = new UsageEvidence(true, nowMs, ZONE,
                0L, 0L, 0L, 0L, Collections.emptyMap());
        return RuleEngine.evaluate("app.example", "group:1", 1L,
                policy, ControlLevel.SEAL, evidence, 180_000L);
    }
}
