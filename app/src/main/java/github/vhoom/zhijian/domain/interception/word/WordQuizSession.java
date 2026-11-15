package github.vhoom.zhijian.domain.interception.word;

import github.vhoom.zhijian.data.model.settings.WordChallengeSettings;

import java.util.ArrayList;
import java.util.AbstractList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 单词摩擦的深 Module：在一个小界面后封装双向出题、六选一、停留锁定与进度。
 *
 * 维护日期：2026年11月08日
 */
public final class WordQuizSession {
    /**
     * 封装 Direction 的数据与职责，供所属模块统一使用。
     */
    public enum Direction { ENGLISH_TO_CHINESE, CHINESE_TO_ENGLISH }
    /**
     * 封装 AnswerResult 的数据与职责，供所属模块统一使用。
     */
    public enum AnswerResult { LOCKED, WRONG, NEXT, COMPLETE }

    private static final int OPTION_COUNT = 6;

    private final long[] dwellDurationsMs;
    private final List<PlannedQuestion> plan;
    private int questionIndex;
    private long unlockAtElapsedMs;

    /**
     * 用 SQLite 提供的词库开始新会话，第一题从当前时刻起执行停留锁定。
     *
     * @param entries 本次答题可选择的不可变词库
     * @param settings 经过边界校验的全局或挑战设置
     * @param seed 同次会话内保持随机结果稳定的种子
     * @param nowElapsedMs 当前系统启动后单调计时值，单位为毫秒
     */
    public static WordQuizSession start(List<WordEntry> entries, WordChallengeSettings settings,
            String seed, long nowElapsedMs) {
        if (settings == null) throw new IllegalArgumentException("Settings are required");
        requireCapacity(entries, settings.questionCount);
        long[] durations = uniformDurations(settings.questionCount,
                settings.minDwellMillis);
        return start(entries, durations, seed, nowElapsedMs);
    }

    /**
     * 用保存的题号与解锁时刻恢复同一套可重现题目。
     *
     * @param entries 本次答题可选择的不可变词库
     * @param settings 经过边界校验的全局或挑战设置
     * @param seed 同次会话内保持随机结果稳定的种子
     * @param state 待恢复或处理的状态快照
     * @param nowElapsedMs 当前系统启动后单调计时值，单位为毫秒
     */
    public static WordQuizSession restore(List<WordEntry> entries,
            WordChallengeSettings settings, String seed, State state, long nowElapsedMs) {
        if (settings == null) throw new IllegalArgumentException("Settings are required");
        requireCapacity(entries, settings.questionCount);
        return restore(entries, uniformDurations(settings.questionCount,
                settings.minDwellMillis), seed, state, nowElapsedMs);
    }

    /**
     * 让全部题目的最低停留时间精确合计为指定的中断摩擦时长。
     *
     * @param entries SQLite 词库提供的候选词条
     * @param questionCount 本次需要完成的题目数
     * @param totalDurationMs 页面最终选定的实际摩擦总时长
     * @param seed 生成可重现题目的会话种子
     * @param nowElapsedMs 当前单调时钟毫秒值
     */
    public static WordQuizSession startForTotalDuration(List<WordEntry> entries,
            int questionCount, long totalDurationMs, String seed, long nowElapsedMs) {
        requireCapacity(entries, questionCount);
        return start(entries, distributedDurations(questionCount, totalDurationMs),
                seed, nowElapsedMs);
    }

    /**
     * 恢复按实际摩擦总时长分配出的单词摩擦会话。
     *
     * @param entries SQLite 词库提供的候选词条
     * @param questionCount 本次需要完成的题目数
     * @param totalDurationMs 页面最终选定的实际摩擦总时长
     * @param seed 生成可重现题目的会话种子
     * @param state 上次保存的题目进度和解锁时刻
     * @param nowElapsedMs 当前单调时钟毫秒值，仅在状态无效时用于重建
     */
    public static WordQuizSession restoreForTotalDuration(List<WordEntry> entries,
            int questionCount, long totalDurationMs, String seed, State state,
            long nowElapsedMs) {
        requireCapacity(entries, questionCount);
        return restore(entries, distributedDurations(questionCount, totalDurationMs),
                seed, state, nowElapsedMs);
    }

    /**
     * 使用逐题时长表开始会话。
     *
     * @param entries 本次答题可选择的不可变词库
     * @param durations 各题最低停留时长的集合
     * @param seed 同次会话内保持随机结果稳定的种子
     * @param nowElapsedMs 当前系统启动后单调计时值，单位为毫秒
     */
    private static WordQuizSession start(List<WordEntry> entries, long[] durations,
            String seed, long nowElapsedMs) {
        return new WordQuizSession(entries, durations, seed, 0,
                Math.addExact(nowElapsedMs, durations[0]));
    }

