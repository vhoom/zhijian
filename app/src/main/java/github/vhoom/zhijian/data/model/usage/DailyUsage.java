package github.vhoom.zhijian.data.model.usage;

import github.vhoom.zhijian.data.model.AppInfo;

import github.vhoom.zhijian.data.source.UsageStatsReader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 今日使用明细：把系统使用统计与本机应用表关联后的结果。
 *
 * 维护日期：2026年11月08日
 */
public final class DailyUsage {

    /**
     * 供用量展示和排序使用的一条应用明细。
     */
    public static final class Row {
        public final AppInfo app;
        public final long millis;

        /**
         * 创建 Row 实例，并初始化本次操作依赖或不可变字段。
         *
         * @param app 当前应用数据
         * @param millis 需要格式化或处理的毫秒数
         */
        Row(AppInfo app, long millis) {
            this.app = app;
            this.millis = millis;
        }
    }

    /** 未授权时系统不返回任何统计，界面上要引导用户去设置里开权限。 */
    public final boolean accessGranted;
    public final List<Row> rows;

    /** 今日亮屏总时长，独立于 rows：系统统计里还包括不可管控的应用与锁屏时间。 */
    public final long totalMillis;

    /**
     * 创建 DailyUsage 实例，并初始化本次操作依赖或不可变字段。
     *
     * @param accessGranted 当前是否已经具有所需授权
     * @param rows 需要展示或处理的列表行集合
     * @param totalMillis 全部应用累计用量，单位为毫秒
     */
    private DailyUsage(boolean accessGranted, List<Row> rows, long totalMillis) {
        this.accessGranted = accessGranted;
        this.rows = rows;
        this.totalMillis = totalMillis;
    }

    /**
     * 创建没有用量和明细的空展示数据。
     */
    public static DailyUsage empty() {
        return new DailyUsage(false, Collections.<Row>emptyList(), 0L);
    }

    /** @param archive 本地日期归档 @return 供时长卡片显示的历史榜单，不用于实时强度计算 */
    public static DailyUsage of(ArchivedUsage archive) {
        List<Row> rows = new ArrayList<>();
        for (ArchivedUsage.Entry entry : archive.entries) rows.add(new Row(entry.app, entry.millis));
        rows.sort((a, b) -> {
            int duration = Long.compare(b.millis, a.millis);
            return duration != 0 ? duration : a.app.packageName.compareTo(b.app.packageName);
        });
        return new DailyUsage(archive.available, Collections.unmodifiableList(rows), archive.screenMillis);
    }

    /**
     * 从输入值构造当前类型；枚举序号非法时使用规定的缺省值。
     *
     * @param snapshot 同次系统查询获得的用量快照
     * @param apps 应用列表或策略绑定的应用集合
     */
    public static DailyUsage of(UsageStatsReader.Snapshot snapshot, List<AppInfo> apps) {
        if (!snapshot.accessGranted) return new DailyUsage(false, Collections.<Row>emptyList(), 0L);

        List<Row> rows = new ArrayList<>();
        for (AppInfo app : apps) {
            Long millis = snapshot.foregroundMillis.get(app.packageName);
            if (millis == null || millis <= 0L) continue;
            rows.add(new Row(app, millis));
        }

        Collections.sort(rows, new Comparator<Row>() {
            /**
             * 按用量和稳定标识比较两条应用明细。
             *
             * @param a 参与比较的第一条记录
             * @param b 参与比较的第二条记录
             */
            @Override
            public int compare(Row a, Row b) {
                return Long.compare(b.millis, a.millis);
            }
        });
        return new DailyUsage(true, rows, snapshot.screenMillis);
    }
}
