package github.vhoom.zhijian.util.time;

import android.content.Context;

import github.vhoom.zhijian.R;

/**
 * 时长文案统一从这里出，避免各处自己拼「小时/分钟」。
 *
 * 维护日期：2026年11月08日
 */
public final class DurationFormat {

    private static final long MINUTE_MS = 60_000L;
    private static final long HOUR_MS = 60 * MINUTE_MS;

    /**
     * 工具类不允许实例化，调用方直接使用静态方法。
     */
    private DurationFormat() {
    }

    /**
     * 从输入值构造当前类型；枚举序号非法时使用规定的缺省值。
     *
     * @param context 访问系统服务、资源或持久化文件的上下文
     * @param millis 需要格式化或处理的毫秒数
     */
    public static String of(Context context, long millis) {
        if (millis < MINUTE_MS) return context.getString(R.string.duration_below_minute);

        long hours = millis / HOUR_MS;
        long minutes = (millis % HOUR_MS) / MINUTE_MS;
        return hours == 0
                ? context.getString(R.string.duration_minutes, minutes)
                : context.getString(R.string.duration_hours_minutes, hours, minutes);
    }

    /**
     * 将整数分钟额度精确换算为毫秒。
     *
     * @param minutes 需要换算的整数分钟数
     */
    public static long quotaToMillis(int minutes) {
        return minutes * MINUTE_MS;
    }
}
