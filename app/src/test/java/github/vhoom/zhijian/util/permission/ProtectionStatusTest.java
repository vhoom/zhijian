package github.vhoom.zhijian.util.permission;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * 验证功能状态的优先级，防止只开启权限便错误显示正常运行。
 *
 * 维护日期：2026年11月08日
 */
public class ProtectionStatusTest {
    /** 页面实际显示未确认时，基础权限齐备也不能报告为正常。 */
    @Test public void missingDisplayReceiptNeedsAttention() {
        assertEquals(ProtectionStatus.DISPLAY_UNCONFIRMED,
                ProtectionStatus.resolve(true, true, true, true, false, true));
    }

    /** 缺少基础权限时优先显示授权问题，不被较晚的页面诊断覆盖。 */
    @Test public void missingPermissionsTakePriorityOverDisplayWarning() {
        assertEquals(ProtectionStatus.MISSING_PERMISSION,
                ProtectionStatus.resolve(false, true, true, true, false, true));
    }
    /** 遍历所有权限与连接组合，缺必要权限必须优先显示。 */
    @Test
    public void allPermissionAndServiceCombinationsAreClassified() {
        for (int mask = 0; mask < 32; mask++) {
            boolean usage = (mask & 1) != 0;
            boolean installed = (mask & 2) != 0;
            boolean enabled = (mask & 4) != 0;
            boolean connected = (mask & 8) != 0;
            boolean restricted = (mask & 16) != 0;
            ProtectionStatus expected = !usage || !installed || !enabled
                    ? ProtectionStatus.MISSING_PERMISSION : !connected
                    ? ProtectionStatus.WAITING_CONNECTION : restricted
                    ? ProtectionStatus.BACKGROUND_RESTRICTED : ProtectionStatus.RUNNING;
            assertEquals(expected, ProtectionStatus.resolve(usage, installed, enabled, connected, restricted));
        }
    }
}
