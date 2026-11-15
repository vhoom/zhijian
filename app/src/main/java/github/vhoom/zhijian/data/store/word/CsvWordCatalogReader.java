package github.vhoom.zhijian.data.store.word;

import github.vhoom.zhijian.domain.interception.word.WordEntry;
import github.vhoom.zhijian.domain.interception.word.WordMeaningFormatter;
import github.vhoom.zhijian.util.io.BoundedTextReader;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

/**
 * CSV 到领域词条的适配器；加载与检查共用规则，保留全部义项而不限制展示数量。
 *
 * 维护日期：2026年11月08日
 */
public final class CsvWordCatalogReader {
    private static final List<String> HEADER = Arrays.asList("english", "part_of_speech", "chinese");
    private static final int MINIMUM_WORDS = 6;
    private static final int MAX_RECORD_CHARACTERS = 16_384;
    private static final long MAX_FILE_CHARACTERS = 8L * 1024L * 1024L;
    private static final int MAX_RECORDS = 100_000;
    private static final int MAX_ISSUES = 100;

    /** 工具适配器不创建实例。 */
    private CsvWordCatalogReader() { }

    /** 可由界面翻译的检查原因，解析层不依赖 Android 视图或资源。 */
    public enum Problem { HEADER, CSV_SYNTAX, COLUMN_COUNT, EMPTY_FIELD, UNKNOWN_PART_OF_SPEECH,
        DUPLICATE_ROW, TOO_SMALL, READ_ERROR, RESOURCE_LIMIT, ANSWER_CHOICES }

    /** 一条检查问题的起始物理行号与补充信息；零行号表示整个文件的问题。 */
    public static final class Issue {
        public final int line;
        public final Problem problem;
        public final String detail;

        /**
         * 创建可显示在检查报告中的问题。
         * @param line CSV 记录开始的行号
         * @param problem 稳定检查原因
         * @param detail 字段名、未知词性或首次出现的行号
         */
        public Issue(int line, Problem problem, String detail) {
            this.line = line;
            this.problem = problem;
            this.detail = detail;
        }
    }

    /** 不可变检查结果，只有通过检查的词库可以用于出题。 */
    public static final class Result {
        public final List<WordEntry> entries;
        public final List<Issue> issues;

        /**
         * 复制解析结果，避免后台检查与页面读取共享可变集合。
         * @param entries 按首次出现顺序合并的词条
         * @param issues 检查发现的全部问题
         */
        public Result(List<WordEntry> entries, List<Issue> issues) {
            this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
            this.issues = Collections.unmodifiableList(new ArrayList<>(issues));
        }

        /** 返回词库是否通过全部格式与出题条件检查。 */
        public boolean isValid() { return issues.isEmpty(); }
    }

