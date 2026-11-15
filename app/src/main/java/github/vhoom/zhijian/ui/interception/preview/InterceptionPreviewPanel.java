package github.vhoom.zhijian.ui.interception.preview;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import github.vhoom.zhijian.R;
import github.vhoom.zhijian.ui.component.dialog.AppDialogs;
import github.vhoom.zhijian.ui.interception.preview.InterceptionPreviewCatalog.Page;
import github.vhoom.zhijian.ui.interception.preview.InterceptionPreviewCatalog.Presentation;
import github.vhoom.zhijian.ui.interception.request.InterceptionIntents;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 可收起的 Debug 预览菜单；只生成请求，不接触实际放行与运行存储。
 *
 * 维护日期：2026年11月11日
 */
public final class InterceptionPreviewPanel {
    private final Activity activity;
    private final Consumer<Intent> navigate;
    private final Button launcher;
    private AlertDialog dialog;
    private int index;
    private int sample;
    private Presentation presentation = Presentation.RANDOM;

    /** @param activity 预览宿主 @param navigate 切换预览请求的回调 */
    public InterceptionPreviewPanel(Activity activity, Consumer<Intent> navigate) {
        this.activity = activity;
        this.navigate = navigate;
        launcher = new Button(activity, null, 0, R.style.Widget_App_Button_InterceptionPreview);
        launcher.setId(R.id.interception_preview_launcher);
        launcher.setText(R.string.interception_preview_open);
        launcher.setContentDescription(activity.getString(R.string.interception_preview_pages));
        launcher.setMinWidth(0);
        launcher.setMinimumWidth(0);
        launcher.setOnClickListener(view -> show());
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        params.topMargin = activity.getResources().getDimensionPixelSize(R.dimen.space_xxs);
        params.setMarginEnd(activity.getResources().getDimensionPixelSize(R.dimen.space_xs));
        activity.addContentView(launcher, params);
    }

    /** @param index 当前实际页面索引 @param request 包含抽样编号和样例的请求 */
    public void update(int index, Intent request) {
        this.index = InterceptionPreviewCatalog.normalize(index);
        sample = request.getIntExtra(InterceptionIntents.EXTRA_DEBUG_SAMPLE,
                request.getIntExtra(InterceptionIntents.EXTRA_COPY_VARIANT, 0));
        presentation = Presentation.parse(request.getStringExtra(InterceptionIntents.EXTRA_DEBUG_PRESENTATION))
                .forPage(InterceptionPreviewCatalog.page(index));
    }

    /** 展开菜单，显示实际页面、触发类别与可用预览动作。 */
    public void show() {
        dismiss();
        Page page = InterceptionPreviewCatalog.page(index);
        List<String> labels = new ArrayList<>();
        labels.add(activity.getString(R.string.interception_preview_pages));
        labels.add(activity.getString(R.string.interception_preview_kind,
                activity.getString(InterceptionPreviewCatalog.reminder(index)
                        ? R.string.interception_preview_block : R.string.interception_preview_reminder)));
        labels.add(activity.getString(R.string.interception_preview_previous));
        labels.add(activity.getString(R.string.interception_preview_next));
        labels.add(activity.getString(R.string.interception_preview_resample));
        if (page.hasChoices()) labels.add(activity.getString(R.string.interception_preview_presentation,
                activity.getString(presentation.label)));
        labels.add(activity.getString(R.string.interception_preview_close));
        dialog = AppDialogs.builder(activity).setTitle(title()).setItems(labels.toArray(new String[0]),
                (selected, which) -> {
                    selected.dismiss();
                    switch (which) {
                        case 0: choosePage(); break;
                        case 1: open(InterceptionPreviewCatalog.toggleKind(index), sample, presentation); break;
                        case 2: open(index - 1, sample, presentation); break;
                        case 3: open(index + 1, sample, presentation); break;
                        case 4: open(index, sample == Integer.MAX_VALUE ? 0 : sample + 1, presentation); break;
                        default:
                            if (which == 5 && page.hasChoices()) choosePresentation();
                            else activity.finishAndRemoveTask();
                            break;
                    }
                }).create();
        AppDialogs.show(dialog);
    }

    /** 选择页面时保留禁用／提醒类别，其他样例按目标页能力归一化。 */
    private void choosePage() {
        Page[] pages = Page.values();
        String[] labels = new String[pages.length];
        for (int position = 0; position < pages.length; position++) labels[position] = activity.getString(pages[position].label);
        dialog = AppDialogs.builder(activity).setTitle(R.string.interception_preview_pages)
                .setSingleChoiceItems(labels, InterceptionPreviewCatalog.page(index).ordinal(), (selected, position) -> {
                    selected.dismiss();
                    open(position + (InterceptionPreviewCatalog.reminder(index) ? pages.length : 0), sample, presentation);
                }).create();
        AppDialogs.show(dialog);
    }

    /** 只列出当前页支持的真实控件组合与特殊结果。 */
    private void choosePresentation() {
        Page page = InterceptionPreviewCatalog.page(index);
        List<Presentation> choices = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (Presentation candidate : Presentation.values()) {
            if (candidate.forPage(page) == candidate) {
                choices.add(candidate);
                labels.add(activity.getString(candidate.label));
            }
        }
        dialog = AppDialogs.builder(activity).setTitle(R.string.interception_preview_open)
                .setSingleChoiceItems(labels.toArray(new String[0]), choices.indexOf(presentation), (selected, position) -> {
                    selected.dismiss();
                    open(index, sample, choices.get(position));
                }).create();
        AppDialogs.show(dialog);
    }

    /** @param pageIndex 目标页 @param sample 抽样编号 @param presentation 控件样例 */
    private void open(int pageIndex, int sample, Presentation presentation) {
        dismiss();
        navigate.accept(InterceptionIntents.debugPageIntent(activity, pageIndex, sample, presentation));
    }

    /** 返回调试专用的当前页面信息。 */
    private String title() {
        return activity.getString(R.string.interception_preview_title, index + 1, InterceptionPreviewCatalog.PAGE_COUNT,
                activity.getString(InterceptionPreviewCatalog.page(index).label),
                activity.getString(InterceptionPreviewCatalog.reminder(index)
                        ? R.string.interception_preview_reminder : R.string.interception_preview_block));
    }

    /** 收起菜单，恢复无遮挡预览。 */
    public void dismiss() {
        if (dialog != null) dialog.dismiss();
        dialog = null;
    }

    /** 移除 Debug 入口并释放窗口，生产请求切入及销毁时调用。 */
    public void close() {
        dismiss();
        if (launcher.getParent() instanceof ViewGroup) ((ViewGroup) launcher.getParent()).removeView(launcher);
    }

}
