package github.vhoom.zhijian.ui.interception.page;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import github.vhoom.zhijian.R;
import github.vhoom.zhijian.data.model.interception.InterceptionEvent;
import github.vhoom.zhijian.data.model.interception.InterceptionStage;
import github.vhoom.zhijian.ui.interception.session.PageSession;

import java.util.EnumMap;
import java.util.function.Consumer;

/**
 * 独立页面的集中装配目录；新增页面只注册布局和实现，Activity 不增加渲染分支。
 *
 * 维护日期：2026年11月14日
 */
public final class InterceptionPageRegistry {
    /** 创建当前注册页的实现。 */
    private interface Factory {
        /**
         * 根据会话和恢复状态创建独立页面。
         * @param root 已加载的页面视图
         * @param session 只读会话
         * @param events 用户事件出口
         * @param state 当前页重建状态
         * @return 页面实现
         */
        InterceptionPage create(View root, PageSession session, Consumer<InterceptionEvent> events, Bundle state);
    }

    /** 保存页面的布局、根控件标识及构造函数。 */
    private static final class Registration {
        final int layout;
        final int rootId;
        final Factory factory;
        /**
         * 绑定一个页面的装配资料。
         * @param layout 独立 XML 布局
         * @param rootId 页面根视图标识
         * @param factory 页面实现构造函数
         */
        Registration(int layout, int rootId, Factory factory) {
            this.layout = layout;
            this.rootId = rootId;
            this.factory = factory;
        }
    }

    private final EnumMap<InterceptionStage, Registration> pages = new EnumMap<>(InterceptionStage.class);

    /** 集中注册现有页面，静态页面复用同一实现，交互页面各自独立。 */
    public InterceptionPageRegistry() {
        register(InterceptionStage.INITIAL, R.layout.view_interception_initial, R.id.interception_page_general,
                (root, session, events, state) -> new ActionChoicePage(root, session, events));
        register(InterceptionStage.FINAL_CONFIRM, R.layout.view_interception_confirmation, R.id.interception_page_confirmation,
                (root, session, events, state) -> new ActionChoicePage(root, session, events));
        register(InterceptionStage.LOCKED, R.layout.view_interception_forbidden, R.id.interception_page_forbidden,
                (root, session, events, state) -> new SealPage(root, session, events));
        register(InterceptionStage.HOLD, R.layout.view_interception_friction_hold, R.id.interception_page_hold, HoldPage::new);
        register(InterceptionStage.HOLD_COMPLETED, R.layout.view_interception_friction_hold, R.id.interception_page_hold, HoldPage::new);
        register(InterceptionStage.WORD_TRANSLATION, R.layout.view_interception_friction_word, R.id.interception_page_word, WordPage::new);
        register(InterceptionStage.WORD_COMPLETED, R.layout.view_interception_friction_word, R.id.interception_page_word, WordPage::new);
        register(InterceptionStage.SUDOKU, R.layout.view_interception_friction_sudoku, R.id.interception_page_sudoku, SudokuPage::new);
        register(InterceptionStage.SUDOKU_COMPLETED, R.layout.view_interception_friction_sudoku, R.id.interception_page_sudoku, SudokuPage::new);
    }

    /**
     * 注册独立页面，重复阶段立即报错，避免静默覆盖。
     * @param stage 页面阶段
     * @param layout 独立页面布局
     * @param rootId 根视图标识
     * @param factory 对应页面实现
     */
    private void register(InterceptionStage stage, int layout, int rootId, Factory factory) {
        if (pages.put(stage, new Registration(layout, rootId, factory)) != null) {
            throw new IllegalArgumentException("Duplicate page: " + stage);
        }
    }

    /**
     * 加载当前页，协调层无需知道页面有哪些控件或如何计时。
     * @param container 页面容器
     * @param session 当前会话参数
     * @param events 用户事件出口
     * @param state 当前页恢复状态
     * @return 装配完成的独立页面
     */
    public InterceptionPage create(ViewGroup container, PageSession session,
            Consumer<InterceptionEvent> events, Bundle state) {
        Registration registration = pages.get(session.stage);
        if (registration == null) throw new IllegalArgumentException("Unregistered page: " + session.stage);
        View root = LayoutInflater.from(container.getContext()).inflate(registration.layout, container, false);
        root.setId(registration.rootId);
        container.addView(root);
        return registration.factory.create(root, session, events, state);
    }
}
