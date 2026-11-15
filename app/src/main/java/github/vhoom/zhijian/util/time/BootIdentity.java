package github.vhoom.zhijian.util.time;

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;

/**
 * 为临时放行标识当前这次开机；重启后自动退回墙钟期限校验。
 *
 * 维护日期：2026年11月08日
 */
public final class BootIdentity {
    /**
     * 工具类不允许实例化，调用方直接使用静态方法。
     */
    private BootIdentity() { }

    /**
     * 读取系统启动身份，区分重启前后的单调计时值。
     *
     * @param context 访问系统服务、资源或持久化文件的上下文
     */
    public static String read(Context context) {
        int count = Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        if (count >= 0) return Build.FINGERPRINT + ':' + count;
        long bootWallMinutes = (System.currentTimeMillis() - SystemClock.elapsedRealtime()) / 60_000L;
        return Build.FINGERPRINT + ":wall:" + bootWallMinutes;
    }
}
