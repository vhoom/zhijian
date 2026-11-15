package github.vhoom.zhijian.ui.settings;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Paint;
import android.net.Uri;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import github.vhoom.zhijian.BuildConfig;
import github.vhoom.zhijian.R;
import github.vhoom.zhijian.ui.about.AboutDocument;
import github.vhoom.zhijian.ui.about.AboutDocumentActivity;
import github.vhoom.zhijian.ui.diagnostics.DiagnosticsActivity;
import github.vhoom.zhijian.ui.preview.DeveloperPreviewActivity;
import github.vhoom.zhijian.util.diagnostics.DiagnosticLog;

/**
 * 关于区的装配入口；设置 Activity 不持有文本、日志或导出流程。
 *
 * 维护日期：2026年11月10日
 */
public final class AboutSettingsBinder {
    private final Activity activity;
    private int debugLogoTaps;

    /**
     * 绑定同一区域内的阅读、诊断、应用信息与调试预览入口。
     * @param activity 采用关于区布局的设置页面
     */
    public AboutSettingsBinder(Activity activity) {
        this.activity = activity;
        ((TextView) activity.findViewById(R.id.app_version)).setText(
                activity.getString(R.string.settings_version, BuildConfig.VERSION_NAME));
        bindRow(R.id.row_author_letter, R.string.about_author_title, () -> open(AboutDocument.AUTHOR));
        bindRow(R.id.row_service_agreement, R.string.about_agreement_title, () -> open(AboutDocument.AGREEMENT));
        bindRow(R.id.row_diagnostics, R.string.diagnostics_title, () ->
                activity.startActivity(new Intent(activity, DiagnosticsActivity.class)));
        TextView github = activity.findViewById(R.id.link_github);
        github.setPaintFlags(github.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        github.setOnClickListener(view -> openProject());
        if (BuildConfig.DEBUG) activity.findViewById(R.id.settings_logo).setOnClickListener(view -> {
            if (++debugLogoTaps < 7) return;
            debugLogoTaps = 0;
            activity.startActivity(DeveloperPreviewActivity.intent(activity));
        });
    }

    /**
     * 为共用列表项装配文案与可访问的整行操作。
     * @param id 列表项标识
     * @param title 标题资源
     * @param action 用户主动点击后的操作
     */
    private void bindRow(int id, int title, Runnable action) {
        View row = activity.findViewById(id);
        ((TextView) row.findViewById(R.id.about_row_title)).setText(title);
        row.setContentDescription(activity.getString(title));
        row.setOnClickListener(view -> action.run());
    }

    /**
     * 通过文档目录打开本地文本。
     * @param document 对应的作者信或服务协议
     */
    private void open(AboutDocument document) {
        activity.startActivity(AboutDocumentActivity.intent(activity, document));
    }

    /** 用户主动点击后交给浏览器，不由应用加载网络内容。 */
    private void openProject() {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(activity.getString(R.string.settings_github_url)));
        try { activity.startActivity(intent); }
        catch (ActivityNotFoundException | SecurityException unavailable) {
            DiagnosticLog.warn("about", "", "project_link_unavailable");
            Toast.makeText(activity, R.string.settings_link_unavailable, Toast.LENGTH_SHORT).show();
        }
    }
}

