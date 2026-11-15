package github.vhoom.zhijian.domain.interception;

import github.vhoom.zhijian.data.model.policy.ControlLevel;
import github.vhoom.zhijian.data.model.policy.RuleGroup;
import github.vhoom.zhijian.data.model.policy.BuiltInGroupPreset;

import org.junit.Test;

import java.time.ZonedDateTime;
import java.util.Collections;

import static org.junit.Assert.*;

/**
 * 通过正式规则引擎验证内置组夜间边界、每天执行及提醒参数。
 *
 * 维护日期：2026年11月08日
 */
public class ShortVideoGroupRuleTest {
    private static final String PACKAGE = "com.ss.android.ugc.aweme";
    private static final String ZONE = "Asia/Shanghai";

    /** 整周每天 23 点开始禁止，次日 6 点 30 分恢复；周日跨周一也不漏掉。 */
    @Test public void nightlyBanUsesExactBoundariesEveryDay() {
        ZonedDateTime monday = ZonedDateTime.parse("2026-11-02T00:00:00+08:00[Asia/Shanghai]");
        for (int day = 0; day < 7; day++) {
            ZonedDateTime date = monday.plusDays(day);
            assertNull(evaluate(date.withHour(23).minusNanos(1_000_000), 0).decision);
            InterceptionDecision night = evaluate(date.withHour(23), 0).decision;
            assertEquals(InterceptionDecision.Trigger.WINDOW_BAN, night.trigger);
            assertEquals(date.plusDays(1).withHour(6).withMinute(30).toInstant().toEpochMilli(), night.resetAtMs);
            assertEquals(InterceptionDecision.Trigger.WINDOW_BAN,
                    evaluate(date.plusDays(1).withHour(6).withMinute(30).minusNanos(1_000_000), 0).decision.trigger);
            assertNull(evaluate(date.plusDays(1).withHour(6).withMinute(30), 0).decision);
        }
    }

    /** 十五分钟阈值准确触发十分钟休息，夜间禁止优先于连续提醒。 */
    @Test public void reminderHasFifteenMinuteThresholdAndTenMinuteRest() {
        ZonedDateTime noon = ZonedDateTime.parse("2026-11-04T12:00:00+08:00[Asia/Shanghai]");
        assertNull(evaluate(noon, 899_999L).decision);
        assertEquals(1L, evaluate(noon, 899_999L).recheckAfterMs);
        InterceptionDecision reminder = evaluate(noon, 900_000L).decision;
        assertEquals(InterceptionDecision.Kind.INTERRUPT, reminder.kind);
        assertEquals(600_000L, reminder.minimumInterruptionPauseDurationMs);
        assertEquals(InterceptionDecision.Trigger.WINDOW_BAN,
                evaluate(noon.withHour(23), 900_000L).decision.trigger);
    }

    /** @param now 设备本地时刻 @param continuousMillis 连续使用毫秒 @return 正式引擎决定 */
    private static InterceptionEvaluation evaluate(ZonedDateTime now, long continuousMillis) {
        RuleGroup group = BuiltInGroupPreset.VIDEO.create("默认短视频组",
                Collections.singletonList(PACKAGE), 180_000L);
        // 手动启用后才验证夜间禁用与连续提醒的执行边界。
        group.enabled = true;
        UsageEvidence evidence = new UsageEvidence(true, now.toInstant().toEpochMilli(), ZONE,
                0, 0, 0, continuousMillis, Collections.emptyMap());
        return RuleEngine.evaluate(PACKAGE, "group:" + group.id, 1, group,
                ControlLevel.SEAL, evidence, group.passDurationMillis);
    }
}
