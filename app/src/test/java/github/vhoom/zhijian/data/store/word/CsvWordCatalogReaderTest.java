package github.vhoom.zhijian.data.store.word;

import github.vhoom.zhijian.data.model.word.BuiltInWordCatalog;
import github.vhoom.zhijian.domain.interception.word.WordEntry;
import github.vhoom.zhijian.domain.interception.word.WordMeaningFormatter;
import github.vhoom.zhijian.domain.interception.word.WordQuizSession;
import github.vhoom.zhijian.data.model.settings.WordChallengeSettings;

import org.junit.Test;

import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 词库格式与真实资源回归；验证 CSV 适配边界、完整义项和可出题性。
 *
 * 维护日期：2026年11月08日
 */
public class CsvWordCatalogReaderTest {
    /** 超长的一行在分配完整内容前被拒绝，合法前缀不能让损坏词库生效。 */
    @Test public void oversizedPhysicalLineFailsWithResourceLimit() throws Exception {
        CsvWordCatalogReader.Result result = parse(base() + "large,n.," + "字".repeat(20_000));
        assertFalse(result.isValid());
        assertEquals(CsvWordCatalogReader.Problem.RESOURCE_LIMIT, result.issues.get(0).problem);
    }

    /** 未闭合引号不断追加小行也受逻辑记录预算限制，不形成反复全量解析。 */
    @Test public void endlessQuotedRecordCannotGrowWithoutBound() throws Exception {
        CsvWordCatalogReader.Result result = parse(base() + "broken,n.,\"" + "x\n".repeat(10_000));
        assertFalse(result.isValid());
        assertEquals(CsvWordCatalogReader.Problem.RESOURCE_LIMIT, result.issues.get(0).problem);
        assertEquals(8, result.issues.get(0).line);
    }

    /** 大量错误只保留有界的问题列表，避免检查报告自身占用过多内存。 */
    @Test public void invalidRowsHaveBoundedIssueReports() throws Exception {
        CsvWordCatalogReader.Result result = parse(base() + "bad,n.,\n".repeat(1_000));
        assertFalse(result.isValid());
        assertEquals(100, result.issues.size());
    }

    /** 多义记录追加不会反复复制已有义项，全部原始义项仍保留。 */
    @Test public void largeMeaningSetIsMergedWithoutTruncation() throws Exception {
        StringBuilder csv = new StringBuilder(base());
        for (int index = 0; index < 5_000; index++) csv.append("word0,v.,动作").append(index).append('\n');
        CsvWordCatalogReader.Result result = parse(csv.toString());
        assertTrue(result.isValid());
        assertEquals(5_001, result.entries.get(0).meanings.size());
    }

    /** 记录数足够但没有六种显示释义时在加载阶段失败，不留到答题页崩溃。 */
    @Test public void indistinguishableAnswerChoicesFailAtLoadTime() throws Exception {
        StringBuilder csv = new StringBuilder("english,part_of_speech,chinese\n");
        for (int index = 0; index < 6; index++) csv.append("word").append(index).append(",n.,相同释义\n");
        CsvWordCatalogReader.Result result = parse(csv.toString());
        assertFalse(result.isValid());
        assertEquals(CsvWordCatalogReader.Problem.ANSWER_CHOICES, result.issues.get(0).problem);
    }

    /** 全部内置资产必须通过同一检查器并能独立生成题目。 */
    @Test public void allBundledCatalogsValidateAndGenerateQuestions() throws Exception {
        for (BuiltInWordCatalog catalog : BuiltInWordCatalog.values()) {
            try (Reader reader = Files.newBufferedReader(Paths.get("src/main/assets", catalog.assetPath),
                    StandardCharsets.UTF_8)) {
                CsvWordCatalogReader.Result result = CsvWordCatalogReader.read(reader);
                assertTrue(catalog.assetPath + " must validate", result.isValid());
                assertTrue(result.entries.size() >= 6);
                WordQuizSession.start(result.entries, new WordChallengeSettings(15, 100L, catalog), catalog.id, 0L);
            }
        }
    }

    /** 同词不同词性保留全部义项；展示仍遵守整个单词最多三个含义。 */
    @Test public void repeatedEnglishMergesMeaningsWithoutTruncatingSource() throws Exception {
        CsvWordCatalogReader.Result result = parse(base() + "WORD0,v.,动作一；动作二\n");
        assertTrue(result.isValid());
        WordEntry word = result.entries.get(0);
        assertEquals(6, result.entries.size());
        assertEquals(2, word.meanings.size());
        assertEquals("名义零；名义一；名义二；名义三", word.meanings.get(0).chinese);
        assertEquals("n. 名义零；名义一；名义二", new WordMeaningFormatter(3).format(word));
    }

