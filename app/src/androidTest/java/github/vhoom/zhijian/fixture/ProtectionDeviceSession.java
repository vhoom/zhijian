package github.vhoom.zhijian.fixture;

import android.app.Activity;
import android.app.AppOpsManager;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.ComponentName;
import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.AtomicFile;
import android.view.View;

import androidx.test.platform.app.InstrumentationRegistry;

import github.vhoom.zhijian.R;
import github.vhoom.zhijian.data.model.settings.InteractionSettings;
import github.vhoom.zhijian.data.model.interception.InterceptionMode;
import github.vhoom.zhijian.data.model.interception.InterceptionChallenge;
import github.vhoom.zhijian.data.store.settings.InteractionSettingsStore;
import github.vhoom.zhijian.accessibility.AccessibilityConnection;
import github.vhoom.zhijian.accessibility.ZhijianAccessibilityService;
import github.vhoom.zhijian.data.model.policy.ControlLevel;
import github.vhoom.zhijian.data.model.policy.RuleGroup;
import github.vhoom.zhijian.data.model.settings.SecondsRange;
import github.vhoom.zhijian.data.model.settings.WordChallengeSettings;
import github.vhoom.zhijian.data.store.AppPolicyStore;
import github.vhoom.zhijian.data.store.ZhijianStorage;
import github.vhoom.zhijian.data.store.RuntimeStateStore;
import github.vhoom.zhijian.data.model.runtime.PendingInterception;
import github.vhoom.zhijian.data.store.interception.PendingInterceptionStore;
import github.vhoom.zhijian.data.store.interception.WordProgressStore;
import github.vhoom.zhijian.data.store.settings.GlobalSettings;
import github.vhoom.zhijian.ui.interception.InterceptionActivity;
import github.vhoom.zhijian.util.config.AppConfig;

import java.io.InputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 为真实拦截回归隔离目标应用配置，并在结束时恢复系统授权和设置。
 *
 * 维护日期：2026年11月14日
 */
public final class ProtectionDeviceSession implements AutoCloseable {
    public final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    public final Context context = instrumentation.getTargetContext();
    public final String target = instrumentation.getContext().getPackageName();
    private final UiAutomation automation = instrumentation.getUiAutomation(
            UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
    private final AppPolicyStore policies = new AppPolicyStore(context);
    private final GlobalSettings settings = new GlobalSettings(context, new AppConfig(context));
    private final InteractionSettingsStore interactions = new InteractionSettingsStore(context, new AppConfig(context));
    private final InteractionSettings originalRepair;
    private final InteractionSettings originalWuwei;
    private final RuleGroup original;
    private final ControlLevel originalLevel;
    private final SecondsRange originalHold;
    private final WordChallengeSettings originalWord;
    private final String oldServices;
    private final int oldEnabled;
    private final int oldUsageMode;
    private final String oldImmersiveConfirmation;
    private final Instrumentation.ActivityMonitor monitor;
    private final FixtureCleanup cleanup = new FixtureCleanup();
    private Activity lastInterception;
    private boolean started;
    private AtomicFile wordProgressFile;
    private byte[] originalWordProgress;

    /** 读取需要恢复的配置；创建实例时不修改用户配置。 */
    public ProtectionDeviceSession() {
        ZhijianStorage.get(context).ready().join();
        originalRepair = interactions.read(InterceptionMode.REPAIR);
        originalWuwei = interactions.read(InterceptionMode.WUWEI);
        original = policies.policyOf(target);
        originalLevel = settings.level();
        originalHold = settings.buttonFrictionRange();
        originalWord = settings.wordChallengeSettings();
        oldServices = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        oldEnabled = Settings.Secure.getInt(context.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_ENABLED, 0);
        oldUsageMode = context.getSystemService(AppOpsManager.class).unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.getPackageName());
        oldImmersiveConfirmation = Settings.Secure.getString(context.getContentResolver(),
                "immersive_mode_confirmations");
        monitor = instrumentation.addMonitor(InterceptionActivity.class.getName(), null, false);
        registerCleanup();
    }

    /**
     * 配置短挑战并连接真实服务，随后启动测试目标应用。
     *
     * @param policy 本次测试目标应用的规则
     * @param level 本次验证的管控档位
     */
    public void start(RuleGroup policy, ControlLevel level) throws Exception {
        start(policy, level, () -> { });
    }

