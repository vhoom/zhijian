package github.vhoom.zhijian.ui.interception.page.support;

import android.content.Context;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import github.vhoom.zhijian.R;
import github.vhoom.zhijian.ui.interception.copy.InterceptionCopyCatalog;
import github.vhoom.zhijian.ui.interception.copy.ActionCopy;
import github.vhoom.zhijian.ui.interception.copy.InterceptionCopyCatalog.Page;
import github.vhoom.zhijian.ui.interception.copy.InterceptionCopyCatalog.Pool;
import github.vhoom.zhijian.ui.interception.copy.InterceptionCopyCatalog.State;
import github.vhoom.zhijian.ui.interception.copy.InterceptionCopyCatalog.Scenario;
import github.vhoom.zhijian.ui.interception.session.PageSession;

/**
 * 稳定方案选择和主题适配；不持有答题、计时或流程对象。
 *
 * 维护日期：2026年11月14日
 */
public final class PageAppearance {
    private final Context context;
    private final InterceptionCopyCatalog catalog;
    private final PageSession session;

    /**
     * @param context 主题上下文
     * @param session 当前页只读会话
     */
    public PageAppearance(Context context, PageSession session) {
        this.context = context;
        this.session = session;
        catalog = new InterceptionCopyCatalog(context);
    }

    /**
     * @param page 分类
     * @param state 展示状态 @return 本会话稳定的完整方案
     */
    public Scenario scenario(Page page, State state) {
        return catalog.scenario(page, state, session.copyVariant,
                session.persistentHeadlines ? session.sessionId : null);
    }

    /**
     * @param page 分类
     * @param state 状态
     * @param title 主句
     * @param actions 操作容器
     * @param exit 退出
     * @param proceed 继续，当前不可用时为空 @return 绑定的完整方案
     */
    public Scenario bindScenario(Page page, State state, TextView title, LinearLayout actions, Button exit, Button proceed) {
        Scenario selected = scenario(page, state);
        title.setText(selected.headline);
        exit.setText(ActionCopy.text(context, ActionCopy.Route.REST, stableIndex(page.name() + ":exit-copy", Integer.MAX_VALUE)));
        styleActionButton(exit, page != Page.WORD);
        if (proceed != null) {
            proceed.setText(ActionCopy.text(context, ActionCopy.Route.PASS, stableIndex(page.name() + ":pass-copy", Integer.MAX_VALUE)));
            styleActionButton(proceed, false);
        }
        arrangeChoiceButtons(actions, exit, proceed, page.name());
        return selected;
    }

    /**
     * 为没有主句的挑战装配退出操作，不抽取主句。
     * @param actions 底部操作容器
     * @param exit 退出按钮
     * @param salt 挑战的稳定文案分类
     */
    public void bindChallengeExit(LinearLayout actions, Button exit, String salt) {
        exit.setText(ActionCopy.text(context, ActionCopy.Route.REST,
                stableIndex(salt + ":exit-copy", Integer.MAX_VALUE)));
        styleActionButton(exit, false);
        arrangeChoiceButtons(actions, exit, null, salt);
    }

    /**
     * 使用已保存的随机局身份选句，重建与跨会话恢复不重新抽取。
     * @param pool 完成文案分类
     * @param attemptId 已提交进度的局身份，预览使用隔离的固定身份
     * @return 当前局稳定的完成语句
     */
    public CharSequence pickAttemptText(Pool pool, String attemptId) {
        return catalog.value(pool, Math.floorMod((attemptId + ':' + pool.name()).hashCode(), catalog.count(pool)));
    }

    /**
     * 按本次会话的放行开关设置新增操作，不修改主句或原退出按钮。
     * @param button 新增的继续或临时放行按钮
     */
    public void bindCompletedChallengeAction(Button button) {
        ActionCopy.Route route = session.settings.options.temporaryPassEnabled ? ActionCopy.Route.PASS : ActionCopy.Route.CONTINUE;
        button.setText(ActionCopy.text(context, route, stableIndex("challenge-completed:action", Integer.MAX_VALUE)));
        styleActionButton(button, false);
    }

    /**
     * @param actions 底部容器
     * @param exit 退出按钮
     * @param proceed 继续按钮或空值
     * @param salt 稳定布局种子
     */
    public void arrangeChoiceButtons(LinearLayout actions, Button exit, Button proceed, String salt) {
        actions.removeAllViews();
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setGravity(Gravity.BOTTOM);
        boolean swap = proceed != null && stableIndex(salt + ":order", 2) == 1;
        addAction(actions, swap ? proceed : exit, false);
        if (proceed != null) addAction(actions, swap ? exit : proceed, true);
    }

    /**
     * @param actions 容器
     * @param button 操作
     * @param withMargin 是否添加操作间距
     */
    private void addAction(LinearLayout actions, Button button, boolean withMargin) {
        button.setVisibility(android.view.View.VISIBLE);
        button.setEnabled(true);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        if (withMargin) params.topMargin = context.getResources().getDimensionPixelSize(R.dimen.space_sm);
        actions.addView(button, params);
    }

    /**
     * @param button 目标按钮
     * @param filled 主操作或次操作；使用主题实色表面，主操作保留中等字重
     */
    public void styleActionButton(Button button, boolean filled) {
        button.setBackgroundTintList(null);
        button.setBackgroundResource(filled
                ? R.drawable.bg_interception_button_primary : R.drawable.bg_interception_button_secondary);
        button.setTextAppearance(filled
                ? R.style.TextAppearance_App_ButtonLg : R.style.TextAppearance_App_BodyMd);
        button.setTextColor(context.getColor(R.color.interception_button_text));
        button.setStateListAnimator(null);
        button.setMinHeight(context.getResources().getDimensionPixelSize(R.dimen.interception_action_height));
        int padding = context.getResources().getDimensionPixelSize(R.dimen.space_md);
        button.setPadding(padding, padding, padding, padding);
        button.setMaxLines(Integer.MAX_VALUE);
        button.setEllipsize(null);
    }

    /**
     * @param page 分类
     * @param pool 动态文案分类
     * @param salt 稳定种子 @return 本次会话选中的文案
     */
    public CharSequence pickText(Page page, Pool pool, String salt) {
        return catalog.value(pool, copyIndex(page.name() + ":" + salt, catalog.count(pool)));
    }

    /**
     * @param salt 稳定种子
     * @param count 方案数量 @return 会话内稳定索引
     */
    public int stableIndex(String salt, int count) {
        return Math.floorMod((session.sessionId + ':' + salt).hashCode(), count);
    }

    /**
     * @param salt 分类及状态键
     * @param count 方案数量 @return 连续会话轮换且重建稳定的索引
     */
    private int copyIndex(String salt, int count) {
        return Math.floorMod((long) session.copyVariant + salt.hashCode(), count);
    }

    /**
     * @param value 密度无关像素 @return 当前主题上下文的像素
     */
    public int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
}
