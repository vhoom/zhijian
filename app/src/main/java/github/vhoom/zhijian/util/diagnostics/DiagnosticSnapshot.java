package github.vhoom.zhijian.util.diagnostics;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 日志队列在一个边界生成的副本，后续轮转和清空不影响查看或导出。
 *
 * 维护日期：2026年11月08日
 */
public final class DiagnosticSnapshot {
    private final Map<String, byte[]> entries;
    public final long sizeBytes;

    /**
     * 复制日志文件，保证快照不持有可变文件或调用方数组。
     * @param files 按旧到新排列的日志文件及内容
     */
    public DiagnosticSnapshot(Map<String, byte[]> files) {
        entries = new LinkedHashMap<>();
        long size = 0L;
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            byte[] bytes = file.getValue().clone();
            entries.put(file.getKey(), bytes);
            size += bytes.length;
        }
        sizeBytes = size;
    }

    /** @return 用于导出的独立文件副本，调用方不能改变当前快照 */
    public Map<String, byte[]> files() {
        Map<String, byte[]> copy = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            copy.put(entry.getKey(), entry.getValue().clone());
        }
        return Collections.unmodifiableMap(copy);
    }

    /**
     * 只返回最近文本，查看页面不会一次渲染整个日志历史。
     * @param maximumChars 最大显示字符数
     * @return 最近日志；空快照返回空串
     */
    public String preview(int maximumChars) {
        if (maximumChars < 1) return "";
        StringBuilder result = new StringBuilder();
        for (byte[] file : entries.values()) {
            result.append(new String(file, StandardCharsets.UTF_8));
            if (result.length() > maximumChars) {
                int start = result.length() - maximumChars;
                if (Character.isLowSurrogate(result.charAt(start))) start++;
                result.delete(0, start);
            }
        }
        return result.toString();
    }
}

