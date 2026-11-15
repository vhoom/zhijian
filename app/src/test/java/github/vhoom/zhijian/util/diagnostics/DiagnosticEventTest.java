package github.vhoom.zhijian.util.diagnostics;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * 验证正式版日志保留级别及文本预算，防止丢失关键事件或产生伪造记录。
 *
 * 维护日期：2026年11月08日
 */
public class DiagnosticEventTest {
    /** 两种构建都必须保留关键级别，详细 Debug 输出只在调试版保留。 */
    @Test public void releaseRetainsInfoWarningsAndErrors() {
        assertFalse(DiagnosticLevel.DEBUG.retainedIn(false));
        assertTrue(DiagnosticLevel.INFO.retainedIn(false));
        assertTrue(DiagnosticLevel.WARN.retainedIn(false));
        assertTrue(DiagnosticLevel.ERROR.retainedIn(false));
        for (DiagnosticLevel level : DiagnosticLevel.values()) assertTrue(level.retainedIn(true));
    }

    /** 普通字段换行不能伪造下一条记录，真实发生时间与会话保持不变。 */
    @Test public void eventKeepsTimeSessionAndEscapesMessageNewlines() {
        String text = new DiagnosticEvent(0L, 123L, DiagnosticLevel.INFO, "launch", "session-1",
                "requested\nforged", "", "main").format();
        assertTrue(text.startsWith("1970-01-01T00:00:00Z [INFO]"));
        assertTrue(text.contains("elapsed_ms=123"));
        assertTrue(text.contains("session=session-1"));
        assertTrue(text.contains("requested\\nforged"));
        assertEquals(1L, text.chars().filter(character -> character == '\n').count());
    }

    /** 内容预算限制异常记录的磁盘占用，同时保留明确截断标识。 */
    @Test public void largeMessagesAndStacksAreBounded() {
        String text = new DiagnosticEvent(0L, 0L, DiagnosticLevel.ERROR, "crash", "",
                "字".repeat(5000), "stack".repeat(10000), "worker").format();
        assertTrue(text.contains("[truncated]"));
        assertTrue(text.length() < 11_000);
    }
}
