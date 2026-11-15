package github.vhoom.zhijian.ui.interception.copy;

import android.content.Context;
import android.content.res.Resources;
import github.vhoom.zhijian.R;
import github.vhoom.zhijian.data.store.interception.InterceptionHeadlineStore;
import github.vhoom.zhijian.util.format.TextTemplate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 动态文案的 XML 适配器：各库独立选句，固定题干与状态通过字符串资源维护。
 *
 * 维护日期：2026年11月14日
 */
public final class InterceptionCopyCatalog {
    /** 唯一的动态文案分类表；每一项对应一个可独立增删的数组。 */
    public enum Pool {
        /** 普通拦截主句，提醒、限制及闭关共用。 */
        GENERAL_HEADLINES(R.array.interception_general_headlines),
        /** 按压挑战主句，按压状态变化时保持原句。 */
        HOLD_HEADLINES(R.array.interception_hold_headlines),
        /** 单词风暴主句。 */
        WORD_HEADLINES(R.array.interception_word_headlines),
        /** 数独完成语句，按本局身份稳定选取。 */
        SUDOKU_COMPLETIONS(R.array.interception_sudoku_completions),
        /** 再次确认主句。 */
        CONFIRMATION_HEADLINES(R.array.interception_confirmation_headlines),
        /** 单词选错文案。 */
        WORD_WRONG_FEEDBACK(R.array.interception_word_wrong_feedback),
        /** 退出控件文案。 */
        EXIT_ACTIONS(R.array.interception_exit_actions),
        /** 继续使用控件文案，临时放行共用。 */
        CONTINUE_ACTIONS(R.array.interception_continue_actions),
        /** 前往挑战控件文案。 */
        CHALLENGE_ACTIONS(R.array.interception_challenge_actions),
        /** 盲盒控件文案。 */
        BLIND_BOX_ACTIONS(R.array.interception_blind_box_actions);
        /** 对应的 Android 字符串数组资源。 */
        final int resource;
        /** @param resource 对应字符串数组 */
        Pool(int resource) { this.resource = resource; }
    }

    /** 按压状态仅切换固定按钮提示，主句保持本次选句。 */
    public enum State { IDLE, ACTIVE }

    /** 实际使用文案的页面；普通首屏与闭关页面共用普通主句库。 */
    public enum Page { GENERAL, CONFIRMATION, HOLD, WORD, FORBIDDEN }

    private static final Map<Resources, Map<Integer, List<String>>> CACHE = new WeakHashMap<>();
    private final Resources resources;
    private final InterceptionHeadlineStore headlines;

    /** @param context 资源上下文，不保存 Activity */
    public InterceptionCopyCatalog(Context context) {
        resources = context.getResources();
        headlines = new InterceptionHeadlineStore(context);
    }

    /** 后台启动校验动态库及固定按压、单词题干模板。 */
    public void preload() {
        for (Pool pool : Pool.values()) array(pool.resource);
        TextTemplate.validate(resources.getString(R.string.interception_word_prompt_en_zh), "word");
        TextTemplate.validate(resources.getString(R.string.interception_word_prompt_zh_en), "word");
        TextTemplate.validate(resources.getString(R.string.interception_hold_idle_hint), 1L);
        TextTemplate.validate(resources.getString(R.string.interception_hold_active_hint), 1L);
    }

    /**
     * 按序号读取可重复的预览样例，不消耗正式主句队列。
     * @param page 实际使用文案的页面
     * @param state 按压状态，其他页面使用 IDLE
     * @param variant 会话内稳定的选句序号
     * @return 主句及固定按压引导，操作控件文案由 ActionCopy 适配
     */
    public Scenario scenario(Page page, State state, int variant) {
        return scenario(page, state, variant, null);
    }

    /**
     * 正式页面按规则会话复用已选正文，新会话首次显示此类主句时独立随机抽取。
     * @param page 实际使用文案的页面，普通首屏与闭关共享普通库
     * @param state 按压状态，其他页面使用 IDLE
     * @param variant 预览样例序号，正式主句不依赖此序号
     * @param sessionId 本轮规则拦截身份；为空时只读取可重复的预览样例
     * @return 本轮稳定的主句及固定按压提示
     */
    public Scenario scenario(Page page, State state, int variant, String sessionId) {
        if (state != State.IDLE && !(page == Page.HOLD && state == State.ACTIVE)) {
            throw new IllegalArgumentException("Unsupported copy state: " + page + ':' + state);
        }
        Pool pool;
        switch (page) {
            case GENERAL:
            case FORBIDDEN: pool = Pool.GENERAL_HEADLINES; break;
            case HOLD: pool = Pool.HOLD_HEADLINES; break;
            case WORD: pool = Pool.WORD_HEADLINES; break;
            case CONFIRMATION: pool = Pool.CONFIRMATION_HEADLINES; break;
            default: throw new IllegalArgumentException("Unsupported copy page: " + page);
        }
        String hint = page == Page.HOLD ? resources.getString(state == State.ACTIVE
                ? R.string.interception_hold_active_hint : R.string.interception_hold_idle_hint) : "";
        List<String> candidates = array(pool.resource);
        String headline = sessionId == null ? candidates.get(Math.floorMod(variant, candidates.size()))
                : headlines.getOrCreate(pool.name(), sessionId, candidates);
        return new Scenario(headline, hint);
    }

    /** @param pool 动态文案分类 @return 当前分类的可选文案数量 */
    public int count(Pool pool) { return array(pool.resource).size(); }

    /** @param pool 动态文案分类 @param index 已归一化索引 @return 文案原文 */
    public String value(Pool pool, int index) { return array(pool.resource).get(index); }

    /** @param resource 字符串数组资源 @return 已校验非空的共享只读列表 */
    private List<String> array(int resource) {
        synchronized (CACHE) {
            Map<Integer, List<String>> cache = CACHE.computeIfAbsent(resources, ignored -> new HashMap<>());
            List<String> values = cache.get(resource);
            if (values == null) {
                values = new ArrayList<>(Arrays.asList(resources.getStringArray(resource)));
                if (values.isEmpty()) throw new IllegalStateException("Empty copy array: " + resources.getResourceEntryName(resource));
                for (String value : values) {
                    if (value.trim().isEmpty()) throw new IllegalStateException("Blank copy: " + resources.getResourceEntryName(resource));
                }
                values = Collections.unmodifiableList(values);
                cache.put(resource, values);
            }
            return values;
        }
    }

    /** 本次选中的动态主句与固定按压引导。 */
    public static final class Scenario {
        public final String headline, interactionAction;
        /** @param headline 主句 @param interactionAction 按压引导模板 */
        private Scenario(String headline, String interactionAction) {
            this.headline = headline;
            this.interactionAction = interactionAction;
        }
    }
}