    /**
     * 在正式页面冻结选项前完成本次交互配置，避免测试使用预览或修改已创建的会话。
     * @param policy 本次测试目标应用的规则
     * @param level 本次验证的管控档位
     * @param configureInteractions 默认短挑战配置后、连接服务和启动目标前执行的配置动作
     */
    public void start(RuleGroup policy, ControlLevel level, Runnable configureInteractions) throws Exception {
        cleanup.initialize(() -> {
            started = true;
            isolateWordProgress();
            policies.save(target, policy).join();
            settings.setLevel(level).join();
            settings.setButtonFrictionRange(new SecondsRange(100L, 100L));
            settings.setWordChallengeSettings(new WordChallengeSettings(1, 100L));
            // 旧场景使用短时按压和单词；数独只由对应场景显式启用。
            for (InterceptionChallenge challenge : new InterceptionChallenge[]{
                    InterceptionChallenge.BUTTON_HOLD, InterceptionChallenge.WORD_TRANSLATION}) {
                interactions.setEnabled(InterceptionMode.REPAIR, challenge, true);
            }
            interactions.setEnabled(InterceptionMode.REPAIR, InterceptionChallenge.SUDOKU, false);
            interactions.setSudokuDifficulty(github.vhoom.zhijian.data.model.settings.SudokuDifficulty.EASY);
            configureInteractions.run();
            shell("appops set " + context.getPackageName() + " GET_USAGE_STATS allow");
            // 首次全屏的系统提示会占用返回输入焦点；隔离此引导，结束后恢复原设置。
            writeSecureSetting("immersive_mode_confirmations", "confirmed");
            connectService();
            openTarget();
        });
    }

    /** 重建测试运行器终止旧进程后的服务连接，保留其他无障碍服务并去重当前组件。 */
    private void connectService() throws Exception {
        ComponentName component = new ComponentName(context, ZhijianAccessibilityService.class);
        Set<String> services = new LinkedHashSet<>();
        if (oldServices != null && !oldServices.isEmpty()) {
            for (String saved : oldServices.split(":")) {
                if (!component.equals(ComponentName.unflattenFromString(saved))) services.add(saved);
            }
        }
        if (!AccessibilityConnection.isConnected()) {
            writeSecureSetting(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, String.join(":", services));
            // 系统设置观察者异步执行；保留停用间隔，使旧连接状态确实清除后再重新启用。
            SystemClock.sleep(500L);
        }
        services.add(component.flattenToString());
        writeSecureSetting(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, String.join(":", services));
        writeSecureSetting(Settings.Secure.ACCESSIBILITY_ENABLED, "1");
        long deadline = SystemClock.elapsedRealtime() + 8_000L;
        while (!AccessibilityConnection.isConnected() && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(50L);
        }
        if (!AccessibilityConnection.isConnected()) throw new AssertionError("Service did not connect");
    }

    /** 从系统启动测试目标应用，等待切换完成，使前台窗口和用量事件真实发生。 */
    public void openTarget() throws Exception {
        shell("am start -W -n " + context.getPackageManager().getLaunchIntentForPackage(target)
                .getComponent().flattenToString());
    }

    /** 返回桌面并消费旧页面的重复恢复通知，供再次打开目标应用测试。 */
    public void goHome() throws Exception {
        monitor.waitForActivityWithTimeout(1L);
        shell("am start -W -a android.intent.action.MAIN -c android.intent.category.HOME");
        monitor.waitForActivityWithTimeout(1L);
    }

