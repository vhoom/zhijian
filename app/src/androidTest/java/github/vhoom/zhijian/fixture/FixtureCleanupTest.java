package github.vhoom.zhijian.fixture;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

/**
 * 注入初始化和恢复故障，验证设备夹具共用的恢复顺序、错误保留及重复关闭边界。
 *
 * 维护日期：2026年11月14日
 */
public final class FixtureCleanupTest {
    /** 已改授权和策略后初始化失败，仍立即恢复全部旧值并释放监听器。 */
    @Test public void failedInitializationRestoresAlreadyChangedValues() throws Exception {
        int[] state = {3, 7};
        boolean[] monitoring = {true};
        IOException startupFailure = new IOException("Could not open target");
        FixtureCleanup cleanup = new FixtureCleanup();
        cleanup.add(() -> state[0] = 3);
        cleanup.add(() -> state[1] = 7);
        cleanup.add(() -> monitoring[0] = false);

        try {
            cleanup.initialize(() -> {
                state[0] = 0;
                state[1] = 99;
                throw startupFailure;
            });
            fail("Initialization must report its original failure");
        } catch (IOException actual) {
            assertSame(startupFailure, actual);
            assertEquals(0, actual.getSuppressed().length);
        }

        assertArrayEquals(new int[]{3, 7}, state);
        assertFalse(monitoring[0]);
    }

    /** 授权恢复和规则清理分别失败，也继续其余步骤，保留顺序及所有错误。 */
    @Test public void cleanupContinuesAfterExceptionAndAssertionError() throws Exception {
        List<String> attempted = new ArrayList<>();
        IOException permissionFailure = new IOException("Permission restore failed");
        AssertionError policyFailure = new AssertionError("Policy restore failed");
        FixtureCleanup cleanup = new FixtureCleanup();
        cleanup.add(() -> {
            attempted.add("permission");
            throw permissionFailure;
        });
        cleanup.add(() -> attempted.add("first-policy"));
        cleanup.add(() -> {
            attempted.add("second-policy");
            throw policyFailure;
        });
        cleanup.add(() -> attempted.add("monitor"));

        try {
            cleanup.close();
            fail("Cleanup failures must be reported");
        } catch (IOException actual) {
            assertSame(permissionFailure, actual);
            assertEquals(1, actual.getSuppressed().length);
            assertSame(policyFailure, actual.getSuppressed()[0]);
        }
        assertEquals(Arrays.asList("permission", "first-policy", "second-policy", "monitor"), attempted);
    }

    /** 初始化断言失败不被恢复异常覆盖，末尾的监听器释放仍然执行。 */
    @Test public void initializationKeepsOriginalFailureWhenRollbackFails() throws Exception {
        AssertionError startupFailure = new AssertionError("Service did not connect");
        IOException cleanupFailure = new IOException("Permission restore failed");
        boolean[] monitoring = {true};
        FixtureCleanup cleanup = new FixtureCleanup();
        cleanup.add(() -> { throw cleanupFailure; });
        cleanup.add(() -> monitoring[0] = false);

        try {
            cleanup.initialize(() -> { throw startupFailure; });
            fail("The startup assertion must be propagated");
        } catch (AssertionError actual) {
            assertSame(startupFailure, actual);
            assertEquals(1, actual.getSuppressed().length);
            assertSame(cleanupFailure, actual.getSuppressed()[0]);
        }
        assertFalse(monitoring[0]);
        cleanup.close();
    }

    /** 第一次关闭虽失败但已尝试全部步骤，外层再次关闭不能重复恢复设备。 */
    @Test public void repeatedCloseDoesNotReplayPartialCleanup() throws Exception {
        int[] attempts = {0, 0};
        IOException failure = new IOException("First restore failed");
        FixtureCleanup cleanup = new FixtureCleanup();
        cleanup.add(() -> {
            attempts[0]++;
            throw failure;
        });
        cleanup.add(() -> attempts[1]++);

        try {
            cleanup.close();
            fail("The first close must report the restore failure");
        } catch (IOException actual) {
            assertSame(failure, actual);
        }
        cleanup.close();
        assertArrayEquals(new int[]{1, 1}, attempts);
    }

    /** 测试主体失败时，try-with-resources 保留主体异常并附加关闭错误。 */
    @Test public void testFailureRemainsPrimaryWhenCloseAlsoFails() throws Exception {
        AssertionError testFailure = new AssertionError("Expected interception did not appear");
        IOException cleanupFailure = new IOException("Could not restore settings");
        boolean[] monitoring = {true};
        FixtureCleanup cleanup = new FixtureCleanup();
        cleanup.add(() -> { throw cleanupFailure; });
        cleanup.add(() -> monitoring[0] = false);

        try (FixtureCleanup ignored = cleanup) {
            throw testFailure;
        } catch (AssertionError actual) {
            assertSame(testFailure, actual);
            assertEquals(1, actual.getSuppressed().length);
            assertSame(cleanupFailure, actual.getSuppressed()[0]);
        }
        assertFalse(monitoring[0]);
    }
}
