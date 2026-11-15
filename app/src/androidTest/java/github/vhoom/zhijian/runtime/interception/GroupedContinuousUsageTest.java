package github.vhoom.zhijian.runtime.interception;

import android.app.AppOpsManager;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import github.vhoom.zhijian.data.model.policy.RuleGroup;
import github.vhoom.zhijian.data.model.policy.RuleType;
import github.vhoom.zhijian.data.model.policy.RuleWindow;
import github.vhoom.zhijian.data.model.runtime.PendingInterception;
import github.vhoom.zhijian.data.source.UsageEvidenceReader;
import github.vhoom.zhijian.data.source.HomePackageSource;
import github.vhoom.zhijian.data.store.AppPolicyStore;
import github.vhoom.zhijian.data.store.ZhijianStorage;
import github.vhoom.zhijian.data.store.RuleGroupStore;
import github.vhoom.zhijian.data.store.interception.PendingInterceptionStore;
import github.vhoom.zhijian.data.store.usage.ContinuousUsageStore;
import github.vhoom.zhijian.domain.interception.InterceptionDecision;
import github.vhoom.zhijian.domain.interception.InterceptionEvaluation;
import github.vhoom.zhijian.domain.usage.ContinuousUsageCycle;
import github.vhoom.zhijian.fixture.FixtureCleanup;
import github.vhoom.zhijian.util.time.BootIdentity;
import org.junit.Test;
import org.junit.BeforeClass;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import static org.junit.Assert.*;

/**
 * 在隔离模拟器用真实 UsageEvents 验证同组累计、共享休息和跨组独立使用。
 *
 * 维护日期：2026年11月15日
 */
public class GroupedContinuousUsageTest {
    /** 辅助应用必须可启动、未配置独立或组规则，且不能被系统识别为桌面。 */
    private static final List<String> TEST_PEER_PACKAGES = Arrays.asList(
            "com.android.settings", "com.oneplus.calculator",
            "com.android.calculator2", "com.google.android.calculator");

    /** 显式传入旧测试组 id 时恢复失败清理；校验测试成员后仅删除指定组的测试事实。 */
    @BeforeClass public static void recoverRequestedTestGroups() throws Exception {
        String requested = InstrumentationRegistry.getArguments().getString("groupCleanupIds", "");
        if (requested.isEmpty()) return;
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String target = InstrumentationRegistry.getInstrumentation().getContext().getPackageName();
        ZhijianStorage storage = ZhijianStorage.get(context);
        storage.ready().join();
        RuleGroupStore groups = new RuleGroupStore(context);
        FixtureCleanup recovery = new FixtureCleanup();
        for (String value : requested.split(",")) {
            RuleGroup group = storage.snapshot().group(Long.parseLong(value.trim()));
            if (group == null) continue;
            assertEquals("Recovery is restricted to the two test members", 2, group.apps.size());
            assertTrue(group.apps.contains(target));
            for (String pkg : group.apps) {
                if (!target.equals(pkg)) assertTrue(TEST_PEER_PACKAGES.contains(pkg));
            }
            recovery.add(() -> clearTestGroupRestrictions(context, "group:" + group.id));
            recovery.add(() -> groups.remove(group.id).join());
        }
        recovery.close();
    }

    /**
     * 删除指定测试组的禁用与休息事实，避免闭关保护阻止夹具恢复配置。
     * @param context 测试应用上下文
     * @param policyId 本次创建或显式指定恢复的测试组策略键
     */
    private static void clearTestGroupRestrictions(Context context, String policyId) throws Exception {
        PendingInterceptionStore pending = new PendingInterceptionStore(context);
        FixtureCleanup facts = new FixtureCleanup();
        for (PendingInterception fact : pending.restrictions()) {
            if (!policyId.equals(fact.decision.policyId)) continue;
            facts.add(() -> pending.clearBlock(fact));
            facts.add(() -> pending.clear(fact.decision.packageName, fact.sessionId));
        }
        facts.close();
    }

