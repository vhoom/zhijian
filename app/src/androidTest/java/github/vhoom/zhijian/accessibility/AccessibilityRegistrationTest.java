package github.vhoom.zhijian.accessibility;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.res.XmlResourceParser;
import android.view.accessibility.AccessibilityEvent;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.xmlpull.v1.XmlPullParser;

import static org.junit.Assert.*;

/**
 * 校验无障碍服务在 Manifest 和 XML 中声明的系统绑定权限与窗口事件范围。
 *
 * 维护日期：2026年11月14日
 */
@RunWith(AndroidJUnit4.class)
public class AccessibilityRegistrationTest {
    /**
     * 验证服务受系统绑定权限保护，且只订阅窗口状态变化。
     */
    @Test
    public void serviceIsProtectedBySystemPermissionAndRequestsOnlyWindowEvents() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PackageManager packages = context.getPackageManager();
        ServiceInfo service = packages.getServiceInfo(new ComponentName(context, ZhijianAccessibilityService.class),
                PackageManager.GET_META_DATA);
        assertEquals("android.permission.BIND_ACCESSIBILITY_SERVICE", service.permission);
        assertTrue(service.exported);
        assertTrue(service.enabled);
        try (XmlResourceParser xml = service.loadXmlMetaData(packages, AccessibilityService.SERVICE_META_DATA)) {
            assertNotNull(xml);
            while (xml.next() != XmlPullParser.START_TAG && xml.getEventType() != XmlPullParser.END_DOCUMENT) { }
            assertEquals("accessibility-service", xml.getName());
            String android = "http://schemas.android.com/apk/res/android";
            assertFalse(xml.getAttributeBooleanValue(android, "canRetrieveWindowContent", true));
            assertEquals(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                    xml.getAttributeIntValue(android, "accessibilityEventTypes", 0));
        }
    }
}
