package github.vhoom.zhijian.ui.interception.request;

import android.content.Context;
import android.content.Intent;

import github.vhoom.zhijian.BuildConfig;
import github.vhoom.zhijian.R;
import github.vhoom.zhijian.data.model.interception.InterceptionStage;
import github.vhoom.zhijian.data.model.policy.ControlLevel;
import github.vhoom.zhijian.data.model.runtime.PendingInterception;
import github.vhoom.zhijian.data.store.interception.InterceptionCopyStore;
import github.vhoom.zhijian.domain.interception.InterceptionDecision;
import github.vhoom.zhijian.ui.interception.preview.InterceptionPreviewCatalog;
import github.vhoom.zhijian.ui.interception.preview.InterceptionPreviewCatalog.Presentation;

/**
 * 拦截请求编解码与预览目录，独立于页面渲染和运行存储。
 *
 * 维护日期：2026年11月10日
 */
public final class InterceptionIntents {
    public static final String EXTRA_PACKAGE = "interception.package";
    public static final String EXTRA_LABEL = "interception.label";
    public static final String EXTRA_POLICY = "interception.policy";
    public static final String EXTRA_REVISION = "interception.revision";
    public static final String EXTRA_KIND = "interception.kind";
    public static final String EXTRA_TRIGGER = "interception.trigger";
    public static final String EXTRA_LEVEL = "interception.level";
    public static final String EXTRA_WINDOW = "interception.window";
    public static final String EXTRA_CURRENT = "interception.current";
    public static final String EXTRA_LIMIT = "interception.limit";
    public static final String EXTRA_RESET = "interception.reset";
    public static final String EXTRA_PASS = "interception.pass";
    public static final String EXTRA_INTERRUPT_PAUSE = "interception.interrupt_pause";
    public static final String EXTRA_SESSION = "interception.session";
    public static final String EXTRA_OCCURRED = "interception.occurred";
    public static final String EXTRA_STARTED_ELAPSED = "interception.started_elapsed";
    public static final String EXTRA_STARTED_BOOT = "interception.started_boot";
    public static final String EXTRA_COPY_VARIANT = "interception.copy_variant";
    public static final String EXTRA_DEBUG_PREVIEW = "interception.debug_preview";
    public static final String EXTRA_DEBUG_STAGE = "interception.debug_stage";
    public static final String EXTRA_DEBUG_GALLERY = "interception.debug_gallery";
    public static final String EXTRA_DEBUG_PAGE = "interception.debug_page";
    public static final String EXTRA_DEBUG_SAMPLE = "interception.debug_sample";
    public static final String EXTRA_DEBUG_PRESENTATION = "interception.debug_presentation";

    /** 只有 Debug 构建访问预览目录，release 不初始化预览对象。 */
    public static final int PAGE_COUNT = BuildConfig.DEBUG ? InterceptionPreviewCatalog.PAGE_COUNT : 0;

    /** 工具类禁止实例化。 */
    private InterceptionIntents() { }

    /**
     * 将规则判断结果封装成生产环境拦截页启动参数。
     *
     * @param context 访问系统服务、资源或持久化文件的上下文
     * @param decision 规则引擎给出的拦截决定
     * @param appLabel 供界面和审计显示的应用名称
     * @param sessionId 本次拦截或使用会话的稳定标识
     */
    public static Intent intent(Context context, InterceptionDecision decision,
            String appLabel, String sessionId) {
        return createIntent(context, decision, appLabel, sessionId, InterceptionCopyStore.next(context));
    }

    /** @param context 启动上下文 @param decision 原决定 @param appLabel 应用名称
     * @param sessionId 稳定会话 @param copyVariant 会话文案种子 @return 完整启动请求 */
    private static Intent createIntent(Context context, InterceptionDecision decision, String appLabel,
            String sessionId, int copyVariant) {
        return new Intent().setClassName(context, "github.vhoom.zhijian.ui.interception.InterceptionActivity")
                .putExtra(EXTRA_PACKAGE, decision.packageName)
                .putExtra(EXTRA_LABEL, appLabel)
                .putExtra(EXTRA_POLICY, decision.policyId)
                .putExtra(EXTRA_REVISION, decision.policyRevision)
                .putExtra(EXTRA_KIND, decision.kind.name())
                .putExtra(EXTRA_TRIGGER, decision.trigger.name())
                .putExtra(EXTRA_LEVEL, decision.level.name())
                .putExtra(EXTRA_WINDOW, decision.windowId)
                .putExtra(EXTRA_CURRENT, decision.currentValue)
                .putExtra(EXTRA_LIMIT, decision.limitValue)
                .putExtra(EXTRA_RESET, decision.resetAtMs)
                .putExtra(EXTRA_PASS, decision.passDurationMs)
                .putExtra(EXTRA_INTERRUPT_PAUSE, decision.minimumInterruptionPauseDurationMs)
                .putExtra(EXTRA_SESSION, sessionId)
                .putExtra(EXTRA_OCCURRED, System.currentTimeMillis())
                .putExtra(EXTRA_COPY_VARIANT, copyVariant);
    }

