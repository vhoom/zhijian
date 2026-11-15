package github.vhoom.zhijian.data.source;

import android.app.usage.UsageEvents;

import github.vhoom.zhijian.domain.usage.UsageTimeline;

/**
 * 将系统事件映射到纯 Java 时间轴，两个用量读取器共用事件分类。
 *
 * 维护日期：2026年11月14日
 */
final class UsageEventTimeline {
    /** 工具类不允许实例化。 */
    private UsageEventTimeline() { }

    /**
     * 顺序读取事件，在真实窗口观察时刻补足尚未落盘的恢复事件。
     *
     * @param events 系统用量查询返回的事件流
     * @param selfPackage 止间自身包名
     * @param observedPackage 无障碍服务刚确认处于前台的目标包名，首页查询为 null
     * @param observedAtMs 真实前台观察时刻，无补足事件时为零
     */
    static UsageTimeline read(UsageEvents events, String selfPackage,
            String observedPackage, long observedAtMs) {
        return read(events, selfPackage, observedPackage, observedAtMs, null, 0L);
    }

    /**
     * 增量查询从已保存的前台状态开始，缺少新 RESUMED 时仍能结算持续使用。
     * @param events 系统增量事件
     * @param selfPackage 止间包名
     * @param observedPackage 当前窗口观察包名
     * @param observedAtMs 窗口观察时间，零表示不补足
     * @param seedPackage 查询边界仍在前台的包名
     * @param seedAtMs 已处理边界时间
     * @return 同一口径的前台时间轴
     */
    static UsageTimeline read(UsageEvents events, String selfPackage, String observedPackage,
            long observedAtMs, String seedPackage, long seedAtMs) {
        return read(events, selfPackage, observedPackage, observedAtMs, seedPackage, null, seedAtMs);
    }

    /**
     * 增量重放保留页面类名，避免把 止间设置页面当作休息页面。
     * @param events 系统事件 @param selfPackage 止间包名 @param observedPackage 最近窗口包名
     * @param observedAtMs 窗口观察时间 @param seedPackage 上次前台包名 @param seedClass 上次页面类名
     * @param seedAtMs 上次已处理边界 @return 包含页面区间的时间轴
     */
    static UsageTimeline read(UsageEvents events, String selfPackage, String observedPackage,
            long observedAtMs, String seedPackage, String seedClass, long seedAtMs) {
        UsageTimeline timeline = new UsageTimeline(selfPackage);
        if (seedPackage != null) timeline.resumed(seedPackage, seedClass, seedAtMs);
        boolean pendingObservation = observedPackage != null && observedAtMs > 0L;
        UsageEvents.Event event = new UsageEvents.Event();
        while (events != null && events.hasNextEvent()) {
            events.getNextEvent(event);
            long timeMs = event.getTimeStamp();
            if (pendingObservation && timeMs >= observedAtMs) {
                timeline.resumed(observedPackage, null, observedAtMs);
                pendingObservation = false;
            }
            int type = event.getEventType();
            if (type == UsageEvents.Event.ACTIVITY_RESUMED) {
                timeline.activityChanged(UsageTimeline.ActivityEvent.RESUMED,
                        event.getPackageName(), event.getClassName(), timeMs);
            } else if (type == UsageEvents.Event.ACTIVITY_PAUSED) {
                timeline.activityChanged(UsageTimeline.ActivityEvent.PAUSED,
                        event.getPackageName(), event.getClassName(), timeMs);
            } else if (type == UsageEvents.Event.ACTIVITY_STOPPED) {
                timeline.activityChanged(UsageTimeline.ActivityEvent.STOPPED,
                        event.getPackageName(), event.getClassName(), timeMs);
            } else if (type == UsageEvents.Event.SCREEN_INTERACTIVE
                    || type == UsageEvents.Event.SCREEN_NON_INTERACTIVE) {
                timeline.screenChanged(type == UsageEvents.Event.SCREEN_INTERACTIVE, timeMs);
            } else if (type == UsageEvents.Event.DEVICE_SHUTDOWN
                    || type == UsageEvents.Event.DEVICE_STARTUP
                    || type == UsageEvents.Event.KEYGUARD_SHOWN) {
                timeline.screenChanged(false, timeMs);
            } else if (type == UsageEvents.Event.KEYGUARD_HIDDEN) {
                timeline.screenChanged(true, timeMs);
            }
        }
        if (pendingObservation) timeline.resumed(observedPackage, null, observedAtMs);
        return timeline;
    }
}
