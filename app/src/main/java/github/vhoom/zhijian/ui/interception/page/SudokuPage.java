package github.vhoom.zhijian.ui.interception.page;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import github.vhoom.zhijian.R;
import github.vhoom.zhijian.data.model.interception.InterceptionEvent;
import github.vhoom.zhijian.data.store.interception.SudokuProgressStore;
import github.vhoom.zhijian.domain.interception.sudoku.SudokuBoard;
import github.vhoom.zhijian.domain.interception.sudoku.SudokuProgress;
import github.vhoom.zhijian.domain.interception.sudoku.SudokuPuzzles;
import github.vhoom.zhijian.ui.interception.copy.InterceptionCopyCatalog.Pool;
import github.vhoom.zhijian.ui.interception.page.support.ChallengeCompletionAction;
import github.vhoom.zhijian.ui.interception.page.support.SudokuGridView;
import github.vhoom.zhijian.ui.interception.page.support.SudokuPanelLayout;
import github.vhoom.zhijian.ui.interception.page.support.SudokuCompletionPresentation;
import github.vhoom.zhijian.ui.interception.session.PageSession;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 数独页只接收已提交快照；每次编辑在后台落盘后生效，完成后仍由原状态机决定去向。
 *
 * 维护日期：2026年11月14日
 */
public final class SudokuPage extends BaseInterceptionPage {
    private static final String STATE_SELECTED = "sudoku.selected";
    private static final String STATE_ATTEMPT = "sudoku.attempt";
    private static final String STATE_VALUES = "sudoku.preview.values";
    private static final String STATE_CLUES = "sudoku.preview.clues";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SudokuProgressStore store;
    private final SudokuGridView grid;
    private final GridLayout keys;
    private final Button retry;
    private final TextView completionMessage;
    private final SudokuCompletionPresentation completion;
    private SudokuProgress progress;
    private boolean visible;
    private boolean pending;
    private boolean completed;
    private boolean failed;
    private int selected = -1;
    private String restoredAttempt;

    /** @param root 页面根 @param session 不可变配置 @param events 状态机事件出口 @param state 页面重建快照 */
    public SudokuPage(View root, PageSession session, Consumer<InterceptionEvent> events, Bundle state) {
        super(root, session, events);
        grid = findViewById(R.id.interception_sudoku_grid);
        grid.setVisibility(View.INVISIBLE);
        keys = findViewById(R.id.interception_sudoku_keys);
        retry = findViewById(R.id.interception_sudoku_retry);
        if (state != null) selected = state.getInt(STATE_SELECTED, -1);
        restoredAttempt = state == null ? null : state.getString(STATE_ATTEMPT);
        if (selected < -1 || selected >= SudokuBoard.CELL_COUNT) selected = -1;
        store = session.persistentProgress ? new SudokuProgressStore(context)
                : SudokuProgressStore.preview(previewProgress(state));
        LinearLayout actions = findViewById(R.id.interception_sudoku_actions);
        Button exit = findViewById(R.id.interception_sudoku_exit);
        appearance.bindChallengeExit(actions, exit, "SUDOKU");
        exit.setOnClickListener(view -> dispatch(InterceptionEvent.ABANDON));
        ChallengeCompletionAction action = new ChallengeCompletionAction(actions, appearance, R.id.interception_sudoku_continue,
                () -> dispatch(InterceptionEvent.CONTINUE));
        if (session.settings.options.confirmationEnabled) action.setLabel(R.string.interception_sudoku_next);
        completionMessage = findViewById(R.id.interception_sudoku_completion_message);
        completion = new SudokuCompletionPresentation(findViewById(R.id.interception_sudoku_panel),
                keys, completionMessage, action);
        retry.setOnClickListener(view -> load());
        grid.setOnSelect(index -> {
            if (progress == null || pending || completed || progress.board.isGiven(index)) return;
            selected = index;
            update();
        });
        createKeys();
    }

    /** @param state 只用于预览恢复的内存快照 @return 与正式文件隔离的固定题面 */
    private SudokuProgress previewProgress(Bundle state) {
        SudokuBoard board = SudokuPuzzles.create(session.sessionId, session.settings.sudokuDifficulty);
        String saved = state == null ? null : state.getString(STATE_VALUES);
        String clues = state == null ? null : state.getString(STATE_CLUES);
        if (saved != null && clues != null) board = new SudokuBoard(clues, saved);
        if (session.stage.isCompletedChallenge() && !board.isComplete()) {
            // 完成态预览使用一张确定的合法终盘，不影响正式题目。
            String solution = "534678912672195348198342567859761423426853791713924856961537284287419635345286179";
            board = new SudokuBoard(solution, solution);
        }
        return new SudokuProgress("preview:" + session.sessionId, 0, board, board.isComplete() ? session.sessionId : "",
                session.settings.sudokuDifficulty);
    }

