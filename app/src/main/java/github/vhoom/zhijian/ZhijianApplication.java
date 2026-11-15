package github.vhoom.zhijian;

import android.app.Application;

import github.vhoom.zhijian.data.store.ZhijianStorage;
import github.vhoom.zhijian.util.diagnostics.DiagnosticLog;
import github.vhoom.zhijian.ui.interception.copy.InterceptionCopyCatalog;
import github.vhoom.zhijian.data.model.settings.WordChallengeSettings;
import github.vhoom.zhijian.data.model.settings.SecondsRange;
import github.vhoom.zhijian.util.config.AppConfig;
import github.vhoom.zhijian.runtime.policy.InstalledPackageObserver;
import github.vhoom.zhijian.runtime.usage.UsageArchiveCoordinator;
import github.vhoom.zhijian.runtime.usage.UsageArchiveJobService;

import java.util.concurrent.CompletableFuture;

/**
 * 创建进程级存储并在后台校验必要资源，页面重建复用同一次启动结果。
 *
 * 维护日期：2026年11月14日
 */
public final class ZhijianApplication extends Application {
    private CompletableFuture<Void> startupReady;

    /**
     * 创建页面或进程依赖，并绑定本次启动所需的状态。
     */
    @Override
    public void onCreate() {
        super.onCreate();
        DiagnosticLog.install(this);
        InstalledPackageObserver.register(this, ZhijianStorage.get(this));
        UsageArchiveJobService.schedule(this);
        ZhijianStorage.get(this).ready().thenRun(() -> UsageArchiveCoordinator.synchronize(this, true));
        startupReady = ZhijianStorage.get(this).ready().thenRunAsync(this::checkRequiredResources);
        startupReady.whenComplete((ignored, failure) -> {
            if (failure == null) DiagnosticLog.info("startup", "", "storage_and_resources_ready");
            else DiagnosticLog.error("startup", "", "storage_or_resources_failed", failure);
        });
    }

    /** 返回包括数据库、全部内置词库及拦截文案配置完整性检查的启动就绪信号。 */
    public CompletableFuture<Void> ready() { return startupReady.thenApply(ignored -> null); }

    /** 在后台启动阶段复用正式配置解析器；资源缺失或损坏时阻止页面进入可用状态。 */
    private void checkRequiredResources() {
        InterceptionCopyCatalog catalog = new InterceptionCopyCatalog(this);
        catalog.preload();
        AppConfig config = new AppConfig(this);
        new WordChallengeSettings(config.wordChallengeQuestionCount(), config.wordChallengeMinDwellSeconds() * 1000L);
        new SecondsRange(config.buttonFrictionMinSeconds() * 1000L, config.buttonFrictionMaxSeconds() * 1000L);
    }
}
