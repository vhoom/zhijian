package github.vhoom.zhijian.domain.interception.word;

import github.vhoom.zhijian.data.model.settings.WordChallengeSettings;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 单词摩擦深 Module 的界面行为测试。
 *
 * 维护日期：2026年11月08日
 */
public class WordQuizSessionTest {
    /** 极端非法题量在分配数组前被拒绝，不能触发内存耗尽。 */
    @Test(expected = IllegalArgumentException.class)
    public void rejectsImpossibleCountBeforeAllocatingDurations() {
        WordQuizSession.start(ENTRIES, new WordChallengeSettings(Integer.MAX_VALUE, 1_000L), "oversized", 0L);
    }

    /** 总时长入口执行相同的容量检查，不能绕过正常出题校验。 */
    @Test(expected = IllegalArgumentException.class)
    public void totalDurationEntryAlsoRejectsImpossibleCount() {
        WordQuizSession.startForTotalDuration(ENTRIES, Integer.MAX_VALUE, Long.MAX_VALUE, "oversized", 0L);
    }

    /** 中断答题完成后重建页面要保留完成态，不能跳回第一题。 */
    @Test
    public void restorePreservesCompletedQuiz() {
        WordChallengeSettings settings = new WordChallengeSettings(1, 1_000L);
        WordQuizSession original = WordQuizSession.start(ENTRIES, settings, "completed", 0L);
        assertEquals(WordQuizSession.AnswerResult.COMPLETE,
                original.answer(correctId(original.currentQuestion(), ENTRIES), 1_000L));
        WordQuizSession restored = WordQuizSession.restore(ENTRIES, settings, "completed",
                original.snapshot(), 2_000L);
        assertTrue(restored.isComplete());
    }
    private static final List<WordEntry> ENTRIES = Arrays.asList(
            word("good", WordEntry.meaning(WordEntry.PartOfSpeech.ADJECTIVE, "好的"),
                    WordEntry.meaning(WordEntry.PartOfSpeech.NOUN, "商品")),
            word("focus", WordEntry.meaning(WordEntry.PartOfSpeech.NOUN, "专注")),
            word("pause", WordEntry.meaning(WordEntry.PartOfSpeech.VERB, "暂停")),
            word("choice", WordEntry.meaning(WordEntry.PartOfSpeech.NOUN, "选择")),
            word("habit", WordEntry.meaning(WordEntry.PartOfSpeech.NOUN, "习惯")),
            word("balance", WordEntry.meaning(WordEntry.PartOfSpeech.NOUN, "平衡")),
            word("calm", WordEntry.meaning(WordEntry.PartOfSpeech.ADJECTIVE, "平静的")),
            word("rest", WordEntry.meaning(WordEntry.PartOfSpeech.VERB, "休息")));

    /** 单个词条应保留多词性与多义项。 */
    @Test
    public void goodEntryKeepsPartsOfSpeech() {
        WordEntry good = ENTRIES.get(0);

        assertEquals("adj. 好的；n. 商品", good.chineseLabel());
    }

    /** 三题会话应同时包含英译汉与汉译英，每题都给出六个选项。 */
    @Test
    public void mixesBothDirectionsWithSixOptions() {
        WordQuizSession session = WordQuizSession.start(ENTRIES,
                new WordChallengeSettings(3, 1_000L), "mixed-directions", 0L);
        boolean sawEnglishToChinese = false;
        boolean sawChineseToEnglish = false;

        for (int index = 0; index < 3; index++) {
            WordQuizSession.Question question = session.currentQuestion();
            assertEquals(6, question.options.size());
            sawEnglishToChinese |= question.direction
                    == WordQuizSession.Direction.ENGLISH_TO_CHINESE;
            sawChineseToEnglish |= question.direction
                    == WordQuizSession.Direction.CHINESE_TO_ENGLISH;
            long answerTime = (index + 1L) * 1_000L;
            assertTrue(answerCorrectly(session, question, answerTime));
        }

        assertTrue(sawEnglishToChinese);
        assertTrue(sawChineseToEnglish);
        assertTrue(session.isComplete());
    }

    /** 每题的最少停留时间结束前，即使选对也不能进题。 */
    @Test
    public void locksAnswersUntilMinimumDwellExpires() {
        WordQuizSession session = WordQuizSession.start(ENTRIES,
                new WordChallengeSettings(1, 5_000L), "locked-answer", 1_000L);
        WordQuizSession.Question question = session.currentQuestion();
        String correctId = correctId(question, ENTRIES);

        assertEquals(WordQuizSession.AnswerResult.LOCKED,
                session.answer(correctId, 5_999L));
        assertEquals(WordQuizSession.AnswerResult.COMPLETE,
                session.answer(correctId, 6_000L));
    }

