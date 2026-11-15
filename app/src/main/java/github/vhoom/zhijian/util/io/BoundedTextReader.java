package github.vhoom.zhijian.util.io;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.Reader;

/**
 * 对文本资源设置总字符及单行预算，超限前停止分配，调用方统一处理加载失败。
 *
 * 维护日期：2026年11月08日
 */
public final class BoundedTextReader implements Closeable {
    private final BufferedReader reader;
    private final long maxCharacters;
    private final int maxLineCharacters;
    private long charactersRead;

    /**
     * 包装调用方提供的文本流，关闭此对象同时关闭原流。
     * @param source 原始解码文本流
     * @param maxCharacters 整个文件允许读取的最大字符数
     * @param maxLineCharacters 一行允许保留的最大字符数
     */
    public BoundedTextReader(Reader source, long maxCharacters, int maxLineCharacters) {
        if (maxCharacters < 1 || maxLineCharacters < 1) throw new IllegalArgumentException("Invalid text limits");
        reader = new BufferedReader(source);
        this.maxCharacters = maxCharacters;
        this.maxLineCharacters = maxLineCharacters;
    }

    /**
     * 读取有界的一行，兼容 LF、CRLF 和 CR，不保留换行符。
     * @return 文件结束返回 null，否则返回本行文本
     * @throws IOException 超限、取消或底层读取失败
     */
    public String readLine() throws IOException {
        StringBuilder line = new StringBuilder();
        int character;
        while ((character = readCharacter()) != -1) {
            if (character == '\n') return line.toString();
            if (character == '\r') {
                reader.mark(1);
                int next = readCharacter();
                if (next != '\n' && next != -1) {
                    reader.reset();
                    charactersRead--;
                }
                return line.toString();
            }
            if (line.length() == maxLineCharacters) throw new LimitExceededException("Line is too long");
            line.append((char) character);
        }
        return line.length() == 0 ? null : line.toString();
    }

    /**
     * 读取有界完整文本，JSON 等配置可复用相同总量限制。
     * @return 保留原始字符的完整资源文本
     * @throws IOException 超限、取消或底层读取失败
     */
    public String readAll() throws IOException {
        StringBuilder text = new StringBuilder();
        int character;
        while ((character = readCharacter()) != -1) text.append((char) character);
        return text.toString();
    }

    /**
     * 在分配新内容之前执行读取预算和线程中断检查。
     * @return 下一个字符或 EOF
     * @throws IOException 超限、取消或底层读取失败
     */
    private int readCharacter() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Text loading interrupted");
        int character = reader.read();
        if (character != -1 && ++charactersRead > maxCharacters) {
            throw new LimitExceededException("Text resource is too large");
        }
        return character;
    }

    /** 释放包装的资源流。 @throws IOException 原流关闭失败 */
    @Override public void close() throws IOException { reader.close(); }

    /** 超出资源预算与普通 I/O 失败区分，便于给出明确诊断。 */
    public static final class LimitExceededException extends IOException {
        /** @param message 超出哪一种读取预算 */
        public LimitExceededException(String message) { super(message); }
    }
}
