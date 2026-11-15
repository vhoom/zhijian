package github.vhoom.zhijian.data.store.interception;

import android.content.Context;
import android.util.AtomicFile;

import github.vhoom.zhijian.data.model.settings.WordChallengeSettings;
import github.vhoom.zhijian.data.model.word.BuiltInWordCatalog;
import github.vhoom.zhijian.data.store.ZhijianStorage;
import github.vhoom.zhijian.domain.interception.word.WordEntry;
import github.vhoom.zhijian.domain.interception.word.WordProgress;
import github.vhoom.zhijian.domain.interception.word.WordQuizSession;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * 单线程原子保存全局单词挑战；未作答重进换题，开始后保留配置、题号和解锁时钟。
 *
 * 维护日期：2026年11月14日
 */
public final class WordProgressStore {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private final AtomicFile file;
    private final Function<WordChallengeSettings, List<WordEntry>> catalogs;

    /** @param context 已完成异步初始化的应用上下文，正式词库与文件均在后台访问 */
    public WordProgressStore(Context context) {
        Context app = context.getApplicationContext();
        file = new AtomicFile(new File(app.getFilesDir(), "word_progress.json"));
        catalogs = settings -> ZhijianStorage.get(app).wordCatalog(settings.catalog);
    }

    /** @param path 独立测试文件 @param entries 测试固定词库，不访问正式文件 */
    WordProgressStore(File path, List<WordEntry> entries) {
        file = new AtomicFile(path);
        catalogs = settings -> entries;
    }

    /** @return 已排队操作全部结束的屏障，供关闭后的文件备份与恢复使用 */
    public static CompletableFuture<Void> awaitPendingWrites() {
        return CompletableFuture.runAsync(() -> { }, IO);
    }

    /** @param sessionId 拦截身份 @param entryId 本次进入 @param settings 新局配置 @param bootId 当前启动 @param now 单调毫秒 @return 已落盘的恢复或新建记录 */
    public CompletableFuture<WordProgress> open(String sessionId, String entryId,
            WordChallengeSettings settings, String bootId, long now) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                WordProgress current = read();
                if (current != null && current.resumesIn(sessionId, entryId)) {
                    WordProgress restored = current.onBoot(bootId, now);
                    restored.quiz(catalogs.apply(restored.settings));
                    if (restored != current) write(restored);
                    return restored;
                }
                List<WordEntry> entries = catalogs.apply(settings);
                String previousPrompt = current == null ? null
                        : current.quiz(catalogs.apply(current.settings)).currentQuestion().prompt;
                WordProgress next;
                do {
                    next = WordProgress.start(entries, settings, UUID.randomUUID().toString(), entryId, bootId, now);
                } while (next.quiz(entries).currentQuestion().prompt.equals(previousPrompt));
                write(next);
                return next;
            } catch (IOException | JSONException failure) {
                throw new CompletionException(failure);
            }
        }, IO);
    }

    /** @param expected 页面最后收到的快照 @param optionId 选择的选项 @param sessionId 拦截身份 @param now 点击时的单调毫秒 @return 保存后才能展示的答案结果与新快照 */
    public CompletableFuture<Update> answer(WordProgress expected, String optionId, String sessionId, long now) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                WordProgress current = read();
                if (current == null || !current.attemptId.equals(expected.attemptId)
                        || current.revision != expected.revision || current.isComplete()) {
                    throw new IllegalStateException("Stale word progress");
                }
                WordQuizSession quiz = current.quiz(catalogs.apply(current.settings));
                WordQuizSession.AnswerResult answer = quiz.answer(optionId, now);
                if (answer == WordQuizSession.AnswerResult.LOCKED) return new Update(current, answer);
                WordProgress next = current.answered(quiz.snapshot(), sessionId);
                write(next);
                return new Update(next, answer);
            } catch (IOException | JSONException failure) {
                throw new CompletionException(failure);
            }
        }, IO);
    }

    /** @param sessionId 结果已保存的拦截身份 @return 只结束已完成局，未完成的有效答案始终保留 */
    public CompletableFuture<Void> finishSession(String sessionId) {
        return CompletableFuture.runAsync(() -> {
            try {
                WordProgress current = read();
                if (current != null && current.completedSession.equals(sessionId)) write(current.retired());
            } catch (IOException | JSONException failure) {
                throw new CompletionException(failure);
            }
        }, IO);
    }

    /** @return 当前完整快照；损坏时传播错误，不静默重新出题 */
    private WordProgress read() throws IOException, JSONException {
        if (!file.getBaseFile().exists() && !new File(file.getBaseFile().getPath() + ".bak").exists()) return null;
        JSONObject row = new JSONObject(new String(file.readFully(), StandardCharsets.UTF_8));
        String catalogId = row.getString("catalog");
        BuiltInWordCatalog catalog = BuiltInWordCatalog.fromId(catalogId);
        if (!catalog.id.equals(catalogId)) throw new JSONException("Unknown word catalog");
        return new WordProgress(row.getString("attempt"), row.getString("entry_id"), row.getLong("revision"),
                new WordChallengeSettings(row.getInt("count"), row.getLong("dwell_ms"), catalog),
                new WordQuizSession.State(row.getInt("question_index"), row.getLong("unlock_at_elapsed_ms")),
                row.getString("boot_id"), row.getBoolean("started"), row.getString("completed_session"));
    }

    /** @param progress 候选记录；原子写入、同步和回读成功后才发布 */
    private void write(WordProgress progress) throws IOException, JSONException {
        JSONObject row = new JSONObject().put("attempt", progress.attemptId).put("entry_id", progress.entryId)
                .put("revision", progress.revision).put("count", progress.settings.questionCount)
                .put("dwell_ms", progress.settings.minDwellMillis).put("catalog", progress.settings.catalog.id)
                .put("question_index", progress.state.questionIndex).put("unlock_at_elapsed_ms", progress.state.unlockAtElapsedMs)
                .put("boot_id", progress.bootId).put("started", progress.started)
                .put("completed_session", progress.completedSession);
        byte[] encoded = row.toString().getBytes(StandardCharsets.UTF_8);
        FileOutputStream stream = file.startWrite();
        try {
            stream.write(encoded);
            stream.getFD().sync();
            file.finishWrite(stream);
        } catch (IOException | RuntimeException failure) {
            file.failWrite(stream);
            throw failure;
        }
        if (!java.util.Arrays.equals(encoded, file.readFully())) throw new IOException("Word progress commit failed");
    }

    /** 保存已确认的快照与本次反馈；只读页面恢复没有答题结果。 */
    public static final class Update {
        public final WordProgress progress;
        public final WordQuizSession.AnswerResult answer;

        /** @param progress 已提交记录 @param answer 答题结果，读取时为空 */
        public Update(WordProgress progress, WordQuizSession.AnswerResult answer) {
            this.progress = progress;
            this.answer = answer;
        }
    }
}
