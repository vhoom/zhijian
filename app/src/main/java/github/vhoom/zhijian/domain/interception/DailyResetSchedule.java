package github.vhoom.zhijian.domain.interception;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 计算全天限时和全天限次规则的自定义日界线。
 *
 * 维护日期：2026年11月08日
 */
public final class DailyResetSchedule {
    public static final int MINUTE_OF_DAY_MIN = 0;
    public static final int MINUTE_OF_DAY_MAX = 24 * 60 - 1;

    /** 工具类不允许实例化。 */
    private DailyResetSchedule() { }

    /**
     * 返回当前全天统计周期的开始时刻。
     *
     * @param nowMs 当前墙钟时间戳，单位为毫秒
     * @param zoneId 记录或时间计算使用的时区标识
     * @param resetMinute 每日恢复时刻相对零点的分钟数
     */
    public static long periodStart(long nowMs, String zoneId, int resetMinute) {
        ZoneId zone = ZoneId.of(zoneId);
        ZonedDateTime now = Instant.ofEpochMilli(nowMs).atZone(zone);
        LocalDate date = now.toLocalDate();
        long todayReset = atMinute(date, resetMinute, zone);
        return todayReset <= nowMs ? todayReset : atMinute(date.minusDays(1), resetMinute, zone);
    }

    /**
     * 返回当前全天统计周期结束、额度恢复的下一时刻。
     *
     * @param nowMs 当前墙钟时间戳，单位为毫秒
     * @param zoneId 记录或时间计算使用的时区标识
     * @param resetMinute 每日恢复时刻相对零点的分钟数
     */
    public static long nextReset(long nowMs, String zoneId, int resetMinute) {
        ZoneId zone = ZoneId.of(zoneId);
        ZonedDateTime now = Instant.ofEpochMilli(nowMs).atZone(zone);
        LocalDate date = now.toLocalDate();
        long todayReset = atMinute(date, resetMinute, zone);
        return todayReset > nowMs ? todayReset : atMinute(date.plusDays(1), resetMinute, zone);
    }

    /**
     * 把本地日期和分钟数转换成能正确处理夏令时的时间戳。
     *
     * @param date 需要查询或拆分的本地日期
     * @param minute 一天内对应的分钟值
     * @param zone 日期归属或时间换算使用的时区
     */
    private static long atMinute(LocalDate date, int minute, ZoneId zone) {
        if (minute < MINUTE_OF_DAY_MIN || minute > MINUTE_OF_DAY_MAX) {
            throw new IllegalArgumentException("Invalid reset minute");
        }
        return date.atStartOfDay(zone).plusMinutes(minute).toInstant().toEpochMilli();
    }
}
