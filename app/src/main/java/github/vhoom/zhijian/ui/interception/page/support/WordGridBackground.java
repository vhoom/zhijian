package github.vhoom.zhijian.ui.interception.page.support;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import github.vhoom.zhijian.R;

/**
 * 单词页的低对比度网格背景；颜色从浅色与深色资源读取，大小随窗口调整。
 *
 * 维护日期：2026年11月08日
 */
public final class WordGridBackground extends Drawable {
    private final Paint surface = new Paint();
    private final Paint grid = new Paint();
    private final float step;

    /**
     * 创建当前主题的网格背景，不保留页面上下文。
     * @param context 读取主题颜色与网格尺寸的上下文
     */
    public WordGridBackground(Context context) {
        surface.setColor(context.getColor(R.color.word_canvas));
        grid.setColor(context.getColor(R.color.word_grid));
        grid.setStrokeWidth(context.getResources().getDisplayMetrics().density);
        step = context.getResources().getDimension(R.dimen.word_grid_step);
    }

    /**
     * 在实际外壳边界绘制表面与固定间隔网格。
     * @param canvas 系统提供的绘图画布
     */
    @Override public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        canvas.drawRect(bounds, surface);
        for (float x = bounds.left; x < bounds.right; x += step) {
            canvas.drawLine(x, bounds.top, x, bounds.bottom, grid);
        }
        for (float y = bounds.top; y < bounds.bottom; y += step) {
            canvas.drawLine(bounds.left, y, bounds.right, y, grid);
        }
    }

    /**
     * 同步调整表面与网格透明度。
     * @param alpha 系统指定的整体透明度
     */
    @Override public void setAlpha(int alpha) {
        surface.setAlpha(alpha);
        grid.setAlpha(Math.round(alpha * 0.02f));
        invalidateSelf();
    }

    /**
     * 应用系统颜色滤镜。
     * @param filter 系统提供的颜色滤镜，空值清除滤镜
     */
    @Override public void setColorFilter(ColorFilter filter) {
        surface.setColorFilter(filter);
        grid.setColorFilter(filter);
        invalidateSelf();
    }

    /** 返回支持透明度变化的绘制格式。 */
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
