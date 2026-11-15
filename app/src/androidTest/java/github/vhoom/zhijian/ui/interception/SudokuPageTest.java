package github.vhoom.zhijian.ui.interception;

import android.content.Context;
import android.os.SystemClock;
import android.view.View;
import android.widget.GridLayout;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.lifecycle.Lifecycle;
import androidx.test.platform.app.InstrumentationRegistry;

import github.vhoom.zhijian.R;
import github.vhoom.zhijian.data.model.interception.InterceptionMode;
import github.vhoom.zhijian.data.model.interception.InterceptionStage;
import github.vhoom.zhijian.data.model.policy.ControlLevel;
import github.vhoom.zhijian.data.model.settings.SudokuDifficulty;
import github.vhoom.zhijian.data.store.settings.InteractionSettingsStore;
import github.vhoom.zhijian.domain.interception.InterceptionDecision;
import github.vhoom.zhijian.fixture.SudokuDeviceDriver;
import github.vhoom.zhijian.util.config.AppConfig;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * 数独真实控件、页面重建与完成路由；使用隔离预览，不改写正式棋盘。
 *
 * 维护日期：2026年11月14日
 */
public class SudokuPageTest {
    /** 九键填写、同数字清空及替换可重建恢复；给定格不能夺取选择，换格后按当前格判断输入。 */
    @Test public void editingClearingAndReplacingSurvivePageRecreation() {
        try (ActivityScenario<InterceptionActivity> scenario = launch(InterceptionStage.SUDOKU, "editing")) {
            await(scenario, R.id.interception_sudoku_grid);
            int[] editable = {-1};
            int[] anotherEditable = {-1};
            int[] given = {-1};
            scenario.onActivity(activity -> {
                assertPanelFits(activity);
                assertEquals(0, activity.getResources().getIdentifier(
                        "interception_sudoku_title", "id", activity.getPackageName()));
                assertEquals(View.GONE, activity.findViewById(R.id.interception_sudoku_completion_message).getVisibility());
                GridLayout grid = activity.findViewById(R.id.interception_sudoku_grid);
                GridLayout keys = activity.findViewById(R.id.interception_sudoku_keys);
                assertEquals(9, keys.getChildCount());
                assertEquals(3, keys.getColumnCount());
                assertEquals(3, keys.getRowCount());
                for (int index = 0; index < grid.getChildCount(); index++) {
                    TextView cell = (TextView) grid.getChildAt(index);
                    if (cell.getText().length() == 0) {
                        if (anotherEditable[0] < 0) anotherEditable[0] = index;
                        editable[0] = index;
                    } else {
                        given[0] = index;
                        assertFalse(cell.isEnabled());
                        assertFalse(cell.isClickable());
                        assertFalse(cell.isFocusable());
                        assertFalse(cell.requestFocus());
                        cell.performClick();
                        assertFalse(cell.isSelected());
                        assertFalse(keys.getChildAt(0).isEnabled());
                    }
                }
                assertTrue(editable[0] >= 0);
                assertTrue(given[0] >= 0);
                assertNotEquals(editable[0], anotherEditable[0]);
                grid.getChildAt(editable[0]).performClick();
                grid.getChildAt(given[0]).performClick();
                assertTrue(grid.getChildAt(editable[0]).isSelected());
                assertFalse(grid.getChildAt(given[0]).isSelected());
                assertTrue(keys.getChildAt(0).isEnabled());
                keys.getChildAt(0).performClick();
            });
            awaitDigit(scenario, editable[0], "1");
            scenario.recreate();
            awaitDigit(scenario, editable[0], "1");
            scenario.onActivity(activity -> {
                GridLayout keys = activity.findViewById(R.id.interception_sudoku_keys);
                assertEquals(activity.getString(R.string.interception_sudoku_clear_digit, 1),
                        keys.getChildAt(0).getContentDescription().toString());
                keys.getChildAt(0).performClick();
            });
            awaitDigit(scenario, editable[0], "");
            scenario.recreate();
            awaitDigit(scenario, editable[0], "");
            scenario.onActivity(activity -> {
                assertPanelFits(activity);
                ((GridLayout) activity.findViewById(R.id.interception_sudoku_keys)).getChildAt(1).performClick();
            });
            awaitDigit(scenario, editable[0], "2");
            scenario.onActivity(activity ->
                    ((GridLayout) activity.findViewById(R.id.interception_sudoku_keys)).getChildAt(2).performClick());
            awaitDigit(scenario, editable[0], "3");
            scenario.recreate();
            awaitDigit(scenario, editable[0], "3");
            scenario.onActivity(activity -> {
                GridLayout grid = activity.findViewById(R.id.interception_sudoku_grid);
                grid.getChildAt(anotherEditable[0]).performClick();
                ((GridLayout) activity.findViewById(R.id.interception_sudoku_keys)).getChildAt(2).performClick();
            });
            awaitDigit(scenario, anotherEditable[0], "3");
            awaitDigit(scenario, editable[0], "3");
        }
    }

