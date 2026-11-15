package github.vhoom.zhijian.ui.interception.page;

import android.content.Context;
import android.os.Bundle;
import android.view.View;

import github.vhoom.zhijian.data.model.interception.InterceptionEvent;
import github.vhoom.zhijian.domain.interception.InterceptionDecision;
import github.vhoom.zhijian.ui.interception.page.support.PageAppearance;
import github.vhoom.zhijian.ui.interception.session.PageSession;

import java.util.function.Consumer;

/**
 * 各独立页面共有的事件出口和资源访问；页面实现之间没有相互引用。
 *
 * 维护日期：2026年11月08日
 */
abstract class BaseInterceptionPage implements InterceptionPage {
    protected final View root;
    protected final Context context;
    protected final PageSession session;
    protected final InterceptionDecision decision;
    protected final PageAppearance appearance;
    private final Consumer<InterceptionEvent> events;

    /**
     * 保存显示依赖和单向事件出口。
     * @param root 已加载的独立页面视图
     * @param session 不可变会话参数
     * @param events 用户事件接收函数
     */
    BaseInterceptionPage(View root, PageSession session, Consumer<InterceptionEvent> events) {
        this.root = root;
        this.context = root.getContext();
        this.session = session;
        this.decision = session.decision;
        this.events = events;
        this.appearance = new PageAppearance(context, session);
    }

    /** 返回本页根视图。 */
    @Override public final View view() { return root; }

    /**
     * 向统一状态机报告事件，页面不能自行签发通行证。
     * @param event 当前用户事件
     */
    protected final void dispatch(InterceptionEvent event) { events.accept(event); }

    /**
     * 查找当前页面控件。
     * @param id 当前布局内的资源标识
     * @param <T> 返回的控件类型
     * @return 对应控件
     */
    protected final <T extends View> T findViewById(int id) { return root.findViewById(id); }

    /** 默认页面没有可暂停的交互。 */
    @Override public void pause() { }

    /**
     * 默认页面没有休息显示，由承载休息的页面覆盖。
     * @param remainingMs 剩余休息毫秒数
     */
    @Override public void updateRest(long remainingMs) { }

    /**
     * 默认页面没有需要保存的进度。
     * @param state 系统重建状态容器
     */
    @Override public void save(Bundle state) { }
}
