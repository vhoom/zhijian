package github.vhoom.zhijian.domain.interception.word;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按全词义项上限生成答题文本，保留原词条并按首次出现顺序合并词性。
 *
 * 维护日期：2026年11月08日
 */
public final class WordMeaningFormatter {
    /** 两种题型统一使用的默认全词含义上限。 */
    public static final int DEFAULT_MAX_MEANING_COUNT = 3;

    /** 当前展示规则的全词上限，实例创建后不再变化。 */
    private final int maxMeaningCount;

    /**
     * 创建独立于 Android、存储和答题计时的释义展示规则。
     *
     * @param maxMeaningCount 整个单词最多展示的独立含义数，必须大于零
     */
    public WordMeaningFormatter(int maxMeaningCount) {
        if (maxMeaningCount <= 0) {
            throw new IllegalArgumentException("Meaning count must be positive");
        }
        this.maxMeaningCount = maxMeaningCount;
    }

    /**
     * 按词库顺序选择含义，同词性重复含义不计数，中英文分号分别界定义项。
     * 同一词性只显示一次缩写，逗号等其他标点保留在原释义内。
     *
     * @param entry 包含完整释义的不可变词条
     * @return 可用于中文题干和中文选项的限量释义文本
     */
    public String format(WordEntry entry) {
        if (entry == null) throw new IllegalArgumentException("Word entry is required");
        Map<WordEntry.PartOfSpeech, List<String>> groups = new LinkedHashMap<>();
        int count = 0;
        for (WordEntry.Meaning meaning : entry.meanings) {
            for (String text : meaning.chinese.split("[;；]", -1)) {
                String chinese = text.trim();
                if (chinese.isEmpty()) continue;
                List<String> group = groups.computeIfAbsent(meaning.partOfSpeech,
                        ignored -> new ArrayList<>());
                if (group.contains(chinese)) continue;
                group.add(chinese);
                count++;
                if (count == maxMeaningCount) return formatGroups(groups);
            }
        }
        if (count == 0) {
            throw new IllegalArgumentException("Word has no displayable meanings: " + entry.english);
        }
        return formatGroups(groups);
    }

    /**
     * 将已选择的义项按词性合并为紧凑标签。
     *
     * @param groups 按首次出现顺序排列、每组至少有一个含义的词性与义项
     * @return 保留各义项完整文字的展示文本
     */
    private static String formatGroups(Map<WordEntry.PartOfSpeech, List<String>> groups) {
        StringBuilder label = new StringBuilder();
        for (Map.Entry<WordEntry.PartOfSpeech, List<String>> group : groups.entrySet()) {
            if (label.length() > 0) label.append('；');
            label.append(group.getKey().abbreviation).append(' ');
            label.append(String.join("；", group.getValue()));
        }
        return label.toString();
    }
}
