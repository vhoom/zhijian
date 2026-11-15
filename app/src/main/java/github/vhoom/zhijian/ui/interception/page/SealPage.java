package github.vhoom.zhijian.ui.interception.page;

import android.view.View;
import android.widget.Button;
import github.vhoom.zhijian.R;
import github.vhoom.zhijian.data.model.interception.InterceptionEvent;
import github.vhoom.zhijian.ui.interception.copy.InterceptionCopyCatalog.Page;
import github.vhoom.zhijian.ui.interception.copy.InterceptionCopyCatalog.State;
import github.vhoom.zhijian.ui.interception.session.PageSession;
import java.util.function.Consumer;

/**
 * 闭关页只展示主句和一个退出按钮。
 *
 * 维护日期：2026年11月08日
 */
public final class SealPage extends BaseInterceptionPage {
    /** @param root 页面根 @param session 稳定会话 @param events 退出事件接收器 */
    public SealPage(View root, PageSession session, Consumer<InterceptionEvent> events) {
        super(root, session, events);
        ((Button) findViewById(R.id.interception_forbidden_exit))
                .setOnClickListener(view -> dispatch(InterceptionEvent.ABANDON));
    }

    /** 按禁用或提醒读取闭关主句，退出控件使用稳定动态文案。 */
    @Override public void render() {
        appearance.bindScenario(Page.FORBIDDEN, State.IDLE,
                findViewById(R.id.interception_forbidden_title), findViewById(R.id.interception_forbidden_actions),
                findViewById(R.id.interception_forbidden_exit), null);
    }
}
