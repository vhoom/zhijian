package github.vhoom.zhijian.util.diagnostics;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 导出快照的 ZIP Adapter；只包含环境说明和固定命名的本地日志。
 *
 * 维护日期：2026年11月08日
 */
public final class DiagnosticArchive {
    /** 静态导出工具不允许实例化。 */
    private DiagnosticArchive() { }

    /**
     * 写入并关闭输出流，不读取数据库或其他应用文件。
     * @param output 用户主动选择的文档输出流，此方法负责关闭
     * @param snapshot 已冻结的日志副本
     * @param environment 当前应用、系统和权限说明
     * @throws IOException 文档写入失败
     */
    public static void write(OutputStream output, DiagnosticSnapshot snapshot, String environment)
            throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            entry(zip, "environment.txt", environment.getBytes(StandardCharsets.UTF_8));
            for (Map.Entry<String, byte[]> file : snapshot.files().entrySet()) {
                if (!file.getKey().matches("diagnostics-[0-9]+\\.log")) {
                    throw new IOException("Unexpected diagnostic filename");
                }
                entry(zip, "logs/" + file.getKey(), file.getValue());
            }
        }
    }

    /**
     * 写入一个固定名称的条目。
     * @param zip 正在写入的归档
     * @param name 归档内的安全相对路径
     * @param bytes UTF-8 文本或日志内容
     * @throws IOException 写入失败
     */
    private static void entry(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }
}