    /**
     * 使用逐题时长表恢复会话，无效状态会回到第一题。
     *
     * @param entries 本次答题可选择的不可变词库
     * @param durations 各题最低停留时长的集合
     * @param seed 同次会话内保持随机结果稳定的种子
     * @param state 待恢复或处理的状态快照
     * @param nowElapsedMs 当前系统启动后单调计时值，单位为毫秒
     */
    private static WordQuizSession restore(List<WordEntry> entries, long[] durations,
            String seed, State state, long nowElapsedMs) {
        if (state == null || state.questionIndex < 0
                || state.questionIndex > durations.length) {
            return start(entries, durations, seed, nowElapsedMs);
        }
        return new WordQuizSession(entries, durations, seed,
                state.questionIndex, state.unlockAtElapsedMs);
    }

    /**
     * 构造确定性出题计划并恢复指定进度。
     *
     * @param entries 本次答题可选择的不可变词库
     * @param dwellDurationsMs 各题最低停留时长，单位为毫秒
     * @param seed 同次会话内保持随机结果稳定的种子
     * @param questionIndex 从零开始的当前题目索引
     * @param unlockAtElapsedMs 本题允许作答的单调计时值
     */
    private WordQuizSession(List<WordEntry> entries, long[] dwellDurationsMs, String seed,
            int questionIndex, long unlockAtElapsedMs) {
        this.dwellDurationsMs = dwellDurationsMs;
        this.plan = buildPlan(entries, dwellDurationsMs.length, seed == null ? "" : seed);
        this.questionIndex = questionIndex;
        this.unlockAtElapsedMs = unlockAtElapsedMs;
    }

    /** 返回 UI 渲染当前题所需的全部信息，不泄露正确答案。 */
    public Question currentQuestion() {
        if (isComplete()) throw new IllegalStateException("Word quiz is complete");
        PlannedQuestion planned = plan.get(questionIndex);
        return new Question(questionIndex + 1, plan.size(), planned.direction,
                planned.prompt, planned.options);
    }

    /**
     * 尝试提交选项；锁定期不接受输入，答对后进入下一题或完成。
     *
     * @param optionId 用户点击的选项稳定 ID
     * @param nowElapsedMs 当前系统启动后单调计时值，单位为毫秒
     */
    public AnswerResult answer(String optionId, long nowElapsedMs) {
        if (isComplete()) return AnswerResult.COMPLETE;
        if (remainingLockMs(nowElapsedMs) > 0) return AnswerResult.LOCKED;
        PlannedQuestion current = plan.get(questionIndex);
        if (!current.correctOptionId.equals(optionId)) return AnswerResult.WRONG;
        questionIndex++;
        if (isComplete()) return AnswerResult.COMPLETE;
        unlockAtElapsedMs = Math.addExact(nowElapsedMs, dwellDurationsMs[questionIndex]);
        return AnswerResult.NEXT;
    }

    /**
     * 返回当前题距离允许点击的剩余毫秒数。
     *
     * @param nowElapsedMs 当前系统启动后单调计时值，单位为毫秒
     */
    public long remainingLockMs(long nowElapsedMs) {
        return Math.max(0L, unlockAtElapsedMs - nowElapsedMs);
    }

    /**
     * 返回按最快正确作答计算的剩余必需摩擦时间。
     * 当前题使用真实锁定余量，后续每题各计一个完整停留时长。
     *
     * @param nowElapsedMs 当前系统启动后单调计时值，单位为毫秒
     */
    public long minimumRemainingDurationMs(long nowElapsedMs) {
        if (isComplete()) return 0L;
        long remaining = remainingLockMs(nowElapsedMs);
        for (int index = questionIndex + 1; index < dwellDurationsMs.length; index++) {
            remaining = Math.addExact(remaining, dwellDurationsMs[index]);
        }
        return remaining;
    }

    /** 返回每题需要锁定的完整毫秒数，供 UI 计算进度。 */
    public long dwellDurationMs() {
        if (isComplete()) return 0L;
        return dwellDurationsMs[questionIndex];
    }

    /**
     * 为每题生成相同的停留时长。
     *
     * @param questionCount 需要完成的题目数量
     * @param durationMs 时长，单位为毫秒
     */
    private static long[] uniformDurations(int questionCount, long durationMs) {
        if (questionCount <= 0 || durationMs <= 0) {
            throw new IllegalArgumentException("Invalid word friction duration");
        }
        long[] durations = new long[questionCount];
        for (int index = 0; index < questionCount; index++) durations[index] = durationMs;
        return durations;
    }

