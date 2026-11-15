package github.vhoom.zhijian.ui.interception.page.support;

import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import github.vhoom.zhijian.R;
import github.vhoom.zhijian.domain.interception.word.WordEntry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 单词展示适配器：词性富文本与停留进度，不拥有答题或计时状态。
 *
 * 维护日期：2026年11月11日
 */
public final class WordOptionsPresentation {
    private static final Pattern PART_OF_SPEECH = partOfSpeechPattern();
    private final View options;
    private final ProgressBar line;
    private final Button[] buttons;

    /**
     * @param root 当前单词页
     * @param buttons 六个选项按钮
     */
    public WordOptionsPresentation(View root, Button[] buttons) {
        this.buttons = buttons.clone();
        options = root.findViewById(R.id.interception_word_choices);
        line = root.findViewById(R.id.interception_word_progress);
    }

    /** 从词库词性枚举构造规则，新增词性无需修改展示代码。 */
    private static Pattern partOfSpeechPattern() {
        StringBuilder choices = new StringBuilder();
        for (WordEntry.PartOfSpeech value : WordEntry.PartOfSpeech.values()) {
            if (choices.length() > 0) choices.append('|');
            choices.append(Pattern.quote(value.abbreviation));
        }
        return Pattern.compile("(?<![a-zA-Z])(?:" + choices + ")");
    }

    /**
     * @param label 领域层已限制为最多三条含义的文本
     * @return 小号斜体词性和完整含义，均继承按钮的实色文字
     */
    public CharSequence formatOption(String label) {
        SpannableString styled = new SpannableString(label);
        Matcher matcher = PART_OF_SPEECH.matcher(label);
        while (matcher.find()) {
            int start = matcher.start(), end = matcher.end();
            styled.setSpan(new RelativeSizeSpan(0.8f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            styled.setSpan(new StyleSpan(Typeface.ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            styled.setSpan(new TypefaceSpan("serif"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return styled;
    }

    /** 全部选项直接出现，没有延迟或淡入。 */
    public void enter() { options.setAlpha(1f); }

    /**
     * @param isLocked 是否处于最低停留期
     * @param progress 停留进度零到一千；颜色与透明度不随锁定变化
     */
    public void showAvailability(boolean isLocked, int progress) {
        line.setProgress(progress);
        line.setAlpha(1f);
        options.setAlpha(1f);
        for (Button button : buttons) button.setEnabled(!isLocked);
    }

    /** 暂停不会改变选项的颜色或透明度。 */
    public void pause() {
        options.animate().cancel();
        line.animate().cancel();
        options.setAlpha(1f);
        line.setAlpha(1f);
    }
}
