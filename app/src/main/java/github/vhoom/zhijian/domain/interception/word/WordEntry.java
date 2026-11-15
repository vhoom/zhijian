package github.vhoom.zhijian.domain.interception.word;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一个可出题的英文词条，包含一个或多个带词性的中文义项。
 *
 * 维护日期：2026年11月08日
 */
public final class WordEntry {
    public final String english;
    public final List<Meaning> meanings;

    /**
     * 构造不可变词条，并拒绝空单词或空义项。
     *
     * @param english 词条的英文文本
     * @param meanings 词条对应的释义集合
     */
    public WordEntry(String english, Meaning... meanings) {
        if (english == null || english.trim().isEmpty() || meanings == null
                || meanings.length == 0) {
            throw new IllegalArgumentException("Word entry requires english and meanings");
        }
        this.english = english.trim();
        List<Meaning> copy = new ArrayList<>();
        Collections.addAll(copy, meanings);
        if (copy.contains(null)) throw new IllegalArgumentException("Meaning cannot be null");
        this.meanings = Collections.unmodifiableList(copy);
    }

    /** 返回完整词性与释义标签；答题中的数量限制由独立释义展示规则负责。 */
    public String chineseLabel() {
        StringBuilder label = new StringBuilder();
        for (Meaning meaning : meanings) {
            if (label.length() > 0) label.append('；');
            label.append(meaning.partOfSpeech.abbreviation).append(' ').append(meaning.chinese);
        }
        return label.toString();
    }

    /**
     * 快速创建一个带词性的中文义项。
     *
     * @param partOfSpeech 该义项对应的词性
     * @param chinese 词条对应的中文释义
     */
    public static Meaning meaning(PartOfSpeech partOfSpeech, String chinese) {
        return new Meaning(partOfSpeech, chinese);
    }

    /** 词库当前支持的词性；后续可只扩展枚举而不改出题模块。 */
    public enum PartOfSpeech {
        ADJECTIVE("adj."),
        ADVERB("adv."),
        NOUN("n."),
        VERB("v."),
        PREPOSITION("prep."),
        PRONOUN("pron.");

        public final String abbreviation;

        /**
         * 保留用于展示的标准词性缩写。
         *
         * @param abbreviation 界面显示的词性缩写
         */
        PartOfSpeech(String abbreviation) {
            this.abbreviation = abbreviation;
        }
    }

    /** 词条中的单个词性与中文释义组合。 */
    public static final class Meaning {
        public final PartOfSpeech partOfSpeech;
        public final String chinese;

        /**
         * 构造一个非空的词性义项。
         *
         * @param partOfSpeech 该义项对应的词性
         * @param chinese 词条对应的中文释义
         */
        private Meaning(PartOfSpeech partOfSpeech, String chinese) {
            if (partOfSpeech == null || chinese == null || chinese.trim().isEmpty()) {
                throw new IllegalArgumentException("Meaning requires part of speech and chinese");
            }
            this.partOfSpeech = partOfSpeech;
            this.chinese = chinese.trim();
        }
    }
}
