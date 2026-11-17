package github.vhoom.zhijian.data.store;

import android.content.Context;
import android.os.SystemClock;

import github.vhoom.zhijian.data.db.ZhijianDatabase;
import github.vhoom.zhijian.data.db.entity.runtime.PassGrantEntity;
import github.vhoom.zhijian.data.db.dao.PolicyDao;
import github.vhoom.zhijian.data.db.entity.policy.PolicyEntity;
import github.vhoom.zhijian.data.model.policy.ControlLevel;
import github.vhoom.zhijian.data.model.policy.RuleGroup;
import github.vhoom.zhijian.data.model.policy.RuleGroupMembershipPolicy;
import github.vhoom.zhijian.data.model.policy.RuleGroupValidator;
import github.vhoom.zhijian.data.model.policy.RuleType;
import github.vhoom.zhijian.data.model.policy.BuiltInGroupPreset;
import github.vhoom.zhijian.data.model.runtime.PendingInterception;
import github.vhoom.zhijian.data.source.BuiltInGroupAppSource;
import github.vhoom.zhijian.data.source.ContinuousUsageReader;
import github.vhoom.zhijian.data.source.ContinuousRuleChangeCoordinator;
import github.vhoom.zhijian.data.source.ReminderUsageReader;
import github.vhoom.zhijian.data.source.RetreatProtectionReader;
import github.vhoom.zhijian.data.store.settings.GlobalSettings;
import github.vhoom.zhijian.data.store.usage.InstallationIdentityStore;
import github.vhoom.zhijian.data.store.interception.PendingInterceptionStore;
import github.vhoom.zhijian.data.model.word.BuiltInWordCatalog;
import github.vhoom.zhijian.data.store.word.BuiltInWordCatalogStore;
import github.vhoom.zhijian.domain.interception.word.WordEntry;
import github.vhoom.zhijian.domain.interception.RetreatChangePolicy;
import github.vhoom.zhijian.domain.interception.RuleConfigurationComparison;
import github.vhoom.zhijian.util.concurrent.TaskQueue;
import github.vhoom.zhijian.util.diagnostics.DiagnosticLog;
import github.vhoom.zhijian.util.time.DecimalDuration;
import github.vhoom.zhijian.util.time.BootIdentity;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.time.ZoneId;
import java.util.function.Supplier;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;

/**
 * 一个进程一个实例；初始化与写入在同一后台队列，提交后才发布新配置。
 *
 * 维护日期：2026年11月16日
 */
public final class ZhijianStorage {
    private static volatile ZhijianStorage instance;

    private final TaskQueue writer = new TaskQueue("zhijian-storage", 1, 128);
    private final TaskQueue readers = new TaskQueue("zhijian-storage-read", 2, 64);
    /** 接收任务与关闭共享此锁，数据库只在两个队列排空后关闭。 */
    private final Object lifecycle = new Object();
    private boolean closing;
    private CompletableFuture<Void> closed;
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private final BuiltInWordCatalogStore builtInWords;
    private final BuiltInRuleGroupStore builtInGroups;
    private final Context context;
    private final RetreatProtectionReader retreatProtection;
    private final PendingInterceptionStore pendingInterceptions;
    private final ContinuousRuleChangeCoordinator continuousRuleChanges;
    private volatile State state;
    private ZhijianDatabase database;
    private Throwable initializationFailure;
    private String installationId;

    /**
     * 以应用上下文获取进程共享的存储实例，避免页面重建重复初始化。
     *
     * @param context 访问系统服务、资源或持久化文件的上下文
     */
    public static ZhijianStorage get(Context context) {
        if (instance == null) {
            synchronized (ZhijianStorage.class) {
                if (instance == null) instance = new ZhijianStorage(context.getApplicationContext(),
                        ZhijianDatabase.FILE_NAME, new BuiltInGroupAppSource(context)::loadInstalledPackages);
            }
        }
        return instance;
    }

    // 独立命名数据库供仪器测试使用，测试不清除手机上的真实数据。
    /**
     * 创建 ZhijianStorage 实例，并初始化本次操作依赖或不可变字段。
     *
     * @param context 访问系统服务、资源或持久化文件的上下文
     * @param databaseName 数据库文件名；测试使用独立名称隔离真实数据
     */
    ZhijianStorage(Context context, String databaseName) {
        this(context, databaseName, null);
    }

