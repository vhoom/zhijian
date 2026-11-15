package github.vhoom.zhijian.runtime.interception;

import android.content.Context;
import android.os.SystemClock;

import androidx.test.platform.app.InstrumentationRegistry;

import github.vhoom.zhijian.data.model.policy.RuleGroup;
import github.vhoom.zhijian.data.model.policy.RuleType;
import github.vhoom.zhijian.data.model.policy.RuleWindow;
import github.vhoom.zhijian.data.model.runtime.PendingInterception;
import github.vhoom.zhijian.data.store.ZhijianStorage;
import github.vhoom.zhijian.data.store.PolicySnapshot;
import github.vhoom.zhijian.data.store.RuleGroupStore;
import github.vhoom.zhijian.data.store.interception.PendingInterceptionStore;
import github.vhoom.zhijian.data.store.usage.ContinuousUsageStore;
import github.vhoom.zhijian.domain.interception.InterceptionDecision;
import github.vhoom.zhijian.domain.interception.InterceptionEvaluation;
import github.vhoom.zhijian.domain.usage.ContinuousUsageCycle;
import github.vhoom.zhijian.util.time.BootIdentity;

import org.junit.Test;

import java.time.LocalTime;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 用正式运行入口验证新禁用优先展示，同时保留独立持久化的未完成休息。
 *
 * 维护日期：2026年11月15日
 */
public final class InterceptionPriorityRuntimeTest {
    /** 当前应用已有提醒时，新禁用替换展示，但不能删除未完成休息。 */
    @Test public void newBanPreservesRestForSameApp() {
        assertReminderReplacement(false);
    }

    /** 同组另一应用已有提醒时，当前应用优先展示禁用，原组级休息仍保留。 */
    @Test public void newBanPreservesSharedRestFromAnotherApp() {
        assertReminderReplacement(true);
    }

    /** @param switchedApp 是否从同组另一应用恢复提醒 */
    private static void assertReminderReplacement(boolean switchedApp) {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ZhijianStorage.get(context).ready().join();
        RuleGroupStore groups = new RuleGroupStore(context);
        PendingInterceptionStore pendingStore = new PendingInterceptionStore(context);
        String suffix = UUID.randomUUID().toString();
        String source = "priority.source." + suffix;
        String target = switchedApp ? "priority.target." + suffix : source;
        RuleGroup group = groups.create();
        group.apps.add(source);
        group.apps.add("priority.target." + suffix);
        group.types.add(RuleType.CONTINUOUS);
        group.continuousLimitMillis = 60_000L;
        group.interruptionPauseMillis = 60_000L;
        group.passDurationMillis = 180_000L;
        group.types.add(RuleType.WINDOW_BAN);
        LocalTime now = LocalTime.now();
        int minute = now.getHour() * 60 + now.getMinute();
        group.windows.add(new RuleWindow("active-ban", RuleType.WINDOW_BAN,
                Math.floorMod(minute - 60, 1440), (minute + 60) % 1440, 0L));
        String oldSession = "old-reminder-" + suffix;
        String policyId = "group:" + group.id;
        try {
            groups.save(group).join();
            PolicySnapshot snapshot = ZhijianStorage.get(context).snapshot();
            assertEquals(policyId, snapshot.effectivePolicyId(source));
            InterceptionDecision reminder = new InterceptionDecision(source, policyId,
                    snapshot.revisionOf(policyId), InterceptionDecision.Kind.INTERRUPT,
                    InterceptionDecision.Trigger.CONTINUOUS, snapshot.level(), "",
                    60_000L, 60_000L, 0L, 180_000L, 60_000L);
            String bootId = BootIdentity.read(context);
            long wall = System.currentTimeMillis();
            long elapsed = SystemClock.elapsedRealtime();
            PendingInterception rest = new PendingInterception(reminder, oldSession, bootId,
                    wall, elapsed, 0L, wall, policyId, 0L);
            new ContinuousUsageStore(context).save(policyId, new ContinuousUsageCycle(
                    bootId, wall, elapsed, 60_000L, 0L, source, "", 0L), snapshot.usageStart(policyId).id);
            pendingStore.saveRest(rest);
            pendingStore.save(rest);

            InterceptionEvaluation result = new InterceptionRuntime(context).evaluate(target);
            PendingInterception replacement = result.pending;
            assertNotNull(replacement);
            assertEquals(InterceptionDecision.Kind.BLOCK, replacement.decision.kind);
            assertEquals(InterceptionDecision.Trigger.WINDOW_BAN, replacement.decision.trigger);
            assertEquals(target, replacement.decision.packageName);
            assertNotEquals(oldSession, replacement.sessionId);
            assertEquals(replacement.sessionId, pendingStore.read(target).sessionId);
            PendingInterception retainedRest = pendingStore.restFor(rest);
            assertNotNull("Replacing the page must not erase the rest obligation", retainedRest);
            assertEquals(oldSession, retainedRest.sessionId);
            assertEquals(wall, retainedRest.occurredAtMs);
            assertEquals(policyId, retainedRest.continuousKey);
            assertEquals(1, pendingStore.rests(policyId).size());
            PendingInterception persistedBlock = pendingStore.restrictions().stream()
                    .filter(value -> value.sessionId.equals(replacement.sessionId)
                            && value.decision.kind == InterceptionDecision.Kind.BLOCK)
                    .findFirst().orElse(null);
            assertNotNull("The new ban must have its own persistent restriction", persistedBlock);
        } finally {
            for (PendingInterception fact : pendingStore.restrictions()) {
                if (!policyId.equals(fact.decision.policyId)) continue;
                if (fact.decision.kind == InterceptionDecision.Kind.BLOCK) pendingStore.clearBlock(fact);
                pendingStore.clear(fact.decision.packageName, fact.sessionId);
            }
            pendingStore.clear(source, oldSession);
            groups.remove(group.id).join();
            assertTrue("The test cycle must be removed", context
                    .getSharedPreferences("continuous_usage_cycles", Context.MODE_PRIVATE)
                    .edit().remove(policyId).commit());
        }
    }
}