    /** 中断页右侧数字应等于当前锁定余量加后续题目的最低停留时间。 */
    @Test
    public void reportsMinimumRemainingFrictionDuration() {
        WordQuizSession session = WordQuizSession.start(ENTRIES,
                new WordChallengeSettings(3, 5_000L), "remaining-duration", 1_000L);

        assertEquals(15_000L, session.minimumRemainingDurationMs(1_000L));
        assertEquals(12_000L, session.minimumRemainingDurationMs(4_000L));
        WordQuizSession.Question question = session.currentQuestion();
        assertEquals(WordQuizSession.AnswerResult.NEXT,
                session.answer(correctId(question, ENTRIES), 6_000L));
        assertEquals(10_000L, session.minimumRemainingDurationMs(6_000L));
    }

    /** 规则给出的最低暂停可均匀分配到单词题，并精确保留总时长。 */
    @Test
    public void distributesInterruptionDurationAcrossQuestions() {
        WordQuizSession session = WordQuizSession.startForTotalDuration(
                ENTRIES, 3, 65_000L, "distributed-duration", 0L);

        assertEquals(65_000L, session.minimumRemainingDurationMs(0L));
    }

    /** 页面重建后应恢复原题目、原选项和原解锁时刻。 */
    @Test
    public void restoreRebuildsTheSameQuestionPlan() {
        WordChallengeSettings settings = new WordChallengeSettings(3, 5_000L);
        WordQuizSession original = WordQuizSession.start(ENTRIES, settings,
                "restore-seed", 1_000L);
        WordQuizSession.Question before = original.currentQuestion();
        WordQuizSession restored = WordQuizSession.restore(ENTRIES, settings, "restore-seed",
                original.snapshot(), 2_000L);
        WordQuizSession.Question after = restored.currentQuestion();

        assertEquals(before.direction, after.direction);
        assertEquals(before.prompt, after.prompt);
        assertEquals(labels(before.options), labels(after.options));
        assertEquals(4_000L, restored.remainingLockMs(2_000L));
    }

    /** 选中错误答案应留在当前题。 */
    @Test
    public void wrongAnswerDoesNotAdvance() {
        WordQuizSession session = WordQuizSession.start(ENTRIES,
                new WordChallengeSettings(1, 1_000L), "wrong-answer", 0L);
        WordQuizSession.Question question = session.currentQuestion();
        String correctId = correctId(question, ENTRIES);
        String wrongId = question.options.stream().map(option -> option.id)
                .filter(id -> !id.equals(correctId)).findFirst().orElseThrow(AssertionError::new);

        assertEquals(WordQuizSession.AnswerResult.WRONG, session.answer(wrongId, 1_000L));
        assertFalse(session.isComplete());
        assertEquals(question.prompt, session.currentQuestion().prompt);
    }

    /** 中英文两个出题方向和恢复流程统一使用三个含义，仍按英文 id 判定答案。 */
    @Test
    public void limitsChinesePromptsAndOptionsAndRestoresTheSameLabels() {
        List<WordEntry> entries = new ArrayList<>(ENTRIES);
        entries.add(word("light", WordEntry.meaning(WordEntry.PartOfSpeech.NOUN, "光；灯"),
                WordEntry.meaning(WordEntry.PartOfSpeech.ADJECTIVE, "轻的；浅色的"),
                WordEntry.meaning(WordEntry.PartOfSpeech.VERB, "点燃")));
        WordChallengeSettings settings = new WordChallengeSettings(entries.size(), 1_000L);
        WordQuizSession session = WordQuizSession.start(entries, settings, "limited-meanings", 0L);
        boolean sawLimitedChinese = false;

        for (int index = 0; index < entries.size(); index++) {
            WordQuizSession.Question question = session.currentQuestion();
            assertFalse(question.prompt.contains("浅色的"));
            assertFalse(question.prompt.contains("点燃"));
            for (WordQuizSession.Option option : question.options) {
                assertFalse(option.label.contains("浅色的"));
                assertFalse(option.label.contains("点燃"));
                sawLimitedChinese |= option.label.equals("n. 光；灯；adj. 轻的");
            }
            sawLimitedChinese |= question.prompt.equals("n. 光；灯；adj. 轻的");
            WordQuizSession restored = WordQuizSession.restore(entries, settings, "limited-meanings",
                    session.snapshot(), index * 1_000L);
            assertEquals(question.prompt, restored.currentQuestion().prompt);
            assertEquals(labels(question.options), labels(restored.currentQuestion().options));
            String correct = question.prompt.equals("n. 光；灯；adj. 轻的")
                    ? "light" : correctId(question, entries);
            assertEquals(index == entries.size() - 1 ? WordQuizSession.AnswerResult.COMPLETE
                    : WordQuizSession.AnswerResult.NEXT, session.answer(correct, (index + 1L) * 1_000L));
        }
        assertTrue(sawLimitedChinese);
    }