    /**
     * 读取 UTF-8 解码后的 CSV，支持 BOM、引号逗号、双引号转义和跨行字段。
     * @param source 调用方负责关闭的文本流
     * @return 合并后的完整词条与带行号的问题
     * @throws IOException 文本流无法读取时由仓库转换为文件检查问题
     */
    public static Result read(Reader source) throws IOException {
        BoundedTextReader reader = new BoundedTextReader(source, MAX_FILE_CHARACTERS, MAX_RECORD_CHARACTERS);
        Map<String, WordBuilder> words = new LinkedHashMap<>();
        Map<String, Integer> firstRows = new LinkedHashMap<>();
        List<Issue> issues = new ArrayList<>();
        StringBuilder record = new StringBuilder();
        boolean headerSeen = false;
        int lineNumber = 0;
        int recordLine = 0;
        int records = 0;
        boolean quoteOpen = false;
        String line;
        try {
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (lineNumber == 1 && line.startsWith("\uFEFF")) line = line.substring(1);
                if (record.length() == 0) {
                    if (line.trim().isEmpty()) continue;
                    recordLine = lineNumber;
                } else {
                    record.append('\n');
                }
                if (line.length() > MAX_RECORD_CHARACTERS - record.length()) {
                    issues.add(new Issue(recordLine, Problem.RESOURCE_LIMIT, "Record is too long"));
                    record.setLength(0);
                    break;
                }
                record.append(line);
                // 只扫描新读取的字符；未闭合字段不再每行重解析整个累计记录。
                for (int index = 0; index < line.length(); index++) {
                    if (line.charAt(index) == '"') quoteOpen = !quoteOpen;
                }
                if (quoteOpen) continue;
                List<String> fields;
                try {
                    fields = splitRecord(record.toString());
                } catch (IllegalArgumentException malformed) {
                    issues.add(new Issue(recordLine, Problem.CSV_SYNTAX, ""));
                    record.setLength(0);
                    if (issues.size() >= MAX_ISSUES) break;
                    continue;
                }
                if (fields == null) continue;
                record.setLength(0);
                if (!headerSeen) {
                    headerSeen = true;
                    if (!HEADER.equals(fields)) {
                        issues.add(new Issue(recordLine, Problem.HEADER, ""));
                        return new Result(Collections.emptyList(), issues);
                    }
                } else {
                    if (++records > MAX_RECORDS) {
                        issues.add(new Issue(recordLine, Problem.RESOURCE_LIMIT, "Too many records"));
                        break;
                    }
                    appendWord(fields, recordLine, words, firstRows, issues);
                    if (issues.size() >= MAX_ISSUES) break;
                }
            }
            } catch (BoundedTextReader.LimitExceededException limit) {
                issues.add(new Issue(Math.max(1, lineNumber + 1), Problem.RESOURCE_LIMIT, limit.getMessage()));
                record.setLength(0);
        }
        if (record.length() > 0) issues.add(new Issue(recordLine, Problem.CSV_SYNTAX, ""));
        if (!headerSeen) issues.add(new Issue(1, Problem.HEADER, ""));
        List<WordEntry> entries = new ArrayList<>();
        for (WordBuilder word : words.values()) entries.add(word.build());
        if (entries.size() < MINIMUM_WORDS) issues.add(new Issue(0, Problem.TOO_SMALL, ""));
        else if (issues.isEmpty()) {
            Set<String> answerLabels = new HashSet<>();
            WordMeaningFormatter formatter = new WordMeaningFormatter(WordMeaningFormatter.DEFAULT_MAX_MEANING_COUNT);
            for (WordEntry entry : entries) {
                answerLabels.add(formatter.format(entry));
                if (answerLabels.size() >= MINIMUM_WORDS) break;
            }
            if (answerLabels.size() < MINIMUM_WORDS) {
                issues.add(new Issue(0, Problem.ANSWER_CHOICES, "Not enough distinct answer labels"));
            }
        }
        return new Result(entries, issues);
    }

    /**
     * 拆分一条逻辑记录；尚未闭合的引号表示继续读取下一物理行。
     * @param record 包含可能跨行字段的逻辑记录
     * @return 三列或其他列的字段，未闭合引号返回 null
     */
    private static List<String> splitRecord(String record) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean closedQuote = false;
        for (int i = 0; i < record.length(); i++) {
            char character = record.charAt(i);
            if (quoted) {
                if (character != '"') field.append(character);
                else if (i + 1 < record.length() && record.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else {
                    quoted = false;
                    closedQuote = true;
                }
            } else if (character == ',') {
                fields.add(field.toString().trim());
                field.setLength(0);
                closedQuote = false;
            } else if (closedQuote) {
                if (!Character.isWhitespace(character)) throw new IllegalArgumentException("After quote");
            } else if (character == '"') {
                if (!field.toString().trim().isEmpty()) throw new IllegalArgumentException("Inside field");
                field.setLength(0);
                quoted = true;
            } else {
                field.append(character);
            }
        }
        if (quoted) return null;
        fields.add(field.toString().trim());
        return fields;
    }

    /**
     * 校验一条记录，同词不同义项合法，完全重复的记录指出首次出现行号。
     * @param fields 已解析的 CSV 字段
     * @param line 当前记录起始行号
     * @param words 按英文合并的完整义项
     * @param firstRows 记录首次出现的行号
     * @param issues 接收检查问题的集合
     */
    private static void appendWord(List<String> fields, int line,
            Map<String, WordBuilder> words, Map<String, Integer> firstRows, List<Issue> issues) {
        if (fields.size() != HEADER.size()) {
            issues.add(new Issue(line, Problem.COLUMN_COUNT, ""));
            return;
        }
        for (int i = 0; i < fields.size(); i++) {
            if (fields.get(i).isEmpty()) {
                issues.add(new Issue(line, Problem.EMPTY_FIELD, HEADER.get(i)));
                return;
            }
        }
        WordEntry.PartOfSpeech part = null;
        for (WordEntry.PartOfSpeech candidate : WordEntry.PartOfSpeech.values()) {
            if (candidate.abbreviation.equals(fields.get(1))) part = candidate;
        }
        if (part == null) {
            issues.add(new Issue(line, Problem.UNKNOWN_PART_OF_SPEECH, fields.get(1)));
            return;
        }
        String english = fields.get(0);
        String normalizedEnglish = english.toLowerCase(Locale.ROOT);
        String chinese = fields.get(2);
        if (Arrays.stream(chinese.split("[;；]", -1)).allMatch(value -> value.trim().isEmpty())) {
            issues.add(new Issue(line, Problem.EMPTY_FIELD, "chinese"));
            return;
        }
        String key = normalizedEnglish + '\u0000' + part.name() + '\u0000' + chinese;
        Integer firstLine = firstRows.putIfAbsent(key, line);
        if (firstLine != null) {
            issues.add(new Issue(line, Problem.DUPLICATE_ROW, firstLine.toString()));
            return;
        }
        WordBuilder word = words.computeIfAbsent(normalizedEnglish, ignored -> new WordBuilder(english));
        word.meanings.add(WordEntry.meaning(part, chinese));
    }

    /** 解析时追加义项，结束后一次构造不可变词条，避免大量同词义项反复复制。 */
    private static final class WordBuilder {
        private final String english;
        private final List<WordEntry.Meaning> meanings = new ArrayList<>();

        /** @param english 首次出现的原始拼写 */
        private WordBuilder(String english) { this.english = english; }

        /** @return 不与解析草稿共享可变集合的完整词条 */
        private WordEntry build() {
            return new WordEntry(english, meanings.toArray(new WordEntry.Meaning[0]));
        }
    }
}
