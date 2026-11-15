package github.vhoom.zhijian.data.store.interception;

import android.util.AtomicFile;

import androidx.test.platform.app.InstrumentationRegistry;

import github.vhoom.zhijian.data.model.settings.WordChallengeSettings;
import github.vhoom.zhijian.domain.interception.word.WordEntry;
import github.vhoom.zhijian.domain.interception.word.WordProgress;
import github.vhoom.zhijian.domain.interception.word.WordQuizSession;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * 独立文件验证未答刷新、已答恢复、最低停留、旧写入拒绝和完成换题，不改写正式进度。
 *
 * 维护日期：2026年11月14日
 */
public class WordProgressStoreTest {
    private static final List<WordEntry> ENTRIES = Arrays.asList(word("apple", "苹果"), word("book", "书"),
            word("cat", "猫"), word("desk", "桌子"), word("egg", "鸡蛋"), word("fish", "鱼"));
    private static final WordChallengeSettings SETTINGS = new WordChallengeSettings(2, 1_000L);
    private File path;
    private WordProgressStore store;

    /** 使用本用例独立缓存文件与固定词库。 */
    @Before public void setUp() {
        path = new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(),
                "word-progress-test-" + UUID.randomUUID() + ".json");
        store = new WordProgressStore(path, ENTRIES);
    }

    /** 删除本用例完整文件及未提交临时文件。 */
    @After public void tearDown() throws Exception {
        new AtomicFile(path).delete();
        Files.deleteIfExists(new File(path.getPath() + ".new").toPath());
    }

    /** 原次进入重建保持题目，退出再进换题并拒绝旧页面答案；锁定点击不算开始。 */
    @Test public void untouchedEntryRefreshesOnlyAfterReentry() throws Exception {
        WordProgress first = open("a", "first", SETTINGS, "boot", 0L);
        WordProgress locked = store.answer(first, correct(first), "a", 500L).get(5, TimeUnit.SECONDS).progress;
        assertFalse(locked.started);
        assertEquals(first.revision, locked.revision);
        WordProgress same = new WordProgressStore(path, ENTRIES).open("a", "first", SETTINGS, "boot", 600L).get(5, TimeUnit.SECONDS);
        assertEquals(first.attemptId, same.attemptId);
        WordProgress next = open("a", "second", SETTINGS, "boot", 700L);
        assertNotEquals(first.attemptId, next.attemptId);
        assertNotEquals(first.quiz(ENTRIES).currentQuestion().prompt, next.quiz(ENTRIES).currentQuestion().prompt);
        assertThrows(ExecutionException.class, () -> store.answer(first, correct(first), "a", 2_000L).get(5, TimeUnit.SECONDS));
    }

    /** 答错后跨应用和实例恢复原题与配置；新设置从下一局生效。 */
    @Test public void wrongAnswerKeepsProgressAndSettingsAcrossSessions() throws Exception {
        WordProgress first = open("a", "first", SETTINGS, "boot", 0L);
        String correct = correct(first);
        String wrong = first.quiz(ENTRIES).currentQuestion().options.stream()
                .filter(option -> !option.id.equals(correct)).findFirst().get().id;
        WordProgress saved = store.answer(first, wrong, "a", 1_000L).get(5, TimeUnit.SECONDS).progress;
        store = new WordProgressStore(path, ENTRIES);
        WordProgress restored = open("b", "second", new WordChallengeSettings(1, 60_000L), "boot", 2_000L);
        assertTrue(restored.started);
        assertEquals(saved.attemptId, restored.attemptId);
        assertEquals(0, restored.state.questionIndex);
        assertEquals(2, restored.settings.questionCount);
        assertEquals(1_000L, restored.settings.minDwellMillis);
        assertEquals(saved.quiz(ENTRIES).currentQuestion().prompt, restored.quiz(ENTRIES).currentQuestion().prompt);
        assertThrows(ExecutionException.class, () -> store.answer(first, correct, "a", 2_000L).get(5, TimeUnit.SECONDS));
    }

    /** 正确答案保存题号和下一题期限，设备重启不换题并为当前题重新计时。 */
    @Test public void correctAnswerAndBootRecoveryKeepQuestionIndex() throws Exception {
        WordProgress first = open("a", "first", SETTINGS, "boot-a", 0L);
        WordProgress saved = store.answer(first, correct(first), "a", 1_000L).get(5, TimeUnit.SECONDS).progress;
        WordProgress restored = open("b", "second", SETTINGS, "boot-a", 1_500L);
        assertEquals(1, restored.state.questionIndex);
        assertEquals(2_000L, restored.state.unlockAtElapsedMs);
        WordProgress rebooted = open("b", "second", SETTINGS, "boot-b", 50L);
        assertEquals(saved.attemptId, rebooted.attemptId);
        assertEquals(1, rebooted.state.questionIndex);
        assertEquals(1_050L, rebooted.state.unlockAtElapsedMs);
        assertEquals(1_050L, open("b", "second", SETTINGS, "boot-b", 100L).state.unlockAtElapsedMs);
    }

    /** 完成同会话恢复，退出后同一运行身份也生成新局。 */
    @Test public void finishingCompletedSessionAllowsNextPuzzle() throws Exception {
        WordProgress first = open("a", "first", new WordChallengeSettings(1, 1_000L), "boot", 0L);
        WordProgress solved = store.answer(first, correct(first), "a", 1_000L).get(5, TimeUnit.SECONDS).progress;
        assertTrue(open("a", "first", SETTINGS, "boot", 1_001L).isComplete());
        store.finishSession("a").get(5, TimeUnit.SECONDS);
        WordProgress next = open("a", "next", SETTINGS, "boot", 2_000L);
        assertFalse(next.isComplete());
        assertNotEquals(solved.attemptId, next.attemptId);
        assertEquals(2, next.settings.questionCount);
    }

    /** 读取损坏时保留原字节，不能借恢复失败换题。 */
    @Test public void corruptFileIsPreserved() throws Exception {
        Files.write(path.toPath(), "broken".getBytes(StandardCharsets.UTF_8));
        assertThrows(ExecutionException.class, () -> open("a", "entry", SETTINGS, "boot", 0L));
        assertEquals("broken", new String(Files.readAllBytes(path.toPath()), StandardCharsets.UTF_8));
    }

    /** @param session 运行会话 @param entry 实际进入 @param settings 新局配置 @param boot 启动身份 @param now 单调毫秒 @return 等待成功提交的进度 */
    private WordProgress open(String session, String entry, WordChallengeSettings settings, String boot, long now) throws Exception {
        return store.open(session, entry, settings, boot, now).get(5, TimeUnit.SECONDS);
    }

    /** @param progress 当前题目 @return 按独立词库匹配出的答案 */
    private static String correct(WordProgress progress) {
        WordQuizSession.Question question = progress.quiz(ENTRIES).currentQuestion();
        return ENTRIES.stream().filter(entry -> question.direction == WordQuizSession.Direction.ENGLISH_TO_CHINESE
                ? entry.english.equals(question.prompt) : entry.chineseLabel().equals(question.prompt)).findFirst().get().english;
    }

    /** @param english 单词 @param chinese 唯一释义 @return 测试词条 */
    private static WordEntry word(String english, String chinese) {
        return new WordEntry(english, WordEntry.meaning(WordEntry.PartOfSpeech.NOUN, chinese));
    }
}
