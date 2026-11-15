package github.vhoom.zhijian.domain.interception.word;

import github.vhoom.zhijian.data.model.settings.WordChallengeSettings;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * 单词开始边界、错误答案与开机时钟恢复；通过独立领域会话验证进度。
 *
 * 维护日期：2026年11月14日
 */
public class WordProgressTest {
    private static final List<WordEntry> ENTRIES = Arrays.asList(
            word("apple", "苹果"), word("book", "书"), word("cat", "猫"),
            word("desk", "桌子"), word("egg", "鸡蛋"), word("fish", "鱼"));

    /** 未答题仅原次进入恢复；设备重启的版本变化也不能误标为已答。 */
    @Test public void untouchedProgressOnlyResumesItsEntry() {
        WordProgress first = start();
        assertTrue(first.resumesIn("session", "entry"));
        assertFalse(first.resumesIn("session", "next-entry"));
        WordProgress rebooted = first.onBoot("boot-b", 50L);
        assertFalse(rebooted.started);
        assertFalse(rebooted.resumesIn("session", "next-entry"));
    }

    /** 错误答案也算开始，切换应用与重启均保留题目，重启后重新建立当前题停留时钟。 */
    @Test public void wrongAnswerPreservesQuestionAcrossEntriesAndBoots() {
        WordProgress first = start();
        WordQuizSession quiz = first.quiz(ENTRIES);
        String prompt = quiz.currentQuestion().prompt;
        String correct = correctId(quiz.currentQuestion());
        String wrong = quiz.currentQuestion().options.stream().filter(option -> !option.id.equals(correct)).findFirst().get().id;
        assertEquals(WordQuizSession.AnswerResult.WRONG, quiz.answer(wrong, 1_000L));
        WordProgress answered = first.answered(quiz.snapshot(), "a");
        assertTrue(answered.started);
        assertTrue(answered.resumesIn("b", "another-entry"));
        assertEquals(prompt, answered.quiz(ENTRIES).currentQuestion().prompt);
        WordProgress rebooted = answered.onBoot("boot-b", 50L);
        assertEquals(0, rebooted.state.questionIndex);
        assertEquals(1_050L, rebooted.state.unlockAtElapsedMs);
        assertSame(rebooted, rebooted.onBoot("boot-b", 500L));
    }

    /** 完成后原会话可显示终题，退出后复用会话身份也不恢复旧局。 */
    @Test public void completedProgressRetiresAfterOutcome() {
        WordProgress first = start();
        WordQuizSession quiz = first.quiz(ENTRIES);
        assertEquals(WordQuizSession.AnswerResult.COMPLETE, quiz.answer(correctId(quiz.currentQuestion()), 1_000L));
        WordProgress completed = first.answered(quiz.snapshot(), "a");
        assertTrue(completed.isComplete());
        assertTrue(completed.resumesIn("a", "new-entry"));
        assertFalse(completed.resumesIn("b", "entry"));
        assertEquals(1, completed.quiz(ENTRIES).currentQuestion().number);
        assertFalse(completed.retired().resumesIn("a", "entry"));
    }

    /** @return 固定种子的未开始一题挑战 */
    private static WordProgress start() {
        return WordProgress.start(ENTRIES, new WordChallengeSettings(1, 1_000L), "seed", "entry", "boot-a", 0L);
    }

    /** @param english 单词 @param chinese 唯一释义 @return 测试词条 */
    private static WordEntry word(String english, String chinese) {
        return new WordEntry(english, WordEntry.meaning(WordEntry.PartOfSpeech.NOUN, chinese));
    }

    /** @param question 显示题目 @return 按独立测试词库匹配的正确词条 */
    private static String correctId(WordQuizSession.Question question) {
        return ENTRIES.stream().filter(entry -> question.direction == WordQuizSession.Direction.ENGLISH_TO_CHINESE
                ? entry.english.equals(question.prompt) : entry.chineseLabel().equals(question.prompt)).findFirst().get().english;
    }
}