    /**
     * 注入安装状态读取器；隔离测试构造器传 null，避免引入真实手机的内置策略。
     * @param context 用于打开数据库及读取资源的应用上下文
     * @param databaseName 数据库名称；测试使用独立临时名称
     * @param installedApps 后台安装状态适配器，null 表示测试不启用内置组
     */
    ZhijianStorage(Context context, String databaseName, Supplier<List<String>> installedApps) {
        this.context = context;
        pendingInterceptions = new PendingInterceptionStore(context);
        ReminderUsageReader reminderReader = new ReminderUsageReader(context,
                new ContinuousUsageReader(context, key -> state().runtime.checkpoint(key),
                        this::snapshot));
        retreatProtection = new RetreatProtectionReader(context, reminderReader);
        continuousRuleChanges = new ContinuousRuleChangeCoordinator(reminderReader, pendingInterceptions);
        builtInWords = new BuiltInWordCatalogStore(context.getAssets());
        builtInGroups = installedApps == null ? null : new BuiltInRuleGroupStore(
                context::getString, installedApps);
        writer.execute(() -> {
            try {
                installationId = InstallationIdentityStore.readOrCreate(context);
                database = ZhijianDatabase.open(context, databaseName);
                PolicyBootstrap.initialize(database);
                if (builtInGroups != null) {
                    List<String> members = builtInGroups.members();
                    database.runInTransaction(() -> builtInGroups.synchronize(database, members));
                }
                State initialState = database.runInTransaction(() -> {
                    if (UsageTrackingStore.belongsToAnotherInstallation(database.policies(), installationId)) {
                        // 先同步清理偏好，再提交新安装身份；中途失败仍保留旧身份，重启可幂等重试。
                        pendingInterceptions.clearForNewInstallation();
                        database.runtime().removeInterruptionResumeCheckpoints();
                        database.runtime().removeAllGrants();
                    }
                    PolicySnapshot policies = PolicySnapshot.load(database.policies());
                    return new State(PolicySnapshot.load(database.policies(), UsageTrackingStore.synchronize(
                            database.policies(), policies, installationId, BootIdentity.read(context),
                            System.currentTimeMillis(), SystemClock.elapsedRealtime())),
                            RuntimeSnapshot.load(database.runtime()), WordCatalogStore.initialize(context, database.words()));
                });
                builtInWords.initialize();
                pendingInterceptions.clearCancelledReminders(initialState.policies, ZoneId.systemDefault().getId());
                state = initialState;
                ready.complete(null);
            } catch (Throwable failure) {
                initializationFailure = failure;
                if (database != null) {
                    try { database.close(); }
                    catch (RuntimeException closeFailure) { failure.addSuppressed(closeFailure); }
                }
                ready.completeExceptionally(failure);
            }
        });
    }

    /**
     * 返回存储初始化完成信号，调用方不能修改内部初始化 Future。
     */
    public CompletableFuture<Void> ready() { return ready.thenApply(ignored -> null); }

    /**
     * 读取当前已提交的内存快照。
     */
    public PolicySnapshot snapshot() {
        return state().policies;
    }

    /** 后台核对内置组成员，事务成功后发布同版本的配置及运行快照。 */
    public CompletableFuture<Void> refreshBuiltInGroups() {
        if (builtInGroups == null) return CompletableFuture.completedFuture(null);
        return submit(db -> {
            List<String> members = builtInGroups.members();
            commitConfigurationOnWriter(db, database -> {
                builtInGroups.synchronize(database, members);
                return null;
            });
            return null;
        });
    }

    /** 返回从 SQLite 加载的只读单词词库快照。 */
    public List<WordEntry> wordCatalog() {
        return state().wordCatalog;
    }

    /**
     * 读取本会话选择的内置词库，主线程不执行资产 I/O。
     * @param catalog 设置快照中的词库选择
     * @return 缓存的不可变词条
     */
    public List<WordEntry> wordCatalog(BuiltInWordCatalog catalog) {
        state();
        return builtInWords.entries(catalog);
    }

