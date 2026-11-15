package github.vhoom.zhijian.data.store;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import github.vhoom.zhijian.data.model.policy.ControlLevel;
import github.vhoom.zhijian.data.model.usage.HistoryCursor;
import github.vhoom.zhijian.data.model.runtime.InterceptionRecord;
import github.vhoom.zhijian.data.model.runtime.PassGrant;
import github.vhoom.zhijian.data.model.policy.RuleGroup;
import github.vhoom.zhijian.data.model.policy.RuleType;
import github.vhoom.zhijian.data.model.policy.RuleWindow;
import github.vhoom.zhijian.data.model.runtime.RuntimeCheckpoint;
import github.vhoom.zhijian.data.model.usage.UsageDay;
import github.vhoom.zhijian.data.model.usage.UsageSession;
import github.vhoom.zhijian.domain.interception.word.WordEntry;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.StringWriter;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;

import static org.junit.Assert.*;

/**
 * 覆盖 StorageIntegration 的边界输入、状态变化与回归行为。
 *
 * 维护日期：2026年11月14日
 */
@RunWith(AndroidJUnit4.class)
public class StorageIntegrationTest {
    /** 首次建立全局配置，重新打开不覆盖用户修改。 */
    @Test public void initializationCreatesDefaultsAndPreservesCommittedSettings() throws Exception {
        open();
        assertEquals(ControlLevel.REPAIR, storage.snapshot().level());
        assertEquals(0L, storage.snapshot().dailyQuotaMillis());
        assertEquals(180_000L, storage.snapshot().effectiveFor("app.new").passDurationMillis);
        assertTrue(storage.snapshot().groups().isEmpty());
        await(storage.setLevel(ControlLevel.MORTAL));
        await(storage.setDailyQuota(3_600_000L));
        reopen();
        assertEquals(ControlLevel.MORTAL, storage.snapshot().level());
        assertEquals(3_600_000L, storage.snapshot().dailyQuotaMillis());
    }

    /** 规则组必须提供稳定正 ID，保存失败不写入临时组。 */
    @Test public void groupWithoutStableIdIsRejected() throws Exception {
        open();
        assertFails(storage.saveGroup(group(0, "app.unassigned")));
        assertTrue(storage.snapshot().groups().isEmpty());
    }

    /** 公共保存入口拒绝重复时段且事务回滚，不发布非法配置。 */
    @Test public void duplicateWindowsCannotBypassUiValidation() throws Exception {
        open();
        RuleGroup draft = group(35, "app.duplicate-window");
        draft.types.clear();
        draft.types.add(RuleType.WINDOW_BAN);
        draft.windows.add(new RuleWindow("first", RuleType.WINDOW_BAN, 480, 1380, 0));
        draft.windows.add(new RuleWindow("second", RuleType.WINDOW_BAN, 480, 1380, 0));
        try { await(storage.saveGroup(draft)); fail("Duplicate windows must not persist"); }
        catch (ExecutionException failure) { assertTrue(failure.getCause() instanceof IllegalArgumentException); }
        assertNull(storage.snapshot().group(35));
    }

    /** 关闭存储时排空正在读取数据库的任务，关闭后的读写 Future 都明确失败。 */
    @Test public void closeWaitsForReadersBeforeClosingDatabase() throws Exception {
        open();
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Integer> read = storage.read(db -> {
            reading.countDown();
            release.await();
            return db.words().wordCount();
        });
        try {
            assertTrue(reading.await(5, TimeUnit.SECONDS));
            CompletableFuture<Void> closed = storage.closeForTest();
            assertFalse(closed.isDone());
            try { await(storage.submit(db -> null)); fail("Closed storage must reject writes"); }
            catch (ExecutionException expected) { assertTrue(expected.getCause() instanceof java.util.concurrent.RejectedExecutionException); }
            release.countDown();
            assertEquals(24, (int) await(read));
            await(closed);
            try { await(storage.read(db -> 1)); fail("Closed storage must reject reads"); }
            catch (ExecutionException expected) { assertTrue(expected.getCause() instanceof java.util.concurrent.RejectedExecutionException); }
        } finally { release.countDown(); }
    }

