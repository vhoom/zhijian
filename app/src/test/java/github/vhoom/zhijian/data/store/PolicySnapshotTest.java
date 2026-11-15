package github.vhoom.zhijian.data.store;

import github.vhoom.zhijian.data.db.entity.policy.PolicyAppEntity;
import github.vhoom.zhijian.data.db.dao.PolicyDao;
import github.vhoom.zhijian.data.db.entity.policy.PolicyEntity;
import github.vhoom.zhijian.data.db.entity.policy.PolicyRuleEntity;
import github.vhoom.zhijian.data.db.entity.policy.PolicyWindowEntity;
import github.vhoom.zhijian.data.model.policy.RuleSource;
import github.vhoom.zhijian.data.model.policy.RuleType;
import github.vhoom.zhijian.domain.usage.UsageTimeline;
import java.util.function.Predicate;

import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * 覆盖 PolicySnapshot 的边界输入、状态变化与回归行为。
 *
 * 维护日期：2026年11月14日
 */
public class PolicySnapshotTest {
    /** 同组额度按实际来源汇总，独立覆盖、较晚组和全局应用不能混入本组。 */
    @Test public void sharedUsageExcludesOverridesAndOtherEffectiveGroups() {
        StoredPolicies data = new StoredPolicies();
        data.group(9, "共享组", true, true);
        data.bind("group:9", "app.second");
        data.bind("group:9", "app.override");
        data.group(11, "另一个组", true, true);
        data.bind("group:11", "app.other");
        data.policy("app:app.override", "app", -1, "独立", true, true);
        data.apps.get(data.apps.size() - 1).packageName = "app.override";
        Predicate<String> scope = data.load().usageScopeFor("app.second");
        UsageTimeline timeline = new UsageTimeline("zhijian");
        timeline.resumed("app.shared", "Main", 1_000L);
        timeline.resumed("app.second", "Main", 5_000L);
        timeline.resumed("app.override", "Main", 8_000L);
        timeline.resumed("app.other", "Main", 10_000L);
        timeline.resumed("app.unmanaged", "Main", 13_000L);
        assertEquals(7_000L, timeline.foregroundMillis(scope, 0L, 14_000L));
        assertEquals(2L, timeline.openCount(scope, 0L, 14_000L));
        assertEquals(4_000L, timeline.foregroundMillis(scope, 3_000L, 7_000L));
        assertEquals(2_000L, timeline.foregroundMillis(scope, 6_000L, 14_000L));
    }

    /** 独立策略和全局非共享统计仍只读取目标应用。 */
    @Test public void independentUsageScopeDoesNotIncludeOtherApplications() {
        StoredPolicies data = new StoredPolicies();
        data.group(9, "共享组", true, true);
        data.policy("app:app.shared", "app", -1, "独立", true, true);
        PolicySnapshot snapshot = data.load();
        assertTrue(snapshot.usageScopeFor("app.shared").test("app.shared"));
        assertFalse(snapshot.usageScopeFor("app.shared").test("app.other"));
        assertFalse(snapshot.usageScopeFor("unmanaged.first").test("unmanaged.second"));
    }

    /**
     * 验证生效规则和展示名称来自同一个首匹配组。
     */
    @Test
    public void firstMatchingGroupSuppliesBothEffectivePolicyAndDisplayedName() {
        StoredPolicies data = new StoredPolicies();
        data.group(99, "工作时段", true, true);
        data.group(1, "晚间专注", true, true);

        PolicySnapshot snapshot = data.load();
        assertEquals(99, snapshot.effectiveFor("app.shared").id);
        assertEquals("group:99", snapshot.effectivePolicyId("app.shared"));
        assertEquals("工作时段", snapshot.groupNameFor("app.shared"));
        assertEquals(RuleSource.GROUP, snapshot.ruleSourceFor("app.shared"));
        assertEquals(99, snapshot.groups().get(0).id);
    }

    /**
     * 验证停用或空规则组不会遮挡后面的生效组。
     */
    @Test
    public void disabledAndEmptyGroupsDoNotHideTheNextActiveGroup() {
        StoredPolicies data = new StoredPolicies();
        data.group(9, "已关闭", false, true);
        data.group(3, "未选规则", true, false);
        data.group(7, "实际生效", true, true);

        PolicySnapshot snapshot = data.load();
        assertEquals(7, snapshot.firstGroupFor("app.shared").id);
        assertEquals("实际生效", snapshot.groupNameFor("app.shared"));
        assertEquals(3, snapshot.groups().size());
        assertEquals("", snapshot.groupNameFor("unmanaged.app"));
    }

    /**
     * 验证应用独立规则仍优先于首个匹配组。
     */
    @Test
    public void independentPolicyStillTakesPriorityOverTheFirstGroup() {
        StoredPolicies data = new StoredPolicies();
        data.group(9, "规则组 1", true, true);
        data.policy("app:app.shared", "app", -1, "独立", true, true);

        PolicySnapshot snapshot = data.load();
        assertEquals(RuleSource.INDEPENDENT, snapshot.ruleSourceFor("app.shared"));
        assertEquals("app:app.shared", snapshot.effectivePolicyId("app.shared"));
        assertEquals("独立", snapshot.effectiveFor("app.shared").name);
    }

