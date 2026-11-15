package github.vhoom.zhijian.data.store;

import github.vhoom.zhijian.data.db.dao.PolicyDao;
import github.vhoom.zhijian.data.db.entity.policy.PolicyAppEntity;
import github.vhoom.zhijian.data.db.entity.policy.PolicyEntity;
import github.vhoom.zhijian.data.db.entity.policy.PolicyRuleEntity;
import github.vhoom.zhijian.data.model.policy.ControlLevel;
import github.vhoom.zhijian.data.model.policy.RetreatRestrictionException;
import github.vhoom.zhijian.data.model.runtime.PendingInterception;
import github.vhoom.zhijian.domain.interception.InterceptionDecision;
import github.vhoom.zhijian.domain.interception.RetreatChangePolicy;

import org.junit.Test;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

/**
 * 使用真实策略快照验证闭关保护、独立覆盖和全局归属边界。
 *
 * 维护日期：2026年11月08日
 */
public class RetreatChangePolicyTest {
    /** 没有触发约束时，即使闭关也允许切档。 */
    @Test public void retreatBeforeAnyTriggerCanSwitch() {
        Fixture data = new Fixture(ControlLevel.SEAL);
        PolicySnapshot before = data.load();
        data.level(ControlLevel.MORTAL);
        RetreatChangePolicy.requireAllowed(before, data.load(), Collections.emptyList());
    }

    /** 未完成休息与禁用都锁住强度，不能因当前页面不同而切出。 */
    @Test public void restAndBlockBothPreventSwitchingOutOfRetreat() {
        for (boolean rest : new boolean[]{true, false}) {
            Fixture data = new Fixture(ControlLevel.SEAL);
            PolicySnapshot before = data.load();
            data.level(ControlLevel.REPAIR);
            assertBlocked(() -> RetreatChangePolicy.requireAllowed(before, data.load(),
                    Collections.singletonList(restriction(before, "app.a", rest))), true);
        }
    }

    /** 无为、修心保留用户主导能力，即使有待完成休息也可进入闭关。 */
    @Test public void firstTwoLevelsCanSwitchWithExistingRest() {
        for (ControlLevel level : new ControlLevel[]{ControlLevel.MORTAL, ControlLevel.REPAIR}) {
            Fixture data = new Fixture(level);
            PolicySnapshot before = data.load();
            data.level(ControlLevel.SEAL);
            RetreatChangePolicy.requireAllowed(before, data.load(),
                    Collections.singletonList(restriction(before, "app.a", true)));
        }
    }

    /** 修改或删除正在执行的策略都被拒绝。 */
    @Test public void protectedRuleCannotBeRevisedOrRemoved() {
        Fixture data = new Fixture(ControlLevel.SEAL);
        PolicySnapshot before = data.load();
        List<PendingInterception> active = Collections.singletonList(restriction(before, "app.a", true));
        data.group.revision++;
        assertBlocked(() -> RetreatChangePolicy.requireAllowed(before, data.load(), active), false);
        data.rows.remove(data.group);
        assertBlocked(() -> RetreatChangePolicy.requireAllowed(before, data.load(), active), false);
    }

    /** 对另一个同组成员新建独立覆盖，也不能绕开已经执行的组约束。 */
    @Test public void independentOverrideCannotDetachAnotherProtectedMember() {
        Fixture data = new Fixture(ControlLevel.SEAL);
        PolicySnapshot before = data.load();
        data.add("app:app.b", "app", -1L, "app.b");
        assertBlocked(() -> RetreatChangePolicy.requireAllowed(before, data.load(),
                Collections.singletonList(restriction(before, "app.a", true))), false);
    }

    /** 全局规则触发后，新建覆盖不能把尚未单独配置的应用移出全局约束。 */
    @Test public void globalRestrictionProtectsPreviouslyUnlistedApplications() {
        Fixture data = new Fixture(ControlLevel.SEAL);
        PolicySnapshot before = data.load();
        data.add("app:app.new", "app", -1L, "app.new");
        assertBlocked(() -> RetreatChangePolicy.requireAllowed(before, data.load(),
                Collections.singletonList(restriction(before, "app.global", false))), false);
    }

