package github.vhoom.zhijian.data.store;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import github.vhoom.zhijian.R;
import github.vhoom.zhijian.data.model.policy.RuleGroup;
import github.vhoom.zhijian.data.model.policy.BuiltInGroupPreset;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * 注入安装状态，用独立临时数据库验证内置组；不安装、卸载或清空用户应用。
 *
 * 维护日期：2026年11月14日
 */
@RunWith(AndroidJUnit4.class)
public class BuiltInRuleGroupStoreTest {
    private static final String DOUYIN = "com.ss.android.ugc.aweme";
    private static final String XHS = "com.xingin.xhs";
    private final AtomicReference<List<String>> installed = new AtomicReference<>(Collections.emptyList());
    private Context context;
    private String databaseName;
    private ZhijianStorage storage;

    /** 每个用例使用独立数据库和可控安装清单。 */
    @Before public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        databaseName = "built-in-test-" + UUID.randomUUID() + ".db";
        reopen();
    }

    /** 先排空存储队列并关闭，再删除当前用例的临时数据库。 */
    @After public void tearDown() throws Exception {
        if (storage != null) storage.closeForTest().get(20, TimeUnit.SECONDS);
        if (context != null) context.deleteDatabase(databaseName);
    }

    /** 初次零交集也显示默认组并可保存，重启不重复创建，安装后更新实际数量。 */
    @Test public void emptyIntersectionRemainsVisibleAndOneAppCanBeSaved() throws Exception {
        assertEquals(3, storage.snapshot().groups().size());
        assertFalse(group().enabled);
        assertTrue(storage.snapshot().groups().get(0).apps.isEmpty());
        assertEquals(context.getString(R.string.rule_group_name_with_count,
                context.getString(R.string.rule_default_short_video_group), 0),
                context.getString(R.string.rule_group_name_with_count, group().name, group().apps.size()));
        assertEquals(7, group().weekdayRules.size());
        assertEquals(900_000L, group().weekdayRules.get(1).continuousLimitMillis);
        storage.saveGroup(group()).get(20, TimeUnit.SECONDS);
        reopen();
        assertEquals(3, storage.snapshot().groups().size());
        assertTrue(group().apps.isEmpty());
        assertNotEquals(PolicyCodec.groupKey(group().id), storage.snapshot().effectivePolicyId(DOUYIN));
        install(Collections.singletonList(DOUYIN));
        assertEquals(3, storage.snapshot().groups().size());
        RuleGroup group = group();
        assertEquals(Collections.singletonList(DOUYIN), group.apps);
        assertEquals(7, group.weekdayRules.size());
        assertEquals(900_000L, group.weekdayRules.get(1).continuousLimitMillis);
        assertFalse(group.enabled);
        assertNotEquals(group.id, storage.snapshot().effectiveFor(DOUYIN).id);
        group.enabled = true;
        storage.saveGroup(group).get(20, TimeUnit.SECONDS);
        assertEquals(group.id, storage.snapshot().effectiveFor(DOUYIN).id);
    }

    /** 用户编辑后安装、卸载和重启只更新成员，零成员组仍显示并保留参数及停用状态。 */
    @Test public void memberRefreshAndRestartPreserveEditedConfiguration() throws Exception {
        install(Collections.singletonList(DOUYIN));
        RuleGroup edited = group();
        edited.name = "我的短视频";
        edited.enabled = false;
        edited.weekdayRules.get(3).continuousLimitMillis = 420_000L;
        edited.weekdayRules.get(3).interruptionPauseMillis = 900_000L;
        edited.weekdayRules.get(3).windows.get(0).startMinute = 22 * 60;
        storage.saveGroup(edited).get(20, TimeUnit.SECONDS);
        install(Arrays.asList(XHS, DOUYIN, "outside.catalog", XHS));
        assertEquals(Arrays.asList(DOUYIN, XHS), group().apps);
        install(Collections.emptyList());
        assertEquals(3, storage.snapshot().groups().size());
        assertTrue(storage.snapshot().groups().get(0).apps.isEmpty());
        assertNotNull(group());
        reopen();
        install(Collections.singletonList(XHS));
        assertEquals("我的短视频", group().name);
        assertFalse(group().enabled);
        assertEquals(420_000L, group().weekdayRules.get(3).continuousLimitMillis);
        assertEquals(900_000L, group().weekdayRules.get(3).interruptionPauseMillis);
        assertEquals(22 * 60, group().weekdayRules.get(3).windows.get(0).startMinute);
        assertEquals(900_000L, group().weekdayRules.get(4).continuousLimitMillis);
    }

    /** 相同安装清单不增加修订号，组员共用同一来源且不会包含目录外应用。 */
    @Test public void refreshIsIdempotentAndMembersShareUsageScope() throws Exception {
        install(Arrays.asList(DOUYIN, XHS));
        RuleGroup enabled = group();
        enabled.enabled = true;
        storage.saveGroup(enabled).get(20, TimeUnit.SECONDS);
        String key = storage.snapshot().effectivePolicyId(DOUYIN);
        long revision = storage.snapshot().revisionOf(key);
        install(Arrays.asList(XHS, DOUYIN, XHS));
        assertEquals(revision, storage.snapshot().revisionOf(key));
        assertEquals(key, storage.snapshot().effectivePolicyId(XHS));
        assertTrue(storage.snapshot().usageScopeFor(DOUYIN).test(XHS));
        assertFalse(storage.snapshot().usageScopeFor(DOUYIN).test("outside.catalog"));
    }

    /** 用户手动开启后，成员更新和进程重启不能被新的默认关闭值覆盖。 */
    @Test public void manuallyEnabledGroupStaysEnabledAfterRefreshAndRestart() throws Exception {
        install(Collections.singletonList(DOUYIN));
        RuleGroup enabled = group();
        enabled.enabled = true;
        storage.saveGroup(enabled).get(20, TimeUnit.SECONDS);
        install(Arrays.asList(DOUYIN, XHS));
        assertTrue(group().enabled);
        reopen();
        assertTrue(group().enabled);
        assertEquals(group().id, storage.snapshot().effectiveFor(XHS).id);
    }

    /** 页面过期成员不能把未安装或目录外应用带回存储。 */
    @Test public void savingStaleDraftUsesCurrentInstalledIntersection() throws Exception {
        install(Arrays.asList(DOUYIN, XHS));
        RuleGroup stale = group();
        stale.apps.add("outside.catalog");
        install(Collections.singletonList(XHS));
        storage.saveGroup(stale).get(20, TimeUnit.SECONDS);
        assertEquals(Collections.singletonList(XHS), group().apps);
    }

    /** 用户明确删除后，安装事件或进程重启都不会重新创建组。 */
    @Test public void deletionRemainsDeletedAfterRefreshAndRestart() throws Exception {
        install(Collections.singletonList(DOUYIN));
        storage.removePolicy(PolicyCodec.groupKey(BuiltInGroupPreset.VIDEO.groupId))
                .get(20, TimeUnit.SECONDS);
        install(Arrays.asList(DOUYIN, XHS));
        reopen();
        assertNull(group());
        assertEquals(2, storage.snapshot().groups().size());
    }

    /** 普通单成员组仍不能保存，内置组例外不会扩散到用户规则。 */
    @Test public void userGroupsStillRequireTwoApps() throws Exception {
        RuleGroup user = BuiltInGroupPreset.VIDEO.create("用户组", Collections.singletonList(DOUYIN), 180_000L);
        RuleGroup draft = new RuleGroup(123, user.name);
        draft.apps.addAll(user.apps);
        draft.types.addAll(user.types);
        draft.windows.addAll(user.windows);
        draft.continuousLimitMillis = user.continuousLimitMillis;
        draft.interruptionPauseMillis = user.interruptionPauseMillis;
        draft.passDurationMillis = user.passDurationMillis;
        try {
            storage.saveGroup(draft).get(20, TimeUnit.SECONDS);
            fail("A user group with one app must be rejected");
        } catch (java.util.concurrent.ExecutionException expected) {
            assertTrue(expected.getCause() instanceof IllegalArgumentException);
        }
        assertNull(storage.snapshot().group(123));
    }

    /** 全目录安装时各组只获取自己的成员，过期草稿不能混入另一组的应用。 */
    @Test public void allCatalogsSynchronizeAndSaveTheirOwnMembers() throws Exception {
        install(BuiltInGroupPreset.ALL_PACKAGES);
        for (BuiltInGroupPreset preset : BuiltInGroupPreset.values()) {
            RuleGroup group = storage.snapshot().group(preset.groupId);
            assertEquals(preset.packages, group.apps);
            assertEquals(context.getString(preset.nameRes), group.name);
            group.apps.clear();
            group.apps.addAll(BuiltInGroupPreset.ALL_PACKAGES);
            storage.saveGroup(group).get(20, TimeUnit.SECONDS);
            assertEquals(preset.packages, storage.snapshot().group(preset.groupId).apps);
        }
    }

    /** 三组删除标记互相独立，全部删除后刷新和重启均不重建。 */
    @Test public void everyPresetHasAnIndependentPersistentDismissal() throws Exception {
        int remaining = 3;
        for (BuiltInGroupPreset preset : BuiltInGroupPreset.values()) {
            storage.removePolicy(PolicyCodec.groupKey(preset.groupId)).get(20, TimeUnit.SECONDS);
            install(BuiltInGroupPreset.ALL_PACKAGES);
            reopen();
            assertNull(storage.snapshot().group(preset.groupId));
            assertEquals(--remaining, storage.snapshot().groups().size());
        }
    }

    /** 明确移除后允许空成员，安装刷新、重新安装和重启均不会加回。 */
    @Test public void removedMemberStaysExcludedAcrossRefreshReinstallAndRestart() throws Exception {
        install(Collections.singletonList(DOUYIN));
        RuleGroup draft = group();
        draft.apps.clear();
        storage.saveGroup(draft, Collections.singletonList(DOUYIN)).get(20, TimeUnit.SECONDS);
        assertTrue(group().apps.isEmpty());
        install(Collections.emptyList());
        install(Arrays.asList(DOUYIN, XHS));
        reopen();
        assertEquals(Collections.singletonList(XHS), group().apps);
        assertEquals(7, group().weekdayRules.size());
    }

    /** 只排除明确点击移除的应用，编辑期间新增的目录成员不会被过期草稿丢掉。 */
    @Test public void staleDraftRemovalKeepsNewlyInstalledMembers() throws Exception {
        install(Collections.singletonList(DOUYIN));
        RuleGroup stale = group();
        install(Arrays.asList(DOUYIN, XHS));
        stale.apps.remove(DOUYIN);
        storage.saveGroup(stale, Collections.singletonList(DOUYIN)).get(20, TimeUnit.SECONDS);
        assertEquals(Collections.singletonList(XHS), group().apps);
        storage.refreshBuiltInGroups().get(20, TimeUnit.SECONDS);
        assertEquals(Collections.singletonList(XHS), group().apps);
    }

    /** 参数保存失败时，移除记录与成员一起回滚，后续刷新仍保留该应用。 */
    @Test public void failedSaveRollsBackMemberExclusion() throws Exception {
        install(Collections.singletonList(DOUYIN));
        RuleGroup invalid = group();
        invalid.weekdayRules.get(1).continuousLimitMillis = -1L;
        try {
            storage.saveGroup(invalid, Collections.singletonList(DOUYIN)).get(20, TimeUnit.SECONDS);
            fail("Invalid configuration must roll back exclusions");
        } catch (java.util.concurrent.ExecutionException expected) {
            assertTrue(expected.getCause() instanceof IllegalArgumentException);
        }
        reopen();
        assertEquals(Collections.singletonList(DOUYIN), group().apps);
    }

    /** @param packages 模拟当前手机安装清单，同步完成后再断言已提交快照 */
    private void install(List<String> packages) throws Exception {
        installed.set(packages);
        storage.refreshBuiltInGroups().get(20, TimeUnit.SECONDS);
    }

    /** 重建存储实例，验证初始化阶段的实际数据库恢复。 */
    private void reopen() throws Exception {
        if (storage != null) storage.closeForTest().get(20, TimeUnit.SECONDS);
        storage = new ZhijianStorage(context, databaseName, installed::get);
        storage.ready().get(20, TimeUnit.SECONDS);
    }

    /** @return 当前已提交的内置组草稿，零成员组也可以读取其配置 */
    private RuleGroup group() { return storage.snapshot().group(BuiltInGroupPreset.VIDEO.groupId); }
}
