package github.vhoom.zhijian.data.source;

import android.app.usage.UsageStatsManager;
import android.app.usage.UsageEvents;
import android.content.Context;

import github.vhoom.zhijian.data.model.policy.RuleGroup;
import github.vhoom.zhijian.data.model.policy.RuleType;
import github.vhoom.zhijian.data.model.policy.RuleWindow;
import github.vhoom.zhijian.domain.interception.DailyResetSchedule;
import github.vhoom.zhijian.domain.interception.RuleWindowSchedule;
import github.vhoom.zhijian.domain.interception.UsageEvidence;
import github.vhoom.zhijian.domain.interception.count.CountRuleEvaluator;
import github.vhoom.zhijian.domain.usage.OpenCountSnapshot;
import github.vhoom.zhijian.domain.usage.UsageTimeline;
import github.vhoom.zhijian.domain.usage.ForegroundObservation;
import github.vhoom.zhijian.util.permission.PermissionStatusReader;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.function.Predicate;

/**
 * 把系统 UsageEvents 转换为规则模块需要的稳定证据。
 *
 * 维护日期：2026年11月15日
 */
public final class UsageEvidenceReader {
    private final Context context;
    private final UsageStatsManager manager;
    private final String selfPackage;

    /**
     * 创建 UsageEvidenceReader 实例，并初始化本次操作依赖或不可变字段。
     *
     * @param context 访问系统服务、资源或持久化文件的上下文
     */
    public UsageEvidenceReader(Context context) {
        this.context = context.getApplicationContext();
        manager = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        selfPackage = context.getPackageName();
    }

    /**
     * 服务初次连接时读取当前前台应用，避免必须切换一次窗口才开始检查。
     *
     * @param nowMs 本次查询使用的墙钟时间
     */
    public String currentForegroundPackage(long nowMs) {
        if (manager == null || !PermissionStatusReader.hasUsageAccess(context)) return null;
        return UsageEventTimeline.read(manager.queryEvents(Math.max(0L, nowMs - 86_400_000L), nowMs),
                selfPackage, null, 0L).foregroundPackage();
    }

    /**
     * 从本次继续确认边界重放真实访问；拦截返回与同应用页面交接不算重新打开，锁屏和其他应用后返回算。
     * @param packageName 被忽略禁用的应用
     * @param startedAtMs 本条禁用的继续确认时间，查询起点以原应用为前台种子
     * @param nowMs 本次检查墙钟时间
     * @param observedAtMs 最近真实窗口观察，过期或未来观察不补入时间轴
     * @return 系统证据中是否出现后续的新应用访问；证据缺失由服务观察维持原边界
     */
    public boolean reopenedSince(String packageName, long startedAtMs, long nowMs, long observedAtMs) {
        if (manager == null || nowMs <= startedAtMs || !PermissionStatusReader.hasUsageAccess(context)) return false;
        UsageEvents events = manager.queryEvents(startedAtMs, nowMs);
        if (events == null) return false;
        long observation = github.vhoom.zhijian.domain.usage.ForegroundObservation.withinInterval(
                observedAtMs, startedAtMs, nowMs);
        UsageTimeline timeline = UsageEventTimeline.read(events, selfPackage, packageName,
                observation, packageName, startedAtMs);
        return timeline.openCount(packageName, startedAtMs + 1L, nowMs) > 0L;
    }

    /**
     * 使用午夜作为日界线读取证据，保留给无需自定义恢复时间的调用方。
     *
     * @param packageName 需要查询或管控的应用包名
     * @param policy 待判断或持久化的策略
     * @param nowMs 当前墙钟时间戳，单位为毫秒
     * @param continuousFloorMs 最近放行结束时刻，连续使用统计不能早于此时刻
     */
    public UsageEvidence read(String packageName, RuleGroup policy, long nowMs,
            long continuousFloorMs) {
        return read(packageName, policy, nowMs, continuousFloorMs, 0);
    }

    /**
     * 按指定日界线读取全天用量，同时保留跨午夜时段规则需要的事件范围。
     *
     * @param packageName 需要查询或管控的应用包名
     * @param policy 待判断或持久化的策略
     * @param nowMs 当前墙钟时间戳，单位为毫秒
     * @param continuousFloorMs 最近放行结束时刻，连续使用统计不能早于此时刻
     * @param dailyResetMinute 每日额度恢复时刻相对零点的分钟数
     */
    public UsageEvidence read(String packageName, RuleGroup policy, long nowMs,
            long continuousFloorMs, int dailyResetMinute) {
        return read(packageName, policy, nowMs, continuousFloorMs, dailyResetMinute, 0L);
    }

    /**
     * 结合真实前台窗口观察补足系统尚未落盘的恢复事件。
     *
     * @param packageName 正在检查的目标应用包名
     * @param policy 当前生效策略
     * @param nowMs 本次统计的墙钟时间
     * @param continuousFloorMs 连续使用重新起算的最早时间
     * @param dailyResetMinute 全天规则的每日恢复分钟
     * @param foregroundObservedAtMs 服务确认前台应用的墙钟时间，零表示没有补足事件
     */
    public UsageEvidence read(String packageName, RuleGroup policy, long nowMs,
            long continuousFloorMs, int dailyResetMinute, long foregroundObservedAtMs) {
        return read(packageName, policy, nowMs, continuousFloorMs, dailyResetMinute,
                foregroundObservedAtMs, packageName::equals);
    }

