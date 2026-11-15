package github.vhoom.zhijian.ui.interception.page;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import github.vhoom.zhijian.R;
import github.vhoom.zhijian.data.model.interception.InterceptionEvent;
import github.vhoom.zhijian.data.store.ZhijianStorage;
import github.vhoom.zhijian.data.store.interception.WordProgressStore;
import github.vhoom.zhijian.domain.interception.word.WordProgress;
import github.vhoom.zhijian.domain.interception.word.WordQuizSession;
import github.vhoom.zhijian.ui.interception.copy.InterceptionCopyCatalog.Page;
import github.vhoom.zhijian.ui.interception.copy.InterceptionCopyCatalog.Pool;
import github.vhoom.zhijian.ui.interception.copy.InterceptionCopyCatalog.State;
import github.vhoom.zhijian.ui.interception.page.support.ChallengeCompletionAction;
import github.vhoom.zhijian.ui.interception.page.support.WordOptionsPresentation;
import github.vhoom.zhijian.ui.interception.session.PageSession;
import github.vhoom.zhijian.util.time.BootIdentity;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 单词挑战页；正式答案保存成功后才展示进度与完成事件，预览继续使用隔离的内存状态。
 *
 * 维护日期：2026年11月14日
 */
public final class WordPage extends BaseInterceptionPage {
    private static final String STATE_INDEX = "interception.state.word_index";
    private static final String STATE_UNLOCK = "interception.state.word_unlock_at";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable wordUnlockTick = this::updateWordUnlock;
    private WordQuizSession quiz;
    private final WordProgressStore store;
    private WordProgress committed;
    private boolean visible;
    private boolean pending;
    private boolean failed;
    private boolean completed;
    private boolean questionBound;
    private final TextView title, prompt, status, feedback;
    private final ProgressBar progress;
    private final Button[] choices;
    private final LinearLayout actions;
    private final Button exit;
    private final Button retry;
    private final WordOptionsPresentation visuals;
    private final ChallengeCompletionAction completionAction;

    /**
     * @param root 页面根
     * @param session 只读配置
     * @param events 事件出口
     * @param state 同会话重建状态
     */
    public WordPage(View root, PageSession session, Consumer<InterceptionEvent> events, Bundle state) {
        super(root, session, events);
        completed = session.stage.isCompletedChallenge();
        title = findViewById(R.id.interception_word_title);
        prompt = findViewById(R.id.interception_word_prompt);
        prompt.setMovementMethod(android.text.method.ScrollingMovementMethod.getInstance());
        status = findViewById(R.id.interception_word_status);
        feedback = findViewById(R.id.interception_word_feedback);
        progress = findViewById(R.id.interception_word_progress);
        choices = new Button[]{findViewById(R.id.interception_word_choice_1), findViewById(R.id.interception_word_choice_2),
                findViewById(R.id.interception_word_choice_3), findViewById(R.id.interception_word_choice_4),
                findViewById(R.id.interception_word_choice_5), findViewById(R.id.interception_word_choice_6)};
        actions = findViewById(R.id.interception_word_actions);
        exit = findViewById(R.id.interception_word_exit);
        retry = findViewById(R.id.interception_word_retry);
        retry.setOnClickListener(view -> load());
        store = session.persistentProgress ? new WordProgressStore(context) : null;
        visuals = new WordOptionsPresentation(root, choices);
        completionAction = new ChallengeCompletionAction(actions, appearance, R.id.interception_word_continue,
                () -> dispatch(InterceptionEvent.CONTINUE));
        exit.setOnClickListener(v -> dispatch(InterceptionEvent.ABANDON));
        if (store == null) restorePreview(state);
        else {
            renderCopy();
            visuals.showAvailability(true, 0);
        }
    }

    /** @param state 预览的页面快照；不读写正式挑战文件 */
    private void restorePreview(Bundle state) {
        long now = SystemClock.elapsedRealtime();
        WordQuizSession.State saved = null;
        if (completed) {
            // 完成阶段只重现最后一题的选项，答题权限始终由 completed 禁止。
            saved = new WordQuizSession.State(session.settings.word.questionCount - 1, now);
        } else if (state != null && state.containsKey(STATE_INDEX) && state.containsKey(STATE_UNLOCK)) {
            saved = new WordQuizSession.State(state.getInt(STATE_INDEX), state.getLong(STATE_UNLOCK));
        }
        quiz = saved == null
                ? WordQuizSession.start(session.wordCatalog, session.settings.word, session.sessionId, now)
                : WordQuizSession.restore(session.wordCatalog, session.settings.word, session.sessionId, saved, now);
    }

    /** 离开前台停止刷新；绝对解锁时刻保留在答题会话。 */
    @Override public void pause() {
        visible = false;
        main.removeCallbacks(wordUnlockTick);
        visuals.pause();
        completionAction.finishAnimation();
    }

    /**
     * @param state 重建容器，完成阶段由 Activity 单独保存
     */
    @Override public void save(Bundle state) {
        if (completed || quiz == null || store != null) return;
        WordQuizSession.State snapshot = quiz.snapshot();
        state.putInt(STATE_INDEX, snapshot.questionIndex);
        state.putLong(STATE_UNLOCK, snapshot.unlockAtElapsedMs);
    }

    /** 选项完整展示；完成后在原题目位置显示结果并保留六个选项。 */
    @Override public void render() {
        visible = true;
        retry.setVisibility(failed ? View.VISIBLE : View.GONE);
        if (quiz == null) {
            if (store != null && !pending && !failed) load();
            return;
        }
        if (pending) return;
        if (!completed && (quiz.isComplete() || committed != null && committed.isComplete())) {
            dispatch(InterceptionEvent.CHALLENGE_COMPLETED);
            return;
        }
        if (!questionBound) {
            renderCopy();
            visuals.enter();
            bindQuestion();
        }
        if (completed) {
            renderCompleted(false);
            return;
        }
        updateWordUnlock();
    }

