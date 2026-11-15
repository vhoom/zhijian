package github.vhoom.zhijian.data.store;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import github.vhoom.zhijian.data.model.policy.ControlLevel;
import github.vhoom.zhijian.data.model.policy.RetreatRestrictionException;
import github.vhoom.zhijian.data.model.policy.RuleGroup;
import github.vhoom.zhijian.data.model.policy.RuleType;
import github.vhoom.zhijian.data.model.policy.RuleWindow;
import github.vhoom.zhijian.data.model.runtime.InterceptionRecord;
import github.vhoom.zhijian.data.model.runtime.PassGrant;
import github.vhoom.zhijian.data.model.runtime.PendingInterception;
import github.vhoom.zhijian.data.store.interception.PendingInterceptionStore;
import github.vhoom.zhijian.data.store.usage.ContinuousUsageStore;
import github.vhoom.zhijian.domain.interception.InterceptionDecision;
import github.vhoom.zhijian.domain.interception.InterceptionEvaluation;
import github.vhoom.zhijian.domain.interception.RuleEngine;
import github.vhoom.zhijian.domain.interception.UsageEvidence;
import github.vhoom.zhijian.domain.usage.ContinuousUsageCycle;
import github.vhoom.zhijian.util.time.BootIdentity;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.time.LocalTime;
import java.time.ZoneId;
import static org.junit.Assert.*;

/**
 * 使用隔离数据库和偏好文件验证真实提交入口的闭关保护，不操作用户规则。
 *
 * 维护日期：2026年11月15日
 */
@RunWith(AndroidJUnit4.class)
public class RetreatStorageTest {
    private Context base, context;
    private String prefix, databaseName;
    private ZhijianStorage storage;
    private final Set<String> preferenceNames = new HashSet<>();