    /** 构建三行三列的数字键盘，同数字再次点击清空，其他数字替换，不提供独立清除键。 */
    private void createKeys() {
        for (int digit = 1; digit <= 9; digit++) {
            Button key = new Button(context);
            key.setText(String.valueOf(digit));
            key.setContentDescription(context.getString(R.string.interception_sudoku_input, digit));
            key.setTag(digit);
            appearance.styleActionButton(key, false);
            key.setTextAppearance(R.style.TextAppearance_App_SudokuKey);
            key.setIncludeFontPadding(false);
            key.setMinWidth(0);
            key.setMinimumWidth(0);
            int padding = context.getResources().getDimensionPixelSize(R.dimen.space_xxs);
            key.setPadding(padding, padding, padding, padding);
            key.setMinHeight(context.getResources().getDimensionPixelSize(R.dimen.control_height));
            GridLayout.LayoutParams params = new GridLayout.LayoutParams(
                    GridLayout.spec((digit - 1) / 3), GridLayout.spec((digit - 1) % 3, 1f));
            params.width = 0;
            params.height = GridLayout.LayoutParams.WRAP_CONTENT;
            int gap = context.getResources().getDimensionPixelSize(R.dimen.space_xxs);
            params.setMargins(gap, gap, gap, gap);
            key.setLayoutParams(params);
            int value = digit;
            key.setOnClickListener(view -> edit(value));
            keys.addView(key, digit - 1);
        }
    }

    /** 首次异步加载；从后台返回时沿用已完成的磁盘操作结果。 */
    @Override public void render() {
        visible = true;
        if (progress == null && !pending) load();
        else update();
    }

    /** 只停止展示回调，不取消已经排队的持久化操作。 */
    @Override public void pause() {
        visible = false;
        completion.pause();
    }

    /** 操作棋盘或数字键期间，外壳不得执行 Debug 翻页。 */
    @Override public boolean isHandlingHorizontalGesture() {
        SudokuPanelLayout panel = findViewById(R.id.interception_sudoku_panel);
        return panel.isTouching();
    }

    /** @param state 正式棋盘由磁盘恢复，Bundle 只保存选择与预览内容 */
    @Override public void save(Bundle state) {
        state.putInt(STATE_SELECTED, selected);
        if (progress != null) state.putString(STATE_ATTEMPT, progress.attemptId);
        if (!session.persistentProgress && progress != null) {
            state.putString(STATE_VALUES, progress.board.values());
            state.putString(STATE_CLUES, progress.board.clues());
        }
    }

    /** 状态机确认挑战完成后，上移终盘并显示本局完成语句及主动继续按钮。 */
    @Override public boolean completeChallenge() {
        completed = true;
        root.announceForAccessibility(context.getString(R.string.interception_sudoku_completed));
        showCompletion(true);
        return true;
    }

    /** 读取或恢复完整记录，读取失败时保留退出与重试入口。 */
    private void load() {
        await(store.open(session.sessionId, session.persistentProgress ? session.challengeEntryId : "",
                session.settings.sudokuDifficulty));
    }

    /** @param digit 点击的数字；与所选格当前数字相同则清空，否则填入或替换，保存成功后生效 */
    private void edit(int digit) {
        if (pending || progress == null || completed || selected < 0 || progress.board.isGiven(selected)) return;
        int nextDigit = progress.board.digit(selected) == digit ? 0 : digit;
        await(store.save(progress, selected, nextDigit, session.sessionId));
    }

    /** @param operation 后台加载或原子写入任务，完成事件只能在保存成功后发送 */
    private void await(CompletableFuture<SudokuProgress> operation) {
        pending = true;
        failed = false;
        update();
        operation.whenComplete((result, failure) -> main.post(() -> {
            pending = false;
            failed = failure != null;
            if (failure == null) {
                if (restoredAttempt != null && !restoredAttempt.equals(result.attemptId)) selected = -1;
                restoredAttempt = result.attemptId;
                progress = result;
                completed = session.stage.isCompletedChallenge() && result.board.isComplete();
            }
            if (!visible) return;
            update();
            if (failure != null) {
                Toast.makeText(context, R.string.interception_sudoku_save_failed, Toast.LENGTH_LONG).show();
            }
        }));
    }

    /** 将已提交棋盘映射到控件，所有冲突都由领域对象判断。 */
    private void update() {
        if (progress != null && selected >= 0 && progress.board.isGiven(selected)) selected = -1;
        boolean editable = progress != null && !pending && !progress.board.isComplete();
        boolean canInput = editable && selected >= 0 && !progress.board.isGiven(selected);
        int selectedDigit = canInput ? progress.board.digit(selected) : 0;
        for (int index = 0; index < keys.getChildCount(); index++) {
            View key = keys.getChildAt(index);
            int digit = index + 1;
            key.setEnabled(canInput);
            key.setContentDescription(context.getString(digit == selectedDigit
                    ? R.string.interception_sudoku_clear_digit : R.string.interception_sudoku_input, digit));
        }
        retry.setVisibility(failed ? View.VISIBLE : View.GONE);
        if (progress == null) return;
        SudokuBoard board = progress.board;
        grid.setVisibility(View.VISIBLE);
        grid.bind(board, selected, editable);
        if (visible && !pending && board.isComplete()) {
            if (completed) showCompletion(false);
            else dispatch(InterceptionEvent.CHALLENGE_COMPLETED);
        }
    }

    /** @param animate 新完成时播放动画，恢复终盘时直接显示本局相同的完成语句 */
    private void showCompletion(boolean animate) {
        completionMessage.setText(appearance.pickAttemptText(Pool.SUDOKU_COMPLETIONS, progress.attemptId));
        completion.show(animate);
    }
}
