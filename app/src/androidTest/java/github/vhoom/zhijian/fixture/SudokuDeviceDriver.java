package github.vhoom.zhijian.fixture;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.os.SystemClock;
import android.util.AtomicFile;
import android.widget.GridLayout;
import android.widget.TextView;

import github.vhoom.zhijian.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import static org.junit.Assert.*;

/**
 * 正式页面数独操作夹具；保存原文件，按屏幕题面独立求解，再通过格子与数字键完成。
 *
 * 维护日期：2026年11月17日
 */
public final class SudokuDeviceDriver implements AutoCloseable {
    private final AtomicFile file;
    private final byte[] original;

    /** @param context 真实应用上下文；调用方应先停止服务，在关闭设备会话后恢复本夹具 */
    public SudokuDeviceDriver(Context context) throws IOException {
        file = new AtomicFile(new File(context.getFilesDir(), "sudoku_progress.json"));
        original = file.getBaseFile().exists() ? file.readFully() : null;
        file.delete();
    }

    /** 恢复原始字节，不把测试完成记录留给用户。 */
    @Override public void close() throws IOException {
        file.delete();
        if (original != null) {
            FileOutputStream stream = file.startWrite();
            try {
                stream.write(original);
                stream.getFD().sync();
                file.finishWrite(stream);
            } catch (IOException failure) {
                file.failWrite(stream);
                throw failure;
            }
        }
    }

    /** @param instrumentation 主线程操作入口 @param page 已打开的正式数独页面 */
    public static void complete(Instrumentation instrumentation, Activity page) {
        GridLayout[] grid = new GridLayout[1];
        await(instrumentation, () -> {
            grid[0] = page.findViewById(R.id.interception_sudoku_grid);
            if (grid[0] == null || !grid[0].isShown() || grid[0].getChildCount() != 81) return false;
            // 给定格始终不可编辑；任意可填格就绪才表示题面加载完成。
            for (int index = 0; index < grid[0].getChildCount(); index++) {
                if (grid[0].getChildAt(index).isEnabled()) return true;
            }
            return false;
        });
        char[] board = new char[81];
        instrumentation.runOnMainSync(() -> {
            for (int index = 0; index < board.length; index++) {
                String digit = ((TextView) grid[0].getChildAt(index)).getText().toString();
                board[index] = digit.isEmpty() ? '0' : digit.charAt(0);
            }
        });
        char[] clues = board.clone();
        assertTrue("Visible Sudoku must have a solution", solve(board, 0));
        for (int index = 0; index < board.length; index++) {
            if (clues[index] != '0') continue;
            int target = index;
            await(instrumentation, () -> grid[0].getChildAt(target).isEnabled());
            instrumentation.runOnMainSync(() -> {
                grid[0].getChildAt(target).performClick();
                GridLayout keys = page.findViewById(R.id.interception_sudoku_keys);
                keys.getChildAt(board[target] - '1').performClick();
            });
            await(instrumentation, () -> ((TextView) grid[0].getChildAt(target)).getText().toString()
                    .equals(String.valueOf(board[target])));
        }
    }

    /** @param instrumentation 主线程入口 @param condition 仅在主线程读取控件的等待条件 */
    private static void await(Instrumentation instrumentation, java.util.function.BooleanSupplier condition) {
        long deadline = SystemClock.elapsedRealtime() + 5_000L;
        boolean[] ready = {false};
        while (!ready[0] && SystemClock.elapsedRealtime() < deadline) {
            instrumentation.runOnMainSync(() -> ready[0] = condition.getAsBoolean());
            if (!ready[0]) SystemClock.sleep(20L);
        }
        assertTrue("Sudoku control did not become ready", ready[0]);
    }

    /** @param board 从页面读取的独立数组 @param start 下一搜索位置 @return 是否求得完整解 */
    private static boolean solve(char[] board, int start) {
        int index = start;
        while (index < 81 && board[index] != '0') index++;
        if (index == 81) return true;
        for (char digit = '1'; digit <= '9'; digit++) {
            boolean valid = true;
            for (int offset = 0; offset < 9; offset++) {
                if (board[index / 9 * 9 + offset] == digit || board[index % 9 + offset * 9] == digit
                        || board[index / 27 * 27 + index % 9 / 3 * 3 + offset / 3 * 9 + offset % 3] == digit) valid = false;
            }
            if (valid) {
                board[index] = digit;
                if (solve(board, index + 1)) return true;
            }
        }
        board[index] = '0';
        return false;
    }
}
