package github.vhoom.zhijian.util.diagnostics;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 只管理自身命名的固定数量文件；所有操作由诊断队列串行调用。
 *
 * 维护日期：2026年11月08日
 */
public final class RollingDiagnosticJournal {
    private final File directory;
    private final int maximumBytes;
    private final int fileCount;

    /**
     * 创建有界日志策略，不在构造时执行磁盘操作。
     * @param directory 专用的应用私有日志目录
     * @param maximumBytes 每个文件的字节上限
     * @param fileCount 包括当前文件在内的最多文件数
     */
    public RollingDiagnosticJournal(File directory, int maximumBytes, int fileCount) {
        if (maximumBytes < 1 || fileCount < 1) throw new IllegalArgumentException("Invalid log budget");
        this.directory = directory;
        this.maximumBytes = maximumBytes;
        this.fileCount = fileCount;
    }

    /**
     * 写入完整 UTF-8 记录；超过单文件预算的记录拒绝写入。
     * @param record 已格式化且有界的事件
     * @throws IOException 创建、轮转或写入失败
     */
    public void append(String record) throws IOException {
        byte[] bytes = record.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maximumBytes) throw new IOException("Diagnostic record exceeds file budget");
        Files.createDirectories(directory.toPath());
        if (file(0).length() + bytes.length > maximumBytes) rotate();
        Files.write(file(0).toPath(), bytes, java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
    }

    /**
     * 取得有界文件副本，损坏或外部增大的文件也不会无限读取。
     * @return 按时间排列的不可变日志快照
     * @throws IOException 读取失败
     */
    public DiagnosticSnapshot snapshot() throws IOException {
        Map<String, byte[]> contents = new LinkedHashMap<>();
        for (int index = fileCount - 1; index >= 0; index--) {
            File source = file(index);
            if (!source.isFile()) continue;
            try (InputStream input = Files.newInputStream(source.toPath())) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int count;
                while (bytes.size() < maximumBytes && (count = input.read(buffer, 0,
                        Math.min(buffer.length, maximumBytes - bytes.size()))) != -1) {
                    bytes.write(buffer, 0, count);
                }
                contents.put(source.getName(), bytes.toByteArray());
            }
        }
        return new DiagnosticSnapshot(contents);
    }

    /**
     * 清空自身日志，不删除目录内任何其他文件。
     * @throws IOException 删除失败
     */
    public void clear() throws IOException {
        for (int index = 0; index < fileCount; index++) Files.deleteIfExists(file(index).toPath());
    }

    /**
     * 保留最近文件，只淘汰预算之外最旧的一份。
     * @throws IOException 文件操作失败
     */
    private void rotate() throws IOException {
        Files.deleteIfExists(file(fileCount - 1).toPath());
        for (int index = fileCount - 2; index >= 0; index--) {
            if (file(index).exists()) Files.move(file(index).toPath(), file(index + 1).toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 所有读写目标均由固定文件名构造，不接受外部路径。
     * @param index 当前文件为 0，越大越旧
     * @return 专用目录内的日志文件
     */
    private File file(int index) { return new File(directory, "diagnostics-" + index + ".log"); }
}