    /** 每个用例创建独立数据空间。 */
    @Before public void open() throws Exception {
        base = InstrumentationRegistry.getInstrumentation().getTargetContext();
        prefix = "retreat-test-" + UUID.randomUUID();
        databaseName = prefix + ".db";
        context = new ContextWrapper(base) {
            /** @return 仍保留测试偏好命名空间的应用上下文 */
            @Override public Context getApplicationContext() { return this; }
            /** @param name 逻辑偏好名称 @param mode 打开模式 @return 隔离后的偏好文件 */
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                synchronized (preferenceNames) { preferenceNames.add(name); }
                return base.getSharedPreferences(prefix + "-" + name, mode);
            }
        };
        storage = new ZhijianStorage(context, databaseName);
        await(storage.ready());
        await(storage.setLevel(ControlLevel.MORTAL));
    }

    /** 只清理本用例创建的数据库和偏好。 */
    @After public void close() throws Exception {
        if (storage != null) await(storage.closeForTest());
        base.deleteDatabase(databaseName);
        for (String name : preferenceNames) {
            base.deleteSharedPreferences(prefix + "-" + name);
        }
    }

    /** 全局策略版本随切档变化，旧放行的截止时间仍保持并可从磁盘恢复。 */
    @Test public void existingGrantKeepsItsDeadlineAcrossLevelChangesAndReopen() throws Exception {
        RuntimeStateStore runtime = new RuntimeStateStore(storage);
        long wall = System.currentTimeMillis(), elapsed = SystemClock.elapsedRealtime();
        long revision = storage.snapshot().revisionOf("global");
        PassGrant grant = new PassGrant("app.granted", "global", revision, BootIdentity.read(context),
                wall, 180_000L, elapsed + 180_000L);
        InterceptionRecord record = new InterceptionRecord("pass:test", "app.granted", "global", revision,
                "interrupt", storage.snapshot().level().code, "continuous", "none", "allowed", wall, wall);
        await(runtime.recordInterception(record, grant));
        await(storage.setLevel(ControlLevel.SEAL));
        assertEquals(grant.deadlineElapsedMs, runtime.grantFor("app.granted").deadlineElapsedMs);
        await(storage.setLevel(ControlLevel.MORTAL));
        await(storage.closeForTest());
        storage = new ZhijianStorage(context, databaseName);
        await(storage.ready());
        assertEquals(grant.deadlineElapsedMs, new RuntimeStateStore(storage).grantFor("app.granted").deadlineElapsedMs);
    }

    /** 待完成休息跨存储重建继续保护切档和规则写入，拒绝操作不发布候选配置。 */
    @Test public void pendingRestProtectsLevelAndRuleUntilTheCycleCompletes() throws Exception {
        RuleGroup policy = reminder();
        await(storage.saveGroup(policy));
        await(storage.setLevel(ControlLevel.SEAL));
        long wall = System.currentTimeMillis(), elapsed = SystemClock.elapsedRealtime();
        String boot = BootIdentity.read(context);
        ContinuousUsageStore cycles = new ContinuousUsageStore(context);
        cycles.save("group:1", new ContinuousUsageCycle(boot, wall, elapsed, 60_000L, 0L, "app.a", "Main", "", 0L),
                storage.snapshot().usageStart("group:1").id);
        InterceptionDecision decision = new InterceptionDecision("app.a", "group:1",
                storage.snapshot().revisionOf("group:1"), InterceptionDecision.Kind.INTERRUPT,
                InterceptionDecision.Trigger.CONTINUOUS, ControlLevel.SEAL, "", 60_000L, 60_000L,
                0L, 180_000L, 300_000L);
        PendingInterception rest = new PendingInterception(decision, "rest", boot, wall, elapsed, 0L,
                wall, "group:1", 0L);
        new PendingInterceptionStore(context).saveRest(rest);
        await(storage.closeForTest());
        storage = new ZhijianStorage(context, databaseName);
        await(storage.ready());
        assertBlocked(storage.setLevel(ControlLevel.REPAIR), true);
        RuleGroup changed = storage.snapshot().group(1L);
        changed.enabled = false;
        assertBlocked(storage.saveGroup(changed), false);
        assertBlocked(storage.removePolicy("group:1"), false);
        assertTrue(storage.snapshot().group(1L).enabled);
        assertEquals(ControlLevel.SEAL, storage.snapshot().level());
        // 真实周期已完成足额休息后，即使展示清理尚未执行也应该能够切档。
        cycles.save("group:1", new ContinuousUsageCycle(boot, System.currentTimeMillis(), SystemClock.elapsedRealtime(),
                0L, 300_000L, null, null, "", 1L), storage.snapshot().usageStart("group:1").id);
        await(storage.setLevel(ControlLevel.REPAIR));
        assertEquals(ControlLevel.REPAIR, storage.snapshot().level());
    }

    /** 切入闭关前先按旧强度结算，已有进度不从零重开。 */
    @Test public void enteringRetreatPreservesVerifiedRestProgress() throws Exception {
        await(storage.saveGroup(reminder()));
        ContinuousUsageStore cycles = new ContinuousUsageStore(context);
        long wall = System.currentTimeMillis(), elapsed = SystemClock.elapsedRealtime();
        cycles.save("group:1", new ContinuousUsageCycle(BootIdentity.read(context), wall, elapsed,
                60_000L, 120_000L, "other.app", "Main", "", 0L), storage.snapshot().usageStart("group:1").id);
        await(storage.setLevel(ControlLevel.SEAL));
        ContinuousUsageCycle after = cycles.read("group:1");
        assertEquals(60_000L, after.usedMs);
        assertTrue(after.restMs >= 120_000L);
        assertEquals(0L, after.generation);
    }

    /** 原触发应用在前两档改为独立配置后，组内剩余成员仍承担原休息。 */
    @Test public void movingTheOriginalTargetDoesNotReleaseTheRemainingGroup() throws Exception {
        await(storage.saveGroup(reminder()));
        long wall = System.currentTimeMillis(), elapsed = SystemClock.elapsedRealtime();
        String boot = BootIdentity.read(context);
        new ContinuousUsageStore(context).save("group:1", new ContinuousUsageCycle(boot, wall, elapsed,
                60_000L, 0L, "app.b", "Main", "", 0L), storage.snapshot().usageStart("group:1").id);
        InterceptionDecision decision = new InterceptionDecision("app.a", "group:1",
                storage.snapshot().revisionOf("group:1"), InterceptionDecision.Kind.INTERRUPT,
                InterceptionDecision.Trigger.CONTINUOUS, storage.snapshot().level(), "", 60_000L, 60_000L,
                0L, 180_000L, 300_000L);
        new PendingInterceptionStore(context).saveRest(new PendingInterception(decision, "moved-rest", boot,
                wall, elapsed, 0L, wall, "group:1", 0L));
        RuleGroup independent = new RuleGroup(0L, "独立配置");
        independent.types.add(RuleType.DAILY_LIMIT);
        independent.dailyLimitMillis = 600_000L;
        independent.passDurationMillis = 180_000L;
        await(storage.saveApp("app.a", independent));
        await(storage.setLevel(ControlLevel.SEAL));
        assertEquals("app:app.a", storage.snapshot().effectivePolicyId("app.a"));
        assertEquals("group:1", storage.snapshot().effectivePolicyId("app.b"));
        assertBlocked(storage.setLevel(ControlLevel.REPAIR), true);
        assertBlocked(storage.removePolicy("group:1"), false);
    }

    /** 已完成休息不能解开另一条尚未到期的禁用；所有原约束到期后才恢复切换。 */
    @Test public void switchingWaitsForBothRestAndBanToResolve() throws Exception {
        RuleGroup policy = activeBan();
        policy.types.add(RuleType.CONTINUOUS);
        policy.continuousLimitMillis = 60_000L;
        policy.interruptionPauseMillis = 300_000L;
        await(storage.saveGroup(policy));
        await(storage.setLevel(ControlLevel.SEAL));
        long wall = System.currentTimeMillis(), elapsed = SystemClock.elapsedRealtime();
        String boot = BootIdentity.read(context);
        long revision = storage.snapshot().revisionOf("group:1");
        InterceptionEvaluation evaluated = RuleEngine.evaluate("app.a", "group:1", revision, policy,
                ControlLevel.SEAL, new UsageEvidence(false, wall, ZoneId.systemDefault().getId(),
                        0L, 0L, 0L, 0L, new HashMap<>()), 180_000L);
        PendingInterceptionStore pending = new PendingInterceptionStore(context);
        pending.save(new PendingInterception(evaluated.decision, "combined-ban", boot, wall, elapsed));
        pending.clearPresentation("combined-ban");
        InterceptionDecision rest = new InterceptionDecision("app.a", "group:1", revision,
                InterceptionDecision.Kind.INTERRUPT, InterceptionDecision.Trigger.CONTINUOUS,
                ControlLevel.SEAL, "", 60_000L, 60_000L, 0L, 180_000L, 300_000L);
        pending.saveRest(new PendingInterception(rest, "completed-rest", boot, wall, elapsed, 0L,
                wall, "group:1", 0L));
        new ContinuousUsageStore(context).save("group:1", new ContinuousUsageCycle(boot, wall, elapsed,
                0L, 300_000L, null, null, "", 1L), storage.snapshot().usageStart("group:1").id);
        assertBlocked(storage.setLevel(ControlLevel.REPAIR), true);
        // 原禁用已在当前检查前到期；当前仍活动但尚未再次触发的时段不延长原约束。
        InterceptionDecision expired = new InterceptionDecision("app.a", "group:1", revision,
                InterceptionDecision.Kind.BLOCK, InterceptionDecision.Trigger.WINDOW_BAN, ControlLevel.SEAL,
                evaluated.decision.windowId, 1L, 1L, wall - 1L, 180_000L, 0L);
        pending.saveBlock(new PendingInterception(expired, "combined-ban", boot, wall - 2L, elapsed));
        await(storage.setLevel(ControlLevel.REPAIR));
        assertEquals(ControlLevel.REPAIR, storage.snapshot().level());
    }

    /** 禁用事实不随展示清理消失；改归属后的新限制不覆盖原策略，逐项复查解除后才移除。 */
    @Test public void blockFactSurvivesPresentationCleanupAndRecreation() {
        PendingInterceptionStore pending = new PendingInterceptionStore(context);
        InterceptionDecision decision = new InterceptionDecision("app.a", "global", 1L,
                InterceptionDecision.Kind.BLOCK, InterceptionDecision.Trigger.DAILY_LIMIT,
                ControlLevel.SEAL, "", 60_000L, 60_000L, 120_000L, 180_000L, 0L);
        PendingInterception block = new PendingInterception(decision, "block", "boot", 1_000L, 1_000L);
        pending.save(block);
        InterceptionDecision reassigned = new InterceptionDecision("app.a", "app:app.a", 1L,
                InterceptionDecision.Kind.BLOCK, InterceptionDecision.Trigger.DAILY_LIMIT,
                ControlLevel.REPAIR, "", 60_000L, 60_000L, 130_000L, 180_000L, 0L);
        PendingInterception other = new PendingInterception(reassigned, "other-block", "boot", 2_000L, 2_000L);
        pending.saveBlock(other);
        pending.clearPresentation(block.sessionId);
        PendingInterceptionStore reopened = new PendingInterceptionStore(context);
        assertNull(reopened.read("app.a"));
        assertEquals(2, reopened.restrictions().size());
        reopened.clearBlock(block);
        assertEquals(1, reopened.restrictions().size());
        reopened.clearBlock(other);
        assertTrue(reopened.restrictions().isEmpty());
    }

    /** 进程重建后保留页面身份；缺失必需字段时拒绝损坏记录。 */
    @Test public void cycleStorageKeepsActivityIdentityAndRejectsIncompleteRows() {
        ContinuousUsageStore cycles = new ContinuousUsageStore(context);
        String activity = "github.vhoom.zhijian.ui.interception.InterceptionActivity";
        cycles.save("page", new ContinuousUsageCycle("boot", 10_000L, 10_000L,
                60_000L, 2_000L, context.getPackageName(), activity, "", 0L));
        assertEquals(activity, new ContinuousUsageStore(context).read("page").foregroundClass);
        context.getSharedPreferences("continuous_usage_cycles", Context.MODE_PRIVATE).edit()
                .putString("incomplete", "{\"boot\":\"boot\",\"wall\":1000,\"elapsed\":1000,\"used\":60000,"
                        + "\"rest\":2000,\"foreground\":null,\"resume\":\"\",\"generation\":0}").commit();
        try {
            cycles.read("incomplete");
            fail("Missing activity identity must be rejected");
        } catch (IllegalStateException expected) { }
        cycles.save("unknown", new ContinuousUsageCycle("boot", 10_000L, 10_000L,
                60_000L, 2_000L, null, null, "", 0L));
        assertNull(cycles.read("unknown").foregroundClass);
        assertEquals(2_000L, cycles.read("unknown").restMs);
    }

    /** 当前起点只接受对应周期；另一安装的旧累计不能覆盖新起点，也不能被只读检查删除。 */
    @Test public void cycleTrackingIdentityIsolatesRestoredUsageWithoutDeletingIt() {
        ContinuousUsageStore cycles = new ContinuousUsageStore(context);
        cycles.save("identity", new ContinuousUsageCycle("boot", 10_000L, 10_000L,
                60_000L, 2_000L, "app.a", "Main", "", 3L), "old-installation:tracking");
        ContinuousUsageStore restored = new ContinuousUsageStore(context);
        assertNull(restored.read("identity", "new-installation:tracking"));
        assertEquals(60_000L, restored.read("identity", "old-installation:tracking").usedMs);
        assertEquals(3L, restored.read("identity").generation);
        cycles.save("identity", new ContinuousUsageCycle("boot", 20_000L, 20_000L,
                5_000L, 0L, "app.a", "Main", "", 0L), "new-installation:tracking");
        assertNull(restored.read("identity", "old-installation:tracking"));
        assertEquals(5_000L, restored.read("identity", "new-installation:tracking").usedMs);
    }

    /** 关闭整组会取消原休息，立即重新启用也不能恢复旧要求或旧展示。 */
    @Test public void disablingAndReenablingGroupDoesNotRestoreCancelledRest() throws Exception {
        RuleGroup policy = reminder();
        await(storage.saveGroup(policy));
        PendingInterceptionStore pending = new PendingInterceptionStore(context);
        InterceptionDecision decision = new InterceptionDecision("app.a", "group:1",
                storage.snapshot().revisionOf("group:1"), InterceptionDecision.Kind.INTERRUPT,
                InterceptionDecision.Trigger.CONTINUOUS, storage.snapshot().level(), "", 60_000L, 60_000L,
                0L, 180_000L, 300_000L);
        PendingInterception rest = new PendingInterception(decision, "cancelled-rest", "boot", 1_000L, 1_000L);
        pending.saveRest(rest);
        pending.save(new PendingInterception(decision, "cancelled-page", "boot", 1_000L, 1_000L));
        policy.enabled = false;
        await(storage.saveGroup(policy));
        assertTrue(pending.rests("group:1").isEmpty());
        assertNull(pending.read("app.a"));
        policy.enabled = true;
        await(storage.saveGroup(policy));
        assertTrue(new PendingInterceptionStore(context).rests("group:1").isEmpty());
    }

    /** 上次关闭提交后即使残留旧事实，本次启用前仍按关闭快照清理。 */
    @Test public void reenablingCleansFactsLeftAfterAnInterruptedCancellation() throws Exception {
        RuleGroup policy = reminder();
        policy.enabled = false;
        await(storage.saveGroup(policy));
        InterceptionDecision decision = new InterceptionDecision("app.a", "group:1", 1L,
                InterceptionDecision.Kind.INTERRUPT, InterceptionDecision.Trigger.CONTINUOUS,
                ControlLevel.MORTAL, "", 60_000L, 60_000L, 0L, 180_000L, 300_000L);
        new PendingInterceptionStore(context).saveRest(new PendingInterception(
                decision, "interrupted-cancellation", "boot", 1_000L, 1_000L));
        policy.enabled = true;
        await(storage.saveGroup(policy));
        assertTrue(new PendingInterceptionStore(context).rests("group:1").isEmpty());
    }

    /** @return 两个测试成员共用的一分钟提醒与五分钟休息 */
    private static RuleGroup reminder() {
        RuleGroup policy = new RuleGroup(1L, "测试组");
        policy.apps.addAll(Arrays.asList("app.a", "app.b"));
        policy.types.add(RuleType.CONTINUOUS);
        policy.continuousLimitMillis = 60_000L;
        policy.interruptionPauseMillis = 300_000L;
        policy.passDurationMillis = 180_000L;
        return policy;
    }

    /** @return 当前必定处于禁用窗口内的测试组，避免依赖设备使用历史或用量授权 */
    private static RuleGroup activeBan() {
        RuleGroup policy = new RuleGroup(1L, "测试禁用组");
        policy.apps.addAll(Arrays.asList("app.a", "app.b"));
        policy.types.add(RuleType.WINDOW_BAN);
        LocalTime now = LocalTime.now();
        int minute = now.getHour() * 60 + now.getMinute();
        policy.windows.add(RuleWindow.create(RuleType.WINDOW_BAN,
                Math.floorMod(minute - 1, 1440), Math.floorMod(minute + 60, 1440)));
        policy.passDurationMillis = 180_000L;
        return policy;
    }

    /** @param task 待拒绝提交 @param levelChange 是否为切档拒绝 */
    private static void assertBlocked(CompletableFuture<Void> task, boolean levelChange) throws Exception {
        try { await(task); fail("Active retreat must reject the mutation"); }
        catch (ExecutionException expected) {
            assertTrue(expected.getCause() instanceof RetreatRestrictionException);
            assertEquals(levelChange, ((RetreatRestrictionException) expected.getCause()).levelChange);
        }
    }

    /** @param task 后台存储任务 @param <T> 结果类型 @return 有界等待后的实际结果 */
    private static <T> T await(CompletableFuture<T> task) throws Exception { return task.get(10, TimeUnit.SECONDS); }
}
