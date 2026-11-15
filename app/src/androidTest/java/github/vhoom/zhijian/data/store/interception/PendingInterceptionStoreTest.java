package github.vhoom.zhijian.data.store.interception;

import android.content.Context;
import android.os.SystemClock;
import android.app.AppOpsManager;
import android.app.UiAutomation;
import android.os.Process;
import android.os.ParcelFileDescriptor;
import java.io.InputStream;

import androidx.test.platform.app.InstrumentationRegistry;

import github.vhoom.zhijian.data.model.policy.ControlLevel;
import github.vhoom.zhijian.data.model.policy.RuleGroup;
import github.vhoom.zhijian.data.model.policy.RuleType;
import github.vhoom.zhijian.data.model.runtime.InterceptionRecord;
import github.vhoom.zhijian.data.model.runtime.PendingInterception;
import github.vhoom.zhijian.data.model.interception.InterceptionEffect;
import github.vhoom.zhijian.data.model.interception.InterceptionMode;
import github.vhoom.zhijian.data.model.settings.InteractionSettings;
import github.vhoom.zhijian.data.model.settings.InterceptionOptions;
import github.vhoom.zhijian.data.store.AppPolicyStore;
import github.vhoom.zhijian.data.store.ZhijianStorage;
import github.vhoom.zhijian.data.store.RuntimeStateStore;
import github.vhoom.zhijian.data.store.settings.InteractionSettingsStore;
import github.vhoom.zhijian.domain.interception.InterceptionDecision;
import github.vhoom.zhijian.runtime.interception.InterceptionRuntime;
import github.vhoom.zhijian.util.time.BootIdentity;
import github.vhoom.zhijian.data.store.usage.ContinuousUsageStore;
import github.vhoom.zhijian.domain.usage.ContinuousUsageCycle;
import github.vhoom.zhijian.ui.interception.session.InterceptionOutcomeWriter;
import github.vhoom.zhijian.util.config.AppConfig;

import org.junit.Test;

import java.util.UUID;

import static org.junit.Assert.*;

/**
 * 验证实际磁盘恢复、旧完成回调隔离及跨存储完成一致性。
 *
 * 维护日期：2026年11月15日
 */