    /**
     * 按已提交快照的实际归属读取共享额度，单应用调用方沿用原统计范围。
     * @param packageName 当前前台目标，用于补足尚未落盘的窗口事件
     * @param policy 本次生效的规则及各时段额度
     * @param nowMs 本次统计墙钟边界
     * @param continuousFloorMs 旧连续统计接口的最早起点
     * @param dailyResetMinute 每日额度恢复分钟
     * @param foregroundObservedAtMs 最近真实前台窗口观察时间
     * @param belongs 同一配置快照提供的组内用量归属策略
     * @return 按每日与各独立时段边界统计的只读证据
     */
    public UsageEvidence read(String packageName, RuleGroup policy, long nowMs,
            long continuousFloorMs, int dailyResetMinute, long foregroundObservedAtMs,
            Predicate<String> belongs) {
        return read(packageName, policy, nowMs, continuousFloorMs, dailyResetMinute,
                foregroundObservedAtMs, belongs, 0L);
    }

    /**
     * 自然统计周期与固定生效起点取交集；切换强度不影响起点及额度恢复边界。
     * @param packageName 当前目标 @param policy 本次规则 @param nowMs 查询墙钟终点
     * @param continuousFloorMs 旧连续接口的恢复下界 @param dailyResetMinute 每日恢复分钟
     * @param foregroundObservedAtMs 最近前台观察 @param belongs 共享归属策略
     * @param trackingFloorMs 本次安装内该策略首次生效起点
     * @return 不包含启用前时长和打开次数的规则证据
     */
    public UsageEvidence read(String packageName, RuleGroup policy, long nowMs,
            long continuousFloorMs, int dailyResetMinute, long foregroundObservedAtMs,
            Predicate<String> belongs, long trackingFloorMs) {
        ZoneId zone = ZoneId.systemDefault();
        String zoneId = zone.getId();
        if (manager == null || !PermissionStatusReader.hasUsageAccess(context)) {
            return new UsageEvidence(false, nowMs, zoneId, 0, 0, 0, 0, new HashMap<>());
        }

        ZonedDateTime now = Instant.ofEpochMilli(nowMs).atZone(zone);
        long queryStart = now.toLocalDate().minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        List<CountRuleEvaluator.Query> countQueries = CountRuleEvaluator.queries(policy, nowMs, zoneId, dailyResetMinute);
        for (CountRuleEvaluator.Query query : countQueries) {
            // 较长的滚动窗口保留额外上下文，继续识别 Activity 交接和拦截页返回。
            if (query.startMs < queryStart) queryStart = Math.max(0L, query.startMs - 86_400_000L);
        }
        UsageEvents events = manager.queryEvents(queryStart, nowMs);
        if (events == null) {
            return new UsageEvidence(false, nowMs, zoneId, 0, 0, 0, 0, new HashMap<>());
        }
        UsageTimeline timeline = UsageEventTimeline.read(events, selfPackage, packageName,
                ForegroundObservation.withinInterval(foregroundObservedAtMs, queryStart, nowMs));
        return summarize(timeline, packageName, policy, nowMs, zoneId, dailyResetMinute,
                continuousFloorMs, trackingFloorMs, belongs);
    }

    /**
     * 用同一聚合入口裁剪真实时间轴，设备回归可注入包含启用前历史的时间轴。
     * @param timeline 已解析的系统前台及亮屏时间轴 @param packageName 当前目标
     * @param policy 本次规则 @param nowMs 查询终点 @param zoneId 查询时区
     * @param dailyResetMinute 自然日恢复分钟 @param continuousFloorMs 连续恢复边界
     * @param trackingFloorMs 固定生效起点 @param belongs 共享归属策略
     * @return 与自然周期及生效区间取交集后的真实用量
     */
    static UsageEvidence summarize(UsageTimeline timeline, String packageName, RuleGroup policy,
            long nowMs, String zoneId, int dailyResetMinute, long continuousFloorMs,
            long trackingFloorMs, Predicate<String> belongs) {
        long dayStart = Math.max(trackingFloorMs, DailyResetSchedule.periodStart(nowMs, zoneId, dailyResetMinute));
        Map<String, Long> windowUsage = new HashMap<>();
        if (policy.has(RuleType.WINDOW_LIMIT)) {
            for (RuleWindow window : policy.windowsFor(RuleType.WINDOW_LIMIT)) {
                RuleWindowSchedule.Interval interval = RuleWindowSchedule.activeInterval(window, nowMs, zoneId);
                if (interval != null) windowUsage.put(window.id, timeline.foregroundMillis(belongs,
                        Math.max(trackingFloorMs, interval.startMs), Math.min(nowMs, interval.endMs)));
            }
        }
        Map<String, OpenCountSnapshot> countSnapshots = new HashMap<>();
        for (CountRuleEvaluator.Query query : CountRuleEvaluator.queries(policy, nowMs, zoneId, dailyResetMinute)) {
            countSnapshots.put(query.key, timeline.openingSnapshot(belongs,
                    Math.max(trackingFloorMs, query.startMs), query.endMs));
        }
        return new UsageEvidence(true, nowMs, zoneId,
                timeline.foregroundMillis(belongs, dayStart, nowMs),
                timeline.screenMillis(dayStart, nowMs), timeline.openCount(belongs, dayStart, nowMs),
                timeline.continuousMillis(packageName, nowMs, Math.max(trackingFloorMs, continuousFloorMs)), windowUsage)
                .withCountSnapshots(countSnapshots);
    }
}
