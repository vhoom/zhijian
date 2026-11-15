package github.vhoom.zhijian.data.store;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import github.vhoom.zhijian.data.db.ZhijianDatabase;
import github.vhoom.zhijian.data.model.policy.RuleGroup;
import github.vhoom.zhijian.data.model.policy.RuleType;
import github.vhoom.zhijian.data.model.runtime.InterceptionRecord;
import github.vhoom.zhijian.data.model.runtime.PassGrant;
import github.vhoom.zhijian.data.model.runtime.PendingInterception;
import github.vhoom.zhijian.data.model.runtime.RuntimeCheckpoint;
import github.vhoom.zhijian.data.store.interception.PendingInterceptionStore;
import github.vhoom.zhijian.data.store.usage.ContinuousUsageStore;
import github.vhoom.zhijian.data.store.usage.InstallationIdentityStore;
import github.vhoom.zhijian.domain.interception.InterceptionDecision;
import github.vhoom.zhijian.domain.usage.ContinuousUsageCycle;
import github.vhoom.zhijian.domain.usage.UsageTrackingStart;
import github.vhoom.zhijian.fixture.FixtureCleanup;
import github.vhoom.zhijian.util.time.BootIdentity;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * 通过真实存储初始化验证跨安装恢复隔离及失败重试，数据库、偏好与安装文件均独立。
 *
 * 维护日期：2026年11月15日
 */
@RunWith(AndroidJUnit4.class)
public final class InstallationRestoreTest {
    private static final String TARGET = "fixture.first";
    private static final String POLICY = "group:1";
    private static final String WINDOW_CYCLE = POLICY + ":window:fixture:1000";
    private static final String OTHER_CHECKPOINT = "archive:fixture";
    private static final long MINUTE_MS = 60_000L;
    private static final long DAY_MS = 86_400_000L;
    private final FixtureCleanup cleanup = new FixtureCleanup();
    private final Set<String> preferenceNames = new HashSet<>();
    private Context base, context;
    private String prefix, databaseName;
    private File identityDirectory;
    private ZhijianStorage storage;
    private PendingInterception savedRest;

