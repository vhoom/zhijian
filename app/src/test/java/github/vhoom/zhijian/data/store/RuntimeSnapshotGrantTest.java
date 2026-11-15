package github.vhoom.zhijian.data.store;

import github.vhoom.zhijian.data.db.dao.RuntimeDao;
import github.vhoom.zhijian.data.db.entity.runtime.PassGrantEntity;
import github.vhoom.zhijian.data.model.runtime.PassGrant;
import org.junit.Test;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

/**
 * 分组授权读取与多启动身份的回归，不依赖 Android 时钟。
 *
 * 维护日期：2026年11月08日
 */
public class RuntimeSnapshotGrantTest {
    /** 旧启动的较长期限不能遮住新启动真正有效的授权。 */
    @Test public void previousBootGrantCannotHideCurrentSharedGrant() {
        RuntimeSnapshot snapshot = snapshot(grant("a", "old", 900_000L), grant("b", "current", 61_000L));
        PassGrant selected = snapshot.grantFor("c", "group:1", "current", 2_000L, 2_000L);
        assertEquals("b", selected.packageName);
        assertEquals(59_000L, selected.remainingMs("current", 2_000L, 2_000L));
    }
    /** 独立覆盖不能读取原分组授权，组内成员可以共用期限。 */
    @Test public void sharedGrantDoesNotCoverAnOverride() {
        RuntimeSnapshot snapshot = snapshot(grant("a", "boot", 61_000L));
        assertNotNull(snapshot.grantFor("b", "group:1", "boot", 2_000L, 2_000L));
        assertNull(snapshot.grantFor("b", "app:b", "boot", 2_000L, 2_000L));
    }
    /** @param rows 模拟已提交记录 @return 同一只读快照 */
    private static RuntimeSnapshot snapshot(PassGrantEntity... rows) {
        RuntimeDao dao = (RuntimeDao) Proxy.newProxyInstance(RuntimeDao.class.getClassLoader(),
                new Class<?>[]{RuntimeDao.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("grants")) return Arrays.asList(rows);
                    if (method.getName().equals("checkpoints")) return Collections.emptyList();
                    throw new UnsupportedOperationException(method.getName());
                });
        return RuntimeSnapshot.load(dao);
    }
    /** @param pkg 原目标 @param boot 启动身份 @param deadline 单调期限 @return 合法原授权实体 */
    private static PassGrantEntity grant(String pkg, String boot, long deadline) {
        PassGrantEntity row = new PassGrantEntity();
        row.packageName = pkg;
        row.policyId = "group:1";
        row.policyRevision = 1L;
        row.bootId = boot;
        row.issuedAtMs = 1_000L;
        row.durationMs = 60_000L;
        row.deadlineElapsedMs = deadline;
        return row;
    }
}