    /** 后台恢复与按钮恢复竞争时仅第一次生效，不重复审计、不重新推动计时起点。 */
    @Test
    public void interruptionResumeFromDifferentEntrancesIsIdempotent() throws Exception {
        open();
        RuntimeStateStore runtime = new RuntimeStateStore(storage);
        long revision = storage.snapshot().revisionOf("global");
        InterceptionRecord automatic = new InterceptionRecord("rest-race", "test.rest-race", "global",
                revision, "interrupt", ControlLevel.REPAIR.code, "continuous", "rest_elapsed",
                "allowed", START, START + 60_000L);
        InterceptionRecord button = new InterceptionRecord("rest-race", "test.rest-race", "global",
                revision, "interrupt", ControlLevel.REPAIR.code, "continuous", "button_hold_60",
                "allowed", START, START + 65_000L);
        await(runtime.recordInterruptionResume(automatic, "boot", 60_000L));
        await(runtime.recordInterruptionResume(button, "boot", 65_000L));
        String key = RuntimeStateStore.interruptionResumeKey("test.rest-race");
        assertEquals(60_000L, runtime.checkpoint(key).continuousStartedElapsedMs);
        assertEquals(1L, runtime.checkpoint(key).sequence);
        List<InterceptionRecord> rows = await(runtime.page(HistoryCursor.first(), 10));
        assertEquals(1, rows.size());
        assertEquals("rest_elapsed", rows.get(0).challenge);
    }
    /** 中断继续只重置连续计时；重试和重启不会重新发放或丢失起点。 */
    @Test
    public void interruptionResumePersistsWithoutBlanketGrant() throws Exception {
        open();
        RuntimeStateStore runtime = new RuntimeStateStore(storage);
        long revision = storage.snapshot().revisionOf("global");
        InterceptionRecord first = new InterceptionRecord("resume-first", "test.resume", "global",
                revision, "interrupt", ControlLevel.REPAIR.code, "continuous", "button_hold_60",
                "allowed", START, START + 60_000L);
        await(runtime.recordInterruptionResume(first, "boot", 60_000L));
        String key = RuntimeStateStore.interruptionResumeKey("test.resume");
        assertNull(runtime.grantFor("test.resume"));
        assertEquals(60_000L, runtime.checkpoint(key).continuousStartedElapsedMs);
        InterceptionRecord next = new InterceptionRecord("resume-next", "test.resume", "global",
                revision, "interrupt", ControlLevel.REPAIR.code, "continuous", "button_hold_60",
                "allowed", START + 120_000L, START + 180_000L);
        await(runtime.recordInterruptionResume(next, "boot", 180_000L));
        await(runtime.recordInterruptionResume(first, "boot", 60_000L));
        assertEquals(180_000L, runtime.checkpoint(key).continuousStartedElapsedMs);
        assertEquals(2L, runtime.checkpoint(key).sequence);
        reopen();
        assertEquals(180_000L, new RuntimeStateStore(storage).checkpoint(key).continuousStartedElapsedMs);
        assertNull(new RuntimeStateStore(storage).grantFor("test.resume"));
    }
    private static final long START = Instant.parse("2026-10-31T12:00:00Z").toEpochMilli();
    private Context context;
    private Context base;
    private String prefix;
    private String databaseName;
    private ZhijianStorage storage;