    /** 完成态重建保留终盘和本局语句，窄屏与宽矮空间完整显示终盘、语句及按钮。 */
    @Test public void completedPreviewRestoresWithoutCreatingAnotherPuzzle() {
        try (ActivityScenario<InterceptionActivity> scenario = launch(InterceptionStage.SUDOKU_COMPLETED, "completed")) {
            await(scenario, R.id.interception_sudoku_continue);
            String[] original = new String[2];
            scenario.onActivity(activity -> {
                original[0] = boardText(activity);
                original[1] = completionText(activity);
                assertCompletionFits(activity);
                View root = activity.findViewById(R.id.interception_page_sudoku);
                float density = activity.getResources().getDisplayMetrics().density;
                for (int[] size : new int[][]{{320, 480}, {640, 300}}) {
                    int width = Math.round(size[0] * density);
                    int height = Math.round(size[1] * density);
                    root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                    root.layout(0, 0, width, height);
                    assertCompletionFits(activity);
                }
            });
            scenario.recreate();
            await(scenario, R.id.interception_sudoku_continue);
            scenario.onActivity(activity -> {
                assertEquals(View.INVISIBLE, activity.findViewById(R.id.interception_sudoku_keys).getVisibility());
                assertEquals(original[0], boardText(activity));
                assertEquals(original[1], completionText(activity));
                assertCompletionFits(activity);
                GridLayout grid = activity.findViewById(R.id.interception_sudoku_grid);
                for (int index = 0; index < grid.getChildCount(); index++) {
                    assertFalse(grid.getChildAt(index).isEnabled());
                }
            });
        }
    }

    /** 终盘上移后才开放继续，动画中离开前台可恢复；两种反问配置均等待用户主动点击。 */
    @Test public void solvingUsesConfiguredConfirmationRoute() {
        Context context = context();
        InteractionSettingsStore settings = new InteractionSettingsStore(context, new AppConfig(context));
        boolean original = settings.readOptions(InterceptionMode.REPAIR).confirmationEnabled;
        SudokuDifficulty originalDifficulty = settings.sudokuDifficulty();
        settings.setSudokuDifficulty(SudokuDifficulty.EASY);
        try {
            for (boolean confirmation : new boolean[]{false, true}) {
                settings.setConfirmationEnabled(confirmation);
                try (ActivityScenario<InterceptionActivity> scenario = launch(InterceptionStage.SUDOKU, "solve-" + confirmation)) {
                    await(scenario, R.id.interception_sudoku_grid);
                    InterceptionActivity[] page = new InterceptionActivity[1];
                    float[] originalTop = new float[1];
                    scenario.onActivity(activity -> {
                        page[0] = activity;
                        originalTop[0] = activity.findViewById(R.id.interception_sudoku_grid).getY();
                        assertNull(activity.findViewById(R.id.interception_sudoku_continue));
                    });
                    SudokuDeviceDriver.complete(InstrumentationRegistry.getInstrumentation(), page[0]);
                    if (confirmation) {
                        scenario.moveToState(Lifecycle.State.CREATED);
                        scenario.moveToState(Lifecycle.State.RESUMED);
                    }
                    awaitCondition(scenario, activity -> {
                        View next = activity.findViewById(R.id.interception_sudoku_continue);
                        if (next != null && next.isShown() && next.isEnabled()) {
                            assertEquals(View.INVISIBLE, activity.findViewById(R.id.interception_sudoku_keys).getVisibility());
                            assertEquals(1f, activity.findViewById(R.id.interception_sudoku_completion_message).getAlpha(), 0f);
                        }
                        return next != null && next.isShown() && next.isEnabled();
                    });
                    scenario.onActivity(activity -> {
                        assertEquals(View.INVISIBLE, activity.findViewById(R.id.interception_sudoku_keys).getVisibility());
                        float completedTop = activity.findViewById(R.id.interception_sudoku_grid).getY();
                        assertTrue(completedTop <= originalTop[0] + 1f);
                        if (originalTop[0] > 1f) assertTrue(completedTop < originalTop[0]);
                        assertCompletionFits(activity);
                        assertNull(activity.findViewById(R.id.interception_confirmation_continue));
                        if (confirmation) activity.findViewById(R.id.interception_sudoku_continue).performClick();
                    });
                    int result = confirmation ? R.id.interception_confirmation_continue : R.id.interception_sudoku_continue;
                    await(scenario, result);
                    scenario.recreate();
                    await(scenario, result);
                }
            }
        } finally {
            settings.setConfirmationEnabled(original);
            settings.setSudokuDifficulty(originalDifficulty);
        }
    }

