package github.vhoom.zhijian.ui.preview;

import android.app.Activity;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.EditText;
import android.widget.TextView;

import github.vhoom.zhijian.R;
import github.vhoom.zhijian.data.source.DailyPoem;

/**
 * Debug 诗句预览：将自定义文本绑定到首页共用布局，显示字符数和实际排版行数。
 * 草稿仅保存在页面重建快照中，不修改诗句资源或正式首页设置。
 *
 * 维护日期：2026年11月11日
 */
public final class PoemPreviewBinder implements AutoCloseable {
    private static final String STATE_TEXT = "developer.poem_text";
    private final Activity activity;
    private final EditText input;
    private final TextView poem;
    private final TextView metrics;
    private final TextWatcher textWatcher;
    /** 在文本和可用宽度完成排版后读取实际行数。 */
    private final ViewTreeObserver.OnGlobalLayoutListener layoutListener = this::updateMetrics;

    /**
     * 连接预览输入、样例操作和共用诗句区域，恢复页面中的文本草稿。
     * @param activity 提供当前主题及字符串资源的开发者页面
     * @param root 仅包含诗句输入和共用强度区域的预览根布局
     * @param savedInstanceState 页面重建快照，首次进入时为空
     */
    public PoemPreviewBinder(Activity activity, View root, Bundle savedInstanceState) {
        this.activity = activity;
        input = root.findViewById(R.id.poem_preview_text);
        poem = root.findViewById(R.id.strength_poem);
        metrics = root.findViewById(R.id.poem_preview_metrics);
        ((TextView) root.findViewById(R.id.strength_mode)).setText(R.string.level_seal);
        textWatcher = createTextWatcher();
        input.addTextChangedListener(textWatcher);
        poem.getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
        root.findViewById(R.id.poem_preview_random).setOnClickListener(view ->
                setPreviewText(DailyPoem.random(activity)));
        root.findViewById(R.id.poem_preview_longest).setOnClickListener(view ->
                setPreviewText(longestPoem()));
        String restored = savedInstanceState == null ? null : savedInstanceState.getString(STATE_TEXT);
        setPreviewText(restored == null ? DailyPoem.random(activity) : restored);
    }

    /**
     * 创建输入观察器，在文本修改完成后同步预览正文。
     * @return 只观察当前预览输入的文本监听器
     */
    private TextWatcher createTextWatcher() {
        return new TextWatcher() {
            /**
             * 修改前无需处理，等待完整文本更新。
             * @param text 修改前的文本
             * @param start 修改起始位置
             * @param count 将被替换的字符数量
             * @param after 替换后的字符数量
             */
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }

            /**
             * 修改中不更新布局，由修改完成回调统一处理。
             * @param text 修改中的文本
             * @param start 修改起始位置
             * @param before 原有字符数量
             * @param count 新增字符数量
             */
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { }

            /**
             * 将完成修改的文本交给共用诗句布局。
             * @param text 输入框当前的完整文本
             */
            @Override public void afterTextChanged(Editable text) {
                poem.setText(text.toString());
                poem.setVisibility(text.length() == 0 ? View.GONE : View.VISIBLE);
                updateMetrics();
            }
        };
    }

    /**
     * 用资源样例或恢复草稿替换输入，沿用相同的预览更新流程。
     * @param text 准备显示的完整诗句文本
     */
    private void setPreviewText(String text) {
        input.setText(text);
        input.setSelection(input.length());
    }

    /**
     * 从当前资源中选取字符最多的诗句，长度相同时保留先出现的正文。
     * @return 最长诗句正文；资源数组为空时返回空字符串
     */
    private String longestPoem() {
        String longest = "";
        int maximum = 0;
        for (String text : activity.getResources().getStringArray(R.array.daily_poems)) {
            int characters = text.codePointCount(0, text.length());
            if (characters > maximum) {
                longest = text;
                maximum = characters;
            }
        }
        return longest;
    }

    /** 更新 Unicode 字符数量和实际显示行数，避免相同统计反复触发布局。 */
    private void updateMetrics() {
        String text = input.getText().toString();
        int characters = text.codePointCount(0, text.length());
        int lines = poem.getVisibility() == View.VISIBLE ? poem.getLineCount() : 0;
        String value = activity.getString(R.string.poem_preview_metrics, characters, lines);
        if (!TextUtils.equals(metrics.getText(), value)) metrics.setText(value);
    }

    /**
     * 保存当前输入，重建页面后继续检查同一段文本。
     * @param state 系统用于恢复开发者页面的快照
     */
    public void saveState(Bundle state) {
        state.putString(STATE_TEXT, input.getText().toString());
    }

    /** 移除输入和排版监听，释放已销毁页面的观察关系。 */
    @Override public void close() {
        input.removeTextChangedListener(textWatcher);
        ViewTreeObserver observer = poem.getViewTreeObserver();
        if (observer.isAlive()) observer.removeOnGlobalLayoutListener(layoutListener);
    }
}
