package github.vhoom.zhijian.util.format;

import java.util.Locale;

/**
 * 动态文案只接受一个无宽度参数，拒绝会请求巨量输出的格式化配置。
 *
 * 维护日期：2026年11月08日
 */
public final class TextTemplate {
    /** 模板工具不创建实例。 */
    private TextTemplate() { }

    /**
     * 验证一个字符串或整数参数的模板，然后检查格式化类型。
     * @param template 待验证的资源文案，支持 %s/%d、编号形式、%% 和 %n
     * @param argument 正式调用时传入的参数样例
     * @throws IllegalArgumentException 类型、格式或参数宽度不受支持
     */
    public static void validate(String template, Object argument) {
        String conversion = argument instanceof Number ? "d" : "s";
        String plain = template.replaceAll("%(?:1\\$)?" + conversion + "|%%|%n", "");
        if (plain.contains("%")) throw new IllegalArgumentException("Unsupported text template format");
        String.format(Locale.ROOT, template, argument);
    }
}