    /** @param stage 预览阶段 @param seed 隔离会话种子 @return 已启动的真实 Activity */
    private static ActivityScenario<InterceptionActivity> launch(InterceptionStage stage, String seed) {
        return ActivityScenario.launch(InterceptionActivity.debugIntent(context(), InterceptionDecision.Kind.BLOCK,
                ControlLevel.REPAIR, stage, "sudoku-test-" + seed));
    }

    /** @return 已等待应用异步初始化的上下文 */
    private static Context context() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ((github.vhoom.zhijian.ZhijianApplication) context.getApplicationContext()).ready().join();
        return context;
    }

    /** @param scenario 运行页面 @param id 等待可见的控件 */
    private static void await(ActivityScenario<InterceptionActivity> scenario, int id) {
        awaitCondition(scenario, activity -> {
            View view = activity.findViewById(id);
            return view != null && view.isShown() && view.getWidth() > 0 && view.getHeight() > 0;
        });
    }

    /** @param activity 数独页面 @return 屏幕上的完整终盘，用于验证重建未换题 */
    private static String boardText(InterceptionActivity activity) {
        GridLayout grid = activity.findViewById(R.id.interception_sudoku_grid);
        StringBuilder digits = new StringBuilder();
        for (int index = 0; index < grid.getChildCount(); index++) {
            digits.append(((TextView) grid.getChildAt(index)).getText());
        }
        return digits.toString();
    }

    /** @param activity 已完成的数独页面 @return 当前局实际显示的完成语句 */
    private static String completionText(InterceptionActivity activity) {
        return ((TextView) activity.findViewById(R.id.interception_sudoku_completion_message)).getText().toString();
    }

    /** @param activity 已布局的完成页面，检查文案位于终盘下方、按钮上方且来自独立文案库 */
    private static void assertCompletionFits(InterceptionActivity activity) {
        assertPanelFits(activity);
        View board = activity.findViewById(R.id.interception_sudoku_grid);
        TextView message = activity.findViewById(R.id.interception_sudoku_completion_message);
        View panel = activity.findViewById(R.id.interception_sudoku_panel);
        View actions = activity.findViewById(R.id.interception_sudoku_actions);
        assertTrue(message.isShown());
        assertEquals(1f, message.getAlpha(), 0f);
        assertTrue(message.getY() >= board.getY() + board.getHeight() * board.getScaleY());
        assertTrue(panel.getY() + message.getY() + message.getHeight() * message.getScaleY() <= actions.getY() + 1f);
        assertTrue(java.util.Arrays.asList(activity.getResources().getStringArray(R.array.interception_sudoku_completions))
                .contains(message.getText().toString()));
    }

    /** @param activity 已布局页面，检查实际绘制边界而非复制缩放公式 */
    private static void assertPanelFits(InterceptionActivity activity) {
        android.view.ViewGroup panel = activity.findViewById(R.id.interception_sudoku_panel);
        assertTrue(panel.getWidth() > 0 && panel.getHeight() > 0);
        for (int index = 0; index < panel.getChildCount(); index++) {
            View child = panel.getChildAt(index);
            if (child.getVisibility() != View.VISIBLE) continue;
            android.graphics.RectF bounds = new android.graphics.RectF(0, 0, child.getWidth(), child.getHeight());
            child.getMatrix().mapRect(bounds);
            bounds.offset(child.getLeft(), child.getTop());
            assertTrue(bounds.width() > 0 && bounds.height() > 0);
            assertTrue(bounds.left >= -1f && bounds.top >= -1f);
            assertTrue(bounds.right <= panel.getWidth() + 1f && bounds.bottom <= panel.getHeight() + 1f);
        }
    }

    /** @param scenario 运行页面 @param index 格子编号 @param expected 已保存后的数字文字 */
    private static void awaitDigit(ActivityScenario<InterceptionActivity> scenario, int index, String expected) {
        awaitCondition(scenario, activity -> {
            GridLayout grid = activity.findViewById(R.id.interception_sudoku_grid);
            return grid != null && grid.isShown() && grid.getChildAt(index).isEnabled()
                    && expected.contentEquals(((TextView) grid.getChildAt(index)).getText());
        });
    }

    /** @param scenario 页面场景 @param condition 主线程控件条件 */
    private static void awaitCondition(ActivityScenario<InterceptionActivity> scenario,
            java.util.function.Predicate<InterceptionActivity> condition) {
        boolean[] ready = {false};
        long deadline = SystemClock.elapsedRealtime() + 5_000L;
        while (!ready[0] && SystemClock.elapsedRealtime() < deadline) {
            scenario.onActivity(activity -> ready[0] = condition.test(activity));
            if (!ready[0]) SystemClock.sleep(20L);
        }
        assertTrue("Sudoku page did not become ready", ready[0]);
    }
}
