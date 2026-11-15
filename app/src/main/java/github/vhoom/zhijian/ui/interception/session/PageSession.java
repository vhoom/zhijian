package github.vhoom.zhijian.ui.interception.session;

import github.vhoom.zhijian.data.model.interception.InterceptionStage;
import github.vhoom.zhijian.data.model.settings.InteractionSettings;
import github.vhoom.zhijian.domain.interception.InterceptionDecision;
import github.vhoom.zhijian.domain.interception.word.WordEntry;

import java.util.List;

/**
 * 传给独立页面的会话快照；只含值和只读配置，不持有页面或存储对象。
 *
 * 维护日期：2026年11月14日
 */
public final class PageSession {
    public final InterceptionDecision decision;
    public final String sessionId;
    public final int copyVariant;
    /** 正式页面按规则会话保存主句；独立页面样例及 Debug 预览只读取序号样例。 */
    public final boolean persistentHeadlines;
    /** 单词与数独正式页面使用磁盘进度；预览和独立样例仅使用内存。 */
    public final boolean persistentProgress;
    /** 本次实际进入的标识；配置重建沿用，退出重进更换，用于刷新未开始的挑战。 */
    public final String challengeEntryId;
    /** 本次打开首屏的控件种子，与主句、挑战和运行会话身份独立。 */
    public final String initialActionSeed;
    public final InterceptionStage stage;
    public final InteractionSettings settings;
    public final List<WordEntry> wordCatalog;
    public final long holdDurationMs;

    /**
     * 使用稳定会话种子装配可重复的预览和独立页面。
     * @param decision 原规则决定
     * @param sessionId 本轮稳定会话标识
     * @param copyVariant 稳定文案变体
     * @param stage 当前页面阶段
     * @param settings 本模式配置快照
     * @param wordCatalog 只读单词目录
     */
    public PageSession(InterceptionDecision decision, String sessionId, int copyVariant,
            InterceptionStage stage, InteractionSettings settings, List<WordEntry> wordCatalog) {
        this(decision, sessionId, copyVariant, stage, settings, wordCatalog, sessionId);
    }

    /**
     * 保存当前页面上下文，首屏控件可按打开次数独立抽样。
     * @param decision 原规则决定
     * @param sessionId 本轮稳定运行会话标识
     * @param copyVariant 稳定主句变体
     * @param stage 当前页面阶段
     * @param settings 本模式配置快照
     * @param wordCatalog 只读单词目录
     * @param initialActionSeed 本次打开的控件种子，系统重建时复用
     */
    public PageSession(InterceptionDecision decision, String sessionId, int copyVariant,
            InterceptionStage stage, InteractionSettings settings, List<WordEntry> wordCatalog,
            String initialActionSeed) {
        this(decision, sessionId, copyVariant, stage, settings, wordCatalog, initialActionSeed, false);
    }

    /**
     * 显式区分正式会话主句和不写存储的预览样例，不改变控件独立抽样。
     * @param decision 原规则决定
     * @param sessionId 本轮规则拦截的稳定身份
     * @param copyVariant 固定提示变体及预览样例序号
     * @param stage 当前页面阶段
     * @param settings 本模式配置快照
     * @param wordCatalog 只读单词目录
     * @param initialActionSeed 本次打开的控件种子，系统重建时复用
     * @param persistentHeadlines 正式页面为 true，Debug 预览及独立样例为 false
     */
    public PageSession(InterceptionDecision decision, String sessionId, int copyVariant,
            InterceptionStage stage, InteractionSettings settings, List<WordEntry> wordCatalog,
            String initialActionSeed, boolean persistentHeadlines) {
        this(decision, sessionId, copyVariant, stage, settings, wordCatalog, initialActionSeed, persistentHeadlines, false);
    }

    /**
     * 分别指定文案与挑战进度的保存边界。
     * @param decision 规则决定 @param sessionId 拦截身份 @param copyVariant 文案序号
     * @param stage 当前阶段 @param settings 配置快照 @param wordCatalog 单词目录
     * @param initialActionSeed 首屏控件种子 @param persistentHeadlines 是否保存主句
     * @param persistentProgress 是否保存正式挑战进度
     */
    public PageSession(InterceptionDecision decision, String sessionId, int copyVariant,
            InterceptionStage stage, InteractionSettings settings, List<WordEntry> wordCatalog,
            String initialActionSeed, boolean persistentHeadlines, boolean persistentProgress) {
        this(decision, sessionId, copyVariant, stage, settings, wordCatalog, initialActionSeed,
                persistentHeadlines, persistentProgress, sessionId);
    }

    /**
     * 装配带独立进入标识的正式页面，重建不改变未开始题目。
     * @param decision 规则决定 @param sessionId 拦截身份 @param copyVariant 文案序号
     * @param stage 当前阶段 @param settings 配置快照 @param wordCatalog 单词目录
     * @param initialActionSeed 首屏种子 @param persistentHeadlines 是否保存主句
     * @param persistentProgress 是否保存挑战进度 @param challengeEntryId 本次实际进入标识
     */
    public PageSession(InterceptionDecision decision, String sessionId, int copyVariant,
            InterceptionStage stage, InteractionSettings settings, List<WordEntry> wordCatalog,
            String initialActionSeed, boolean persistentHeadlines, boolean persistentProgress, String challengeEntryId) {
        this.decision = decision;
        this.sessionId = sessionId;
        this.copyVariant = copyVariant;
        this.persistentHeadlines = persistentHeadlines;
        this.persistentProgress = persistentProgress;
        this.challengeEntryId = challengeEntryId;
        this.initialActionSeed = initialActionSeed;
        this.stage = stage;
        this.settings = settings;
        this.wordCatalog = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(wordCatalog));
        this.holdDurationMs = settings.holdRange.valueFor(sessionId + ":button-friction");
    }
}