    /** 为测试准备独立的偏好、数据库和 no-backup 目录，不改设备时间或系统权限。 */
    @Before public void prepare() throws Exception {
        base = InstrumentationRegistry.getInstrumentation().getTargetContext();
        prefix = "installation-restore-" + UUID.randomUUID();
        databaseName = prefix + ".db";
        identityDirectory = new File(base.getCacheDir(), prefix);
        cleanup.add(this::closeStorage);
        cleanup.add(() -> base.deleteDatabase(databaseName));
        cleanup.add(() -> {
            for (String name : preferenceNames) base.deleteSharedPreferences(prefix + "-" + name);
        });
        cleanup.add(this::deleteIdentityFiles);
        cleanup.initialize(() -> {
            assertTrue(identityDirectory.mkdirs());
            context = new ContextWrapper(base) {
                /** @return 保留测试存储隔离的应用上下文 */
                @Override public Context getApplicationContext() { return this; }
                /** @return 仅属于本用例的安装身份目录 */
                @Override public File getNoBackupFilesDir() { return identityDirectory; }
                /** @param name 逻辑偏好名 @param mode 打开模式 @return 本用例的独立偏好 */
                @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                    synchronized (preferenceNames) { preferenceNames.add(name); }
                    return base.getSharedPreferences(prefix + "-" + name, mode);
                }
            };
        });
    }

    /** 即使初始化或某一步清理失败，也尝试释放全部独立测试资源。 */
    @After public void cleanup() throws Exception { cleanup.close(); }

    /** 相同系统指纹及启动次数、旧机单调时间更大时，也不能恢复旧安装的休息或恢复起点。 */
    @Test public void collidingBootIdentityDoesNotRestoreOldRuntimeFacts() throws Exception {
        UsageTrackingStart old = seedState("restored-installation", BootIdentity.read(context),
                System.currentTimeMillis() - MINUTE_MS, SystemClock.elapsedRealtime() + DAY_MS);
        openStorage();
        assertTrue(storage.snapshot().usageStart(POLICY).includes(
                savedRest.bootId, savedRest.occurredAtMs, savedRest.startedElapsedMs));
        assertRestoredStateDiscarded(old);
    }

    /** 不同启动身份且来源设备墙钟较快时，来源隔离仍由安装 UUID 决定。 */
    @Test public void futureSourceWallClockDoesNotRestoreOldRuntimeFacts() throws Exception {
        UsageTrackingStart old = seedState("restored-installation", "source-device-boot",
                System.currentTimeMillis() + DAY_MS, 10_000L);
        openStorage();
        assertTrue(storage.snapshot().usageStart(POLICY).includes(
                savedRest.bootId, savedRest.occurredAtMs, savedRest.startedElapsedMs));
        assertRestoredStateDiscarded(old);
    }

    /** 同次安装初始化与再次打开存储保留原起点、休息、授权及连续周期。 */
    @Test public void sameInstallationRestartPreservesCurrentRuntimeFacts() throws Exception {
        String installation = InstallationIdentityStore.readOrCreate(context);
        UsageTrackingStart old = seedState(installation, BootIdentity.read(context),
                System.currentTimeMillis(), SystemClock.elapsedRealtime());
        openStorage();
        assertCurrentStatePreserved(old);
        closeStorage();
        openStorage();
        assertCurrentStatePreserved(old);
    }

    /** 偏好已清理后数据库提交失败，旧身份与数据库记录回滚；下次初始化必须再次完成清理。 */
    @Test public void failedIdentityCommitRetriesCleanupBeforePublishingState() throws Exception {
        UsageTrackingStart old = seedState("restored-installation", BootIdentity.read(context),
                System.currentTimeMillis(), SystemClock.elapsedRealtime() + DAY_MS);
        ZhijianDatabase triggerDatabase = ZhijianDatabase.open(context, databaseName);
        try {
            triggerDatabase.getOpenHelper().getWritableDatabase().execSQL(
                    "CREATE TRIGGER fail_tracking_write BEFORE INSERT ON storage_meta "
                    + "WHEN NEW.`key` = 'usage_tracking_starts' BEGIN "
                    + "SELECT RAISE(ABORT, 'restore-metadata-write'); END");
        } finally {
            triggerDatabase.close();
        }
        try {
            openStorage();
            fail("Failed metadata commit must prevent storage readiness");
        } catch (ExecutionException expected) {
            assertTrue(expected.getCause().getMessage().contains("restore-metadata-write"));
            assertTrue(storage.ready().isCompletedExceptionally());
        }
        closeStorage();
        assertTrue(new PendingInterceptionStore(context).sessionIds().isEmpty());
        ZhijianDatabase restoredDatabase = ZhijianDatabase.open(context, databaseName);
        try {
            assertTrue(UsageTrackingStore.belongsToAnotherInstallation(
                    restoredDatabase.policies(), InstallationIdentityStore.readOrCreate(context)));
            assertNotNull(restoredDatabase.runtime().checkpoint(RuntimeStateStore.continuousResumeKey(POLICY)));
            assertNotNull(restoredDatabase.runtime().grant(TARGET));
            restoredDatabase.getOpenHelper().getWritableDatabase().execSQL("DROP TRIGGER fail_tracking_write");
        } finally {
            restoredDatabase.close();
        }
        openStorage();
        assertRestoredStateDiscarded(old);
    }

    /**
     * 保存一份完整当前格式的来源安装状态，时间可故意与新安装发生碰撞。
     * @param installation 来源安装身份 @param bootId 来源启动身份
     * @param wallMs 来源墙钟毫秒 @param elapsedMs 来源单调毫秒
     * @return 来源策略的固定统计起点
     */
    private UsageTrackingStart seedState(String installation, String bootId, long wallMs, long elapsedMs) {
        UsageTrackingStart start;
        ZhijianDatabase db = ZhijianDatabase.open(context, databaseName);
        try {
            PolicyBootstrap.initialize(db);
            start = db.runInTransaction(() -> {
                RuleGroup group = new RuleGroup(1L, "安装恢复夹具");
                group.enabled = true;
                group.types.add(RuleType.CONTINUOUS);
                group.continuousLimitMillis = MINUTE_MS;
                group.interruptionPauseMillis = MINUTE_MS;
                group.apps.add(TARGET);
                group.apps.add("fixture.second");
                PolicyCodec.write(db.policies(), PolicyCodec.entity(group, POLICY, "group", 1L, 1L), group);
                PolicySnapshot policies = PolicySnapshot.load(db.policies());
                UsageTrackingStart origin = UsageTrackingStore.synchronize(db.policies(), policies,
                        installation, bootId, wallMs, elapsedMs).get(POLICY);
                InterceptionDecision decision = new InterceptionDecision(TARGET, POLICY, 1L,
                        InterceptionDecision.Kind.INTERRUPT, InterceptionDecision.Trigger.CONTINUOUS,
                        policies.level(), "", MINUTE_MS, MINUTE_MS, 0L, MINUTE_MS, MINUTE_MS);
                savedRest = new PendingInterception(decision, "old-rest", bootId,
                        wallMs, elapsedMs, 0L, wallMs, POLICY, wallMs);
                for (String key : new String[] {RuntimeStateStore.interruptionResumeKey(TARGET),
                        RuntimeStateStore.continuousResumeKey(POLICY),
                        RuntimeStateStore.continuousResumeKey(WINDOW_CYCLE), OTHER_CHECKPOINT}) {
                    db.runtime().putCheckpoint(HistoryCodec.entity(new RuntimeCheckpoint(key, "old-resume",
                            TARGET, bootId, "interruption-resumed", 1L, wallMs, elapsedMs, elapsedMs)));
                }
                db.runtime().putGrant(HistoryCodec.entity(new PassGrant(TARGET, POLICY, 1L, bootId,
                        wallMs, MINUTE_MS, elapsedMs + MINUTE_MS)));
                db.runtime().insertInterception(HistoryCodec.entity(new InterceptionRecord("old-audit",
                        TARGET, POLICY, 1L, "interrupt", policies.level().code, "continuous",
                        "rest_elapsed", "allowed", wallMs, wallMs)));
                return origin;
            });
        } finally {
            db.close();
        }
        PendingInterceptionStore pending = new PendingInterceptionStore(context);
        pending.saveRest(savedRest);
        pending.save(savedRest);
        new ContinuousUsageStore(context).save(POLICY, new ContinuousUsageCycle(bootId, wallMs, elapsedMs,
                MINUTE_MS, 0L, TARGET, "Main", "", 0L), start.id);
        return start;
    }

    /** @param old 来源起点；历史审计和非恢复检查点保留，旧运行记录不得进入新快照 */
    private void assertRestoredStateDiscarded(UsageTrackingStart old) throws Exception {
        UsageTrackingStart current = storage.snapshot().usageStart(POLICY);
        assertNotEquals(old.id, current.id);
        PendingInterceptionStore pending = new PendingInterceptionStore(context);
        assertNull(pending.read(TARGET));
        assertTrue(pending.rests(POLICY).isEmpty());
        assertTrue(pending.restrictions().isEmpty());
        assertNull(storage.state().runtime.checkpoint(RuntimeStateStore.interruptionResumeKey(TARGET)));
        assertNull(storage.state().runtime.checkpoint(RuntimeStateStore.continuousResumeKey(POLICY)));
        assertNull(storage.state().runtime.checkpoint(RuntimeStateStore.continuousResumeKey(WINDOW_CYCLE)));
        assertNotNull(storage.state().runtime.checkpoint(OTHER_CHECKPOINT));
        assertNull(storage.state().runtime.grantFor(TARGET, POLICY));
        assertNotNull(storage.read(db -> db.runtime().interception("old-audit")).get(10L, TimeUnit.SECONDS));
        assertNull(new ContinuousUsageStore(context).read(POLICY, current.id));
    }

    /** @param old 同次安装已保存的起点；初始化不能把正常重启当作新安装 */
    private void assertCurrentStatePreserved(UsageTrackingStart old) {
        assertEquals(old.id, storage.snapshot().usageStart(POLICY).id);
        PendingInterceptionStore pending = new PendingInterceptionStore(context);
        assertEquals(savedRest.sessionId, pending.read(TARGET).sessionId);
        assertEquals(savedRest.sessionId, pending.rests(POLICY).get(0).sessionId);
        assertEquals("old-resume", storage.state().runtime.checkpoint(
                RuntimeStateStore.continuousResumeKey(POLICY)).sessionId);
        assertNotNull(storage.state().runtime.grantFor(TARGET, POLICY));
        assertEquals(MINUTE_MS, new ContinuousUsageStore(context).read(POLICY, old.id).usedMs);
    }

    /** 走正式异步初始化入口，等待成功发布完整快照。 */
    private void openStorage() throws Exception {
        storage = new ZhijianStorage(context, databaseName);
        storage.ready().get(10L, TimeUnit.SECONDS);
    }

    /** 排空并关闭本用例的存储队列，允许重新打开同一数据库。 */
    private void closeStorage() throws Exception {
        if (storage != null) {
            storage.closeForTest().get(10L, TimeUnit.SECONDS);
            storage = null;
        }
    }

    /** 仅删除本用例安装目录内的 AtomicFile 文件，不递归操作其他目录。 */
    private void deleteIdentityFiles() {
        if (!identityDirectory.exists()) return;
        File[] files = identityDirectory.listFiles();
        assertNotNull(files);
        for (File file : files) {
            assertTrue(file.isFile());
            assertTrue(file.delete());
        }
        assertTrue(identityDirectory.delete());
    }
}