public class PendingInterceptionStoreTest {
    /** 无为和修心关闭临时放行后，两种导航都保存提醒起点，不创建授权或清零真实用量。 */
    @Test public void reminderNavigationPersistsAcknowledgementWithoutGrantOrUsageReset() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ZhijianStorage.get(context).ready().join();
        for (ControlLevel level : new ControlLevel[]{ControlLevel.MORTAL, ControlLevel.REPAIR}) {
            for (InterceptionEffect effect : new InterceptionEffect[]{
                    InterceptionEffect.RETURN_HOME, InterceptionEffect.RETURN_TO_TARGET}) {
                assertReminderNavigation(context, level, effect);
            }
        }
    }

    /** @param context 已就绪的测试上下文 @param level 可交互强度 @param effect 本次导航结果 */
    private static void assertReminderNavigation(Context context, ControlLevel level, InterceptionEffect effect) {
        String policy = "test.ack." + UUID.randomUUID();
        String target = policy + ".app";
        PendingInterception seed = pending(context, target, policy, 1L, level);
        PendingInterception rest = new PendingInterception(seed.decision, UUID.randomUUID().toString(),
                seed.bootId, seed.startedAtMs, seed.startedElapsedMs, 2L, seed.occurredAtMs, policy, 0L);
        PendingInterception shown = new PendingInterception(seed.decision, UUID.randomUUID().toString(),
                seed.bootId, seed.startedAtMs, seed.startedElapsedMs, 2L, seed.occurredAtMs, policy, 0L);
        PendingInterceptionStore store = new PendingInterceptionStore(context);
        ContinuousUsageStore cycles = new ContinuousUsageStore(context);
        try {
            store.saveRest(rest);
            store.save(shown);
            cycles.save(policy, new ContinuousUsageCycle(seed.bootId, seed.startedAtMs,
                    seed.startedElapsedMs, 65_000L, 0L, target, "", 2L));
            new InterceptionOutcomeWriter(context).save(shown.decision, shown.sessionId, shown.occurredAtMs,
                    null, directSettings(context), effect).join();
            PendingInterceptionStore recreated = new PendingInterceptionStore(context);
            assertNull(recreated.read(target));
            PendingInterception restored = recreated.restFor(rest);
            assertNotNull(restored);
            assertEquals(65_000L, restored.acknowledgedUsageMs);
            assertEquals(rest.sessionId, restored.sessionId);
            assertEquals(30_000L, restored.decision.minimumInterruptionPauseDurationMs);
            assertEquals(65_000L, cycles.read(policy).usedMs);
            assertEquals(0L, cycles.read(policy).restMs);
            RuntimeStateStore runtime = new RuntimeStateStore(context);
            assertNull(runtime.grantFor(target));
            assertNull(runtime.checkpoint(RuntimeStateStore.continuousResumeKey(policy)));
            recreated.acknowledgeReminder(shown, 90_000L);
            assertEquals(65_000L, recreated.restFor(rest).acknowledgedUsageMs);
        } finally {
            store.clear(target, rest.sessionId);
            store.clearPresentation(shown.sessionId);
            context.getSharedPreferences("continuous_usage_cycles", Context.MODE_PRIVATE).edit().remove(policy).commit();
        }
    }

    /** 禁用页面的直接返回只清展示，原禁用事实仍在且没有任何放行期限。 */
    @Test public void directReturnKeepsBlockRestrictionsWithoutGrant() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ZhijianStorage.get(context).ready().join();
        for (ControlLevel level : new ControlLevel[]{ControlLevel.MORTAL, ControlLevel.REPAIR}) {
            String target = "test.no-pass." + UUID.randomUUID();
            long wall = System.currentTimeMillis();
            InterceptionDecision decision = new InterceptionDecision(target, target, 1L,
                    InterceptionDecision.Kind.BLOCK, InterceptionDecision.Trigger.DAILY_COUNT, level,
                    "", 2L, 1L, wall + 60_000L, 180_000L, 0L);
            PendingInterception shown = new PendingInterception(decision, UUID.randomUUID().toString(),
                    BootIdentity.read(context), wall, SystemClock.elapsedRealtime(), 0L);
            PendingInterceptionStore pending = new PendingInterceptionStore(context);
            try {
                pending.save(shown);
                pending.saveBlock(shown);
                new InterceptionOutcomeWriter(context).save(decision, shown.sessionId, wall,
                        null, directSettings(context), InterceptionEffect.RETURN_TO_TARGET).join();
                assertNull(pending.read(target));
                assertTrue(pending.restrictions().stream().anyMatch(value -> value.sessionId.equals(shown.sessionId)));
                assertNull(new RuntimeStateStore(context).grantFor(target));
            } finally {
                pending.clearPresentation(shown.sessionId);
                pending.clearBlock(shown);
            }
        }
    }

    /** 写入器必须拒绝与会话开关矛盾的放行请求，不能只依赖页面隐藏按钮。 */
    @Test public void disabledTemporaryPassRejectsGrantBeforeWriting() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ZhijianStorage.get(context).ready().join();
        PendingInterception shown = pending(context, "test.reject-pass", "test.reject-policy", 1L, ControlLevel.REPAIR);
        try {
            new InterceptionOutcomeWriter(context).save(shown.decision, shown.sessionId, shown.occurredAtMs,
                    null, directSettings(context), InterceptionEffect.ISSUE_GRANT);
            fail("Disabled temporary pass cannot issue a grant");
        } catch (IllegalArgumentException expected) {
            assertNull(new RuntimeStateStore(context).grantFor(shown.decision.packageName));
        }
    }

    /** @param context 参数资源上下文 @return 关闭两项流程开关的独立会话快照，不修改实际设置 */
    private static InteractionSettings directSettings(Context context) {
        InteractionSettings current = new InteractionSettingsStore(context, new AppConfig(context)).read(InterceptionMode.REPAIR);
        return new InteractionSettings(current.enabledChallenges, current.holdRange, current.word, new InterceptionOptions(false, false));
    }

    /** 已经被新页面替代的回调不能更新新提醒的起点或清除新展示。 */
    @Test public void stalePresentationCannotAcknowledgeANewerReminder() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String policy = "test.stale-ack." + UUID.randomUUID();
        String target = policy + ".app";
        PendingInterception old = pending(context, target, policy, 1L, ControlLevel.MORTAL);
        PendingInterception current = pending(context, target, policy, 1L, ControlLevel.MORTAL);
        PendingInterceptionStore store = new PendingInterceptionStore(context);
        try {
            store.saveRest(current);
            store.save(current);
            store.acknowledgeReminder(old, 90_000L);
            assertEquals(-1L, store.restFor(current).acknowledgedUsageMs);
            assertEquals(current.sessionId, store.read(target).sessionId);
        } finally { store.clear(target, current.sessionId); }
    }

    /** 临时放行只清展示，重建存储后仍保留原休息与独立周期身份。 */
    @Test public void temporaryGrantKeepsPersistentRestAfterPresentationCleared() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String policy = "test.rest." + UUID.randomUUID();
        PendingInterceptionStore store = new PendingInterceptionStore(context);
        InterceptionDecision decision = new InterceptionDecision("test.rest-app", policy, 1L,
                InterceptionDecision.Kind.INTERRUPT, InterceptionDecision.Trigger.WINDOW_CONTINUOUS,
                ControlLevel.REPAIR, "evening", 600_000L, 600_000L, 0L, 180_000L, 300_000L);
        PendingInterception rest = new PendingInterception(decision, UUID.randomUUID().toString(), "boot",
                1_000L, 1_000L, 2L, 1_000L, "original-cycle", 500L);
        try {
            store.saveRest(rest);
            store.save(rest);
            store.clearPresentation(rest.sessionId);
            PendingInterceptionStore recreated = new PendingInterceptionStore(context);
            assertNull(recreated.read(decision.packageName));
            assertEquals(1, recreated.rests(policy).size());
            assertEquals("original-cycle", recreated.rests(policy).get(0).continuousKey);
            assertEquals(500L, recreated.rests(policy).get(0).continuousFloorMs);
            assertEquals(300_000L, recreated.rests(policy).get(0).decision.minimumInterruptionPauseDurationMs);
        } finally { store.clear(decision.packageName, rest.sessionId); }
        assertTrue(store.rests(policy).isEmpty());
    }
    /** 同组不同应用重建请求沿用首次文案序号，新触发的轮换不会影响旧会话。 */
    @Test public void sharedSessionKeepsCopyVariantAcrossTargetsAndStoreRecreation() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PendingInterceptionStore store = new PendingInterceptionStore(context);
        String a = "test.copy-a", b = "test.copy-b";
        PendingInterception pending = pending(context, a, "test.copy-policy", 1L, ControlLevel.REPAIR);
        try {
            store.save(pending);
            int variant = store.copyVariantForSession(pending.sessionId);
            assertTrue(variant >= 0);
            store.save(pending.forTarget(b));
            InterceptionCopyStore.next(context);
            PendingInterceptionStore recreated = new PendingInterceptionStore(context);
            assertEquals(variant, recreated.copyVariantForSession(pending.sessionId));
            assertEquals(variant, github.vhoom.zhijian.ui.interception.InterceptionActivity.intent(context,
                    recreated.read(b), "测试应用").getIntExtra("interception.copy_variant", -1));
        } finally { store.clear(a, pending.sessionId); }
        assertNull(store.read(a));
        assertNull(store.read(b));
    }
    /** 管控档位已经变化的旧页面不能再提交恢复或签发临时放行。 */
    @Test public void staleControlLevelCannotResumeOrIssueGrant() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ZhijianStorage storage = ZhijianStorage.get(context);
        storage.ready().join();
        String pkg = "test.stale-level." + UUID.randomUUID();
        String policyId = storage.snapshot().effectivePolicyId(pkg);
        String stale = storage.snapshot().level() == ControlLevel.REPAIR ? ControlLevel.MORTAL.code : ControlLevel.REPAIR.code;
        long wall = System.currentTimeMillis(), elapsed = SystemClock.elapsedRealtime();
        InterceptionRecord resume = new InterceptionRecord(UUID.randomUUID().toString(), pkg, policyId,
                storage.snapshot().revisionOf(policyId), "interrupt", stale, "continuous", "none", "allowed", wall, wall);
        RuntimeStateStore runtime = new RuntimeStateStore(context);
        try {
            runtime.recordInterruptionResume(resume, BootIdentity.read(context), elapsed).join();
            fail("Stale control level must not complete rest");
        } catch (java.util.concurrent.CompletionException expected) {
            assertTrue(expected.getCause() instanceof IllegalArgumentException);
        }
        github.vhoom.zhijian.data.model.runtime.PassGrant grant = new github.vhoom.zhijian.data.model.runtime.PassGrant(
                pkg, policyId, resume.policyRevision, BootIdentity.read(context), wall, 60_000L, elapsed + 60_000L);
        InterceptionRecord pass = new InterceptionRecord(UUID.randomUUID().toString(), pkg, policyId,
                resume.policyRevision, "block", stale, "daily_limit", "none", "allowed", wall, wall);
        try {
            runtime.recordInterception(pass, grant).join();
            fail("Stale control level must not issue a grant");
        } catch (java.util.concurrent.CompletionException expected) {
            assertTrue(expected.getCause() instanceof IllegalArgumentException);
        }
        assertNull(runtime.grantFor(pkg));
        assertFalse(runtime.wasAllowed(resume.id).join());
        assertFalse(runtime.wasAllowed(pass.id).join());
    }
    /** 已验证足额离组休息的待办能直接恢复，保留新周期已有使用，不重复写主动恢复起点。 */
    @Test
    public void verifiedRestResumesOnNewRuntimeWithoutButton() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ZhijianStorage storage = ZhijianStorage.get(context);
        storage.ready().join();
        String target = "test.expired-rest." + UUID.randomUUID();
        AppPolicyStore policies = new AppPolicyStore(context);
        PendingInterceptionStore store = new PendingInterceptionStore(context);
        RuleGroup policy = new RuleGroup(0L, "");
        policy.types.add(RuleType.CONTINUOUS);
        policy.continuousLimitMillis = 60_000L;
        policy.interruptionPauseMillis = 30_000L;
        policy.passDurationMillis = 60_000L;
        policies.save(target, policy).join();
        String policyId = storage.snapshot().effectivePolicyId(target);
        PendingInterception recent = pending(context, target, policyId,
                storage.snapshot().revisionOf(policyId), storage.snapshot().level());
        // 休息事实发生于本次生效之后；足额休息由新代次周期证明，不使用启用前的历史。
        PendingInterception expired = new PendingInterception(recent.decision, recent.sessionId, recent.bootId,
                recent.startedAtMs, recent.startedElapsedMs, 0L);
        UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        AppOpsManager operations = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        int oldUsage = operations.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), context.getPackageName());
        usageMode(automation, context, "allow");
        new ContinuousUsageStore(context).save(policyId, new ContinuousUsageCycle(BootIdentity.read(context),
                System.currentTimeMillis(), SystemClock.elapsedRealtime(), 0L, 30_000L, null, "", 1L),
                storage.snapshot().usageStart(policyId).id);
        try {
            store.saveRest(expired);
            store.save(expired);
            assertNull(new InterceptionRuntime(context).evaluate(target).decision);
            assertNull(store.read(target));
            RuntimeStateStore runtime = new RuntimeStateStore(context);
            assertTrue(runtime.wasAllowed(expired.sessionId).join());
            assertNull(runtime.grantFor(target));
            String key = RuntimeStateStore.interruptionResumeKey(target);
            assertNull(runtime.checkpoint(key));
        } finally {
            store.clear(target, expired.sessionId);
            policies.reset(target).join();
            context.getSharedPreferences("continuous_usage_cycles", Context.MODE_PRIVATE).edit().remove(policyId).commit();
            usageMode(automation, context, oldUsage == AppOpsManager.MODE_ALLOWED ? "allow"
                    : oldUsage == AppOpsManager.MODE_IGNORED ? "ignore"
                    : oldUsage == AppOpsManager.MODE_ERRORED ? "deny" : "default");
        }
    }

    /** @param automation 测试设备控制 @param context 目标应用 @param mode 需恢复或临时授权的模式 */
    private static void usageMode(UiAutomation automation, Context context, String mode) throws Exception {
        try (InputStream output = new ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(
                "appops set " + context.getPackageName() + " GET_USAGE_STATS " + mode))) {
            byte[] buffer = new byte[256];
            while (output.read(buffer) != -1) { /* 等待授权提交。 */ }
        }
    }
    /** 新存储实例能够读取全部计时信息，旧回调不能删掉较新的会话。 */
    @Test
    public void storedSessionSurvivesNewReaderAndOldCleanup() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String target = "test.pending-store";
        PendingInterceptionStore store = new PendingInterceptionStore(context);
        PendingInterception pending = pending(context, target, "policy", 1L, ControlLevel.REPAIR);
        try {
            store.save(pending);
            PendingInterception restored = new PendingInterceptionStore(context).read(target);
            assertNotNull(restored);
            assertEquals(pending.sessionId, restored.sessionId);
            assertEquals(pending.startedAtMs, restored.startedAtMs);
            assertEquals(pending.startedElapsedMs, restored.startedElapsedMs);
            assertEquals(pending.decision.minimumInterruptionPauseDurationMs,
                    restored.decision.minimumInterruptionPauseDurationMs);
            assertEquals(pending.decision.trigger, restored.decision.trigger);
            store.clear(target, "older-session");
            assertNotNull(store.read(target));
        } finally {
            store.clear(target, pending.sessionId);
        }
        assertNull(store.read(target));
    }

    /** 结果已提交而待办尚未清理时，运行入口恢复提交事实并开始新连续周期。 */
    @Test
    public void committedResumeReconcilesLeftoverPendingSession() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ZhijianStorage storage = ZhijianStorage.get(context);
        storage.ready().join();
        String target = "test.pending-resume." + UUID.randomUUID();
        AppPolicyStore policies = new AppPolicyStore(context);
        PendingInterceptionStore store = new PendingInterceptionStore(context);
        RuleGroup policy = new RuleGroup(0L, "");
        policy.types.add(RuleType.CONTINUOUS);
        policy.continuousLimitMillis = 60_000L;
        policy.interruptionPauseMillis = 30_000L;
        policy.passDurationMillis = 60_000L;
        policies.save(target, policy).join();
        String policyId = storage.snapshot().effectivePolicyId(target);
        PendingInterception pending = pending(context, target, policyId,
                storage.snapshot().revisionOf(policyId), storage.snapshot().level());
        try {
            store.saveRest(pending);
            store.save(pending);
            assertEquals(pending.sessionId, new InterceptionRuntime(context).evaluate(target).pending.sessionId);
            InterceptionRecord allowed = new InterceptionRecord(pending.sessionId, target, policyId,
                    pending.decision.policyRevision, "interrupt", pending.decision.level.code,
                    "continuous", "button_hold_1", "allowed", pending.startedAtMs, System.currentTimeMillis());
            new RuntimeStateStore(context).recordInterruptionResume(allowed,
                    BootIdentity.read(context), SystemClock.elapsedRealtime()).join();
            assertNull(new InterceptionRuntime(context).evaluate(target).decision);
            assertNull(store.read(target));
        } finally {
            store.clear(target, pending.sessionId);
            policies.reset(target).join();
        }
    }

    /** 无法解析的待办不进入页面，且只移除对应应用的损坏条目。 */
    @Test
    public void corruptedEntryIsDiscarded() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String target = "test.pending-corrupted";
        context.getSharedPreferences("pending_interceptions", Context.MODE_PRIVATE)
                .edit().putString(target, "{broken").commit();
        assertNull(new PendingInterceptionStore(context).read(target));
        assertFalse(context.getSharedPreferences("pending_interceptions", Context.MODE_PRIVATE).contains(target));
    }

    /**
     * 为真实磁盘回归创建独立会话。
     *
     * @param context 测试应用上下文
     * @param target 独立目标包名
     * @param policyId 当前有效策略身份
     * @param revision 当前有效策略修订
     * @param level 当前有效档位
     */
    private static PendingInterception pending(Context context, String target, String policyId,
            long revision, ControlLevel level) {
        InterceptionDecision decision = new InterceptionDecision(target, policyId, revision,
                InterceptionDecision.Kind.INTERRUPT, InterceptionDecision.Trigger.CONTINUOUS,
                level, "", 60_000L, 60_000L, 0L, 60_000L, 30_000L);
        return new PendingInterception(decision, UUID.randomUUID().toString(),
                BootIdentity.read(context), System.currentTimeMillis(), SystemClock.elapsedRealtime());
    }
}
