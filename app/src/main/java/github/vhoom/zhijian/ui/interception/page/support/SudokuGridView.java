package github.vhoom.zhijian.ui.interception.page.support;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.GridLayout;
import android.widget.TextView;

import github.vhoom.zhijian.R;
import github.vhoom.zhijian.domain.interception.sudoku.SudokuBoard;

import java.util.function.IntConsumer;

/**
 * 可读屏的紧凑棋盘，仅可填写格支持选中与键盘聚焦；尺寸由外部面板等比缩放。
 *
 * 维护日期：2026年11月14日
 */
public final class SudokuGridView extends GridLayout {
    private final TextView[] cells = new TextView[SudokuBoard.CELL_COUNT];
    private IntConsumer onSelect = index -> { };

    /** @param context 主题上下文 @param attrs XML 属性 */
    public SudokuGridView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setColumnCount(9);
        setRowCount(9);
        setLayoutDirection(LAYOUT_DIRECTION_LTR);
        setBackgroundColor(context.getColor(R.color.hairline_strong));
        int line = Math.max(1, getResources().getDimensionPixelSize(R.dimen.sudoku_grid_line));
        setPadding(line, line, line, line);
        for (int index = 0; index < cells.length; index++) {
            TextView cell = new TextView(context);
            cell.setTextAppearance(R.style.TextAppearance_App_SudokuCell);
            cell.setIncludeFontPadding(false);
            cell.setGravity(Gravity.CENTER);
            int target = index;
            cell.setOnClickListener(view -> {
                if (view.isEnabled()) onSelect.accept(target);
            });
            cell.setOnFocusChangeListener((view, focused) -> {
                if (focused && view.isEnabled()) onSelect.accept(target);
            });
            cell.setEnabled(false);
            cell.setFocusable(false);
            cell.setClickable(false);
            int size = Math.max(getResources().getDimensionPixelSize(R.dimen.sudoku_cell_size),
                    (int) Math.ceil(cell.getTextSize() * 1.8f));
            LayoutParams params = new LayoutParams(spec(index / 9), spec(index % 9));
            params.width = size;
            params.height = size;
            params.leftMargin = index % 9 == 3 || index % 9 == 6 ? line * 2 : 0;
            params.topMargin = index / 9 == 3 || index / 9 == 6 ? line * 2 : 0;
            addView(cell, params);
            cells[index] = cell;
        }
    }

    /** @param listener 只报告可填写格的选择，不处理规则或存储 */
    public void setOnSelect(IntConsumer listener) { onSelect = listener; }

    /** @param board 当前已保存的棋盘 @param selected 选中格子或 -1 @param editable 当前是否允许选择 */
    public void bind(SudokuBoard board, int selected, boolean editable) {
        for (int index = 0; index < cells.length; index++) {
            TextView cell = cells[index];
            boolean given = board.isGiven(index);
            boolean selectable = editable && !given;
            boolean conflict = board.hasConflict(index);
            boolean chosen = !given && index == selected;
            int digit = board.digit(index);
            cell.setText(digit == 0 ? "" : String.valueOf(digit));
            cell.setTypeface(Typeface.DEFAULT, given ? Typeface.BOLD : Typeface.NORMAL);
            cell.setTextColor(getContext().getColor(conflict ? R.color.error_deep : R.color.ink));
            GradientDrawable background = new GradientDrawable();
            background.setColor(getContext().getColor(conflict ? R.color.error_soft
                    : chosen ? R.color.level_selected_bg : given ? R.color.canvas_soft_2 : R.color.canvas_soft));
            background.setStroke(getResources().getDimensionPixelSize(chosen
                            ? R.dimen.sudoku_selection_line : R.dimen.sudoku_grid_line),
                    getContext().getColor(chosen ? R.color.level_selected_text : R.color.hairline));
            cell.setBackground(background);
            cell.setSelected(chosen);
            cell.setEnabled(selectable);
            cell.setFocusable(selectable);
            cell.setClickable(selectable);
            String value = digit == 0 ? getContext().getString(R.string.interception_sudoku_empty) : String.valueOf(digit);
            String role = getContext().getString(given ? R.string.interception_sudoku_given : R.string.interception_sudoku_editable);
            cell.setContentDescription(getContext().getString(R.string.interception_sudoku_cell,
                    index / 9 + 1, index % 9 + 1, value, role)
                    + (conflict ? getContext().getString(R.string.interception_sudoku_cell_conflict) : ""));
        }
    }
}
