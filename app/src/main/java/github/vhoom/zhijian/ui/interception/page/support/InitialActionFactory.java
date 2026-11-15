package github.vhoom.zhijian.ui.interception.page.support;

import android.content.Context;
import android.view.Gravity;
import android.widget.Button;

import github.vhoom.zhijian.R;
import github.vhoom.zhijian.ui.interception.copy.ActionCopy;

/**
 * 双出口控件工厂：绑定交互方式、动态引导语和主题，盲盒外观不泄露预选结果。
 *
 * 维护日期：2026年11月08日
 */
public final class InitialActionFactory {
    private final Context context;
    private final PageAppearance appearance;

    /** @param context 页面主题上下文 @param appearance 会话外观适配器 */
    public InitialActionFactory(Context context, PageAppearance appearance) {
        this.context = context;
        this.appearance = appearance;
    }

    /**
     * @param spec 控件及文案方案
     * @param viewId 页面内唯一标识
     * @param continueRoute 继续方向的真实意图：直接继续、限时放行或前往挑战
     * @param action 已绑定路由的执行操作
     * @return 居中展示引导语的全宽操作控件
     */
    public Button create(InitialActionPlan.Action spec, int viewId, ActionCopy.Route continueRoute, Runnable action) {
        boolean filled = spec.copy != InitialActionPlan.Copy.BLIND_BOX && spec.exit;
        CharSequence text = textFor(spec, continueRoute);
        Button button = spec.kind == InitialActionPlan.Kind.SLIDER
                ? new SlideActionView(context, filled) : new Button(context, null, 0, R.style.Widget_App_Button);
        button.setId(viewId);
        button.setText(text);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setMinWidth(0);
        button.setStateListAnimator(null);
        button.setBackgroundTintList(null);
        button.setTextAppearance(R.style.TextAppearance_App_ButtonLg);
        appearance.styleActionButton(button, filled);
        button.setMinHeight(context.getResources().getDimensionPixelSize(R.dimen.wuwei_action_height));
        if (button instanceof SlideActionView) {
            int side = context.getResources().getDimensionPixelSize(R.dimen.wuwei_slider_thumb)
                    + context.getResources().getDimensionPixelSize(R.dimen.space_sm);
            button.setPadding(side, appearance.dp(16), side, appearance.dp(16));
            button.setContentDescription(context.getString(R.string.interception_slide_action, text));
        }
        button.setOnClickListener(view -> action.run());
        return button;
    }

    /** @param spec 控件方案 @param continueRoute 继续方向的真实意图 @return 会话内稳定的控件文字 */
    private CharSequence textFor(InitialActionPlan.Action spec, ActionCopy.Route continueRoute) {
        ActionCopy.Route route = spec.copy == InitialActionPlan.Copy.BLIND_BOX ? ActionCopy.Route.BLIND_BOX
                : spec.exit ? ActionCopy.Route.REST : continueRoute;
        return ActionCopy.text(context, route, spec.copyVariant);
    }
}
