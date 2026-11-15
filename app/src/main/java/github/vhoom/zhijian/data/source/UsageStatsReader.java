package github.vhoom.zhijian.data.source;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;

import github.vhoom.zhijian.util.permission.PermissionStatusReader;
import github.vhoom.zhijian.domain.usage.UsageTimeline;

import java.util.Calendar;
import java.util.Collections;
import java.util.Map;

/**
 * 今日各应用前台时长。
 *
 * 维护日期：2026年11月08日
 */
public final class UsageStatsReader {

    /** 一次查询的快照。授权与「今日有没有记录」是两件事，分开返回给界面判断。 */
    public static final class Snapshot {
        public final boolean accessGranted;
        public final Map<String, Long> foregroundMillis;
        /** 今日亮屏总时长，由系统的亮/灭屏事件配对得出；锁屏与待机时也计入，所以它大于各应用前台时长之和。 */
        public final long screenMillis;

        /**
         * 创建 Snapshot 实例，并初始化本次操作依赖或不可变字段。
         *
         * @param accessGranted 当前是否已经具有所需授权
         * @param foregroundMillis 累计前台用量，单位为毫秒
         * @param screenMillis 累计屏幕交互时长，单位为毫秒
         */
        Snapshot(boolean accessGranted, Map<String, Long> foregroundMillis, long screenMillis) {
            this.accessGranted = accessGranted;
            this.foregroundMillis = foregroundMillis;
            this.screenMillis = screenMillis;
        }

        static final Snapshot DENIED = new Snapshot(false, Collections.<String, Long>emptyMap(), 0L);
    }

    private final Context context;
    private final UsageStatsManager usm;

    /**
     * 创建 UsageStatsReader 实例，并初始化本次操作依赖或不可变字段。
     *
     * @param context 访问系统服务、资源或持久化文件的上下文
     */
    public UsageStatsReader(Context context) {
        this.context = context;
        this.usm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
    }

    /**
     * 用事件流而不是 queryUsageStats：统计桶由系统隔一段时间才落盘，
     * 当天正在进行的这次会话往往还没进桶，界面就会一直看不到变化。
     */
    public Snapshot loadToday() {
        if (!PermissionStatusReader.hasUsageAccess(context)) return Snapshot.DENIED;

        long now = System.currentTimeMillis();
        long begin = startOfDay(now);
        Calendar previousDay = Calendar.getInstance();
        previousDay.setTimeInMillis(begin);
        previousDay.add(Calendar.DAY_OF_MONTH, -1);
        UsageEvents events = usm.queryEvents(previousDay.getTimeInMillis(), now);
        UsageTimeline timeline = UsageEventTimeline.read(events, context.getPackageName(), null, 0L);
        return new Snapshot(true, timeline.totals(begin, now), timeline.screenMillis(begin, now));
    }

    /**
     * 事件与统计桶都按墙钟时间归档，起点必须用 currentTimeMillis 口径。
     *
     * @param nowMillis 当前墙钟时间戳，单位为毫秒
     */
    private long startOfDay(long nowMillis) {
        Calendar day = Calendar.getInstance();
        day.setTimeInMillis(nowMillis);
        day.set(Calendar.HOUR_OF_DAY, 0);
        day.set(Calendar.MINUTE, 0);
        day.set(Calendar.SECOND, 0);
        day.set(Calendar.MILLISECOND, 0);
        return day.getTimeInMillis();
    }
}