    /** 在已经提交的完成阶段更新原题目，保留页面、选项和退出操作。 */
    @Override public boolean completeChallenge() {
        if (!completed) {
            completed = true;
            renderCompleted(true);
        }
        return true;
    }

    /**
     * 将题目文字改为完成提示，保留并锁定最后一题的选项与满格进度。
     * @param animate 是否浮出本次新增的继续操作
     */
    private void renderCompleted(boolean animate) {
        main.removeCallbacks(wordUnlockTick);
        prompt.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        prompt.setText(R.string.interception_word_completed);
        prompt.setContentDescription(prompt.getText());
        prompt.scrollTo(0, 0);
        int count = committed == null ? session.settings.word.questionCount : committed.settings.questionCount;
        status.setText(context.getString(R.string.interception_word_number, count, count));
        feedback.setText("");
        visuals.pause();
        visuals.showAvailability(true, progress.getMax());
        progress.setContentDescription(context.getString(R.string.interception_word_wait_progress, 100));
        completionAction.show(animate);
    }

    /** 读取单词主句和唯一退出按钮的动态文案。 */
    private void renderCopy() {
        appearance.bindScenario(Page.WORD, State.IDLE, title, actions, exit, null);
    }

    /** 将当前题绑定到两列三行；按翻译方向填入固定题干模板。 */
    private void bindQuestion() {
        WordQuizSession.Question question = quiz.currentQuestion();
        int promptResource = question.direction == WordQuizSession.Direction.ENGLISH_TO_CHINESE
                ? R.string.interception_word_prompt_en_zh : R.string.interception_word_prompt_zh_en;
        prompt.setText(context.getString(promptResource, question.prompt));
        feedback.setText("");
        for (int index = 0; index < choices.length; index++) {
            WordQuizSession.Option option = question.options.get(index);
            Button choice = choices[index];
            choice.setText(visuals.formatOption(option.label));
            choice.setTag(option.id);
            choice.setOnClickListener(this::onChoice);
        }
        questionBound = true;
    }

    /**
     * @param view 被点击的选项；领域层再次校验最低停留时钟
     */
    private void onChoice(View view) {
        if (completed || pending || quiz == null) return;
        if (store != null) {
            await(store.answer(committed, String.valueOf(view.getTag()), session.sessionId, SystemClock.elapsedRealtime()));
            return;
        }
        switch (quiz.answer(String.valueOf(view.getTag()), SystemClock.elapsedRealtime())) {
            case WRONG:
                feedback.setText(appearance.pickText(Page.WORD, Pool.WORD_WRONG_FEEDBACK, "word-wrong"));
                break;
            case NEXT:
                bindQuestion();
                updateWordUnlock();
                break;
            case COMPLETE:
                main.removeCallbacks(wordUnlockTick);
                dispatch(InterceptionEvent.CHALLENGE_COMPLETED);
                break;
            default:
                break;
        }
    }

    /** 题量与停留进度分开显示；不通过颜色或透明度表示锁定。 */
    private void updateWordUnlock() {
        if (completed || pending || quiz == null || quiz.isComplete()) return;
        main.removeCallbacks(wordUnlockTick);
        long remaining = quiz.remainingLockMs(SystemClock.elapsedRealtime());
        long duration = quiz.dwellDurationMs();
        int value = (int) Math.min(1000L, Math.max(0L, (duration - remaining) * 1000L / duration));
        WordQuizSession.Question question = quiz.currentQuestion();
        status.setText(context.getString(R.string.interception_word_number, question.number, question.total));
        prompt.setContentDescription(status.getText() + "，" + prompt.getText());
        progress.setContentDescription(context.getString(R.string.interception_word_wait_progress, value / 10));
        visuals.showAvailability(remaining > 0, value);
        if (remaining > 0) main.postDelayed(wordUnlockTick, Math.min(50L, remaining));
    }

    /** 首次加载或失败重试沿用同次进入标识，避免重复加载意外换题。 */
    private void load() {
        if (store == null || pending) return;
        await(store.open(session.sessionId, session.challengeEntryId, session.settings.word,
                BootIdentity.read(context), SystemClock.elapsedRealtime())
                .thenApply(result -> new WordProgressStore.Update(result, null)));
    }

    /** @param operation 后台读取或答题提交；只发布已持久化结果，离开前台不取消保存也不导航 */
    private void await(CompletableFuture<WordProgressStore.Update> operation) {
        pending = true;
        failed = false;
        retry.setVisibility(View.GONE);
        main.removeCallbacks(wordUnlockTick);
        visuals.showAvailability(true, progress.getProgress());
        operation.whenComplete((result, failure) -> main.post(() -> {
            pending = false;
            failed = failure != null;
            if (failure == null) {
                committed = result.progress;
                completed = session.stage.isCompletedChallenge() && committed.isComplete();
                quiz = committed.quiz(ZhijianStorage.get(context).wordCatalog(committed.settings.catalog));
                questionBound = false;
            }
            if (!visible) return;
            render();
            if (failure != null) {
                Toast.makeText(context, R.string.interception_word_progress_failed, Toast.LENGTH_LONG).show();
            } else if (result.answer == WordQuizSession.AnswerResult.WRONG) {
                feedback.setText(appearance.pickText(Page.WORD, Pool.WORD_WRONG_FEEDBACK, "word-wrong"));
            }
        }));
    }
}
