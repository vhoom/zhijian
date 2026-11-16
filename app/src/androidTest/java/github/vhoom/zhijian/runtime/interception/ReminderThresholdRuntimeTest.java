package github.vhoom.zhijian.runtime.interception;

import android.content.Context;
import android.os.SystemClock;

import androidx.test.platform.app.InstrumentationRegistry;

import github.vhoom.zhijian.data.model.policy.ControlLevel;
import github.vhoom.zhijian.data.model.policy.RuleGroup;
import github.vhoom.zhijian.data.model.policy.RuleType;
import github.vhoom.zhijian.data.model.policy.RuleWindow;
import github.vhoom.zhijian.data.model.runtime.PendingInterception;
import github.vhoom.zhijian.data.store.PolicySnapshot;
import github.vhoom.zhijian.data.store.RuleGroupStore;
import github.vhoom.zhijian.data.store.ZhijianStorage;
import github.vhoom.zhijian.data.store.interception.PendingInterceptionStore;
import github.vhoom.zhijian.data.store.usage.ContinuousUsageStore;
import github.vhoom.zhijian.domain.interception.InterceptionDecision;
import github.vhoom.zhijian.domain.interception.InterceptionEvaluation;
import github.vhoom.zhijian.domain.interception.ReminderSchedule;
import github.vhoom.zhijian.domain.interception.RuleWindowSchedule;
import github.vhoom.zhijian.domain.usage.ContinuousUsageCycle;
import github.vhoom.zhijian.fixture.FixtureCleanup;
import github.vhoom.zhijian.util.permission.PermissionStatusReader;
import github.vhoom.zhijian.util.time.BootIdentity;

import org.junit.Test;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * 正式运行层读取旧提醒及更新后的规则，判定与最终展示共用新阈值，保留原休息。
 *
 * 维护日期：2026年11月16日
 */
public final class ReminderThresholdRuntimeTest {
    private static final long MINUTE_MS = 60_000L;
    private static final long ORIGINAL_REST_MS = 10L * MINUTE_MS;
    private static final long UPDATED_REST_MS = 3L * MINUTE_MS;

    /** 十五分钟改成一分钟后，同应用及同组另一成员均立即展示已达到的新阈值。 */
    @Test public void loweredAllDayThresholdAppliesToBothGroupTargets() throws Exception {
        for (boolean switchMember : new boolean[]{false, true}) {
            assertThresholdChange(RuleType.CONTINUOUS, 15L * MINUTE_MS, MINUTE_MS,
                    MINUTE_MS + 1_000L, true, switchMember);
        }
    }

    /** 一分钟改成十五分钟后，不再按旧间隔提前弹页，原累计使用继续保留。 */
    @Test public void raisedAllDayThresholdWaitsForLatestInterval() throws Exception {
        assertThresholdChange(RuleType.CONTINUOUS, MINUTE_MS, 15L * MINUTE_MS,
                2L * MINUTE_MS, false, false);
    }

    /** 时段提醒修改同一窗口阈值后，最终弹页检查不再退回旧阈值。 */
    @Test public void loweredWindowThresholdAppliesToAnotherGroupMember() throws Exception {
        assertThresholdChange(RuleType.WINDOW_CONTINUOUS, 15L * MINUTE_MS, MINUTE_MS,
                MINUTE_MS + 1_000L, true, true);
    }

    /**
     * 保存旧提醒后实际提交新配置，再调用正式运行入口；仅清理本用例的独立组与周期。
     * @param type 全天或时段连续提醒
     * @param originalLimitMs 原使用阈值，单位为毫秒
     * @param updatedLimitMs 保存后的使用阈值，单位为毫秒
     * @param addedUsageMs 提醒确认后已经产生的真实用量，单位为毫秒
     * @param shouldBlock 新阈值是否已达到
     * @param switchMember 是否由同组另一应用接收再次提醒
     */
    private static void assertThresholdChange(RuleType type, long originalLimitMs, long updatedLimitMs,
            long addedUsageMs, boolean shouldBlock, boolean switchMember) throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ZhijianStorage storage = ZhijianStorage.get(context);
        storage.ready().join();
        assumeTrue("Requires existing usage access", PermissionStatusReader.hasUsageAccess(context));
        assumeTrue("Interactive reminder confirmation requires an interactive level",
                storage.snapshot().level() != ControlLevel.SEAL);

        RuleGroupStore groups = new RuleGroupStore(context);
        PendingInterceptionStore pendingStore = new PendingInterceptionStore(context);
        ContinuousUsageStore cycles = new ContinuousUsageStore(context);
        String suffix = UUID.randomUUID().toString();
        String source = "threshold.source." + suffix;
        String target = switchMember ? "threshold.target." + suffix : source;
        RuleGroup group = groups.create();
        group.enabled = true;
        group.apps.add(source);
        group.apps.add("threshold.target." + suffix);
        group.types.add(type);
        group.continuousLimitMillis = originalLimitMs;
        group.interruptionPauseMillis = ORIGINAL_REST_MS;
        RuleWindow window = null;
        if (type == RuleType.WINDOW_CONTINUOUS) {
            LocalTime time = LocalTime.now();
            int minute = time.getHour() * 60 + time.getMinute();
            window = new RuleWindow("threshold-window", type,
                    Math.floorMod(minute - 60, 1440), (minute + 60) % 1440, originalLimitMs);
            window.interruptionPauseMillis = ORIGINAL_REST_MS;
            group.windows.add(window);
        }
        String policyId = "group:" + group.id;
        String zoneId = ZoneId.systemDefault().getId();
        String cycleKey = window == null ? policyId
                : ReminderSchedule.cycleKey(policyId, window, System.currentTimeMillis(), zoneId);
        String oldSession = "old-threshold-" + suffix;

