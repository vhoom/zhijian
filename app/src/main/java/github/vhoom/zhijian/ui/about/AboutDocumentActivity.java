package github.vhoom.zhijian.ui.about;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.Layout;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.AlignmentSpan;
import android.widget.ScrollView;
import android.widget.TextView;

import github.vhoom.zhijian.R;
import github.vhoom.zhijian.ui.activity.ThemedActivity;
import github.vhoom.zhijian.ui.component.async.FutureUiObserver;
import github.vhoom.zhijian.util.diagnostics.DiagnosticLog;
import github.vhoom.zhijian.util.ui.SystemBars;

/**
 * 共用阅读页面；作者的信与服务承诺使用独立内容和字体。
 *
 * 维护日期：2026年11月14日
 */
public final class AboutDocumentActivity extends ThemedActivity {
    private static final String EXTRA_DOCUMENT = "about.document";
    private static final String STATE_SCROLL = "about.scroll";
    private FutureUiObserver<String> reader;
    private ScrollView scroll;

    /**
     * 创建应用内阅读请求。
     * @param context 用户当前页面
     * @param document 需要阅读的文档
     * @return 不对外导出的文档页面请求
     */
    public static Intent intent(Context context, AboutDocument document) {
        return new Intent(context, AboutDocumentActivity.class).putExtra(EXTRA_DOCUMENT, document.code);
    }

    /**
     * 装配阅读页面，正文读取不等待数据库初始化。
     * @param savedInstanceState 上一次页面的滚动位置
     */
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AboutDocument document = AboutDocument.forCode(getIntent().getStringExtra(EXTRA_DOCUMENT));
        if (document == null) { finish(); return; }
        setContentView(R.layout.activity_about_document);
        SystemBars.applyPadding(findViewById(R.id.root));
        ((TextView) findViewById(R.id.page_title)).setText(document.titleRes);
        ((TextView) findViewById(R.id.document_subtitle)).setText(document.subtitleRes);
        findViewById(R.id.btn_back).setOnClickListener(view -> finish());
        scroll = findViewById(R.id.document_scroll);
        TextView body = findViewById(R.id.document_body);
        body.setTextAppearance(document.letter ? R.style.Text_App_Letter : R.style.Text_App_Document);
        int restoredScroll = savedInstanceState == null ? 0 : savedInstanceState.getInt(STATE_SCROLL);
        reader = new FutureUiObserver<>(AboutDocumentReader.read(this, document), this, (text, failure) -> {
            if (failure != null) {
                body.setText(R.string.about_document_failed);
                DiagnosticLog.error("about", "", "document_read_failed kind=" + document.code, failure);
                return;
            }
            body.setText(formatBody(document, text));
            scroll.post(() -> { if (!isDestroyed()) scroll.scrollTo(0, restoredScroll); });
        });
    }

    /**
     * 将作者信最后两个非空行作为署名与时间右对齐，保留文本和原有换行。
     * @param document 当前阅读文档，作者信采用署名与时间排版
     * @param text 已替换应用名称的完整正文
     * @return 带署名段落样式的作者信，或其他文档的原始正文
     */
    private static CharSequence formatBody(AboutDocument document, String text) {
        if (!document.letter) return text;
        int signatureStart = text.length();
        int lineEnd = text.length();
        for (int line = 0; line < 2; line++) {
            while (lineEnd > 0 && Character.isWhitespace(text.charAt(lineEnd - 1))) {
                lineEnd--;
            }
            if (lineEnd == 0) break;
            signatureStart = text.lastIndexOf('\n', lineEnd - 1) + 1;
            lineEnd = signatureStart;
        }
        if (signatureStart == text.length()) return text;
        SpannableString formatted = new SpannableString(text);
        formatted.setSpan(new AlignmentSpan.Standard(Layout.Alignment.ALIGN_OPPOSITE),
                signatureStart, text.length(), Spanned.SPAN_PARAGRAPH);
        return formatted;
    }

    /**
     * 保存阅读位置，主题切换后继续阅读。
     * @param state 系统用于重建页面的快照
     */
    @Override protected void onSaveInstanceState(Bundle state) {
        if (scroll != null) state.putInt(STATE_SCROLL, scroll.getScrollY());
        super.onSaveInstanceState(state);
    }

    /** 释放已离开页面的正文回调。 */
    @Override protected void onDestroy() {
        if (reader != null) reader.close();
        super.onDestroy();
    }
}

