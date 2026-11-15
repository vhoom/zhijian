package github.vhoom.zhijian.ui.component.apps;

import github.vhoom.zhijian.data.model.AppInfo;
import github.vhoom.zhijian.data.model.policy.RuleSource;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;

/**
 * 覆盖 AppListOrder 的边界输入、状态变化与回归行为。
 *
 * 维护日期：2026年11月09日
 */
public class AppListOrderTest {
    /**
     * 验证单独生效先于规则组生效，各分区内部仍保持原名称顺序。
     */
    @Test
    public void independentSourcesComeBeforeGroupsWithoutChangingOrderWithinEachSection() {
        List<AppInfo> apps = Arrays.asList(app("A"), app("B"), app("C"), app("D"), app("E"), app("F"));
        Map<String, RuleSource> sources = new HashMap<>();
        sources.put("B", RuleSource.GROUP);
        sources.put("C", RuleSource.INDEPENDENT);
        sources.put("E", RuleSource.GROUP);
        sources.put("F", RuleSource.INDEPENDENT);
        List<AppInfo> result = AppListOrder.filter(apps, "", pkg -> sources.getOrDefault(pkg, RuleSource.NONE));
        assertEquals(Arrays.asList("C", "F", "B", "E", "A", "D"), names(result));
        assertEquals(Arrays.asList("A", "B", "C", "D", "E", "F"), names(apps));
    }

    /**
     * 验证搜索忽略大小写及首尾空白，并保持单独、规则组和无规则的顺序。
     */
    @Test
    public void searchKeepsSourcePriorityAndIgnoresCaseAndOuterSpaces() {
        List<AppInfo> apps = Arrays.asList(app("Alpha"), app("Beta"), app("ALPHA Pro"), app("Alpha Solo"));
        List<AppInfo> result = AppListOrder.filter(apps, " alpha ",
                pkg -> pkg.equals("Alpha Solo") ? RuleSource.INDEPENDENT
                        : pkg.equals("ALPHA Pro") ? RuleSource.GROUP : RuleSource.NONE);
        assertEquals(Arrays.asList("Alpha Solo", "ALPHA Pro", "Alpha"), names(result));
    }

    /**
     * 验证规则来源变化会在下一次列表刷新反映出来。
     */
    @Test
    public void changedSourcesAreReflectedOnTheNextRefresh() {
        List<AppInfo> apps = Arrays.asList(app("A"), app("B"));
        Map<String, RuleSource> sources = new HashMap<>();
        sources.put("B", RuleSource.GROUP);
        assertEquals(Arrays.asList("B", "A"), names(AppListOrder.filter(apps, null,
                pkg -> sources.getOrDefault(pkg, RuleSource.NONE))));
        sources.clear();
        assertEquals(Arrays.asList("A", "B"), names(AppListOrder.filter(apps, null,
                pkg -> sources.getOrDefault(pkg, RuleSource.NONE))));
    }

    /**
     * 创建测试使用的最小应用信息。
     *
     * @param name 数据库文件名或规则显示名称
     */
    private AppInfo app(String name) { return new AppInfo(name, name); }

    /**
     * 读取测试列表中的应用名称供顺序断言。
     *
     * @param apps 应用列表或策略绑定的应用集合
     */
    private List<String> names(List<AppInfo> apps) {
        return apps.stream().map(app -> app.label).collect(Collectors.toList());
    }
}