    /**
     * 验证所有公开草稿读取都隔离时段修改与已提交快照。
     */
    @Test
    public void allPublicDraftReadsIsolateWindowEditsFromCommittedSnapshot() {
        StoredPolicies data = new StoredPolicies();
        data.group(9, "规则组 1", true, true);
        PolicyWindowEntity first = data.window("morning", 480, 720, 30, 0);
        data.window("evening", 1080, 1320, 60, 1);

        PolicySnapshot snapshot = data.load();
        first.durationMs = 999 * 60_000L;
        snapshot.group(9).windows.get(0).limitDurationMillis = 100 * 60_000L;
        snapshot.groups().get(0).windows.clear();
        snapshot.effectiveFor("app.shared").windows.get(1).endMinute = 0;

        assertEquals(30 * 60_000L,
                snapshot.group(9).windowsFor(RuleType.WINDOW_LIMIT).get(0).limitDurationMillis);
        assertEquals(60 * 60_000L,
                snapshot.firstGroupFor("app.shared").windows.get(1).limitDurationMillis);
        assertEquals(1320, snapshot.firstGroupFor("app.shared").windows.get(1).endMinute);
    }

    /** 提供 DAO 的有序读取结果，不依赖 Android 或额外的 mock 库。 */
    private static final class StoredPolicies {
        private final List<PolicyEntity> policies = new ArrayList<>();
        private final List<PolicyRuleEntity> rules = new ArrayList<>();
        private final List<PolicyAppEntity> apps = new ArrayList<>();
        private final List<PolicyWindowEntity> windows = new ArrayList<>();

        /** @param policyId 生效来源主键 @param pkg 待绑定的测试应用 */
        void bind(String policyId, String pkg) {
            PolicyAppEntity app = new PolicyAppEntity();
            app.policyId = policyId;
            app.packageName = pkg;
            apps.add(app);
        }

        /**
         * 创建 StoredPolicies 实例，并初始化本次操作依赖或不可变字段。
         */
        StoredPolicies() {
            PolicyEntity global = new PolicyEntity();
            global.id = "global";
            global.scope = "global";
            global.controlLevel = "mortal";
            policies.add(global);
        }

        /**
         * 读取或还原规则组副本，保持规则条件和时段分类。
         *
         * @param id 当前记录、规则或视图的稳定标识
         * @param name 数据库文件名或规则显示名称
         * @param enabled 当前控件或配置项是否可以使用
         * @param selected 当前选中的规则或档位
         */
        void group(long id, String name, boolean enabled, boolean selected) {
            policy("group:" + id, "group", id, name, enabled, selected);
        }

        /**
         * 按指定类型或主键创建、读取测试策略。
         *
         * @param key 稳定标识或待读取的持久化设置键
         * @param scope 策略对应的全局、规则组或应用作用域
         * @param id 当前记录、规则或视图的稳定标识
         * @param name 数据库文件名或规则显示名称
         * @param enabled 当前控件或配置项是否可以使用
         * @param selected 当前选中的规则或档位
         */
        void policy(String key, String scope, long id, String name, boolean enabled, boolean selected) {
            PolicyEntity policy = new PolicyEntity();
            policy.id = key;
            policy.scope = scope;
            policy.groupId = id;
            policy.name = name;
            policy.enabled = enabled;
            policies.add(policy);
            PolicyRuleEntity rule = new PolicyRuleEntity();
            rule.policyId = key;
            rule.type = "daily_limit";
            rule.selected = selected;
            rule.durationMs = 30 * 60_000L;
            rules.add(rule);
            PolicyAppEntity app = new PolicyAppEntity();
            app.policyId = key;
            app.packageName = "app.shared";
            apps.add(app);
        }

        /**
         * 创建具有独立 ID 和额度的测试时段。
         *
         * @param id 当前记录、规则或视图的稳定标识
         * @param start 变化起点或区间开始值
         * @param end 区间结束时刻或一天内结束分钟
         * @param minutes 需要换算的整数分钟数
         * @param position 条目在当前列表中的位置
         */
        PolicyWindowEntity window(String id, int start, int end, int minutes, int position) {
            PolicyWindowEntity window = new PolicyWindowEntity();
            window.policyId = "group:9";
            window.windowId = id;
            window.type = "window_limit";
            window.startMinute = start;
            window.endMinute = end;
            window.durationMs = minutes * 60_000L;
            window.position = position;
            windows.add(window);
            return window;
        }

        /**
         * 从已提交数据库数据组装当前模块的不可变内存快照。
         */
        PolicySnapshot load() {
            PolicyDao dao = (PolicyDao) Proxy.newProxyInstance(PolicyDao.class.getClassLoader(),
                    new Class<?>[]{PolicyDao.class}, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "policies": return policies;
                            case "rules": return rules;
                            case "apps": return apps;
                            case "windows": return windows;
                            default: throw new AssertionError("Unexpected DAO call: " + method.getName());
                        }
                    });
            return PolicySnapshot.load(dao);
        }
    }
}
