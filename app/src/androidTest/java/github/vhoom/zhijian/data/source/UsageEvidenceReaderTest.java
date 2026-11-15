package github.vhoom.zhijian.data.source;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import github.vhoom.zhijian.data.model.policy.ControlLevel;
import github.vhoom.zhijian.data.model.policy.RuleGroup;
import github.vhoom.zhijian.data.model.policy.RuleType;
import github.vhoom.zhijian.data.model.policy.RuleWindow;
import github.vhoom.zhijian.domain.interception.InterceptionEvaluation;
import github.vhoom.zhijian.domain.interception.RuleEngine;
import github.vhoom.zhijian.domain.interception.UsageEvidence;
import github.vhoom.zhijian.domain.usage.UsageTimeline;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

/**
 * 在实际用量聚合及规则引擎入口验证启用前历史裁剪，不访问真实手机使用记录。
 *
 * 维护日期：2026年11月15日
 */
@RunWith(AndroidJUnit4.class)
public final class UsageEvidenceReaderTest {
    /** 新起点排除旧时长及三类旧打开次数，三种强度均允许真实未超限的新使用。 */
    @Test public void activationExcludesHistoryForDurationAndEveryCountRule() {
        RuleGroup policy = timedAndCountedPolicy();
        UsageTimeline timeline = history();
        UsageEvidence before = summarize(timeline, policy, 0L);
        assertEquals(5_000L, before.appForegroundMs);
        assertEquals(3L, before.openCount);
        assertNotNull(RuleEngine.evaluate("target", "app:target", 1L, policy,
                ControlLevel.REPAIR, before, 60_000L).decision);
        for (ControlLevel level : ControlLevel.values()) {
            UsageEvidence after = summarize(timeline, policy, 5_500L);
            assertEquals(2_000L, after.appForegroundMs);
            assertEquals(2_500L, after.screenForegroundMs);
            assertEquals(2_000L, after.windowForegroundMs("duration"));
            assertEquals(1L, after.openCount);
            assertEquals(1L, after.countSnapshot("window_count:count").count());
            assertEquals(1L, after.countSnapshot("rolling_count").count());
            InterceptionEvaluation result = RuleEngine.evaluate("target", "app:target", 1L,
                    policy, level, after, 60_000L);
            assertNull(result.decision);
        }
    }

    /** 启用时应用已在前台，仅裁剪时间，不伪造一次新的打开。 */
    @Test public void activationInsideExistingVisitDoesNotInventOpening() {
        UsageEvidence evidence = summarize(history(), timedAndCountedPolicy(), 6_500L);
        assertEquals(1_500L, evidence.appForegroundMs);
        assertEquals(0L, evidence.openCount);
        assertEquals(0L, evidence.countSnapshot("rolling_count").count());
    }

    /** 本次安装前的亮屏记录也不消耗新全局额度，首页仍可单独读取自然日统计。 */
    @Test public void globalQuotaUsesOnlyScreenTimeAfterActivation() {
        RuleGroup policy = new RuleGroup(0L, "");
        policy.types.add(RuleType.DAILY_LIMIT);
        policy.dailyLimitMillis = 3_000L;
        UsageEvidence evidence = summarize(history(), policy, 5_500L);
        assertNull(RuleEngine.evaluate("target", "global", 1L, policy,
                ControlLevel.REPAIR, evidence, 60_000L).decision);
    }

    /** @param timeline 合成真实前台记录 @param policy 本次配置 @param floor 首次生效毫秒 @return 正式聚合入口的证据 */
    private static UsageEvidence summarize(UsageTimeline timeline, RuleGroup policy, long floor) {
        return UsageEvidenceReader.summarize(timeline, "target", policy, 8_000L, "UTC", 0,
                0L, floor, "target"::equals);
    }

    /** @return 包含两次启用前访问和一次启用后访问的固定时间轴 */
    private static UsageTimeline history() {
        UsageTimeline timeline = new UsageTimeline("zhijian");
        timeline.screenChanged(true, 0L);
        timeline.resumed("target", "Main", 1_000L);
        timeline.resumed("other", "Main", 3_000L);
        timeline.resumed("target", "Main", 4_000L);
        timeline.resumed("other", "Main", 5_000L);
        timeline.resumed("target", "Main", 6_000L);
        return timeline;
    }

    /** @return 每种额度均只允许启用后尚未超限的用量配置 */
    private static RuleGroup timedAndCountedPolicy() {
        RuleGroup policy = new RuleGroup(1L, "测试");
        policy.types.add(RuleType.DAILY_LIMIT);
        policy.types.add(RuleType.WINDOW_LIMIT);
        policy.types.add(RuleType.DAILY_COUNT);
        policy.types.add(RuleType.WINDOW_COUNT);
        policy.types.add(RuleType.ROLLING_COUNT);
        policy.dailyLimitMillis = 3_000L;
        policy.count = 1;
        policy.frequencyCount = 1;
        policy.frequencyWindowMillis = 10_000L;
        policy.windows.add(new RuleWindow("duration", RuleType.WINDOW_LIMIT, 0, 60, 3_000L));
        RuleWindow count = new RuleWindow("count", RuleType.WINDOW_COUNT, 0, 60, 0L);
        count.limitCount = 1;
        policy.windows.add(count);
        return policy;
    }
}