    /** 执行系统返回操作，覆盖不点击继续按钮的恢复路径。 */
    public void pressBack() throws Exception {
        if (!automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)) {
            throw new AssertionError("System back action failed");
        }
    }

    /**
     * 等待真实拦截页面出现并处理完主线程初始化。
     *
     * @param timeoutMs 本次等待允许的最大毫秒数
     */
    public Activity awaitInterception(long timeoutMs) {
        Activity activity = monitor.waitForActivityWithTimeout(timeoutMs);
        instrumentation.waitForIdleSync();
        if (activity != null) lastInterception = activity;
        return activity;
    }

    /** @param page 正式单词页面；完成本夹具默认的一题挑战，每次选择等待落盘再继续 */
    public void completeWordChallenge(Activity page) {
        int[] choices = {R.id.interception_word_choice_1, R.id.interception_word_choice_2,
                R.id.interception_word_choice_3, R.id.interception_word_choice_4,
                R.id.interception_word_choice_5, R.id.interception_word_choice_6};
        for (int id : choices) {
            if (!awaitWordInputOrCompletion(page)) return;
            instrumentation.runOnMainSync(() -> page.findViewById(id).performClick());
        }
        if (awaitWordInputOrCompletion(page)) throw new AssertionError("Expected a completed one-question word challenge");
    }

    /** @param page 正式单词页面 @return 上一答案落盘后仍需作答为 true，进入完成或反问为 false */
    private boolean awaitWordInputOrCompletion(Activity page) {
        boolean[] ready = {false}, completed = {false};
        long deadline = SystemClock.elapsedRealtime() + 5_000L;
        while (!ready[0] && !completed[0] && SystemClock.elapsedRealtime() < deadline) {
            instrumentation.runOnMainSync(() -> {
                View choice = page.findViewById(R.id.interception_word_choice_1);
                completed[0] = page.findViewById(R.id.interception_word_continue) != null
                        || page.findViewById(R.id.interception_confirmation_continue) != null;
                ready[0] = choice != null && choice.isShown() && choice.isEnabled();
            });
            if (!ready[0] && !completed[0]) SystemClock.sleep(25L);
        }
        if (!ready[0] && !completed[0]) throw new AssertionError("Word answer did not finish saving");
        return !completed[0];
    }

    /** 执行全部恢复步骤；初始化失败已回滚或重复关闭时不再次修改设备状态。 */
    @Override public void close() throws Exception {
        cleanup.close();
    }

    /** 按设备退出、授权、测试事实、配置、监听器的顺序登记独立恢复步骤。 */
    private void registerCleanup() {
        addRecovery(this::goHome);
        addRecovery(this::finishInterception);
        addRecovery(() -> writeSecureSetting(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, oldServices));
        addRecovery(() -> writeSecureSetting(Settings.Secure.ACCESSIBILITY_ENABLED, Integer.toString(oldEnabled)));
        addRecovery(() -> writeSecureSetting("immersive_mode_confirmations", oldImmersiveConfirmation));
        addRecovery(this::restoreUsageAccess);
        addRecovery(() -> new RuntimeStateStore(context).revokeGrant(target).join());
        addRecovery(() -> new github.vhoom.zhijian.data.store.interception.BlockContinuationStore(context).clear(target));
        addRecovery(this::clearTestRestrictions);
        addRecovery(() -> {
            if (original == null) policies.reset(target).join();
            else policies.save(target, original).join();
        });
        addRecovery(() -> settings.setLevel(originalLevel).join());
        addRecovery(() -> settings.setButtonFrictionRange(originalHold));
        addRecovery(() -> settings.setWordChallengeSettings(originalWord));
        registerInteractionRecovery(InterceptionMode.REPAIR, originalRepair);
        addRecovery(() -> interactions.setTemporaryPassEnabled(
                InterceptionMode.WUWEI, originalWuwei.options.temporaryPassEnabled));
        addRecovery(() -> interactions.setDynamicButtonsEnabled(originalWuwei.options.dynamicButtonsEnabled));
        addRecovery(this::clearTestPresentation);
        addRecovery(this::clearTestCycle);
        addRecovery(this::restoreWordProgress);
        cleanup.add(() -> instrumentation.removeMonitor(monitor));
    }

    /**
     * 只有正式开始过配置才恢复设备；仅构造后关闭时仍会释放监听器。
     * @param action 一个独立设备恢复步骤
     */
    private void addRecovery(FixtureCleanup.Action action) {
        cleanup.add(() -> {
            if (started) action.run();
        });
    }

    /** 在主线程关闭本次实际展示过的拦截任务，失败不妨碍其余恢复步骤。 */
    private void finishInterception() {
        Activity page = lastInterception;
        instrumentation.runOnMainSync(() -> {
            if (page != null && !page.isDestroyed()) page.finishAndRemoveTask();
        });
    }

    /** 停止的测试页面之外保留原始单词文件，正式回归从独立空进度开始。 */
    private void isolateWordProgress() throws Exception {
        WordProgressStore.awaitPendingWrites().get(5, java.util.concurrent.TimeUnit.SECONDS);
        AtomicFile file = new AtomicFile(new File(context.getFilesDir(), "word_progress.json"));
        byte[] original = file.getBaseFile().exists() || new File(file.getBaseFile().getPath() + ".bak").exists()
                ? file.readFully() : null;
        originalWordProgress = original;
        wordProgressFile = file;
        file.delete();
    }

    /** 等待页面保存结束后恢复原始字节，初始化途中失败也可回滚。 */
    private void restoreWordProgress() throws Exception {
        if (wordProgressFile == null) return;
        WordProgressStore.awaitPendingWrites().get(5, java.util.concurrent.TimeUnit.SECONDS);
        wordProgressFile.delete();
        if (originalWordProgress == null) return;
        FileOutputStream output = wordProgressFile.startWrite();
        try {
            output.write(originalWordProgress);
            output.getFD().sync();
            wordProgressFile.finishWrite(output);
        } catch (IOException failure) {
            wordProgressFile.failWrite(output);
            throw failure;
        }
    }

    /** 恢复用量访问的原始 AppOps 模式，包含仅前台允许的模式。 */
    private void restoreUsageAccess() throws Exception {
        String mode = oldUsageMode == AppOpsManager.MODE_ALLOWED ? "allow"
                : oldUsageMode == AppOpsManager.MODE_IGNORED ? "ignore"
                : oldUsageMode == AppOpsManager.MODE_ERRORED ? "deny"
                : oldUsageMode == AppOpsManager.MODE_FOREGROUND ? "foreground" : "default";
        shell("appops set " + context.getPackageName() + " GET_USAGE_STATS " + mode);
    }

    /** 清理测试应用展示键，提交失败交由恢复队列汇总。 */
    private void clearTestPresentation() {
        if (!context.getSharedPreferences("pending_interceptions", Context.MODE_PRIVATE)
                .edit().remove(target).commit()) {
            throw new IllegalStateException("Could not clear test interception presentation");
        }
    }

    /** 清理测试应用独立策略的连续周期，避免已删除规则仍留下测试身份。 */
    private void clearTestCycle() {
        if (!context.getSharedPreferences("continuous_usage_cycles", Context.MODE_PRIVATE)
                .edit().remove("app:" + target).commit()) {
            throw new IllegalStateException("Could not clear test continuous usage cycle");
        }
    }

    /** 逐条清理测试应用独立策略的事实，单条失败仍尝试其余事实。 */
    private void clearTestRestrictions() throws Exception {
        PendingInterceptionStore pending = new PendingInterceptionStore(context);
        FixtureCleanup facts = new FixtureCleanup();
        for (PendingInterception fact : pending.restrictions()) {
            if (!fact.decision.policyId.equals("app:" + target)) continue;
            facts.add(() -> pending.clearBlock(fact));
            facts.add(() -> pending.clear(fact.decision.packageName, fact.sessionId));
        }
        facts.close();
    }

    /**
     * 通过独立 Shell 写系统设置，避免借用权限的应用进程触发厂商扩展 AppOps 的映射异常。
     *
     * @param key 测试使用的固定系统设置键
     * @param value 不含空白的组件列表、数字或系统确认标记；null 或空串表示未设置
     */
    private void writeSecureSetting(String key, String value) throws Exception {
        // executeShellCommand 直接拆分参数，不能添加会被当作设置内容的 Shell 引号。
        shell(value == null || value.isEmpty() ? "settings delete secure " + key
                : "settings put secure " + key + " " + value);
    }

    /**
     * 在测试设备执行只针对目标应用和授权的系统命令。
     *
     * @param command 已确定目标的系统测试命令
     */
    private void shell(String command) throws Exception {
        try (InputStream output = new ParcelFileDescriptor.AutoCloseInputStream(
                automation.executeShellCommand(command))) {
            byte[] buffer = new byte[256];
            while (output.read(buffer) != -1) { /* 等待命令完成。 */ }
        }
    }
    /**
     * 将模式恢复拆成独立步骤，单项设置失败不跳过其他开关和参数。
     * @param mode 配置所属模式
     * @param original 测试前的独立配置
     */
    private void registerInteractionRecovery(InterceptionMode mode, InteractionSettings original) {
        addRecovery(() -> interactions.setTemporaryPassEnabled(mode, original.options.temporaryPassEnabled));
        if (mode == InterceptionMode.REPAIR) {
            addRecovery(() -> interactions.setObstaclePagesEnabled(original.options.obstaclePagesEnabled));
            addRecovery(() -> interactions.setConfirmationEnabled(original.options.confirmationEnabled));
            addRecovery(() -> interactions.setSudokuDifficulty(original.sudokuDifficulty));
        }
        addRecovery(() -> interactions.setHoldRange(mode, original.holdRange));
        addRecovery(() -> interactions.setWordSettings(mode, original.word));
        // 先恢复原来开启的选项，再关闭其他选项，避免最后一个挑战保护改变恢复结果。
        for (InterceptionChallenge challenge : original.enabledChallenges) {
            addRecovery(() -> interactions.setEnabled(mode, challenge, true));
        }
        for (InterceptionChallenge challenge : InterceptionChallenge.values()) {
            addRecovery(() -> interactions.setEnabled(mode, challenge, original.enabledChallenges.contains(challenge)));
        }
    }
}
