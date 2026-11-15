package github.vhoom.zhijian.util.diagnostics;

import java.time.Instant;

/**
 * 有界、可读的日志值；不依赖 Android，业务层只提供必要运行信息。
 *
 * 维护日期：2026年11月08日
 */
public final class DiagnosticEvent {
    private final long wallMs;
    private final long elapsedMs;
    private final DiagnosticLevel level;
    private final String area;
    private final String session;
    private final String message;
    private final String stack;
    private final String thread;

    /**
     * 固定事件发生时间及内容，排队写入不会改变它们。
     * @param wallMs 事件发生时的 UTC 墙钟毫秒
     * @param elapsedMs 同一次设备启动中的单调时间
     * @param level 诊断级别
     * @param area 事件所属模块
     * @param session 稳定会话标识，可为空
     * @param message 必要运行信息，不传应用内容或用户输入
     * @param stack 异常堆栈，可为空
     * @param thread 产生事件的线程名称
     */
    public DiagnosticEvent(long wallMs, long elapsedMs, DiagnosticLevel level, String area,
            String session, String message, String stack, String thread) {
        this.wallMs = wallMs;
        this.elapsedMs = elapsedMs;
        this.level = level;
        this.area = singleLine(area, 64);
        this.session = singleLine(session, 96);
        this.message = singleLine(message, 2048);
        this.stack = bounded(stack, 8192);
        this.thread = singleLine(thread, 64);
    }

    /** @return 可直接阅读、保留 Unicode 且不会伪造下一条记录的文本 */
    public String format() {
        return Instant.ofEpochMilli(wallMs) + " [" + level + "] elapsed_ms=" + elapsedMs
                + " thread=" + thread + " area=" + area
                + (session.isEmpty() ? "" : " session=" + session) + " | " + message
                + (stack.isEmpty() ? "" : "\n" + stack) + "\n";
    }

    /**
     * 将普通字段内的换行显式转义，避免一条事件伪装成多条。
     * @param value 原始字段，可为空
     * @param maximum 最大字符数
     * @return 经过预算限制的单行文本
     */
    private static String singleLine(String value, int maximum) {
        return bounded(value, maximum).replace("\r", "\\r").replace("\n", "\\n");
    }

    /**
     * 限制每条记录的体积，避免异常消息耗尽日志容量。
     * @param value 原始文本，可为空
     * @param maximum 最大字符数
     * @return 不拆开代理字符对的有界文本
     */
    private static String bounded(String value, int maximum) {
        if (value == null) return "";
        if (value.length() <= maximum) return value;
        int end = maximum;
        if (Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end) + " …[truncated]";
    }
}

