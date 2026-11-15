package github.vhoom.zhijian.domain.interception;

import github.vhoom.zhijian.data.model.policy.RuleWindow;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 把分钟制时段解析成当前这一次实际发生的墙钟区间，统一处理跨午夜。
 *
 * 维护日期：2026年11月08日
 */
public final class RuleWindowSchedule {
    /**
     * 带完整开始和结束时间戳的生效时段。
     */
    public static final class Interval {
        public final long startMs;
        public final long endMs;

        /**
         * 创建 Interval 实例，并初始化本次操作依赖或不可变字段。
         *
         * @param startMs 区间开始时间戳，单位为毫秒
         * @param endMs 区间结束时间戳，单位为毫秒
         */
        Interval(long startMs, long endMs) {
            this.startMs = startMs;
            this.endMs = endMs;
        }
    }

    /**
     * 工具类不允许实例化，调用方直接使用静态方法。
     */
    private RuleWindowSchedule() { }

    /**
     * 求出当前生效时段的完整时间区间，支持跨午夜窗口。
     *
     * @param window 带稳定标识和独立额度的时间窗口
     * @param nowMs 当前墙钟时间戳，单位为毫秒
     * @param zoneId 记录或时间计算使用的时区标识
     */
    public static Interval activeInterval(RuleWindow window, long nowMs, String zoneId) {
        if (window == null) return null;
        ZoneId zone = ZoneId.of(zoneId);
        ZonedDateTime now = Instant.ofEpochMilli(nowMs).atZone(zone);
        int minute = now.getHour() * 60 + now.getMinute();
        boolean crossesMidnight = window.endMinute < window.startMinute;
        boolean active = crossesMidnight
                ? minute >= window.startMinute || minute < window.endMinute
                : minute >= window.startMinute && minute < window.endMinute;
        if (!active) return null;

        LocalDate startDate = now.toLocalDate();
        if (crossesMidnight && minute < window.endMinute) startDate = startDate.minusDays(1);
        if (window.weekday != 0 && startDate.getDayOfWeek().getValue() != window.weekday) return null;
        long start = atMinute(startDate, window.startMinute, zone);
        LocalDate endDate = crossesMidnight ? startDate.plusDays(1) : startDate;
        long end = atMinute(endDate, window.endMinute, zone);
        // 夏令时跳过的钟点可能使区间缩短至零，不返回已经到期或尚未开始的区间。
        return nowMs >= start && nowMs < end ? new Interval(start, end) : null;
    }

    /**
     * 找到最近一次已结束的实际窗口，用于结束边界的延迟复查。
     * @param window 带开始星期的窗口 @param nowMs 本次检查墙钟 @param zoneId 设备时区
     * @return 最近已结束的窗口；无有效区间时为空
     */
    public static Interval latestCompletedInterval(RuleWindow window, long nowMs, String zoneId) {
        if (window == null || window.startMinute == window.endMinute) return null;
        ZoneId zone = ZoneId.of(zoneId);
        LocalDate today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate();
        for (int offset = 0; offset <= 8; offset++) {
            LocalDate startDate = today.minusDays(offset);
            if (window.weekday != 0 && startDate.getDayOfWeek().getValue() != window.weekday) continue;
            LocalDate endDate = window.endMinute < window.startMinute ? startDate.plusDays(1) : startDate;
            long start = atMinute(startDate, window.startMinute, zone);
            long end = atMinute(endDate, window.endMinute, zone);
            if (start >= 0L && end > start && end <= nowMs) return new Interval(start, end);
        }
        return null;
    }

    /**
     * 计算下一次窗口开始前剩余的毫秒数。
     *
     * @param window 带稳定标识和独立额度的时间窗口
     * @param nowMs 当前墙钟时间戳，单位为毫秒
     * @param zoneId 记录或时间计算使用的时区标识
     */
    public static long millisUntilNextStart(RuleWindow window, long nowMs, String zoneId) {
        ZoneId zone = ZoneId.of(zoneId);
        ZonedDateTime now = Instant.ofEpochMilli(nowMs).atZone(zone);
        for (int offset = 0; offset <= 7; offset++) {
            LocalDate date = now.toLocalDate().plusDays(offset);
            if (window.weekday != 0 && date.getDayOfWeek().getValue() != window.weekday) continue;
            long start = atMinute(date, window.startMinute, zone);
            if (start > nowMs) return Math.max(1L, start - nowMs);
        }
        throw new IllegalArgumentException("Invalid weekday window");
    }

    /**
     * 求出当前时区的下一日开始时刻，避免假定一天固定二十四小时。
     *
     * @param nowMs 当前墙钟时间戳，单位为毫秒
     * @param zoneId 记录或时间计算使用的时区标识
     */
    public static long nextDayStart(long nowMs, String zoneId) {
        ZoneId zone = ZoneId.of(zoneId);
        ZonedDateTime now = Instant.ofEpochMilli(nowMs).atZone(zone);
        return now.toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
    }

    /**
     * 将本地日期和一天内分钟换算为指定时区的时间戳。
     *
     * @param date 需要查询或拆分的本地日期
     * @param minute 一天内对应的分钟值
     * @param zone 日期归属或时间换算使用的时区
     */
    private static long atMinute(LocalDate date, int minute, ZoneId zone) {
        return date.atTime(minute / 60, minute % 60).atZone(zone).toInstant().toEpochMilli();
    }
}
