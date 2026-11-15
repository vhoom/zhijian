package github.vhoom.zhijian.util.io;

import org.junit.Test;
import java.io.InterruptedIOException;
import java.io.StringReader;
import static org.junit.Assert.*;

/**
 * 文本读取预算在缓冲内容增长之前生效，兼容正常换行并响应任务取消。
 *
 * 维护日期：2026年11月08日
 */
public class BoundedTextReaderTest {
    /** 三种常见换行和无末尾换行都不丢行或增加空记录。 */
    @Test public void supportsAllLineEndings() throws Exception {
        try (BoundedTextReader reader = new BoundedTextReader(new StringReader("one\r\ntwo\rthree\nfour"), 40, 8)) {
            assertEquals("one", reader.readLine());
            assertEquals("two", reader.readLine());
            assertEquals("three", reader.readLine());
            assertEquals("four", reader.readLine());
            assertNull(reader.readLine());
        }
    }

    /** 单行不能绕过长度预算，异常明确区分为资源超限。 */
    @Test(expected = BoundedTextReader.LimitExceededException.class)
    public void refusesOversizedLine() throws Exception {
        try (BoundedTextReader reader = new BoundedTextReader(new StringReader("123456"), 20, 5)) { reader.readLine(); }
    }

    /** 多行和完整读取共同使用同一总量预算。 */
    @Test(expected = BoundedTextReader.LimitExceededException.class)
    public void refusesOversizedResource() throws Exception {
        try (BoundedTextReader reader = new BoundedTextReader(new StringReader("123456"), 5, 20)) { reader.readAll(); }
    }

    /** 预算恰好用完仍可读到 EOF，不误判完整资源为损坏。 */
    @Test public void exactLimitIsAccepted() throws Exception {
        try (BoundedTextReader reader = new BoundedTextReader(new StringReader("12345"), 5, 5)) {
            assertEquals("12345", reader.readAll());
        }
    }

    /** 中断保留在线程上，加载器退出而不是继续扫描大资源。 */
    @Test public void interruptedReadStopsWithoutClearingInterrupt() throws Exception {
        try (BoundedTextReader reader = new BoundedTextReader(new StringReader("text"), 20, 20)) {
            Thread.currentThread().interrupt();
            try { reader.readLine(); fail("Expected interrupted read"); }
            catch (InterruptedIOException expected) { assertTrue(Thread.currentThread().isInterrupted()); }
        } finally { Thread.interrupted(); }
    }
}