    /** 调用方一次获取配置与放行快照，避免读到跨提交的组合。 */
    public State state() {
        State current = state;
        if (current == null) throw new IllegalStateException("Storage is not ready");
        return current;
    }

    /**
     * 同一次初始化或提交后发布的一致状态快照。
     */
    public static final class State {
        public final PolicySnapshot policies;
        public final RuntimeSnapshot runtime;
        public final List<WordEntry> wordCatalog;

        /**
         * 保存同一次数据库初始化或提交产生的一致内存快照。
         *
         * @param policies 同次提交中读取的策略集合或快照
         * @param runtime 当前已提交的运行快照
         * @param wordCatalog 同次初始化加载的不可变词库快照
         */
        State(PolicySnapshot policies, RuntimeSnapshot runtime, List<WordEntry> wordCatalog) {
            this.policies = policies;
            this.runtime = runtime;
            this.wordCatalog = wordCatalog;
        }
    }

    /**
     * 由存储执行队列调度的数据库工作接口。
     *
     * @param <T> 本次数据或异步工作返回的类型
     */
    interface Work<T> {
        /**
         * 在存储指定的执行队列中完成数据库工作，异常由外层 Future 接收。
         *
         * @param database 本次事务使用的数据库实例
         */
        T run(ZhijianDatabase database) throws Exception;
    }

    /**
     * 将数据库工作加入单一写入队列，通过 Future 发布成功或异常。
     *
     * @param <T> 本次数据或异步工作返回的类型
     * @param work 由存储队列调度的数据库工作
     */
    <T> CompletableFuture<T> submit(Work<T> work) {
        synchronized (lifecycle) {
            if (closing) return rejected();
            return writer.submit(() -> {
                if (initializationFailure != null) throw new IllegalStateException("Storage initialization failed", initializationFailure);
                return work.run(database);
            });
        }
    }

    /**
     * 等待初始化后在读取队列执行数据库查询。
     *
     * @param <T> 本次数据或异步工作返回的类型
     * @param work 由存储队列调度的数据库工作
     */
    <T> CompletableFuture<T> read(Work<T> work) {
        synchronized (lifecycle) {
            if (closing) return rejected();
        }
        return ready.thenCompose(ignored -> {
            synchronized (lifecycle) {
                if (closing) return rejected();
                return readers.submit(() -> work.run(database));
            }
        });
    }

    /**
     * 为关闭后的调用返回失败结果，避免同步异常越过页面保存收尾。
     * @param <T> 调用方预期的工作返回类型
     * @return 已明确失败的 Future
     */
    private static <T> CompletableFuture<T> rejected() {
        CompletableFuture<T> result = new CompletableFuture<>();
        result.completeExceptionally(new RejectedExecutionException("Storage is closing"));
        return result;
    }

    /**
     * 在写入队列提交后整体更新运行状态快照。
     */
    void refreshRuntimeOnWriter() {
        state = new State(state.policies, RuntimeSnapshot.load(database.runtime()),
                state.wordCatalog);
    }

    /**
     * 复制草稿后事务保存规则组，同一策略的原放行期限继续保留。
     *
     * @param source 需要复制的原始草稿、记录或数据来源
     */
    CompletableFuture<Void> saveGroup(RuleGroup source) {
        return saveGroup(source, Collections.emptyList());
    }

