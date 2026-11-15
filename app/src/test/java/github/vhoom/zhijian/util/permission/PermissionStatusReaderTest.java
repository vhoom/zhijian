package github.vhoom.zhijian.util.permission;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 权限组合提醒策略的本地回归测试。
 *
 * 维护日期：2026年11月08日
 */
public class PermissionStatusReaderTest {
    /** 无障碍开启但缺少用量权限时必须提醒。 */
    @Test
    public void warnsWhenAccessibilityEnabledWithoutUsageAccess() {
        assertTrue(PermissionStatusReader.needsUsageAccessWarning(true, false));
    }

    /** 两项权限都已具备时不显示提醒。 */
    @Test
    public void hidesWarningWhenBothPermissionsEnabled() {
        assertFalse(PermissionStatusReader.needsUsageAccessWarning(true, true));
    }

    /** 无障碍尚未开启时继续由原配置引导负责，不重复显示组合提醒。 */
    @Test
    public void hidesCombinationWarningBeforeAccessibilityEnabled() {
        assertFalse(PermissionStatusReader.needsUsageAccessWarning(false, false));
    }
}
