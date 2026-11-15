package github.vhoom.zhijian.ui.component.apps;

import github.vhoom.zhijian.data.model.AppInfo;
import github.vhoom.zhijian.data.model.policy.RuleSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * 按单独生效、规则组生效和无生效规则分区，各区保留原始名称顺序。
 *
 * 维护日期：2026年11月09日
 */
final class AppListOrder {
    /**
     * 工具类不允许实例化，调用方直接使用静态方法。
     */
    private AppListOrder() { }

    /**
     * 按输入关键词筛选应用，优先显示单独生效来源并保留分区内顺序。
     *
     * @param apps 应用列表或策略绑定的应用集合
     * @param query 用户输入的筛选关键词
     * @param sourceFor 根据应用包名读取当前生效规则来源的函数
     */
    static List<AppInfo> filter(List<AppInfo> apps, String query, Function<String, RuleSource> sourceFor) {
        String normalized = query == null ? "" : query.trim().toLowerCase(Locale.CHINA);
        List<AppInfo> independent = new ArrayList<>();
        List<AppInfo> grouped = new ArrayList<>();
        List<AppInfo> other = new ArrayList<>();
        for (AppInfo app : apps) {
            if (!normalized.isEmpty() && !app.label.toLowerCase(Locale.CHINA).contains(normalized)) continue;
            RuleSource source = sourceFor.apply(app.packageName);
            if (source == RuleSource.INDEPENDENT) independent.add(app);
            else if (source == RuleSource.GROUP) grouped.add(app);
            else other.add(app);
        }
        independent.addAll(grouped);
        independent.addAll(other);
        return independent;
    }
}
