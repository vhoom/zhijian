package github.vhoom.zhijian.resources;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;

import androidx.test.platform.app.InstrumentationRegistry;

import github.vhoom.zhijian.R;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * 防止安装器或深色主题把启动图标的深蓝笔画显示成白色。
 *
 * 维护日期：2026年11月09日
 */
public class LauncherIconTest {
    /** 两个启动入口在浅深色资源配置下都必须实际绘制深蓝笔画。 */
    @Test public void launcherKeepsDarkBlueInkInBothThemes() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        for (int mode : new int[]{Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES}) {
            Configuration configuration = new Configuration(context.getResources().getConfiguration());
            configuration.uiMode = (configuration.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | mode;
            Context themed = context.createConfigurationContext(configuration);
            assertBlueInk(themed.getDrawable(R.mipmap.ic_launcher));
            assertBlueInk(themed.getDrawable(R.mipmap.ic_launcher_round));
        }
    }

    /** 通过包管理器加载安装器使用的入口，避免只验证孤立的前景图片。 */
    @Test public void packageManagerIconContainsDarkBlueInk() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertBlueInk(context.getPackageManager().getApplicationIcon(context.getPackageName()));
    }

    /** @param icon 系统资源或包管理器实际加载的图标 */
    private static void assertBlueInk(Drawable icon) {
        Bitmap rendered = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888);
        try {
            icon.setBounds(0, 0, rendered.getWidth(), rendered.getHeight());
            icon.draw(new Canvas(rendered));
            int bluePixels = 0;
            for (int y = 0; y < rendered.getHeight(); y++) {
                for (int x = 0; x < rendered.getWidth(); x++) {
                    if (isDarkBlue(rendered.getPixel(x, y))) bluePixels++;
                }
            }
            assertTrue("启动图标必须包含深蓝笔画，不能只显示白色蒙版", bluePixels > 150);
        } finally {
            rendered.recycle();
        }
    }

    /** @param pixel 系统缩放、抗锯齿后的像素；允许边缘混色，排除白色或绿色笔画 */
    private static boolean isDarkBlue(int pixel) {
        int red = Color.red(pixel);
        int green = Color.green(pixel);
        int blue = Color.blue(pixel);
        return Color.alpha(pixel) > 200 && red < 96 && green < 104 && blue < 160
                && blue - red > 24 && blue - green > 16;
    }
}
