package github.vhoom.zhijian.data.store;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import github.vhoom.zhijian.data.db.ZhijianDatabase;
import github.vhoom.zhijian.data.db.dao.PolicyDao;
import github.vhoom.zhijian.data.db.entity.policy.PolicyEntity;
import github.vhoom.zhijian.data.model.policy.ControlLevel;
import github.vhoom.zhijian.data.model.policy.RuleGroup;
import github.vhoom.zhijian.data.model.policy.RuleType;
import github.vhoom.zhijian.domain.usage.UsageTrackingStart;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.*;

/**
 * 固定起点的 Room 事务、强度切换和安装身份隔离回归，全部使用独立临时数据库。
 *
 * 维护日期：2026年11月15日
 */
@RunWith(AndroidJUnit4.class)
public final class UsageTrackingStoreTest {
    /** 关闭的默认组没有起点，首次启用建立起点，修订及三档切换后原值继续保留。 */
    @Test public void firstActivationAndLevelChangesKeepTheSameCommittedStart() {
        Context context = context();
        String name = "usage-start-" + UUID.randomUUID() + ".db";
        ZhijianDatabase db = ZhijianDatabase.open(context, name);
        try {
            PolicyBootstrap.initialize(db);
            writeGroup(db, false);
            assertNull(synchronize(db, "installation", 1_000L).get("group:1"));
            writeGroup(db, true);
            UsageTrackingStart first = synchronize(db, "installation", 2_000L).get("group:1");
            assertNotNull(first);
            for (ControlLevel level : ControlLevel.values()) {
                PolicyDao dao = db.policies();
                db.runInTransaction(() -> {
                    PolicyEntity global = dao.policy("global");
                    global.controlLevel = level.code;
                    global.revision++;
                    dao.updatePolicy(global);
                });
                UsageTrackingStart after = synchronize(db, "installation", 9_000L).get("group:1");
                assertEquals(first.id, after.id);
                assertEquals(first.wallMs, after.wallMs);
                assertEquals(first.elapsedMs, after.elapsedMs);
            }
            db.close();
            db = ZhijianDatabase.open(context, name);
            UsageTrackingStart restored = synchronize(db, "installation", 20_000L).get("group:1");
            assertEquals(first.id, restored.id);
            assertEquals(2_000L, restored.wallMs);
        } finally {
            db.close();
            context.deleteDatabase(name);
        }
    }

    /** 恢复的数据库来自另一安装时，固定起点与统计身份都从本次生效边界重新建立。 */
    @Test public void anotherInstallationDoesNotReuseRestoredTrackingIdentity() {
        Context context = context();
        String name = "usage-install-" + UUID.randomUUID() + ".db";
        ZhijianDatabase db = ZhijianDatabase.open(context, name);
        try {
            PolicyBootstrap.initialize(db);
            writeGroup(db, true);
            UsageTrackingStart old = synchronize(db, "old-installation", 1_000L).get("group:1");
            assertTrue(UsageTrackingStore.belongsToAnotherInstallation(db.policies(), "new-installation"));
            UsageTrackingStart next = synchronize(db, "new-installation", 10_000L).get("group:1");
            assertNotEquals(old.id, next.id);
            assertEquals(10_000L, next.wallMs);
            assertFalse(UsageTrackingStore.belongsToAnotherInstallation(db.policies(), "new-installation"));
        } finally {
            db.close();
            context.deleteDatabase(name);
        }
    }

    /** 保存失败时，候选起点和配置一起回滚，后续成功启用才建立实际起点。 */
    @Test public void failedConfigurationDoesNotPublishItsCandidateStart() {
        Context context = context();
        String name = "usage-rollback-" + UUID.randomUUID() + ".db";
        ZhijianDatabase db = ZhijianDatabase.open(context, name);
        try {
            PolicyBootstrap.initialize(db);
            writeGroup(db, false);
            synchronize(db, "installation", 1_000L);
            try {
                db.runInTransaction((Runnable) () -> {
                    PolicyEntity group = db.policies().policy("group:1");
                    group.enabled = true;
                    db.policies().updatePolicy(group);
                    UsageTrackingStore.synchronize(db.policies(), PolicySnapshot.load(db.policies()),
                            "installation", "boot", 2_000L, 2_000L);
                    throw new IllegalStateException("rollback fixture");
                });
                fail("Transaction should fail");
            } catch (IllegalStateException expected) {
                assertEquals("rollback fixture", expected.getMessage());
            }
            assertNull(synchronize(db, "installation", 3_000L).get("group:1"));
            writeGroup(db, true);
            assertEquals(4_000L, synchronize(db, "installation", 4_000L).get("group:1").wallMs);
        } finally {
            db.close();
            context.deleteDatabase(name);
        }
    }

    /** @return 仅用于独立数据库的测试上下文，不启动服务或修改真实规则 */
    private static Context context() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    /** @param db 测试数据库 @param enabled 本次组开关，类型和参数保持一致 */
    private static void writeGroup(ZhijianDatabase db, boolean enabled) {
        RuleGroup group = new RuleGroup(1L, "起点夹具");
        group.enabled = enabled;
        group.types.add(RuleType.CONTINUOUS);
        group.continuousLimitMillis = 60_000L;
        group.interruptionPauseMillis = 60_000L;
        group.apps.add("fixture.first");
        group.apps.add("fixture.second");
        db.runInTransaction(() -> PolicyCodec.write(db.policies(),
                PolicyCodec.entity(group, "group:1", "group", 1L, 1L), group));
    }

    /** @param db 测试数据库 @param installation 固定安装身份 @param now 同一生效边界的双时钟
     * @return 实际配置事务完成后的起点集合 */
    private static Map<String, UsageTrackingStart> synchronize(ZhijianDatabase db, String installation, long now) {
        return db.runInTransaction(() -> {
            PolicyDao dao = db.policies();
            return UsageTrackingStore.synchronize(dao, PolicySnapshot.load(dao), installation, "boot", now, now);
        });
    }
}