    /** @param source 规则组草稿 @param removedApps 本次明确移除的默认组应用，与规则同事务保存 */
    CompletableFuture<Void> saveGroup(RuleGroup source, List<String> removedApps) {
        RuleGroup draft = source.copy();
        List<String> removals = new ArrayList<>(removedApps);
        return changeConfiguration(db -> {
            if (draft.id <= 0L) throw new IllegalArgumentException("Rule group requires a positive stable id");
            boolean builtIn = BuiltInGroupPreset.isBuiltIn(draft.id);
            if (builtIn && builtInGroups != null) {
                BuiltInGroupPreset preset = BuiltInGroupPreset.find(draft.id);
                builtInGroups.removeMembers(db.policies(), preset, removals);
                draft.apps.clear();
                draft.apps.addAll(builtInGroups.members(db.policies(), preset, builtInGroups.members()));
            } else if (!removals.isEmpty()) {
                throw new IllegalArgumentException("Only default groups support catalog exclusions");
            }
            if (!RuleGroupMembershipPolicy.hasValidGroupMembers(draft)) {
                throw new IllegalArgumentException("Rule groups require at least two distinct apps");
            }
            if (builtIn) {
                RuleGroupValidator.Result result = RuleGroupValidator.validateConfiguration(draft);
                if (!result.isValid()) throw new IllegalArgumentException("Invalid policy: " + result.error);
            } else requireValidDraft(draft);
            PolicyDao dao = db.policies();
            long id = draft.id;
            String key = PolicyCodec.groupKey(id);
            PolicyEntity old = dao.policy(key);
            RuleGroup previous = state.policies.configuredPolicies().get(key);
            PolicyEntity row = PolicyCodec.entity(draft, key, "group",
                    old == null ? 1 : RuleConfigurationComparison.sameBehavior(previous, draft)
                            ? old.revision : Math.addExact(old.revision, 1),
                    old == null ? dao.nextCreationOrder() : old.creationOrder);
            row.groupId = id;
            PolicyCodec.write(dao, row, draft);
            // 已获得的无敌时间保持原期限，到期再使用最新配置复查。
            return null;
        });
    }

    /**
     * 复制草稿后事务保存应用独立规则，实际归属变化才撤销旧放行。
     *
     * @param pkg 需要查询或绑定的应用包名
     * @param source 需要复制的原始草稿、记录或数据来源
     */
    CompletableFuture<Void> saveApp(String pkg, RuleGroup source) {
        RuleGroup draft = source.copy();
        draft.apps.clear();
        draft.apps.add(pkg);
        draft.enabled = true;
        return changeConfiguration(db -> {
            requireValidDraft(draft);
            String key = PolicyCodec.appKey(pkg);
            PolicyEntity old = db.policies().policy(key);
            RuleGroup previous = state.policies.configuredPolicies().get(key);
            PolicyEntity row = PolicyCodec.entity(draft, key, "app",
                    old == null ? 1 : RuleConfigurationComparison.sameBehavior(previous, draft)
                            ? old.revision : Math.addExact(old.revision, 1), 0);
            row.groupId = -1;
            PolicyCodec.write(db.policies(), row, draft);
            return null;
        });
    }

    /**
     * 删除指定策略及其关联数据。
     *
     * @param key 稳定标识或待读取的持久化设置键
     */
    CompletableFuture<Void> removePolicy(String key) {
        return changeConfiguration(db -> {
            BuiltInRuleGroupStore.dismiss(db.policies(), key);
            db.policies().removePolicy(key);
            db.runtime().removeGrantsForPolicy(key);
            return null;
        });
    }

    /**
     * 事务更新全局管控档位，提交成功后发布新的策略快照。
     *
     * @param level 全局管控档位
     */
    public CompletableFuture<Void> setLevel(ControlLevel level) {
        return changeConfiguration(db -> {
            PolicyEntity global = db.policies().policy(PolicyCodec.GLOBAL);
            String key = PolicyCodec.levelKey(level);
            if (!key.equals(global.controlLevel)) {
                global.controlLevel = key;
                global.revision = Math.addExact(global.revision, 1);
                db.policies().updatePolicy(global);
                // 新强度决定后续能否申请；已签发授权不延长，也不提前撤销。
            }
            return null;
        });
    }

    /**
     * 事务更新全局每日额度，提交成功后发布新的策略快照。
     *
     * @param durationMillis 配置或记录的时长，单位为毫秒
     */
    public CompletableFuture<Void> setDailyQuota(long durationMillis) {
        return changeConfiguration(db -> {
            if (!DecimalDuration.hasOneDecimalPrecision(durationMillis, DecimalDuration.MINUTE_MILLIS)) {
                throw new IllegalArgumentException("Invalid quota precision");
            }
            PolicyEntity row = db.policies().policy(PolicyCodec.GLOBAL);
            RuleGroup global = snapshot().effectiveFor("");
            global.dailyLimitMillis = durationMillis;
            if (durationMillis == 0) global.types.remove(RuleType.DAILY_LIMIT);
            else global.types.add(RuleType.DAILY_LIMIT);
            row.revision = Math.addExact(row.revision, 1);
            PolicyCodec.write(db.policies(), row, global);
            return null;
        });
    }