        try (FixtureCleanup cleanup = new FixtureCleanup()) {
            for (String pkg : group.apps) {
                cleanup.add(() -> {
                    PendingInterception shown = pendingStore.read(pkg);
                    if (shown != null && policyId.equals(shown.decision.policyId)) {
                        pendingStore.clearPresentation(shown.sessionId);
                    }
                });
            }
            cleanup.add(() -> {
                for (PendingInterception rest : pendingStore.rests(policyId)) {
                    pendingStore.clear(rest.decision.packageName, rest.sessionId);
                }
            });
            cleanup.add(() -> groups.remove(group.id).join());
            cleanup.add(() -> assertTrue("Only the test group's cycles are removed", context
                    .getSharedPreferences("continuous_usage_cycles", Context.MODE_PRIVATE)
                    .edit().remove(cycleKey).remove(policyId).commit()));
            cleanup.initialize(() -> groups.save(group).join());

            PolicySnapshot original = storage.snapshot();
            String bootId = BootIdentity.read(context);
            long occurredWall = System.currentTimeMillis();
            long occurredElapsed = SystemClock.elapsedRealtime();
            long floor = window == null ? 0L
                    : RuleWindowSchedule.activeInterval(window, occurredWall, zoneId).startMs;
            InterceptionDecision.Trigger trigger = window == null ? InterceptionDecision.Trigger.CONTINUOUS
                    : InterceptionDecision.Trigger.WINDOW_CONTINUOUS;
            InterceptionDecision oldDecision = new InterceptionDecision(source, policyId,
                    original.revisionOf(policyId), InterceptionDecision.Kind.INTERRUPT, trigger,
                    original.level(), window == null ? "" : window.id,
                    originalLimitMs, originalLimitMs, 0L, group.passDurationMillis, ORIGINAL_REST_MS);
            PendingInterception oldRest = new PendingInterception(oldDecision, oldSession, bootId,
                    occurredWall, occurredElapsed, 2L, occurredWall, cycleKey, floor, originalLimitMs);
            pendingStore.saveRest(oldRest);
            pendingStore.save(oldRest);

            group.continuousLimitMillis = updatedLimitMs;
            group.interruptionPauseMillis = UPDATED_REST_MS;
            if (window != null) {
                window.limitDurationMillis = updatedLimitMs;
                window.interruptionPauseMillis = UPDATED_REST_MS;
            }
            groups.save(group).join();
            PolicySnapshot latest = storage.snapshot();
            assertTrue(latest.revisionOf(policyId) > original.revisionOf(policyId));
            assertEquals(policyId, latest.effectivePolicyId(target));
            long wall = System.currentTimeMillis();
            long elapsed = SystemClock.elapsedRealtime();
            long used = originalLimitMs + addedUsageMs;
            cycles.save(cycleKey, new ContinuousUsageCycle(bootId, wall, elapsed, used, 0L, target, "", 2L),
                    latest.usageStart(policyId).id);

            InterceptionEvaluation result = new InterceptionRuntime(context).evaluate(target, wall);
            if (shouldBlock) {
                assertNotNull("The new threshold must reach the final presentation check", result.pending);
                assertEquals(trigger, result.decision.trigger);
                assertEquals(updatedLimitMs, result.decision.limitValue);
                assertEquals(latest.revisionOf(policyId), result.decision.policyRevision);
                assertEquals(target, result.decision.packageName);
                assertEquals(ORIGINAL_REST_MS, result.decision.minimumInterruptionPauseDurationMs);
            } else {
                assertNull("The old shorter threshold must not trigger a page", result.decision);
                assertTrue(result.recheckAfterMs > 0L);
            }
            PendingInterception retained = new PendingInterceptionStore(context).restFor(oldRest);
            assertNotNull(retained);
            assertEquals(oldSession, retained.sessionId);
            assertEquals(occurredWall, retained.occurredAtMs);
            assertEquals(originalLimitMs, retained.acknowledgedUsageMs);
            assertEquals(originalLimitMs, retained.decision.limitValue);
            assertEquals(ORIGINAL_REST_MS, retained.decision.minimumInterruptionPauseDurationMs);
            ContinuousUsageCycle persisted = cycles.read(cycleKey, latest.usageStart(policyId).id);
            assertNotNull(persisted);
            assertTrue(persisted.usedMs >= used);
            assertEquals(2L, persisted.generation);
        }
    }
}