    /**
     * 准备只供当前测试使用的上下文和数据库文件名。
     */
    @Before
    public void prepareIsolatedStorage() {
        base = InstrumentationRegistry.getInstrumentation().getTargetContext();
        prefix = "storage-test-" + UUID.randomUUID();
        databaseName = prefix + ".db";
        context = new ContextWrapper(base) {
            /**
             * 返回测试隔离上下文，避免打开真实数据库。
             */
            @Override public Context getApplicationContext() { return this; }
            /**
             * 将设置读写重定向到测试专用文件。
             *
             * @param name 数据库文件名或规则显示名称
             * @param mode 界面配色模式或设置文件的访问模式
             */
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return base.getSharedPreferences(prefix + "-" + name, mode);
            }
        };
    }

    /**
     * 关闭并清理测试隔离的数据库和文件。
     */
    @After
    public void closeOnlyTestFiles() throws Exception {
        if (storage != null) await(storage.closeForTest());
        base.deleteDatabase(databaseName);
        for (String file : Arrays.asList("global_settings", "pending_interceptions", "continuous_usage_cycles")) {
            base.deleteSharedPreferences(prefix + "-" + file);
        }
    }

    /**
     * 打开独立命名的测试数据库。
     */
    private void open() throws Exception {
        storage = new ZhijianStorage(context, databaseName);
        await(storage.ready());
    }

    /**
     * 关闭后重新打开测试存储，验证持久化行为。
     */
    private void reopen() throws Exception {
        await(storage.closeForTest());
        open();
    }

    /** 首次打开应把资源词库写入 SQLite，重开后仍从数据库恢复完整义项。 */
    @Test
    public void wordCatalogSeedsSQLiteAndSurvivesReopen() throws Exception {
        open();
        assertEquals(24, storage.wordCatalog().size());
        assertEquals(24, (int) await(storage.read(db -> db.words().wordCount())));
        WordEntry good = storage.wordCatalog().stream()
                .filter(entry -> "good".equals(entry.english))
                .findFirst().orElseThrow(AssertionError::new);
        assertEquals("adj. 好的；n. 商品", good.chineseLabel());

        reopen();
        assertEquals(24, storage.wordCatalog().size());
        assertEquals("good", storage.wordCatalog().get(0).english);
    }

    /** 连续提醒的一位小数分钟时长必须通过 SQLite 提交并在重开后恢复。 */
    @Test
    public void interruptionPauseMinutesSurviveReopen() throws Exception {
        open();
        RuleGroup group = group(10, "app.test");
        group.types.clear();
        group.types.add(RuleType.CONTINUOUS);
        group.continuousLimitMillis = 30 * 60_000L;
        group.interruptionPauseMillis = 90_000L;

        await(storage.saveGroup(group));
        reopen();

        assertEquals(90_000L, storage.snapshot().group(10).interruptionPauseMillis);
    }

    /** 规则组后台保存拒绝单成员，失败不留记录；独立应用策略仍可只保存一个应用。 */
    @Test
    public void singleMemberGroupIsRejectedButIndependentPolicyStillSaves() throws Exception {
        open();
        RuleGroup draft = group(88, "app.minimum");
        draft.apps.remove(1);
        assertFails(storage.saveGroup(draft));
        assertNull(storage.snapshot().group(88));
        await(storage.saveApp("app.minimum", draft));
        assertEquals(1, storage.snapshot().appPolicy("app.minimum").apps.size());
    }

    /**
     * 验证提交后才发布快照，修改草稿不会污染缓存。
     */
    @Test
    public void writesPublishAfterCommitAndMutableDraftCannotChangeCachedPolicy() throws Exception {
        open();
        RuleGroup group = group(10, "app.test");
        CompletableFuture<Void> write = storage.saveGroup(group);
        group.dailyLimitMillis = 999 * 60_000L;
        group.apps.clear();
        await(write);
        assertEquals(30 * 60_000L, storage.snapshot().group(10).dailyLimitMillis);
        RuleGroup copy = storage.snapshot().group(10);
        copy.types.clear();
        assertTrue(storage.snapshot().group(10).has(RuleType.DAILY_LIMIT));
        List<CompletableFuture<Void>> writes = new ArrayList<>();
        for (int i = 11; i < 31; i++) writes.add(storage.saveGroup(group(i, "app." + i)));
        await(CompletableFuture.allOf(writes.toArray(new CompletableFuture[0])));
        assertEquals(21, storage.snapshot().groups().size());
        RuleGroup invalid = storage.snapshot().group(10);
        invalid.dailyLimitMillis = -1;
        assertFails(storage.saveGroup(invalid));
        assertEquals(30 * 60_000L, storage.snapshot().group(10).dailyLimitMillis);
        reopen();
        assertEquals(21, storage.snapshot().groups().size());
        assertEquals(30 * 60_000L, storage.snapshot().group(10).dailyLimitMillis);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            // 如果此路径访问 Room，主线程限制会使该测试失败。
            for (int i = 0; i < 1000; i++) assertEquals(10, storage.snapshot().effectiveFor("app.test").id);
        });
    }

    /**
     * 验证重开数据库后多时段额度和未启用草稿仍完整保留。
     */
    @Test
    public void multipleWindowsKeepTheirOwnQuotasAndInactiveDraftsAcrossReopen() throws Exception {
        open();
        RuleGroup group = group(10, "app.test");
        group.types.clear();
        group.types.addAll(Arrays.asList(RuleType.WINDOW_LIMIT, RuleType.WINDOW_BAN));
        group.windows.add(new RuleWindow("morning", RuleType.WINDOW_LIMIT,
                480, 720, 30 * 60_000L));
        group.windows.add(new RuleWindow("night", RuleType.WINDOW_LIMIT,
                1320, 60, 60 * 60_000L));
        group.windows.add(new RuleWindow("work", RuleType.WINDOW_BAN, 540, 720, 0));
        group.windows.add(new RuleWindow("sleep", RuleType.WINDOW_BAN, 1380, 480, 0));
        await(storage.saveGroup(group));

        RuleGroup draft = storage.snapshot().group(10);
        draft.windowsFor(RuleType.WINDOW_LIMIT).get(0).limitDurationMillis = 45 * 60_000L;
        assertEquals(30 * 60_000L, storage.snapshot().group(10)
                .windowsFor(RuleType.WINDOW_LIMIT).get(0).limitDurationMillis);
        draft.types.remove(RuleType.WINDOW_LIMIT);
        draft.windows.removeIf(window -> window.id.equals("work"));
        await(storage.saveGroup(draft));
        reopen();

        RuleGroup restored = storage.snapshot().group(10);
        assertFalse(restored.has(RuleType.WINDOW_LIMIT));
        assertEquals(2, restored.windowsFor(RuleType.WINDOW_LIMIT).size());
        assertEquals(45 * 60_000L,
                restored.windowsFor(RuleType.WINDOW_LIMIT).get(0).limitDurationMillis);
        assertEquals(60 * 60_000L,
                restored.windowsFor(RuleType.WINDOW_LIMIT).get(1).limitDurationMillis);
        assertEquals(60, restored.windowsFor(RuleType.WINDOW_LIMIT).get(1).endMinute);
        assertEquals("sleep", restored.windowsFor(RuleType.WINDOW_BAN).get(0).id);
        restored.types.add(RuleType.WINDOW_LIMIT);
        await(storage.saveGroup(restored));

        UsageHistoryStore history = new UsageHistoryStore(storage);
        await(history.record(session("keep-history", "app.test", START, 1000, 1, true)));
        await(storage.removePolicy(PolicyCodec.groupKey(10)));
        assertTrue(await(storage.read(db -> db.policies().windows().isEmpty())));
        assertEquals(1, await(history.page(null, HistoryCursor.first(), 10)).size());
    }

    /**
     * 验证检查点提交幂等，跨日汇总重开后保持完整。
     */
    @Test
    public void usageCheckpointsAreIdempotentAndCrossDayTotalsSurviveReopen() throws Exception {
        open();
        UsageHistoryStore history = new UsageHistoryStore(storage);
        long start = Instant.parse("2026-10-31T15:59:30Z").toEpochMilli();
        UsageSession first = UsageSession.builder("session", "app.test", start).zone("Asia/Shanghai")
                .visit("visit", true).checkpoint(start + 15_000, 15_000, 1).build();
        UsageSession last = UsageSession.builder("session", "app.test", start).zone("Asia/Shanghai")
                .visit("visit", true).checkpoint(start + 60_000, 60_000, 2).finished("switched").build();
        await(history.recordBatch(Collections.singletonList(first), checkpoint(1)));
        await(history.recordBatch(Collections.singletonList(last), checkpoint(2)));
        await(history.record(last));
        await(history.record(first));
        UsageSession late = session("late-history", "app.other", START, 1000, 1, true);
        await(history.recordBatch(Collections.singletonList(late), checkpoint(1)));
        assertEquals(2, storage.state().runtime.checkpoint("foreground").sequence);
        reopen();
        history = new UsageHistoryStore(storage);
        List<UsageDay> days = await(history.daily("2026-10-31", "2026-11-01"));
        long total = 0;
        long opens = 0;
        int appDays = 0;
        for (UsageDay day : days) {
            if (!day.packageName.equals("app.test")) continue;
            total += day.foregroundMs;
            opens += day.openCount;
            assertEquals(30_000, day.foregroundMs);
            appDays++;
        }
        assertEquals(60_000, total);
        assertEquals(1, opens);
        assertEquals(2, appDays);
        assertEquals(2, await(history.page(null, HistoryCursor.first(), 10)).size());
        assertEquals(2, storage.state().runtime.checkpoint("foreground").sequence);
    }

    /**
     * 验证失败批次同时回滚明细、日汇总及运行状态。
     */
    @Test
    public void failedBatchRollsBackDetailsTotalsAndRuntimeTogether() throws Exception {
        open();
        UsageHistoryStore history = new UsageHistoryStore(storage);
        await(history.recordBatch(Collections.singletonList(session("old", "app", START, 1000, 1, false)), checkpoint(1)));
        UsageSession conflict = session("old", "app", START, 2000, 1, false);
        UsageSession newRecord = session("new", "app", START + 5000, 1000, 1, true);
        assertFails(history.recordBatch(Arrays.asList(newRecord, conflict), checkpoint(2)));
        assertEquals(1, await(history.page(null, HistoryCursor.first(), 10)).size());
        assertEquals(1000, await(history.daily("2026-10-31", "2026-10-31")).get(0).foregroundMs);
        assertEquals(1, storage.state().runtime.checkpoint("foreground").sequence);
    }

    /**
     * 验证拦截后恢复目标应用不会额外增加打开次数。
     */
    @Test
    public void resumedInterceptionDoesNotCountAnotherOpening() throws Exception {
        open();
        UsageHistoryStore history = new UsageHistoryStore(storage);
        UsageSession before = UsageSession.builder("before", "app", START).visit("visit", true)
                .checkpoint(START + 1000, 1000, 1).finished("intercepted").build();
        UsageSession after = UsageSession.builder("after", "app", START + 10_000).visit("visit", false)
                .checkpoint(START + 12_000, 2000, 1).finished("switched").build();
        await(history.recordBatch(Arrays.asList(before, after), null));
        UsageDay day = await(history.daily("2026-10-31", "2026-10-31")).get(0);
        assertEquals(3000, day.foregroundMs);
        assertEquals(1, day.openCount);
        UsageSession duplicateOpen = UsageSession.builder("bad", "app", START + 20_000).visit("visit", true)
                .checkpoint(START + 21_000, 1000, 1).finished("switched").build();
        assertFails(history.record(duplicateOpen));
    }

    /**
     * 验证相同时间戳的历史分页无重复且无遗漏。
     */
    @Test
    public void paginationWithEqualTimestampsHasNoDuplicatesOrMissingRows() throws Exception {
        open();
        UsageHistoryStore history = new UsageHistoryStore(storage);
        List<UsageSession> records = new ArrayList<>();
        for (int i = 0; i < 41; i++) records.add(session(String.format(java.util.Locale.ROOT, "%03d", i),
                i % 2 == 0 ? "even" : "odd", START, 1000, 1, true));
        await(history.recordBatch(records, null));
        Set<String> ids = new HashSet<>();
        HistoryCursor cursor = HistoryCursor.first();
        while (true) {
            List<UsageSession> page = await(history.page(null, cursor, 7));
            if (page.isEmpty()) break;
            for (UsageSession record : page) assertTrue(ids.add(record.id));
            cursor = UsageHistoryStore.after(page.get(page.size() - 1));
        }
        assertEquals(41, ids.size());
        assertEquals(21, await(history.page("even", HistoryCursor.first(), 100)).size());
    }

    /**
     * 验证放行和审计原子提交，重试不恢复已撤销权限。
     */
    @Test
    public void grantAndAuditAreAtomicAndRetryDoesNotRestoreOldPermission() throws Exception {
        open();
        RuntimeStateStore runtime = new RuntimeStateStore(storage);
        InterceptionRecord first = interception("first", 1, START);
        PassGrant grant = new PassGrant("app", "global", 1, "boot", START, 300_000, 400_000);
        await(runtime.recordInterception(first, grant));
        await(runtime.saveCheckpoint(checkpoint(1)));
        reopen();
        runtime = new RuntimeStateStore(storage);
        assertEquals(300_000, runtime.grantFor("app").durationMs);
        assertEquals(1, runtime.checkpoint("foreground").sequence);
        InterceptionRecord second = interception("second", 1, START + 1000);
        PassGrant newer = new PassGrant("app", "global", 1, "boot", START + 1000, 300_000, 401_000);
        await(runtime.recordInterception(second, newer));
        await(runtime.recordInterception(first, grant));
        assertEquals(401_000, runtime.grantFor("app").deadlineElapsedMs);
        await(storage.saveGroup(group(100, "app"))); // 配置绑定改变了有效来源。
        assertNull(runtime.grantFor("app"));
        assertFails(runtime.recordInterception(interception("stale", 1, START + 2000), grant));
        assertEquals(2, await(runtime.page(HistoryCursor.first(), 20)).size());
        await(storage.removePolicy(PolicyCodec.groupKey(100)));
        assertEquals(2, await(runtime.page(HistoryCursor.first(), 20)).size());
    }

    /**
     * 验证多页流式导出无遗漏且不修改原明细。
     */
    @Test
    public void exportStreamsMultiplePagesAndLeavesEveryDetailIntact() throws Exception {
        open();
        UsageHistoryStore history = new UsageHistoryStore(storage);
        List<UsageSession> batch = new ArrayList<>();
        for (int i = 0; i < 500; i++) batch.add(session("export-" + i, "app", START + i * 2000L, 1000, 1, true));
        await(history.recordBatch(batch, null));
        await(history.record(session("export-last", "app", START + 2_000_000, 1000, 1, true)));
        await(history.record(session("ongoing", "app", START + 3_000_000, 1000, 1, false)));
        StringWriter output = new StringWriter();
        assertEquals(Long.valueOf(501), await(history.exportCompletedSessions(output)));
        String[] lines = output.toString().split("\n");
        assertEquals(502, lines.length);
        assertEquals("zhijian.usage", new JSONObject(lines[0]).getString("format"));
        assertTrue(new JSONObject(lines[1]).has("foregroundMs"));
        assertEquals(500, await(history.page(null, HistoryCursor.first(), 500)).size());
    }

    /**
     * 验证文档导出失败后可重试，并完整保留 Unicode 明细。
     */
    @Test
    public void documentExportRetriesAfterFailureAndPreservesUnicodeDetails() throws Exception {
        open();
        UsageHistoryStore history = new UsageHistoryStore(storage);
        await(history.record(UsageSession.builder("saved", "app.test", START)
                .label("阅读 \"应用\"\n复盘").checkpoint(START + 1000, 1000, 1).finished("switched").build()));
        await(history.record(session("ongoing", "app.test", START + 2000, 1000, 1, false)));
        UsageHistoryExporter exporter = new UsageHistoryExporter(context, history);
        // 目录不能作为文件写入；失败后允许选择一个有效目标重试。
        assertFails(exporter.exportTo(Uri.fromFile(context.getCacheDir())));
        File destination = File.createTempFile(prefix, ".jsonl", context.getCacheDir());
        try {
            assertEquals(Long.valueOf(1), await(exporter.exportTo(Uri.fromFile(destination))));
            List<String> lines = Files.readAllLines(destination.toPath(), StandardCharsets.UTF_8);
            assertEquals(2, lines.size());
            JSONObject row = new JSONObject(lines.get(1));
            assertEquals("阅读 \"应用\"\n复盘", row.getString("appLabel"));
            assertEquals(1000, row.getLong("foregroundMs"));
            assertEquals(2, await(history.page(null, HistoryCursor.first(), 10)).size());
        } finally {
            assertTrue(destination.delete());
        }
    }

    /**
     * 验证空历史导出仍截断旧内容并写入有效文件头。
     */
    @Test
    public void emptyDocumentExportTruncatesDestinationAndWritesValidHeader() throws Exception {
        open();
        UsageHistoryExporter exporter = new UsageHistoryExporter(context, new UsageHistoryStore(storage));
        File destination = File.createTempFile(prefix, ".jsonl", context.getCacheDir());
        try {
            Files.write(destination.toPath(), new byte[2048]);
            assertEquals(Long.valueOf(0), await(exporter.exportTo(Uri.fromFile(destination))));
            List<String> lines = Files.readAllLines(destination.toPath(), StandardCharsets.UTF_8);
            assertEquals(1, lines.size());
            JSONObject header = new JSONObject(lines.get(0));
            assertEquals("zhijian.usage", header.getString("format"));
            assertTrue(header.getBoolean("completedOnly"));
            assertEquals(2, header.getInt("version"));
        } finally {
            assertTrue(destination.delete());
        }
    }

    /**
     * 验证大量历史分页使用复合索引并记录设备查询耗时。
     */
    @Test
    public void largeHistoryUsesCompositeIndexAndReportsDeviceTimings() throws Exception {
        open();
        UsageHistoryStore history = new UsageHistoryStore(storage);
        long began = android.os.SystemClock.elapsedRealtime();
        for (int batch = 0; batch < 20; batch++) {
            List<UsageSession> records = new ArrayList<>();
            for (int offset = 0; offset < 500; offset++) {
                int i = batch * 500 + offset;
                records.add(session("perf-" + i, "app." + (i % 20), START - i * 60_000L, 20_000, 1, true));
            }
            await(history.recordBatch(records, null));
        }
        long insertMs = android.os.SystemClock.elapsedRealtime() - began;
        List<Long> samples = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            long start = android.os.SystemClock.elapsedRealtime();
            assertEquals(40, await(history.page("app.0", new HistoryCursor(START - 8000 * 60_000L, "perf-8000"), 40)).size());
            samples.add(android.os.SystemClock.elapsedRealtime() - start);
        }
        Collections.sort(samples);
        String plan = await(storage.read(db -> {
            StringBuilder details = new StringBuilder();
            try (Cursor cursor = db.getOpenHelper().getReadableDatabase().query(
                    "EXPLAIN QUERY PLAN SELECT * FROM usage_session WHERE packageName = ? "
                            + "AND (startedAtMs, id) < (?, ?) ORDER BY startedAtMs DESC, id DESC LIMIT 40",
                    new Object[]{"app.0", START - 8000 * 60_000L, "perf-8000"})) {
                while (cursor.moveToNext()) details.append(cursor.getString(3));
            }
            return details.toString();
        }));
        assertTrue(plan, plan.contains("SEARCH"));
        assertTrue(plan, plan.contains("index_usage_session_packageName_startedAtMs_id"));
        assertFalse(plan, plan.contains("TEMP B-TREE"));
        Log.i("ZhijianStorageTest", "10000 rows: insertMs=" + insertMs + ", pageP95Ms=" + samples.get(18) + ", " + plan);
    }

    /**
     * 读取或还原规则组副本，保持规则条件和时段分类。
     *
     * @param id 当前记录、规则或视图的稳定标识
     * @param pkg 需要查询或绑定的应用包名
     */
    private RuleGroup group(long id, String pkg) {
        RuleGroup group = new RuleGroup(id, "group-" + id);
        group.apps.add(pkg);
        group.apps.add(pkg + ".peer");
        group.types.add(RuleType.DAILY_LIMIT);
        group.dailyLimitMillis = 30 * 60_000L;
        group.passDurationMillis = 5 * 60_000L;
        return group;
    }

    /**
     * 读取或还原对应的使用会话记录。
     *
     * @param id 当前记录、规则或视图的稳定标识
     * @param pkg 需要查询或绑定的应用包名
     * @param start 变化起点或区间开始值
     * @param duration 测试会话累计的前台时长，单位为毫秒
     * @param sequence 累计状态的单调递增序号
     * @param complete 测试会话是否已经完成结算
     */
    private static UsageSession session(String id, String pkg, long start, long duration, long sequence, boolean complete) {
        UsageSession.Builder builder = UsageSession.builder(id, pkg, start).visit(id, true)
                .label("Test app").checkpoint(start + duration, duration, sequence);
        if (complete) builder.finished("switched");
        return builder.build();
    }

    /**
     * 读取或还原对应的运行检查点记录。
     *
     * @param sequence 累计状态的单调递增序号
     */
    private RuntimeCheckpoint checkpoint(long sequence) {
        return new RuntimeCheckpoint("foreground", "session", "app.test", "boot", "using", sequence,
                START + sequence * 1000, 10_000 + sequence * 1000, 10_000);
    }

    /**
     * 读取或还原对应的拦截审计记录。
     *
     * @param id 当前记录、规则或视图的稳定标识
     * @param revision 当前记录对应的策略修订号
     * @param occurred 测试拦截发生的墙钟时间戳
     */
    private InterceptionRecord interception(String id, long revision, long occurred) {
        return new InterceptionRecord(id, "app", "global", revision, "deny", "repair",
                "daily_limit", "hold", "allowed", occurred, occurred + 100);
    }

    /**
     * 等待测试数据库操作完成，使用超时防止测试永久挂起。
     *
     * @param <T> 本次数据或异步工作返回的类型
     * @param future 等待测试工作完成的异步信号
     */
    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(30, TimeUnit.SECONDS);
    }

    /**
     * 等待异步操作失败，并断言错误能够被调用方观察。
     *
     * @param future 等待测试工作完成的异步信号
     */
    private static void assertFails(CompletableFuture<?> future) throws Exception {
        try {
            await(future);
            fail("Expected storage operation to fail");
        } catch (ExecutionException expected) {
            assertNotNull(expected.getCause());
        }
    }
}