    /**
     * 在单一事务中变更配置，成功提交后再替换内存快照。
     *
     * @param mutation 同一事务内需要执行的配置变更
     */
    private CompletableFuture<Void> changeConfiguration(Work<Void> mutation) {
        return submit(db -> {
            commitConfigurationOnWriter(db, mutation);
            return null;
        });
    }

    /**
     * 配置写入和成员同步共用事务与放行校验，提交后才能更新内存状态。
     * @param db 写入队列持有的数据库
     * @param mutation 同一事务中的配置变更
     */
    private void commitConfigurationOnWriter(ZhijianDatabase db, Work<Void> mutation) {
        ContinuousUsageReader.withCycleLock(() -> {
            PolicySnapshot previous = state.policies;
            long wall = System.currentTimeMillis(), elapsed = SystemClock.elapsedRealtime();
            String bootId = BootIdentity.read(context), zoneId = ZoneId.systemDefault().getId();
            // 上次提交后即使清理中断，重新启用前仍以原已提交配置消除已取消事实。
            pendingInterceptions.clearCancelledReminders(previous, zoneId);
            continuousRuleChanges.checkpoint(previous, bootId, wall, elapsed, zoneId);
            State next = db.runInTransaction(() -> {
                List<PendingInterception> restrictions = previous.level() == ControlLevel.SEAL
                        ? retreatProtection.active(previous,
                                bootId, wall, elapsed, zoneId, GlobalSettings.readDailyResetMinute(context))
                                : Collections.emptyList();
                mutation.run(db);
                PolicySnapshot policies = PolicySnapshot.load(db.policies());
                RetreatChangePolicy.requireAllowed(previous, policies, restrictions);
                if (previous.level() != policies.level()) {
                    retreatProtection.checkpointBeforeLevelChange(previous, bootId, wall, elapsed, zoneId);
                }
                policies = PolicySnapshot.load(db.policies(), UsageTrackingStore.synchronize(db.policies(),
                        policies, installationId, bootId, wall, elapsed));
                // 同一策略的版本变化不缩短旧授权；归属变化或删除才使覆盖关系失效。
                for (PassGrantEntity grant : db.runtime().grants()) {
                    if (!policies.effectivePolicyId(grant.packageName).equals(grant.policyId)
                            || policies.revisionOf(grant.policyId) < 1L) {
                        db.runtime().removeGrant(grant.packageName);
                    }
                }
                return new State(policies, RuntimeSnapshot.load(db.runtime()), state.wordCatalog);
            });
            state = next;
            try {
                pendingInterceptions.clearCancelledReminders(next.policies, zoneId);
                pendingInterceptions.closeChangedWindowUsage(next.policies, wall, zoneId);
                continuousRuleChanges.checkpoint(next.policies, bootId, wall, elapsed, zoneId);
            } catch (IllegalStateException cleanupFailure) {
                // 数据库已经提交；保留成功语义，下次提交前或启动时按已提交配置重试清理。
                DiagnosticLog.error("storage", "", "cancelled_reminder_cleanup_failed", cleanupFailure);
            }
            return null;
        });
    }

    /**
     * 关闭测试使用的存储实例与执行队列。
     */
    CompletableFuture<Void> closeForTest() {
        synchronized (lifecycle) {
            if (closed == null) {
                closing = true;
                closed = CompletableFuture.allOf(writer.shutdown(), readers.shutdown())
                        .thenRun(() -> { if (database != null) database.close(); });
            }
            return closed.thenApply(ignored -> null);
        }
    }

    /**
     * 非页面保存入口也执行完整规则校验，不允许绕过时段冲突检测。
     * @param draft 已复制、不与页面共享的保存草稿
     */
    private static void requireValidDraft(RuleGroup draft) {
        RuleGroupValidator.Result result = RuleGroupValidator.validate(draft);
        if (!result.isValid()) throw new IllegalArgumentException("Invalid policy: " + result.error);
    }
}
