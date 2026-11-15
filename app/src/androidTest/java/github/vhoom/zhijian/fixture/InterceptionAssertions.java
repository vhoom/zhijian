package github.vhoom.zhijian.fixture;

import android.content.Context;

import static org.junit.Assert.assertEquals;

/**
 * 拦截页共享的资源清理断言。
 *
 * 维护日期：2026年11月09日
 */
public final class InterceptionAssertions {
    /** 工具类不创建实例。 */
    private InterceptionAssertions() { }

    /** @param context 当前页面上下文；验证页头和模式标签已从布局资源移除 */
    public static void assertNoHeader(Context context) {
        for (String name : new String[]{"interception_header", "interception_mode"}) {
            assertEquals(name, 0, context.getResources().getIdentifier(name, "id", context.getPackageName()));
        }
    }
}