    /** 与正在执行规则无关的新增组可正常保存。 */
    @Test public void unrelatedRulesRemainEditableDuringRetreat() {
        Fixture data = new Fixture(ControlLevel.SEAL);
        PolicySnapshot before = data.load();
        data.add("group:2", "group", 2L, "other.a", "other.b");
        RetreatChangePolicy.requireAllowed(before, data.load(),
                Collections.singletonList(restriction(before, "app.a", true)));
    }

    /** @param action 待拒绝变更 @param levelChange 是否为切档拒绝 */
    private static void assertBlocked(Runnable action, boolean levelChange) {
        try { action.run(); fail("Active retreat restriction must reject this change"); }
        catch (RetreatRestrictionException expected) { assertEquals(levelChange, expected.levelChange); }
    }

    /** @param snapshot 原配置 @param target 原目标 @param rest 是否为休息 @return 已复查未解除的测试事实 */
    private static PendingInterception restriction(PolicySnapshot snapshot, String target, boolean rest) {
        String policyId = snapshot.effectivePolicyId(target);
        InterceptionDecision decision = new InterceptionDecision(target, policyId, snapshot.revisionOf(policyId),
                rest ? InterceptionDecision.Kind.INTERRUPT : InterceptionDecision.Kind.BLOCK,
                rest ? InterceptionDecision.Trigger.CONTINUOUS : InterceptionDecision.Trigger.DAILY_LIMIT,
                snapshot.level(), "", 60_000L, 60_000L, rest ? 0L : 120_000L, 180_000L, rest ? 60_000L : 0L);
        return new PendingInterception(decision, "pending", "boot", 1_000L, 1_000L, 0L);
    }

    /** 只提供快照读取所需的 DAO 数据，避免把系统能力混入纯变更策略测试。 */
    private static final class Fixture {
        private final List<PolicyEntity> rows = new ArrayList<>();
        private final List<PolicyRuleEntity> rules = new ArrayList<>();
        private final List<PolicyAppEntity> apps = new ArrayList<>();
        private final PolicyEntity global, group;

        /** @param level 初始强度 */
        private Fixture(ControlLevel level) {
            global = add("global", "global", -1L);
            global.controlLevel = level.code;
            group = add("group:1", "group", 1L, "app.a", "app.b");
        }

        /** @param level 候选强度 */
        private void level(ControlLevel level) { global.controlLevel = level.code; global.revision++; }

        /** @param id 策略键 @param scope 作用域 @param groupId 组主键 @param packages 成员 @return 可修改测试行 */
        private PolicyEntity add(String id, String scope, long groupId, String... packages) {
            PolicyEntity row = new PolicyEntity();
            row.id = id;
            row.scope = scope;
            row.groupId = groupId;
            row.enabled = true;
            row.revision = 1L;
            rows.add(row);
            PolicyRuleEntity rule = new PolicyRuleEntity();
            rule.policyId = id;
            rule.type = "daily_limit";
            rule.selected = true;
            rule.durationMs = 60_000L;
            rules.add(rule);
            for (String pkg : packages) {
                PolicyAppEntity app = new PolicyAppEntity();
                app.policyId = id;
                app.packageName = pkg;
                apps.add(app);
            }
            return row;
        }

        /** @return 完整复制测试行后的真实策略快照 */
        private PolicySnapshot load() {
            PolicyDao dao = (PolicyDao) Proxy.newProxyInstance(PolicyDao.class.getClassLoader(),
                    new Class<?>[]{PolicyDao.class}, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "policies": return rows;
                            case "rules": return rules;
                            case "apps": return apps;
                            case "windows": return Collections.emptyList();
                            default: throw new AssertionError(method.getName());
                        }
                    });
            return PolicySnapshot.load(dao);
        }
    }
}
