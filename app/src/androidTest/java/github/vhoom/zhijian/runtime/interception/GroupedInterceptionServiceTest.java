package github.vhoom.zhijian.runtime.interception;

import android.app.Activity;
import android.os.SystemClock;

import github.vhoom.zhijian.data.model.policy.ControlLevel;
import github.vhoom.zhijian.data.model.policy.RuleGroup;
import github.vhoom.zhijian.data.model.policy.RuleType;
import github.vhoom.zhijian.data.store.AppPolicyStore;
import github.vhoom.zhijian.data.store.RuleGroupStore;
import github.vhoom.zhijian.data.store.ZhijianStorage;
import github.vhoom.zhijian.data.store.usage.ContinuousUsageStore;
import github.vhoom.zhijian.domain.usage.ContinuousUsageCycle;
import github.vhoom.zhijian.fixture.FixtureCleanup;
import github.vhoom.zhijian.fixture.ProtectionDeviceSession;
import github.vhoom.zhijian.util.time.BootIdentity;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 验证真实无障碍服务能自动检查规则组，测试过程不主动调用运行判断入口。
 *
 * 维护日期：2026年11月15日
 */
public class GroupedInterceptionServiceTest {
    /** 停留在同一个组员中达到阈值就应出现拦截，无需手动切换应用。 */
    @Test public void stayingInGroupMemberTriggersWithoutSwitchingApplications() throws Exception {
        try (ProtectionDeviceSession session = new ProtectionDeviceSession()) {
            RuleGroup policy = new RuleGroup(0L, "");
            policy.types.add(RuleType.CONTINUOUS);
            policy.continuousLimitMillis = 6_000L;
            policy.interruptionPauseMillis = 6_000L;
            policy.passDurationMillis = 180_000L;
            session.start(policy, ControlLevel.REPAIR);
            RuleGroupStore groups = new RuleGroupStore(session.context);
            RuleGroup group = groups.create();
            group.types.add(RuleType.CONTINUOUS);
            group.continuousLimitMillis = policy.continuousLimitMillis;
            group.interruptionPauseMillis = policy.interruptionPauseMillis;
            group.apps.add(session.target);
            // 第二成员只构成合法测试组；本用例专门验证停留在同一真实前台应用的调度。
            group.apps.add(session.target + ".peer");
            String policyId = "group:" + group.id;
            try (FixtureCleanup cleanup = new FixtureCleanup()) {
                cleanup.add(() -> groups.remove(group.id).join());
                cleanup.add(() -> assertTrue("The test cycle must be removed", session.context
                        .getSharedPreferences("continuous_usage_cycles", android.content.Context.MODE_PRIVATE)
                        .edit().remove(policyId).commit()));
                groups.save(group).join();
                long startedElapsed = SystemClock.elapsedRealtime();
                new ContinuousUsageStore(session.context).save(policyId, new ContinuousUsageCycle(
                        BootIdentity.read(session.context), System.currentTimeMillis(), startedElapsed,
                        0L, 0L, session.target, "", 0L),
                        ZhijianStorage.get(session.context).snapshot().usageStart(policyId).id);
                new AppPolicyStore(session.context).reset(session.target).join();
                Activity page = session.awaitInterception(12_000L);
                assertNotNull("The service must check the group while its member remains foreground", page);
                assertEquals(policyId, page.getIntent().getStringExtra("interception.policy"));
                assertEquals("CONTINUOUS", page.getIntent().getStringExtra("interception.trigger"));
                assertTrue(SystemClock.elapsedRealtime() - startedElapsed >= 5_500L);
            }
        }
    }
}
