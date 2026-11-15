package github.vhoom.zhijian.util.diagnostics;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.Assert.*;

/**
 * 验证真正可读取的 ZIP、中文内容与导出文件范围。
 *
 * 维护日期：2026年11月14日
 */
public class DiagnosticArchiveTest {
    /** 环境说明和固定日志文件完整写入 ZIP，导出不只验证无异常。 */
    @Test public void archiveContainsReadableEnvironmentAndUnicodeLogs() throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("diagnostics-0.log", "页面已显示\n".getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        DiagnosticArchive.write(output, new DiagnosticSnapshot(files), "本地诊断\nbuild=release\n");
        Map<String, String> contents = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(output.toByteArray()))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                contents.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        assertEquals(2, contents.size());
        assertEquals("本地诊断\nbuild=release\n", contents.get("environment.txt"));
        assertEquals("页面已显示\n", contents.get("logs/diagnostics-0.log"));
    }

    /** 归档不允许日志快照中的任意路径冒充日志文件。 */
    @Test public void archiveRejectsUnexpectedFilePaths() throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("../zhijian.db", new byte[0]);
        try {
            DiagnosticArchive.write(new ByteArrayOutputStream(), new DiagnosticSnapshot(files), "local");
            fail("Expected filename rejection");
        } catch (IOException expected) { assertTrue(expected.getMessage().contains("filename")); }
    }
}
