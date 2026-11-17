package github.vhoom.zhijian.accessibility;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.inputmethodservice.InputMethodService;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 将窗口类元数据适配为前台观察策略，不读取窗口内容或按输入法包名整体排除应用。
 *
 * 维护日期：2026年11月17日
 */
final class ForegroundWindowClassifier {
    /** 真实 Activity 可直接接管；明确的键盘忽略；其他窗口先核对系统前台证据。 */
    enum Kind { APPLICATION, TRANSIENT, VERIFY_APPLICATION }

    private final Predicate<ComponentName> activityLookup;
    private final Predicate<ComponentName> inputMethodLookup;
    private final Map<ComponentName, Kind> cache = new HashMap<>();

    /** @param context 提供已安装组件元数据的应用上下文，不请求窗口内容权限 */
    ForegroundWindowClassifier(Context context) {
        this(component -> isActivity(context.getPackageManager(), component),
                component -> isInputMethodService(context.getPackageManager(), component));
    }

    /**
     * @param activityLookup 判断组件是否为真实 Activity 的系统元数据策略
     * @param inputMethodLookup 判断组件是否受输入法绑定权限保护的系统元数据策略
     */
    ForegroundWindowClassifier(Predicate<ComponentName> activityLookup,
            Predicate<ComponentName> inputMethodLookup) {
        this.activityLookup = activityLookup;
        this.inputMethodLookup = inputMethodLookup;
    }

    /**
     * 输入法设置 Activity 优先于键盘判定；缺少或只有控件类名时不推测应用离开。
     * @param packageName 窗口事件报告的包名
     * @param className 窗口或页面类名，可为空；不包含窗口文字和用户输入
     * @return 可直接使用、可忽略或需要后台核实的前台观察策略
     */
    Kind classify(String packageName, String className) {
        if (packageName == null || className == null || className.isEmpty()) return Kind.VERIFY_APPLICATION;
        String name = className.startsWith(".") ? packageName + className : className;
        ComponentName component = new ComponentName(packageName, name);
        Kind known = cache.get(component);
        if (known != null) return known;
        Kind kind;
        if (activityLookup.test(component)) {
            kind = Kind.APPLICATION;
        } else if ("android.inputmethodservice.SoftInputWindow".equals(name)
                || InputMethodService.class.getName().equals(name) || inputMethodLookup.test(component)) {
            kind = Kind.TRANSIENT;
        } else {
            kind = Kind.VERIFY_APPLICATION;
        }
        cache.put(component, kind);
        return kind;
    }

    /** @param packages 系统包管理器 @param component 事件类对应的组件 @return 是否声明为 Activity */
    private static boolean isActivity(PackageManager packages, ComponentName component) {
        try {
            packages.getActivityInfo(component, 0);
            return true;
        } catch (PackageManager.NameNotFoundException | SecurityException unavailable) {
            return false;
        }
    }

    /** @param packages 系统包管理器 @param component 事件类对应的组件 @return 是否为受保护的输入法服务 */
    private static boolean isInputMethodService(PackageManager packages, ComponentName component) {
        try {
            return "android.permission.BIND_INPUT_METHOD".equals(packages.getServiceInfo(component, 0).permission);
        } catch (PackageManager.NameNotFoundException | SecurityException unavailable) {
            return false;
        }
    }
}