    /**
     * 恢复原会话的计时起点，离开页面不会延长或跳过剩余休息。
     *
     * @param context 页面启动上下文
     * @param pending 已持久化的原拦截会话
     * @param appLabel 被拦截应用的显示名称
     */
    public static Intent intent(Context context, PendingInterception pending, String appLabel) {
        int variant = new github.vhoom.zhijian.data.store.interception.PendingInterceptionStore(context)
                .copyVariantForSession(pending.sessionId);
        return createIntent(context, pending.decision, appLabel, pending.sessionId,
                variant < 0 ? pending.sessionId.hashCode() : variant)
                .putExtra(EXTRA_OCCURRED, pending.occurredAtMs)
                .putExtra(EXTRA_STARTED_ELAPSED, pending.startedElapsedMs)
                .putExtra(EXTRA_STARTED_BOOT, pending.bootId);
    }

    /**
     * Debug 变体的 ADB 预览入口，不读取或修改真实策略与运行记录。
     *
     * @param context 访问系统服务、资源或持久化文件的上下文
     * @param kind 普通拦截或打断拦截的类别
     * @param level 全局管控档位
     * @param sessionId 本次拦截或使用会话的稳定标识
     */
    public static Intent debugIntent(Context context, InterceptionDecision.Kind kind,
            ControlLevel level, String sessionId) {
        return debugIntent(context, kind, level, null, sessionId);
    }

    /**
     * Debug 预览可直接打开指定状态，便于真机遍历全部拦截页。
     *
     * @param context 访问系统服务、资源或持久化文件的上下文
     * @param kind 普通拦截或打断拦截的类别
     * @param level 全局管控档位
     * @param previewStage 调试预览指定的交互阶段
     * @param sessionId 本次拦截或使用会话的稳定标识
     */
    public static Intent debugIntent(Context context, InterceptionDecision.Kind kind,
            ControlLevel level, InterceptionStage previewStage, String sessionId) {
        if (!BuildConfig.DEBUG) throw new IllegalStateException("Debug preview requires a debug build");
        return debugIntent(context, kind, level, previewStage, sessionId, InterceptionPreviewCatalog.nextVariant());
    }

    /** @param context 页面上下文 @param kind 触发类别 @param level 档位 @param previewStage 指定页
     * @param sessionId 稳定会话 @param variant 预览专用文案变体 @return 不写生产序号的预览请求 */
    private static Intent debugIntent(Context context, InterceptionDecision.Kind kind,
            ControlLevel level, InterceptionStage previewStage, String sessionId, int variant) {
        if (!BuildConfig.DEBUG) throw new IllegalStateException("Debug preview requires a debug build");
        InterceptionDecision decision = new InterceptionDecision("debug.preview", "debug", 1,
                kind, kind == InterceptionDecision.Kind.INTERRUPT
                ? InterceptionDecision.Trigger.CONTINUOUS
                : InterceptionDecision.Trigger.WINDOW_BAN,
                level, "debug-window", 60_000L, 60_000L,
                kind == InterceptionDecision.Kind.INTERRUPT
                        ? 0L : System.currentTimeMillis() + 10 * 60_000L,
                60_000L, kind == InterceptionDecision.Kind.INTERRUPT ? 60_000L : 0L);
        Intent intent = createIntent(context, decision,
                context.getString(R.string.interception_preview_app), sessionId, variant)
                .putExtra(EXTRA_DEBUG_PREVIEW, true);
        if (previewStage != null) intent.putExtra(EXTRA_DEBUG_STAGE, previewStage.name());
        return intent;
    }

    /**
     * 创建开发者页面使用的全量拦截页面滑动预览。
     *
     * @param context 访问系统服务、资源或持久化文件的上下文
     */
    public static Intent debugGalleryIntent(Context context) {
        return debugPageIntent(context, 0);
    }

    /**
     * 为滑动预览中的指定页生成真实拦截 Activity 参数。
     *
     * @param context 访问系统服务、资源或持久化文件的上下文
     * @param pageIndex 调试图集目标页索引，允许首尾循环
     */
    public static Intent debugPageIntent(Context context, int pageIndex) {
        return debugPageIntent(context, pageIndex, 0, Presentation.RANDOM);
    }

    /** @param context 页面上下文 @param pageIndex 图集页码 @param sample 抽样编号
     * @param requested 指定控件样例 @return 重建及前后翻页均可复现的预览请求 */
    public static Intent debugPageIntent(Context context, int pageIndex, int sample, Presentation requested) {
        if (!BuildConfig.DEBUG) throw new IllegalStateException("Debug preview requires a debug build");
        int normalized = InterceptionPreviewCatalog.normalize(pageIndex);
        InterceptionPreviewCatalog.Page page = InterceptionPreviewCatalog.page(normalized);
        Presentation presentation = requested.forPage(page);
        InterceptionDecision.Kind kind = InterceptionPreviewCatalog.reminder(normalized)
                ? InterceptionDecision.Kind.INTERRUPT : InterceptionDecision.Kind.BLOCK;
        return debugIntent(context, kind, page.level, page.stage,
                InterceptionPreviewCatalog.session(normalized, sample, presentation), sample)
                .putExtra(EXTRA_DEBUG_GALLERY, true)
                .putExtra(EXTRA_DEBUG_PAGE, normalized)
                .putExtra(EXTRA_DEBUG_SAMPLE, sample)
                .putExtra(EXTRA_DEBUG_PRESENTATION, presentation.name());
    }

}