    /**
     * 选择不受现有规则及 HOME 分类干扰的辅助应用，适配包含 FallbackHome 的设置包。
     * @param context 用于包管理器查询和已提交配置读取的测试上下文
     * @return 可以真实启动的非桌面辅助应用包名
     */
    private static String testPeerPackage(Context context) {
        Set<String> homes = HomePackageSource.read(context.getPackageManager());
        for (String pkg : TEST_PEER_PACKAGES) {
            if (homes.contains(pkg) || !"global".equals(
                    ZhijianStorage.get(context).snapshot().effectivePolicyId(pkg))) continue;
            Intent launch = context.getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch != null && launch.getComponent() != null) return pkg;
        }
        throw new AssertionError("No unconfigured, non-HOME Settings or calculator app is available");
    }

    /** 每日限时必须合计两应用，即使当前应用自己的用量没有达到组限额。 */
    @Test public void dailyDurationIsSharedAcrossGroupMembers() throws Exception {
        assertSharedTimedLimit(RuleType.DAILY_LIMIT);
    }

    /** 每段时段限时分别合计组内用量，不能切换到用量较少的组员绕过。 */
    @Test public void windowDurationIsSharedAcrossGroupMembers() throws Exception {
        assertSharedTimedLimit(RuleType.WINDOW_LIMIT);
    }

    /**
     * 使用历史基线构造有效的一位小数分钟额度，让两应用分别不足而合计超限。
     * @param type 每日限时或时段限时
     */
    private static void assertSharedTimedLimit(RuleType type) throws Exception {
        try (DeviceSession device = new DeviceSession()) {
            RuleGroup group = device.groups.create();
            group.types.add(type);
            java.time.LocalTime nowTime = java.time.LocalTime.now();
            int minute = nowTime.getHour() * 60 + nowTime.getMinute();
            RuleWindow window = new RuleWindow("shared-time", RuleType.WINDOW_LIMIT,
                    Math.floorMod(minute - 1, 1440), Math.floorMod(minute + 60, 1440), 6_000L);
            if (type == RuleType.WINDOW_LIMIT) group.windows.add(window);
            UsageEvidenceReader reader = new UsageEvidenceReader(device.context);
            long now = System.currentTimeMillis();
            int reset = new github.vhoom.zhijian.data.store.settings.GlobalSettings(device.context,
                    new github.vhoom.zhijian.util.config.AppConfig(device.context)).dailyResetMinute();
            github.vhoom.zhijian.domain.interception.UsageEvidence a = reader.read(device.a, group, now, 0L, reset);
            github.vhoom.zhijian.domain.interception.UsageEvidence b = reader.read(device.b, group, now, 0L, reset);
            long baseline = type == RuleType.DAILY_LIMIT ? a.appForegroundMs + b.appForegroundMs
                    : a.windowForegroundMs(window.id) + b.windowForegroundMs(window.id);
            long limit = (baseline / 6_000L + 2L) * 6_000L;
            if (type == RuleType.DAILY_LIMIT) group.dailyLimitMillis = limit;
            else window.limitDurationMillis = limit;
            group.apps.addAll(Arrays.asList(device.a, device.b));
            device.created.add(group);
            device.groups.save(group).join();
            long eachDuration = (limit - baseline) / 2L + 600L;
            device.open(device.a);
            SystemClock.sleep(eachDuration);
            device.open(device.b);
            SystemClock.sleep(eachDuration);
            long end = System.currentTimeMillis();
            github.vhoom.zhijian.domain.interception.UsageEvidence current = reader.read(device.b, group, end, 0L, reset);
            assertTrue("Current member alone must still be below the group limit",
                    (type == RuleType.DAILY_LIMIT ? current.appForegroundMs
                            : current.windowForegroundMs(window.id)) < limit);
            InterceptionEvaluation result = new InterceptionRuntime(device.context).evaluate(device.b, device.observedAtMs);
            assertNotNull("Combined foreground duration must consume the group limit", result.decision);
            assertEquals(type == RuleType.DAILY_LIMIT ? InterceptionDecision.Trigger.DAILY_LIMIT
                    : InterceptionDecision.Trigger.WINDOW_LIMIT, result.decision.trigger);
        }
    }

    /** 组内合计到达每日次数仍允许使用，再打开一次才拦截当前应用。 */
    @Test public void dailyCountIsSharedAcrossGroupMembers() throws Exception {
        try (DeviceSession device = new DeviceSession()) {
            RuleGroup group = device.groups.create();
            group.types.add(RuleType.DAILY_COUNT);
            UsageEvidenceReader reader = new UsageEvidenceReader(device.context);
            long now = System.currentTimeMillis();
            int reset = new github.vhoom.zhijian.data.store.settings.GlobalSettings(device.context,
                    new github.vhoom.zhijian.util.config.AppConfig(device.context)).dailyResetMinute();
            group.count = Math.toIntExact(reader.read(device.a, group, now, 0L, reset).openCount
                    + reader.read(device.b, group, now, 0L, reset).openCount + 2L);
            group.apps.addAll(Arrays.asList(device.a, device.b));
            device.created.add(group);
            device.groups.save(group).join();
            device.open(device.a);
            assertNull(new InterceptionRuntime(device.context).evaluate(device.a, device.observedAtMs).decision);
            device.open(device.b);
            assertNull(new InterceptionRuntime(device.context).evaluate(device.b, device.observedAtMs).decision);
            device.open(device.a);
            InterceptionEvaluation result = new InterceptionRuntime(device.context).evaluate(device.a, device.observedAtMs);
            assertNotNull("Exceeding the shared count must block the current member", result.decision);
            assertEquals(InterceptionDecision.Trigger.DAILY_COUNT, result.decision.trigger);
        }
    }

    /** 重开、换同组应用、重建运行对象都不能清零；足额离组后才恢复。 */
    @Test public void groupSharesUsageAndPendingRestAcrossAppsAndNewRuntime() throws Exception {
        try (DeviceSession device = new DeviceSession()) {
            String group = device.createGroup(6_000L, 6_000L, device.a, device.b);
            device.open(device.a);
            SystemClock.sleep(1_600L);
            new InterceptionRuntime(device.context).evaluate(device.a, device.observedAtMs);
            long first = device.cycle(group).usedMs;
            assertTrue("First real foreground interval must be counted", first >= 1_000L);
            device.open(device.b);
            SystemClock.sleep(1_600L);
            new InterceptionRuntime(device.context).evaluate(device.b, device.observedAtMs);
            long shared = device.cycle(group).usedMs;
            assertTrue("Switching within one group must add rather than reset", shared >= first + 1_000L);
            device.open(device.a);
            SystemClock.sleep(Math.max(500L, 6_000L - shared + 500L));
            InterceptionEvaluation firstBlock = new InterceptionRuntime(device.context).evaluate(device.a, device.observedAtMs);
            assertNotNull(firstBlock.pending);
            device.open(device.b);
            InterceptionEvaluation reentry = new InterceptionRuntime(device.context).evaluate(device.b, device.observedAtMs);
            assertNotNull(reentry.pending);
            assertEquals(firstBlock.pending.sessionId, reentry.pending.sessionId);
            assertEquals(device.b, reentry.decision.packageName);
            assertEquals(InterceptionDecision.Trigger.CONTINUOUS, reentry.decision.trigger);
            assertTrue("Premature group reentry restarts a full rest",
                    reentry.pending.remainingPauseMs(BootIdentity.read(device.context),
                            System.currentTimeMillis(), SystemClock.elapsedRealtime()) >= 5_500L);
            device.home();
            SystemClock.sleep(6_500L);
            device.open(device.b);
            InterceptionEvaluation rested = new InterceptionRuntime(device.context).evaluate(device.b, device.observedAtMs);
            assertNull("Actual time outside the whole group permits a new cycle", rested.decision);
            assertTrue(device.cycle(group).usedMs < 2_000L);
            assertNull(new PendingInterceptionStore(device.context).read(device.a));
            assertNull(new PendingInterceptionStore(device.context).read(device.b));
        }
    }

    /** A 被提醒休息时，独立组 B 正常使用；B 的使用时间同时算 A 的离组休息。 */
    @Test public void anotherGroupCanBeUsedWhileFirstGroupRests() throws Exception {
        try (DeviceSession device = new DeviceSession()) {
            // 普通组至少两个成员；占位成员不启动，两个组的真实前台目标仍相互独立。
            String aGroup = device.createGroup(6_000L, 6_000L, device.a, device.a + ".unused");
            String bGroup = device.createGroup(30_000L, 6_000L, device.b, device.b + ".unused");
            device.open(device.a);
            SystemClock.sleep(6_500L);
            assertNotNull(new InterceptionRuntime(device.context).evaluate(device.a, device.observedAtMs).pending);
            device.open(device.b);
            SystemClock.sleep(6_500L);
            assertNull(new InterceptionRuntime(device.context).evaluate(device.b, device.observedAtMs).decision);
            assertTrue(device.cycle(bGroup).usedMs >= 6_000L);
            device.open(device.a);
            assertNull(new InterceptionRuntime(device.context).evaluate(device.a, device.observedAtMs).decision);
            assertTrue(device.cycle(aGroup).usedMs < 1_000L);
        }
    }

    /** 保存测试授权与应用覆盖策略，退出时还原，并清理仅由本测试创建的组。 */
    private static final class DeviceSession implements AutoCloseable {
        private final Context context;
        private final UiAutomation automation;
        private final String a, b;
        private final AppPolicyStore apps;
        private final RuleGroupStore groups;
        private final RuleGroup originalA, originalB;
        private final int oldUsageMode;
        private final List<RuleGroup> created = new ArrayList<>();
        private final FixtureCleanup cleanup = new FixtureCleanup();
        private long observedAtMs;

        /** 初始化真实 UsageEvents 的访问，并暂停自动弹页以单独验证运行入口。 */
        private DeviceSession() throws Exception {
            Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
            context = instrumentation.getTargetContext();
            a = instrumentation.getContext().getPackageName();
            automation = instrumentation.getUiAutomation();
            ZhijianStorage.get(context).ready().join();
            b = testPeerPackage(context);
            apps = new AppPolicyStore(context);
            groups = new RuleGroupStore(context);
            originalA = apps.policyOf(a);
            originalB = apps.policyOf(b);
            AppOpsManager operations = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            oldUsageMode = operations.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(), context.getPackageName());
            registerCleanup();
            cleanup.initialize(() -> {
                shell("appops set " + context.getPackageName() + " GET_USAGE_STATS allow");
                apps.reset(a).join();
                apps.reset(b).join();
                home();
            });
        }

        /** @param limit 使用阈值 @param rest 所需休息 @param packages 同组应用 @return 稳定策略主键 */
        private String createGroup(long limit, long rest, String... packages) {
            RuleGroup group = groups.create();
            group.types.add(RuleType.CONTINUOUS);
            group.continuousLimitMillis = limit;
            group.interruptionPauseMillis = rest;
            group.apps.addAll(Arrays.asList(packages));
            created.add(group);
            groups.save(group).join();
            String key = ZhijianStorage.get(context).snapshot().effectivePolicyId(packages[0]);
            assertEquals("group:" + group.id, key);
            new ContinuousUsageStore(context).save(key, new ContinuousUsageCycle(BootIdentity.read(context),
                    System.currentTimeMillis(), SystemClock.elapsedRealtime(), 0L, 0L, null, "", 0L),
                    ZhijianStorage.get(context).snapshot().usageStart(key).id);
            return key;
        }

        /** @param policyId 此测试创建的组 @return 当前磁盘累计周期 */
        private ContinuousUsageCycle cycle(String policyId) { return new ContinuousUsageStore(context).read(policyId); }

        /** @param pkg 测试目标或已选择的辅助应用包名，启动其真实入口 Activity */
        private void open(String pkg) throws Exception {
            Intent launch = context.getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch == null || launch.getComponent() == null) {
                throw new AssertionError("Cannot launch the test member: " + pkg);
            }
            shell("am start -W -n " + launch.getComponent().flattenToString());
            observedAtMs = System.currentTimeMillis();
            SystemClock.sleep(250L);
        }

        /** 返回桌面，确保整个测试组都不在前台。 */
        private void home() throws Exception { shell("am start -W -a android.intent.action.MAIN -c android.intent.category.HOME"); }

        /** @param command 只在本次测试设备执行的固定授权或前台命令 */
        private void shell(String command) throws Exception {
            try (InputStream output = new ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))) {
                byte[] buffer = new byte[512];
                while (output.read(buffer) != -1) { /* 读取结束后命令才真正完成。 */ }
            }
        }

        /** 逐项恢复设备，构造失败的回滚和正常结束使用同一套清理计划。 */
        @Override public void close() throws Exception {
            cleanup.close();
        }

        /** 在第一次修改授权或规则之前登记所有恢复步骤。 */
        private void registerCleanup() {
            cleanup.add(this::home);
            cleanup.add(this::clearCreatedGroups);
            cleanup.add(() -> {
                if (originalA == null) apps.reset(a).join();
                else apps.save(a, originalA).join();
            });
            cleanup.add(() -> {
                if (originalB == null) apps.reset(b).join();
                else apps.save(b, originalB).join();
            });
            cleanup.add(this::restoreUsageAccess);
        }

        /** 每个测试组的限制、组配置和周期分别恢复，失败不阻断其他组。 */
        private void clearCreatedGroups() throws Exception {
            FixtureCleanup groupCleanup = new FixtureCleanup();
            for (RuleGroup group : created) {
                String policyId = "group:" + group.id;
                groupCleanup.add(() -> clearTestGroupRestrictions(context, policyId));
                groupCleanup.add(() -> groups.remove(group.id).join());
                groupCleanup.add(() -> {
                    if (!context.getSharedPreferences("continuous_usage_cycles", Context.MODE_PRIVATE)
                            .edit().remove(policyId).commit()) {
                        throw new IllegalStateException("Could not clear test cycle: " + policyId);
                    }
                });
            }
            groupCleanup.close();
        }

        /** 恢复原始使用访问模式，不把仅前台允许错误恢复成默认值。 */
        private void restoreUsageAccess() throws Exception {
            shell("appops set " + context.getPackageName() + " GET_USAGE_STATS "
                    + (oldUsageMode == AppOpsManager.MODE_ALLOWED ? "allow"
                    : oldUsageMode == AppOpsManager.MODE_IGNORED ? "ignore"
                    : oldUsageMode == AppOpsManager.MODE_ERRORED ? "deny"
                    : oldUsageMode == AppOpsManager.MODE_FOREGROUND ? "foreground" : "default"));
        }
    }
}