    /** UTF-8 BOM、CRLF、引号中的逗号、转义引号和跨行字段均可正确解析。 */
    @Test public void standardCsvQuotingAndBomAreSupported() throws Exception {
        CsvWordCatalogReader.Result result = parse("\uFEFF" + base().replace("\n", "\r\n")
                + "\"word,seven\",n.,\"释义,含逗号和\"\"引号\"\"\r\n第二行\"\r\n");
        assertTrue(result.isValid());
        assertEquals("word,seven", result.entries.get(6).english);
        assertEquals("释义,含逗号和\"引号\"\n第二行", result.entries.get(6).meanings.get(0).chinese);
    }

    /** 原始英文大小写保留，仅含分号的释义不能通过检查后使出题崩溃。 */
    @Test public void originalSpellingAndDisplayableMeaningsArePreserved() throws Exception {
        CsvWordCatalogReader.Result result = parse(base() + "I,pron.,我\ni,n.,字母\nempty,n.,； ;\n");
        assertEquals("I", result.entries.get(6).english);
        assertEquals(2, result.entries.get(6).meanings.size());
        assertEquals(CsvWordCatalogReader.Problem.EMPTY_FIELD, result.issues.get(0).problem);
        assertEquals(10, result.issues.get(0).line);
    }

    /** 每类记录问题带准确行号；完全重复记录与合法多义记录区别处理。 */
    @Test public void malformedRowsReportTheirPhysicalLines() throws Exception {
        CsvWordCatalogReader.Result result = parse(base()
                + "extra,n.,中文,多一列\nempty,n.,\nunknown,xyz.,中文\n"
                + "word1,n.,释义1\n");
        assertFalse(result.isValid());
        assertEquals(4, result.issues.size());
        assertEquals(CsvWordCatalogReader.Problem.COLUMN_COUNT, result.issues.get(0).problem);
        assertEquals(CsvWordCatalogReader.Problem.EMPTY_FIELD, result.issues.get(1).problem);
        assertEquals(CsvWordCatalogReader.Problem.UNKNOWN_PART_OF_SPEECH, result.issues.get(2).problem);
        assertEquals(CsvWordCatalogReader.Problem.DUPLICATE_ROW, result.issues.get(3).problem);
        for (int i = 0; i < 4; i++) assertEquals(8 + i, result.issues.get(i).line);
        assertEquals("3", result.issues.get(3).detail);
    }

    /** 不闭合或出现在未引用字段中的引号不能误读为正确记录。 */
    @Test public void brokenQuotesCannotPassValidation() throws Exception {
        CsvWordCatalogReader.Result unclosed = parse(base() + "broken,n.,\"没有闭合\n继续\n");
        assertEquals(CsvWordCatalogReader.Problem.CSV_SYNTAX, unclosed.issues.get(0).problem);
        assertEquals(8, unclosed.issues.get(0).line);
        CsvWordCatalogReader.Result unexpected = parse(base() + "broken,n.,未引用\"文本\n");
        assertEquals(CsvWordCatalogReader.Problem.CSV_SYNTAX, unexpected.issues.get(0).problem);
    }

    /** 表头错位、空词库和不足六个候选词的词库不能进入出题。 */
    @Test public void headerAndMinimumCandidatesAreValidated() throws Exception {
        assertEquals(CsvWordCatalogReader.Problem.HEADER, parse("chinese,english,part_of_speech\n").issues.get(0).problem);
        assertFalse(parse("").isValid());
        CsvWordCatalogReader.Result small = parse("english,part_of_speech,chinese\nonly,n.,唯一\n");
        assertEquals(CsvWordCatalogReader.Problem.TOO_SMALL, small.issues.get(0).problem);
    }

    /**
     * 构造测试用六个独立词条，不依赖内置资源内容。
     * @return 有表头且具有完整候选集的 CSV
     */
    private static String base() {
        StringBuilder csv = new StringBuilder("english,part_of_speech,chinese\n");
        csv.append("word0,n.,名义零；名义一；名义二；名义三\n");
        for (int i = 1; i < 6; i++) csv.append("word").append(i).append(",n.,释义").append(i).append('\n');
        return csv.toString();
    }

    /**
     * 使用内存流解析测试记录。
     * @param csv 待验证的 CSV 文本
     * @return 解析和检查结果
     * @throws Exception 文本解析失败时让测试失败
     */
    private static CsvWordCatalogReader.Result parse(String csv) throws Exception {
        return CsvWordCatalogReader.read(new StringReader(csv));
    }
}
