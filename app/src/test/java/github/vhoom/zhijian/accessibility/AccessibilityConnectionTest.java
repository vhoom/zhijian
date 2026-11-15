package github.vhoom.zhijian.accessibility;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * 覆盖 AccessibilityConnection 的边界输入、状态变化与回归行为。
 *
 * 维护日期：2026年11月08日
 */
public class AccessibilityConnectionTest {
    private final Object first = new Object();
    private final Object replacement = new Object();
    private final AtomicInteger changes = new AtomicInteger();
    private final Runnable listener = changes::incrementAndGet;

    /**
     * 注册并记录测试需要观察的服务连接变化。
     */
    @Before
    public void listen() {
        assertFalse(AccessibilityConnection.isConnected());
        AccessibilityConnection.addListener(listener);
    }

    /**
     * 只释放测试创建的连接和监听，保留其他实例状态。
     */
    @After
    public void releaseOnlyTestConnections() {
        AccessibilityConnection.removeListener(listener);
        AccessibilityConnection.disconnected(first);
        AccessibilityConnection.disconnected(replacement);
    }

    /**
     * 验证解绑后销毁只发布一次断开通知。
     */
    @Test
    public void unbindFollowedByDestroyPublishesOneDisconnection() {
        AccessibilityConnection.connected(first);
        assertTrue(AccessibilityConnection.isConnected());
        AccessibilityConnection.disconnected(first);
        AccessibilityConnection.disconnected(first);
        assertFalse(AccessibilityConnection.isConnected());
        assertEquals(2, changes.get());
    }

    /**
     * 验证旧服务销毁不会清除替代它的新连接。
     */
    @Test
    public void oldServiceDestroyCannotEraseItsReplacement() {
        AccessibilityConnection.connected(first);
        AccessibilityConnection.connected(replacement);
        AccessibilityConnection.disconnected(first);
        assertTrue(AccessibilityConnection.isConnected());
        assertEquals(1, changes.get());

        AccessibilityConnection.disconnected(replacement);
        assertFalse(AccessibilityConnection.isConnected());
        assertEquals(2, changes.get());
    }

    /**
     * 验证重复连接和订阅不产生重复状态通知。
     */
    @Test
    public void repeatedConnectionAndSubscriptionDoNotDuplicateUpdates() {
        AccessibilityConnection.addListener(listener);
        AccessibilityConnection.connected(first);
        AccessibilityConnection.connected(first);
        assertEquals(1, changes.get());
    }

    /**
     * 验证页面停止监听后不再收到服务变化。
     */
    @Test
    public void pageThatStoppedListeningReceivesNoUpdates() {
        AccessibilityConnection.removeListener(listener);
        AccessibilityConnection.connected(first);
        AccessibilityConnection.disconnected(first);
        assertEquals(0, changes.get());
    }
}
