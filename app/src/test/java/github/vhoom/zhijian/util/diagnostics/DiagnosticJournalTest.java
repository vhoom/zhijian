package github.vhoom.zhijian.util.diagnostics;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.*;

/**
 * 验证日志预算、历史保留和快照边界，避免排查证据被轮转或清空误删。
 *
 * 维护日期：2026年11月08日
 */
public class DiagnosticJournalTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    /** 达到上限后保留最近三份完整记录，只淘汰最旧文件。 */
    @Test public void rotationRetainsRecentRecordsInChronologicalOrder() throws Exception {
        RollingDiagnosticJournal journal = new RollingDiagnosticJournal(temporary.newFolder(), 4, 3);
        journal.append("one\n");
        journal.append("two\n");
        journal.append("tri\n");
        journal.append("for\n");
        DiagnosticSnapshot snapshot = journal.snapshot();
        assertEquals("two\ntri\nfor\n", snapshot.preview(100));
        assertEquals(3, snapshot.files().size());
        assertEquals(12L, snapshot.sizeBytes);
    }

    /** UTF-8 多字节记录按字节预算轮转，中文与代理字符不被截断。 */
    @Test public void multibyteRecordsRemainCompleteDuringRotation() throws Exception {
        RollingDiagnosticJournal journal = new RollingDiagnosticJournal(temporary.newFolder(), 16, 2);
        journal.append("你好🌙\n");
        journal.append("再见🌙\n");
        assertEquals("你好🌙\n再见🌙\n", journal.snapshot().preview(100));
        for (byte[] bytes : journal.snapshot().files().values()) assertTrue(bytes.length <= 16);
    }

    /** 已冻结的导出不受后续写入和清空影响。 */
    @Test public void snapshotSurvivesLaterWritesAndClear() throws Exception {
        RollingDiagnosticJournal journal = new RollingDiagnosticJournal(temporary.newFolder(), 64, 3);
        journal.append("before\n");
        DiagnosticSnapshot snapshot = journal.snapshot();
        journal.append("after\n");
        journal.clear();
        assertEquals("before\n", snapshot.preview(100));
        assertEquals(0L, journal.snapshot().sizeBytes);
    }

    /** 清空只操作固定文件名，不伤及目录中其他内容。 */
    @Test public void clearPreservesUnrelatedFiles() throws Exception {
        File directory = temporary.newFolder();
        File unrelated = new File(directory, "keep.txt");
        Files.write(unrelated.toPath(), "keep".getBytes(StandardCharsets.UTF_8));
        RollingDiagnosticJournal journal = new RollingDiagnosticJournal(directory, 64, 2);
        journal.append("event\n");
        journal.clear();
        assertTrue(unrelated.isFile());
        assertEquals("keep", new String(Files.readAllBytes(unrelated.toPath()), StandardCharsets.UTF_8));
    }

    /** 单条超大事件不能绕过总容量限制，也不会淘汰现有证据。 */
    @Test public void oversizedRecordIsRejectedBeforeRotation() throws Exception {
        RollingDiagnosticJournal journal = new RollingDiagnosticJournal(temporary.newFolder(), 4, 2);
        journal.append("old\n");
        try { journal.append("too large\n"); fail("Expected bounded record rejection"); }
        catch (IOException expected) { assertEquals("old\n", journal.snapshot().preview(100)); }
    }
}