    /**
     * 将总时长尽量均匀分给各题，并保证各题之和与实际页面时长完全一致。
     *
     * @param questionCount 需要分配的题目数
     * @param totalDurationMs 需要完整覆盖的总毫秒数
     */
    private static long[] distributedDurations(int questionCount, long totalDurationMs) {
        if (questionCount <= 0 || totalDurationMs < questionCount) {
            throw new IllegalArgumentException("Interruption pause is too short");
        }
        long[] durations = new long[questionCount];
        long base = totalDurationMs / questionCount;
        long remainder = totalDurationMs % questionCount;
        for (int index = 0; index < questionCount; index++) {
            durations[index] = base + (index < remainder ? 1L : 0L);
        }
        return durations;
    }

    /** 判断所有题目是否已经答对。 */
    public boolean isComplete() {
        return questionIndex >= plan.size();
    }

    /** 导出页面重建所需的最小状态。 */
    public State snapshot() {
        return new State(questionIndex, unlockAtElapsedMs);
    }

    /**
     * 构建一套可重现的混合方向题目和六个唯一选项。
     *
     * @param source 需要复制的原始草稿、记录或数据来源
     * @param questionCount 需要完成的题目数量
     * @param seed 同次会话内保持随机结果稳定的种子
     */
    private static List<PlannedQuestion> buildPlan(List<WordEntry> source, int questionCount,
            String seed) {
        requireCapacity(source, questionCount);
        WordMeaningFormatter formatter = new WordMeaningFormatter(
                WordMeaningFormatter.DEFAULT_MAX_MEANING_COUNT);
        Map<WordEntry, String> chineseLabels = new HashMap<>();
        for (WordEntry entry : source) chineseLabels.put(entry, formatter.format(entry));
        Random random = new Random(mixSeed(seed));
        List<WordEntry> entries = new ArrayList<>(source);
        Collections.shuffle(entries, random);
        List<Direction> directions = balancedDirections(questionCount, random);
        // 只构造当前题的选项，题量较大时不在进入页面的一瞬间执行全部题目的全库洗牌。
        return new AbstractList<PlannedQuestion>() {
            private int cachedIndex = -1;
            private PlannedQuestion cachedQuestion;

            /**
             * 按题号独立种子生成选项，恢复任意题号都不必重建前面的题目。
             * @param index 零起点的题号
             * @return 不泄露到 UI 的完整题目计划
             */
            @Override public PlannedQuestion get(int index) {
                if (index < 0 || index >= questionCount) throw new IndexOutOfBoundsException();
                if (cachedIndex != index) {
                    Random questionRandom = new Random(mixSeed(seed) ^ (index * 0x9E3779B97F4A7C15L));
                    cachedQuestion = buildQuestion(entries, entries.get(index), directions.get(index),
                            questionRandom, chineseLabels);
                    cachedIndex = index;
                }
                return cachedQuestion;
            }

            /** @return 本会话的固定题量 */
            @Override public int size() { return questionCount; }
        };
    }

    /**
     * 在按题量分配数组之前拒绝不可能完成的会话，防止异常设置触发巨量分配。
     * @param entries 当前词库的候选集
     * @param count 请求的题量
     */
    private static void requireCapacity(List<WordEntry> entries, int count) {
        if (entries == null || entries.size() < OPTION_COUNT
                || !WordChallengeSettings.isValidQuestionCount(count, entries.size())) {
            throw new IllegalArgumentException("Word catalog is too small for this quiz");
        }
    }

