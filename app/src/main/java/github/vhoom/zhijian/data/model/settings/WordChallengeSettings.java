package github.vhoom.zhijian.data.model.settings;

import github.vhoom.zhijian.data.model.word.BuiltInWordCatalog;

/**
 * 单词摩擦的可持久化配置：题量、每题停留时间与词库选择共同组成模式快照。
 *
 * 维护日期：2026年11月08日
 */
public final class WordChallengeSettings {
    public static final int MIN_QUESTION_COUNT = 1;
    public static final long MIN_DWELL_MILLIS = 100L;
    public static final long MAX_DWELL_MILLIS = 60_000L;

    public final int questionCount;
    public final long minDwellMillis;
    public final BuiltInWordCatalog catalog;

    /**
     * 构造经过边界校验的单词摩擦配置。
     *
     * @param questionCount 本次需要完成的题目数量
     * @param minDwellMillis 每题最少停留的毫秒数
     */
    public WordChallengeSettings(int questionCount, long minDwellMillis) {
        this(questionCount, minDwellMillis, BuiltInWordCatalog.JUNIOR_HIGH);
    }

    /**
     * 构造本模式的完整单词配置，词库缺省为初中。
     * @param questionCount 本次需要完成的题目数量
     * @param minDwellMillis 每题最少停留毫秒数
     * @param catalog 出题使用的词库
     */
    public WordChallengeSettings(int questionCount, long minDwellMillis, BuiltInWordCatalog catalog) {
        if (!isValid(questionCount, minDwellMillis)) {
            throw new IllegalArgumentException("Invalid word challenge settings");
        }
        this.questionCount = questionCount;
        this.minDwellMillis = minDwellMillis;
        this.catalog = catalog == null ? BuiltInWordCatalog.JUNIOR_HIGH : catalog;
    }

    /** 返回不考虑答错情况的理论最低摩擦时长，单位为毫秒。 */
    public long minimumDurationMillis() {
        return Math.multiplyExact((long) questionCount, minDwellMillis);
    }

    /**
     * 判断题量为正数且停留时间合法；词库容量由取得词库的调用方另外校验。
     *
     * @param questionCount 题目数量
     * @param minDwellMillis 每题停留的毫秒数
     * @return 两项均合法且时长具有 0.1 秒精度时返回 true
     */
    public static boolean isValid(int questionCount, long minDwellMillis) {
        return questionCount >= MIN_QUESTION_COUNT
                && minDwellMillis >= MIN_DWELL_MILLIS
                && minDwellMillis <= MAX_DWELL_MILLIS
                && minDwellMillis % 100L == 0;
    }

    /**
     * 按当前所选词库的有效词条数检查题量，不修改用户输入。
     * @param questionCount 草稿题量，使用 long 以识别超出整数容量的输入
     * @param catalogSize 所选词库的有效词条数
     * @return 题量至少为一且不超过词库容量时返回 true
     */
    public static boolean isValidQuestionCount(long questionCount, int catalogSize) {
        return questionCount >= MIN_QUESTION_COUNT && questionCount <= catalogSize;
    }
}
