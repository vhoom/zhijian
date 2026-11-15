package github.vhoom.zhijian.data.store.interception;

import android.content.Context;
import android.util.AtomicFile;

import github.vhoom.zhijian.domain.interception.sudoku.SudokuBoard;
import github.vhoom.zhijian.domain.interception.sudoku.SudokuProgress;
import github.vhoom.zhijian.domain.interception.sudoku.SudokuPuzzles;
import github.vhoom.zhijian.data.model.settings.SudokuDifficulty;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 串行、原子保存全局未完成的一局；所有磁盘访问在后台，成功后才发布新快照。
 *
 * 维护日期：2026年11月14日
 */
public final class SudokuProgressStore {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private final AtomicFile file;
    private SudokuProgress preview;

    /** @param context 应用文件目录上下文 */
    public SudokuProgressStore(Context context) {
        this(new File(context.getApplicationContext().getFilesDir(), "sudoku_progress.json"));
    }

    /** @param path 独立进度文件；包内设备测试使用临时路径 */
    SudokuProgressStore(File path) { file = new AtomicFile(path); }

    /** @param initial 只在内存中保存的预览快照 */
    private SudokuProgressStore(SudokuProgress initial) {
        file = null;
        preview = initial;
    }

    /** @param initial 已恢复的样例进度 @return 不读写正式文件的预览存储 */
    public static SudokuProgressStore preview(SudokuProgress initial) { return new SudokuProgressStore(initial); }

    /** @param sessionId 当前拦截身份 @return 已落盘的一局；未完成时跨应用、跨进程复用 */
    public CompletableFuture<SudokuProgress> open(String sessionId) {
        return open(sessionId, SudokuDifficulty.EASY);
    }

    /** @param sessionId 当前拦截身份 @param difficulty 新局难度，未完成局始终保留自己的难度和题面 @return 已提交的一局 */
    public CompletableFuture<SudokuProgress> open(String sessionId, SudokuDifficulty difficulty) {
        return open(sessionId, "", difficulty);
    }

    /** @param sessionId 拦截身份 @param entryId 本次实际进入标识 @param difficulty 新局难度 @return 未开始的旧进入换题，已开始或同次重建恢复 */
    public CompletableFuture<SudokuProgress> open(String sessionId, String entryId, SudokuDifficulty difficulty) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                SudokuProgress current = read();
                if (current != null && current.resumesIn(sessionId, entryId)) return current;
                String id = UUID.randomUUID().toString();
                SudokuBoard board = SudokuPuzzles.create(id, difficulty);
                // 完成后的下一题保证题面与上一局不同。
                while (current != null && board.clues().equals(current.board.clues())) {
                    id = UUID.randomUUID().toString();
                    board = SudokuPuzzles.create(id, difficulty);
                }
                SudokuProgress next = new SudokuProgress(id, 0, board, "", difficulty, entryId);
                write(next);
                return next;
            } catch (IOException | JSONException failure) {
                throw new CompletionException(failure);
            }
        }, IO);
    }

    /** @param expected 页面最后收到的已提交快照 @param index 目标格子 @param digit 数字或清除 @param sessionId 当前拦截身份 @return 保存成功后的新快照 */
    public CompletableFuture<SudokuProgress> save(SudokuProgress expected, int index, int digit, String sessionId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                SudokuProgress current = read();
                if (current == null || !current.attemptId.equals(expected.attemptId)
                        || current.revision != expected.revision) {
                    throw new IllegalStateException("Stale Sudoku progress");
                }
                SudokuProgress next = current.edit(index, digit, sessionId);
                write(next);
                return next;
            } catch (IOException | JSONException failure) {
                throw new CompletionException(failure);
            }
        }, IO);
    }

    /** @param sessionId 已结束的拦截身份 @return 完成局退出后标记为可换题，未完成局始终保留 */
    public CompletableFuture<Void> finishSession(String sessionId) {
        return CompletableFuture.runAsync(() -> {
            try {
                SudokuProgress current = read();
                if (current != null && current.completedSession.equals(sessionId)) {
                    write(new SudokuProgress(current.attemptId, current.revision + 1, current.board,
                            "finished:" + sessionId, current.difficulty, current.entryId));
                }
            } catch (IOException | JSONException failure) {
                throw new CompletionException(failure);
            }
        }, IO);
    }

    /** @return 完整当前记录；首次打开返回空，损坏记录不静默清空 */
    private SudokuProgress read() throws IOException, JSONException {
        if (file == null) return preview;
        if (!file.getBaseFile().exists() && !new File(file.getBaseFile().getPath() + ".bak").exists()) return null;
        JSONObject row = new JSONObject(new String(file.readFully(), StandardCharsets.UTF_8));
        return new SudokuProgress(row.getString("attempt"), row.getLong("revision"),
                new SudokuBoard(row.getString("clues"), row.getString("values")), row.getString("completed_session"),
                SudokuDifficulty.fromName(row.optString("difficulty", SudokuDifficulty.EASY.name())),
                row.optString("entry_id", ""));
    }

    /** @param progress 候选快照，写入失败保留之前的完整文件 */
    private void write(SudokuProgress progress) throws IOException, JSONException {
        if (file == null) {
            preview = progress;
            return;
        }
        JSONObject row = new JSONObject().put("attempt", progress.attemptId).put("revision", progress.revision)
                .put("clues", progress.board.clues()).put("values", progress.board.values())
                .put("completed_session", progress.completedSession).put("difficulty", progress.difficulty.name())
                .put("entry_id", progress.entryId);
        byte[] encoded = row.toString().getBytes(StandardCharsets.UTF_8);
        FileOutputStream stream = file.startWrite();
        try {
            stream.write(encoded);
            // 显式同步并传播失败；不把未落盘的完成态交给页面。
            stream.getFD().sync();
            file.finishWrite(stream);
        } catch (IOException | RuntimeException failure) {
            file.failWrite(stream);
            throw failure;
        }
        if (!java.util.Arrays.equals(encoded, file.readFully())) throw new IOException("Sudoku commit failed");
    }
}
