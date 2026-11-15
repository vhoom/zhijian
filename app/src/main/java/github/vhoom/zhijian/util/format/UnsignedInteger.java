package github.vhoom.zhijian.util.format;

/**
 * 非负整数原文解析：拒绝符号、小数、指数和溢出，不过滤成另一个有效数字。
 *
 * 维护日期：2026年11月13日
 */
public final class UnsignedInteger {
    /** 纯解析工具不创建实例。 */
    private UnsignedInteger() { }

    /** @param text 用户输入原文 @return 精确整数；格式非法或溢出时返回 -1 */
    public static long parse(String text) {
        if (text == null) return -1L;
        String value = text.trim();
        if (value.length() > 64 || !value.matches("[0-9]+")) return -1L;
        try { return Long.parseLong(value); }
        catch (NumberFormatException invalid) { return -1L; }
    }
}
