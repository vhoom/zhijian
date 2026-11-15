package github.vhoom.zhijian.data.store.interception;

import android.content.Context;
import android.util.AtomicFile;
import androidx.test.platform.app.InstrumentationRegistry;

import github.vhoom.zhijian.domain.interception.sudoku.SudokuProgress;
import github.vhoom.zhijian.domain.interception.sudoku.SudokuPuzzles;
import github.vhoom.zhijian.data.model.settings.SudokuDifficulty;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * 使用独立文件验证进度原子保存、重建、跨会话恢复与旧页面写入保护。
 *
 * 维护日期：2026年11月14日
 */
public class SudokuProgressStoreTest {
    private File path;
    private SudokuProgressStore store;

    /** 每个用例创建独立缓存路径，不修改正式挑战。 */
    @Before public void setUp() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        path = new File(context.getCacheDir(), "sudoku-test-" + UUID.randomUUID() + ".json");
        store = new SudokuProgressStore(path);
    }

    /** 清理本用例的文件及原子写入临时文件。 */
    @After public void tearDown() throws Exception {
        new AtomicFile(path).delete();
        Files.deleteIfExists(new File(path.getPath() + ".new").toPath());
    }

    /** 模拟页面退出与存储实例重建，不传 Bundle 也恢复原题、填写和清空后的状态。 */
    @Test public void recreatedStoreResumesExactUnfinishedBoardAcrossSessions() throws Exception {
        SudokuProgress first = store.open("app-a").get(5, TimeUnit.SECONDS);
        int index = first.board.clues().indexOf('0');
        SudokuProgress saved = store.save(first, index, 3, "app-a").get(5, TimeUnit.SECONDS);
        SudokuProgress restored = new SudokuProgressStore(path).open("app-b", SudokuDifficulty.HARD).get(5, TimeUnit.SECONDS);
        assertEquals(first.attemptId, restored.attemptId);
        assertEquals(saved.board.values(), restored.board.values());
        assertEquals(saved.revision, restored.revision);
        assertEquals(SudokuDifficulty.EASY, restored.difficulty);
        SudokuProgress cleared = store.save(restored, index, 0, "app-b").get(5, TimeUnit.SECONDS);
        SudokuProgress reopened = new SudokuProgressStore(path).open("app-c").get(5, TimeUnit.SECONDS);
        assertEquals(first.attemptId, reopened.attemptId);
        assertEquals(first.board.clues(), reopened.board.clues());
        assertEquals(first.board.values(), reopened.board.values());
        assertEquals(0, reopened.board.digit(index));
        assertEquals(saved.revision + 1, cleared.revision);
        assertEquals(cleared.revision, reopened.revision);
        assertEquals(SudokuDifficulty.EASY, reopened.difficulty);
    }

    /** 已完成页重建不换题，新的拦截才生成下一局。 */
    @Test public void completedBoardSurvivesRecreationAndRefreshesNextSession() throws Exception {
        SudokuProgress before = almostComplete();
        SudokuProgress solved = store.save(before, 0, 5, "a").get(5, TimeUnit.SECONDS);
        SudokuProgress same = new SudokuProgressStore(path).open("a").get(5, TimeUnit.SECONDS);
        assertTrue(same.board.isComplete());
        assertEquals(solved.attemptId, same.attemptId);
        SudokuProgress next = store.open("b", SudokuDifficulty.HARD).get(5, TimeUnit.SECONDS);
        assertEquals(SudokuDifficulty.HARD, next.difficulty);
        assertNotEquals(solved.attemptId, next.attemptId);
        assertNotEquals(solved.board.clues(), next.board.clues());
        assertEquals(0, next.board.filledCount());
    }

    /** 未填写的原次进入恢复，重新进入更换题面；填入再清空后不再刷新。 */
    @Test public void untouchedEntryRefreshesButClearedEditsPersist() throws Exception {
        SudokuProgress first = store.open("a", "entry-a", SudokuDifficulty.EASY).get(5, TimeUnit.SECONDS);
        SudokuProgress same = new SudokuProgressStore(path).open("a", "entry-a", SudokuDifficulty.EASY).get(5, TimeUnit.SECONDS);
        assertEquals(first.attemptId, same.attemptId);
        SudokuProgress next = store.open("a", "entry-b", SudokuDifficulty.MEDIUM).get(5, TimeUnit.SECONDS);
        assertNotEquals(first.board.clues(), next.board.clues());
        assertEquals(SudokuDifficulty.MEDIUM, next.difficulty);
        int index = next.board.clues().indexOf('0');
        SudokuProgress edited = store.save(next, index, 1, "a").get(5, TimeUnit.SECONDS);
        SudokuProgress cleared = store.save(edited, index, 0, "a").get(5, TimeUnit.SECONDS);
        SudokuProgress resumed = store.open("b", "entry-c", SudokuDifficulty.HARD).get(5, TimeUnit.SECONDS);
        assertEquals(cleared.attemptId, resumed.attemptId);
        assertEquals(cleared.board.values(), resumed.board.values());
        assertEquals(SudokuDifficulty.MEDIUM, resumed.difficulty);
    }

    /** 旧页面不能覆盖较新的填写或下一局。 */
    @Test public void staleWriterCannotOverwriteNewerProgress() throws Exception {
        SudokuProgress first = store.open("a").get(5, TimeUnit.SECONDS);
        int index = first.board.clues().indexOf('0');
        SudokuProgress saved = store.save(first, index, 4, "a").get(5, TimeUnit.SECONDS);
        assertThrows(ExecutionException.class, () -> store.save(first, index, 1, "a").get(5, TimeUnit.SECONDS));
        assertEquals(saved.board.values(), store.open("a").get(5, TimeUnit.SECONDS).board.values());
    }

    /** 退出不会清空未完成局，完成后退出即使复用拦截身份也换题。 */
    @Test public void finishingSessionPreservesUnfinishedAndRetiresCompletedAttempt() throws Exception {
        SudokuProgress before = almostComplete();
        store.finishSession("a").get(5, TimeUnit.SECONDS);
        assertEquals(before.attemptId, store.open("a").get(5, TimeUnit.SECONDS).attemptId);
        SudokuProgress solved = store.save(before, 0, 5, "a").get(5, TimeUnit.SECONDS);
        store.finishSession("a").get(5, TimeUnit.SECONDS);
        SudokuProgress next = store.open("a").get(5, TimeUnit.SECONDS);
        assertNotEquals(solved.attemptId, next.attemptId);
        assertThrows(ExecutionException.class, () -> store.save(solved, 0, 0, "a").get(5, TimeUnit.SECONDS));
    }

    /** 损坏文件报错并保留原文，不通过重置题目悄悄绕过恢复失败。 */
    @Test public void corruptProgressIsNotSilentlyReplaced() throws Exception {
        Files.write(path.toPath(), "broken".getBytes(StandardCharsets.UTF_8));
        assertThrows(ExecutionException.class, () -> store.open("a").get(5, TimeUnit.SECONDS));
        assertEquals("broken", new String(Files.readAllBytes(path.toPath()), StandardCharsets.UTF_8));
    }

    /** 中断写入留下的临时文件不能替换最后完整快照。 */
    @Test public void interruptedAtomicWriteKeepsCommittedSnapshot() throws Exception {
        SudokuProgress first = store.open("a").get(5, TimeUnit.SECONDS);
        Files.write(new File(path.getPath() + ".new").toPath(), "partial".getBytes(StandardCharsets.UTF_8));
        SudokuProgress restored = new SudokuProgressStore(path).open("b").get(5, TimeUnit.SECONDS);
        assertEquals(first.attemptId, restored.attemptId);
        assertEquals(first.board.values(), restored.board.values());
    }

    /** 预览编辑与完成不会消费正式保存的一局。 */
    @Test public void previewProgressDoesNotTouchProductionFile() throws Exception {
        SudokuProgress official = store.open("a").get(5, TimeUnit.SECONDS);
        byte[] original = Files.readAllBytes(path.toPath());
        SudokuProgress sample = new SudokuProgress("preview", 0, SudokuPuzzles.create("preview"), "");
        SudokuProgressStore preview = SudokuProgressStore.preview(sample);
        preview.save(sample, sample.board.clues().indexOf('0'), 2, "preview").get(5, TimeUnit.SECONDS);
        assertArrayEquals(original, Files.readAllBytes(path.toPath()));
        assertEquals(official.attemptId, store.open("b").get(5, TimeUnit.SECONDS).attemptId);
    }

    /** @return 只差一格的持久记录，用于验证完成边界而非求解性能 */
    private SudokuProgress almostComplete() throws Exception {
        String solution = "534678912672195348198342567859761423426853791713924856961537284287419635345286179";
        String clues = "0" + solution.substring(1);
        JSONObject row = new JSONObject().put("attempt", "fixture").put("revision", 0)
                .put("clues", clues).put("values", clues).put("completed_session", "");
        Files.write(path.toPath(), row.toString().getBytes(StandardCharsets.UTF_8));
        return store.open("a").get(5, TimeUnit.SECONDS);
    }
}
