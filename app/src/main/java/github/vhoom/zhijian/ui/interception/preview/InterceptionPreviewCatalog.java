package github.vhoom.zhijian.ui.interception.preview;

import github.vhoom.zhijian.R;
import github.vhoom.zhijian.data.model.interception.InterceptionStage;
import github.vhoom.zhijian.data.model.policy.ControlLevel;
import github.vhoom.zhijian.domain.interception.InterceptionDecision;
import github.vhoom.zhijian.ui.interception.page.support.InitialActionPlan;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Debug 页面目录与样例选择；复用真实控件算法，不修改生产概率或存储。
 *
 * 维护日期：2026年11月14日
 */
public final class InterceptionPreviewCatalog {
    /** 每种页面均有禁用和提醒两个预览。 */
    public enum Page {
        WUWEI(ControlLevel.MORTAL, InterceptionStage.INITIAL, R.string.interception_preview_wuwei),
        REPAIR(ControlLevel.REPAIR, InterceptionStage.INITIAL, R.string.interception_preview_repair),
        HOLD(ControlLevel.REPAIR, InterceptionStage.HOLD, R.string.interception_preview_hold),
        WORD(ControlLevel.REPAIR, InterceptionStage.WORD_TRANSLATION, R.string.interception_preview_word),
        SUDOKU(ControlLevel.REPAIR, InterceptionStage.SUDOKU, R.string.interception_preview_sudoku),
        HOLD_COMPLETED(ControlLevel.REPAIR, InterceptionStage.HOLD_COMPLETED,
                R.string.interception_preview_hold_completed),
        WORD_COMPLETED(ControlLevel.REPAIR, InterceptionStage.WORD_COMPLETED,
                R.string.interception_preview_word_completed),
        SUDOKU_COMPLETED(ControlLevel.REPAIR, InterceptionStage.SUDOKU_COMPLETED,
                R.string.interception_preview_sudoku_completed),
        CONFIRMATION(ControlLevel.REPAIR, InterceptionStage.FINAL_CONFIRM, R.string.interception_preview_confirmation),
        SEAL(ControlLevel.SEAL, InterceptionStage.LOCKED, R.string.interception_preview_seal);

        public final ControlLevel level;
        public final InterceptionStage stage;
        public final int label;

        /** @param level 档位 @param stage 页面 @param label 调试菜单名称 */
        Page(ControlLevel level, InterceptionStage stage, int label) {
            this.level = level;
            this.stage = stage;
            this.label = label;
        }

        /** 返回是否使用可选按钮／滑块组合。 */
        public boolean hasChoices() { return this == WUWEI || this == REPAIR || this == CONFIRMATION; }
    }

    /** 调试样例只筛选满足条件的会话种子。 */
    public enum Presentation {
        RANDOM(R.string.interception_preview_random),
        BUTTONS(R.string.interception_preview_buttons), SLIDERS(R.string.interception_preview_sliders),
        BUTTON_SLIDER(R.string.interception_preview_button_slider), SLIDER_BUTTON(R.string.interception_preview_slider_button),
        DOUBLE_EXIT(R.string.interception_preview_double_exit),
        BLIND_EXIT(R.string.interception_preview_blind_exit), BLIND_PASS(R.string.interception_preview_blind_pass);

        public final int label;

        /** @param label 控件样例名称 */
        Presentation(int label) { this.label = label; }

        /** @param page 目标页 @return 不适用时回退随机，修心不能使用无为特殊事件 */
        public Presentation forPage(Page page) {
            return !page.hasChoices() || ordinal() >= DOUBLE_EXIT.ordinal() && page != Page.WUWEI ? RANDOM : this;
        }

        /** @param plan 真实算法生成的方案 @return 是否符合当前样例 */
        private boolean matches(InitialActionPlan plan) {
            if (this == RANDOM) return true;
            if (this == DOUBLE_EXIT) return plan.doubleExit && !plan.blindBox;
            if (this == BLIND_EXIT || this == BLIND_PASS) {
                return plan.blindBox && plan.actions.get(0).exit == (this == BLIND_EXIT);
            }
            if (plan.blindBox || plan.doubleExit) return false;
            boolean firstSlider = plan.actions.get(0).kind == InitialActionPlan.Kind.SLIDER;
            boolean secondSlider = plan.actions.get(1).kind == InitialActionPlan.Kind.SLIDER;
            return firstSlider == (this == SLIDERS || this == SLIDER_BUTTON)
                    && secondSlider == (this == SLIDERS || this == BUTTON_SLIDER);
        }

        /** @param value 保存或 ADB 传入的样例名称 @return 无效名称回退随机 */
        public static Presentation parse(String value) {
            try { return value == null ? RANDOM : valueOf(value.toUpperCase(java.util.Locale.ROOT)); }
            catch (IllegalArgumentException invalid) { return RANDOM; }
        }
    }

    public static final int PAGE_COUNT = Page.values().length * 2;
    private static final AtomicInteger NEXT_VARIANT = new AtomicInteger();

    /** 工具类不创建实例。 */
    private InterceptionPreviewCatalog() { }

    /** 返回仅用于预览的文案变体，不推进真实拦截序号。 */
    public static int nextVariant() { return NEXT_VARIANT.getAndUpdate(value -> value == Integer.MAX_VALUE ? 0 : value + 1); }

    /** @param index 可循环索引 @return 规范化页码 */
    public static int normalize(int index) { return Math.floorMod(index, PAGE_COUNT); }

    /** @param index 图集索引 @return 独立页面 */
    public static Page page(int index) { return Page.values()[normalize(index) % Page.values().length]; }

    /** @param index 图集索引 @return 是否预览提醒 */
    public static boolean reminder(int index) { return normalize(index) >= Page.values().length; }

    /** @param level 实际档位 @param stage 实际阶段 @param kind 规则类别 @return 当前页面对应的图集索引 */
    public static int indexOf(ControlLevel level, InterceptionStage stage, InterceptionDecision.Kind kind) {
        Page selected = level == ControlLevel.SEAL ? Page.SEAL : level == ControlLevel.MORTAL ? Page.WUWEI : Page.REPAIR;
        if (level == ControlLevel.REPAIR) {
            for (Page page : Page.values()) if (page.level == level && page.stage == stage) selected = page;
        }
        return selected.ordinal() + (kind == InterceptionDecision.Kind.INTERRUPT ? Page.values().length : 0);
    }

    /** @param index 图集索引 @return 同一页面另一种触发的索引 */
    public static int toggleKind(int index) { return normalize(index + Page.values().length); }

    /** @param index 图集索引 @param sample 稳定抽样编号 @param requested 控件样例 @return 可重复生成的真实会话种子 */
    public static String session(int index, int sample, Presentation requested) {
        Page page = page(index);
        Presentation presentation = requested.forPage(page);
        String prefix = "debug-gallery:" + normalize(index) + ':' + sample + ':' + presentation.name() + ':';
        for (int attempt = 0; attempt < 4096; attempt++) {
            String session = prefix + attempt;
            InitialActionPlan plan = page == Page.CONFIRMATION ? InitialActionPlan.forConfirmation(session)
                    : InitialActionPlan.forSession(session, page == Page.WUWEI);
            if (presentation.matches(plan)) return session;
        }
        throw new IllegalStateException("No preview sample for " + presentation);
    }
}