    /**
     * 生成数量尽量均衡、顺序随机的英译汉与汉译英方向。
     *
     * @param count 处理的条目数或本次变更的字符数量
     * @param random 由会话种子初始化的随机数生成器
     */
    private static List<Direction> balancedDirections(int count, Random random) {
        List<Direction> directions = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            directions.add(index % 2 == 0
                    ? Direction.ENGLISH_TO_CHINESE : Direction.CHINESE_TO_ENGLISH);
        }
        Collections.shuffle(directions, random);
        return directions;
    }

    /**
     * 为一个正确词条生成五个显示值不重复的干扰项。
     *
     * @param entries 本次答题可选择的不可变词库
     * @param correct 当前题目的正确词条或选项
     * @param direction 英文选中文或中文选英文的答题方向
     * @param random 由会话种子初始化的随机数生成器
     * @param chineseLabels 本次会话统一使用的限量中文标签
     */
    private static PlannedQuestion buildQuestion(List<WordEntry> entries, WordEntry correct,
            Direction direction, Random random, Map<WordEntry, String> chineseLabels) {
        String correctChinese = chineseLabels.get(correct);
        String correctLabel = optionLabel(correct, direction, chineseLabels);
        List<WordEntry> candidates = new ArrayList<>(entries);
        Collections.shuffle(candidates, random);
        List<Option> options = new ArrayList<>();
        Set<String> labels = new HashSet<>();
        labels.add(correctLabel);
        options.add(new Option(correct.english, correctLabel));
        for (WordEntry candidate : candidates) {
            if (candidate == correct) continue;
            // 缩短中文题干后，释义相同的其他英文词不能同时成为备选答案。
            if (direction == Direction.CHINESE_TO_ENGLISH
                    && correctChinese.equals(chineseLabels.get(candidate))) continue;
            String label = optionLabel(candidate, direction, chineseLabels);
            if (!labels.add(label)) continue;
            options.add(new Option(candidate.english, label));
            if (options.size() == OPTION_COUNT) break;
        }
        if (options.size() != OPTION_COUNT) {
            throw new IllegalArgumentException("Word catalog lacks unique answer labels");
        }
        Collections.shuffle(options, random);
        String prompt = direction == Direction.ENGLISH_TO_CHINESE
                ? correct.english : correctChinese;
        return new PlannedQuestion(direction, prompt,
                Collections.unmodifiableList(options), correct.english);
    }

    /**
     * 根据出题方向返回选项应显示的一侧文本。
     *
     * @param entry 当前选中的词条
     * @param direction 英文选中文或中文选英文的答题方向
     * @param chineseLabels 已按统一规则生成的限量中文标签
     */
    private static String optionLabel(WordEntry entry, Direction direction,
            Map<WordEntry, String> chineseLabels) {
        return direction == Direction.ENGLISH_TO_CHINESE
                ? chineseLabels.get(entry) : entry.english;
    }

    /**
     * 将字符串种子混合为更适合 Random 的 64 位值。
     *
     * @param seed 同次会话内保持随机结果稳定的种子
     */
    private static long mixSeed(String seed) {
        long value = seed.hashCode();
        value ^= value << 21;
        value ^= value >>> 35;
        value ^= value << 4;
        return value;
    }

    /** UI 可安全渲染的当前题快照。 */
    public static final class Question {
        public final int number;
        public final int total;
        public final Direction direction;
        public final String prompt;
        public final List<Option> options;

        /**
         * 保存一道题的序号、方向、题干和六个选项。
         *
         * @param number 从一开始的题目编号
         * @param total 本次挑战需要完成的总题量
         * @param direction 英文选中文或中文选英文的答题方向
         * @param prompt 当前题目显示的提示文本
         * @param options 按确定顺序排列的六个选项
         */
        private Question(int number, int total, Direction direction, String prompt,
                List<Option> options) {
            this.number = number;
            this.total = total;
            this.direction = direction;
            this.prompt = prompt;
            this.options = options;
        }
    }

    /** 可显示的答案选项；id 在同一题中唯一。 */
    public static final class Option {
        public final String id;
        public final String label;

        /**
         * 构造一个选项标识与显示文本。
         *
         * @param id 当前记录、规则或视图的稳定标识
         * @param label 界面显示的可读名称
         */
        private Option(String id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    /** 用于 Activity 重建的最小会话状态。 */
    public static final class State {
        public final int questionIndex;
        public final long unlockAtElapsedMs;

        /**
         * 保存当前题索引与解锁时刻。
         *
         * @param questionIndex 从零开始的当前题目索引
         * @param unlockAtElapsedMs 本题允许作答的单调计时值
         */
        public State(int questionIndex, long unlockAtElapsedMs) {
            this.questionIndex = questionIndex;
            this.unlockAtElapsedMs = unlockAtElapsedMs;
        }
    }

    /** 模块内部的含正确答案题目，不穿过对外界面。 */
    private static final class PlannedQuestion {
        private final Direction direction;
        private final String prompt;
        private final List<Option> options;
        private final String correctOptionId;

        /**
         * 构造一道仅供模块内部判题的完整题目。
         *
         * @param direction 英文选中文或中文选英文的答题方向
         * @param prompt 当前题目显示的提示文本
         * @param options 按确定顺序排列的六个选项
         * @param correctOptionId 当前题目正确选项的稳定 ID
         */
        private PlannedQuestion(Direction direction, String prompt, List<Option> options,
                String correctOptionId) {
            this.direction = direction;
            this.prompt = prompt;
            this.options = options;
            this.correctOptionId = correctOptionId;
        }
    }
}
