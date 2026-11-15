package github.vhoom.zhijian.util.format;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * 整数输入保留原文后的格式与溢出边界回归。
 *
 * 维护日期：2026年11月13日
 */
public class UnsignedIntegerTest {
    /** 首尾空白和前导零不改变整数含义，长整数上界可精确读取。 */
    @Test public void acceptsWhitespaceLeadingZerosAndLongBoundary() {
        assertEquals(20L, UnsignedInteger.parse(" 0020 "));
        assertEquals(0L, UnsignedInteger.parse("0"));
        assertEquals(Long.MAX_VALUE, UnsignedInteger.parse(Long.toString(Long.MAX_VALUE)));
    }

    /** 非整数原文不能经过过滤变成另一个可保存数值。 */
    @Test public void rejectsSignsDecimalsExponentsAndEmbeddedText() {
        for (String text : new String[]{null, "", " ", "+2", "-2", "2.0", "2,0", "2e1", "2a", "２", "1 2"}) {
            assertEquals(-1L, UnsignedInteger.parse(text));
        }
    }

    /** 溢出及过长输入返回无效标记，不能绕过容量约束。 */
    @Test public void rejectsOverflowAndOverlongInput() {
        assertEquals(-1L, UnsignedInteger.parse("9223372036854775808"));
        assertEquals(-1L, UnsignedInteger.parse(new String(new char[65]).replace('\0', '9')));
    }
}