    /** 完整释义不同但前三义相同的词，不能产生重复中文选项或两个可选英文答案。 */
    @Test
    public void excludesAnswersThatBecomeAmbiguousAfterLimitingMeanings() {
        List<WordEntry> entries = new ArrayList<>(ENTRIES);
        entries.add(word("first", WordEntry.meaning(WordEntry.PartOfSpeech.NOUN,
                "共同一；共同二；共同三；独有一")));
        entries.add(word("second", WordEntry.meaning(WordEntry.PartOfSpeech.NOUN,
                "共同一；共同二；共同三；独有二")));
        WordQuizSession session = WordQuizSession.start(entries,
                new WordChallengeSettings(entries.size(), 1_000L), "short-label-collisions", 0L);
        boolean sawAmbiguousChinesePrompt = false;

        for (int index = 0; index < entries.size(); index++) {
            WordQuizSession.Question question = session.currentQuestion();
            Set<String> uniqueLabels = new HashSet<>();
            for (WordQuizSession.Option option : question.options) uniqueLabels.add(option.label);
            assertEquals(6, uniqueLabels.size());
            String correct;
            if (question.prompt.equals("n. 共同一；共同二；共同三")) {
                sawAmbiguousChinesePrompt = true;
                List<String> matchingAnswers = new ArrayList<>();
                for (WordQuizSession.Option option : question.options) {
                    if (option.id.equals("first") || option.id.equals("second")) {
                        matchingAnswers.add(option.id);
                    }
                }
                assertEquals(1, matchingAnswers.size());
                correct = matchingAnswers.get(0);
            } else {
                correct = correctId(question, entries);
            }
            assertEquals(index == entries.size() - 1 ? WordQuizSession.AnswerResult.COMPLETE
                    : WordQuizSession.AnswerResult.NEXT, session.answer(correct, (index + 1L) * 1_000L));
        }
        assertTrue(sawAmbiguousChinesePrompt);
    }

    /**
     * 提交一道题的正确选项，并返回是否已进入下一题或完成。
     *
     * @param session 当前使用会话明细
     * @param question 需要验证的当前题目及其选项
     * @param nowElapsedMs 当前系统启动后单调计时值，单位为毫秒
     */
    private static boolean answerCorrectly(WordQuizSession session,
            WordQuizSession.Question question, long nowElapsedMs) {
        String correctId = correctId(question, ENTRIES);
        WordQuizSession.AnswerResult result = session.answer(correctId, nowElapsedMs);
        return result == WordQuizSession.AnswerResult.NEXT
                || result == WordQuizSession.AnswerResult.COMPLETE;
    }

    /**
     * 根据题干方向从词库反查正确选项 id。
     *
     * @param question 需要验证的当前题目及其选项
     * @param entries 本次答题可选择的不可变词库
     */
    private static String correctId(WordQuizSession.Question question, List<WordEntry> entries) {
        if (question.direction == WordQuizSession.Direction.ENGLISH_TO_CHINESE) {
            return question.prompt;
        }
        return entries.stream().filter(entry -> entry.chineseLabel().equals(question.prompt))
                .map(entry -> entry.english).findFirst().orElseThrow(AssertionError::new);
    }

    /**
     * 将选项列表转换为可直接比较的标签字符串。
     *
     * @param options 按确定顺序排列的六个选项
     */
    private static String labels(List<WordQuizSession.Option> options) {
        StringBuilder result = new StringBuilder();
        for (WordQuizSession.Option option : options) result.append(option.label).append('|');
        return result.toString();
    }

    /**
     * 简化测试词条声明，避免测试依赖 Android SQLite。
     *
     * @param english 词条的英文文本
     * @param meanings 词条对应的释义集合
     */
    private static WordEntry word(String english, WordEntry.Meaning... meanings) {
        return new WordEntry(english, meanings);
    }
}
