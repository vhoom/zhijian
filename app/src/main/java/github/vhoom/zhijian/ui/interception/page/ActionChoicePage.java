package github.vhoom.zhijian.ui.interception.page;

import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import github.vhoom.zhijian.R;
import github.vhoom.zhijian.data.model.interception.InterceptionEvent;
import github.vhoom.zhijian.data.model.interception.InterceptionStage;
import github.vhoom.zhijian.data.model.policy.ControlLevel;
import github.vhoom.zhijian.ui.interception.copy.ActionCopy;
import github.vhoom.zhijian.ui.interception.copy.InterceptionCopyCatalog.Page;
import github.vhoom.zhijian.ui.interception.copy.InterceptionCopyCatalog.Scenario;
import github.vhoom.zhijian.ui.interception.copy.InterceptionCopyCatalog.State;
import github.vhoom.zhijian.ui.interception.page.support.SlideActionView;
import github.vhoom.zhijian.ui.interception.page.support.InitialActionFactory;
import github.vhoom.zhijian.ui.interception.page.support.InitialActionPlan;
import github.vhoom.zhijian.ui.interception.session.PageSession;

import java.util.function.Consumer;

/**
 * 首次选择与二次询问共用的双路由页面，继续意图由阶段决定。
 *
 * 维护日期：2026年11月13日
 */
public final class ActionChoicePage extends BaseInterceptionPage {
    private final LinearLayout actions;
    private final InitialActionPlan plan;
    private final InitialActionFactory factory;
    private final ActionCopy.Route continueRoute;
    private final boolean confirmation;

    /** @param root 页面根 @param session 只读会话 @param events 状态机事件出口 */
    public ActionChoicePage(View root, PageSession session, Consumer<InterceptionEvent> events) {
        super(root, session, events);
        confirmation = session.stage == InterceptionStage.FINAL_CONFIRM;
        actions = findViewById(confirmation ? R.id.interception_confirmation_actions : R.id.interception_general_actions);
        boolean repair = !confirmation && decision.level == ControlLevel.REPAIR
                && session.settings.options.obstaclePagesEnabled;
        continueRoute = repair ? ActionCopy.Route.CHALLENGE
                : session.settings.options.temporaryPassEnabled ? ActionCopy.Route.PASS : ActionCopy.Route.CONTINUE;
        plan = confirmation ? InitialActionPlan.forConfirmation(session.sessionId)
                : InitialActionPlan.forSession(session.initialActionSeed, decision.level == ControlLevel.MORTAL,
                        session.settings.options.dynamicButtonsEnabled);
        factory = new InitialActionFactory(context, appearance);
    }

    /** 本次打开内复用控件方案；主句仍按原运行会话读取，特殊首屏也保留主句。 */
    @Override public void render() {
        Scenario copy = appearance.scenario(confirmation ? Page.CONFIRMATION : Page.GENERAL, State.IDLE);
        ((TextView) findViewById(confirmation ? R.id.interception_confirmation_title : R.id.interception_general_title)).setText(copy.headline);
        pause();
        actions.removeAllViews();
        for (int index = 0; index < plan.actions.size(); index++) addAction(plan.actions.get(index), index);
    }

    /** @param spec 当前控件方案 @param index 从上往下的控件位置 */
    private void addAction(InitialActionPlan.Action spec, int index) {
        int id = confirmation ? (spec.exit ? R.id.interception_confirmation_exit : R.id.interception_confirmation_continue)
                : plan.blindBox ? R.id.interception_general_blind_box
                : plan.doubleExit && index == 1 ? R.id.interception_general_exit_secondary
                : spec.exit ? R.id.interception_general_exit : R.id.interception_general_continue;
        Button button = factory.create(spec, id, continueRoute,
                () -> dispatch(spec.exit ? InterceptionEvent.ABANDON : InterceptionEvent.CONTINUE));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        if (index > 0) params.topMargin = context.getResources().getDimensionPixelSize(R.dimen.space_sm);
        actions.addView(button, params);
    }

    /** 离开页面时撤销未完成拖动；不重新选择路由。 */
    @Override public void pause() {
        for (int index = 0; index < actions.getChildCount(); index++) {
            View action = actions.getChildAt(index);
            if (action instanceof SlideActionView) ((SlideActionView) action).cancelDrag();
        }
    }

    /** 防止调试图集在滑块松手时抢走本次路由操作。 */
    @Override public boolean isHandlingHorizontalGesture() {
        for (int index = 0; index < actions.getChildCount(); index++) {
            View action = actions.getChildAt(index);
            if (action instanceof SlideActionView && ((SlideActionView) action).isDragging()) return true;
        }
        return false;
    }
}
