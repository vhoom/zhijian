package github.vhoom.zhijian.data.store;

import android.content.Context;

import github.vhoom.zhijian.data.db.dao.WordDao;
import github.vhoom.zhijian.data.db.entity.word.WordEntity;
import github.vhoom.zhijian.data.db.entity.word.WordMeaningEntity;
import github.vhoom.zhijian.domain.interception.word.WordEntry;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 负责把资源词库首次导入 SQLite，并把关系表还原为不可变领域词条。
 *
 * 维护日期：2026年11月08日
 */
final class WordCatalogStore {
    private static final String SEED_ASSET = "word_catalog.tsv";
    private static final int MINIMUM_WORD_COUNT = 6;

    /** 工具类不创建实例。 */
    private WordCatalogStore() { }

    /**
     * 确保 SQLite 已有词库数据，并返回本次进程使用的只读快照。
     *
     * @param context 访问系统服务、资源或持久化文件的上下文
     * @param dao 负责当前数据表查询与写入的 DAO
     */
    static List<WordEntry> initialize(Context context, WordDao dao) {
        if (dao.wordCount() == 0) importSeed(context, dao);
        List<WordEntry> entries = load(dao);
        if (entries.size() < MINIMUM_WORD_COUNT) {
            throw new IllegalStateException("Word catalog must contain at least six entries");
        }
        return entries;
    }

    /**
     * 从 assets 中解析 TSV 种子并批量写入两张 SQLite 表。
     *
     * @param context 访问系统服务、资源或持久化文件的上下文
     * @param dao 负责当前数据表查询与写入的 DAO
     */
    private static void importSeed(Context context, WordDao dao) {
        List<WordEntity> words = new ArrayList<>();
        List<WordMeaningEntity> meanings = new ArrayList<>();
        Map<String, Integer> positions = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getAssets().open(SEED_ASSET), StandardCharsets.UTF_8))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (lineNumber == 1 || line.trim().isEmpty()) continue;
                String[] fields = line.split("\\t", -1);
                if (fields.length != 5) {
                    throw new IllegalStateException("Invalid word seed at line " + lineNumber);
                }
                int wordPosition = parsePosition(fields[0], lineNumber);
                int meaningPosition = parsePosition(fields[1], lineNumber);
                String english = required(fields[2], lineNumber);
                String partOfSpeech = required(fields[3], lineNumber);
                String chinese = required(fields[4], lineNumber);
                Integer previousPosition = positions.putIfAbsent(english, wordPosition);
                if (previousPosition == null) {
                    words.add(new WordEntity(english, wordPosition));
                } else if (previousPosition != wordPosition) {
                    throw new IllegalStateException("Conflicting word position at line " + lineNumber);
                }
                meanings.add(new WordMeaningEntity(english, meaningPosition,
                        partOfSpeech, chinese));
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Could not read word catalog seed", failure);
        }
        if (words.size() < MINIMUM_WORD_COUNT) {
            throw new IllegalStateException("Word seed must contain at least six entries");
        }
        dao.insertWords(words);
        dao.insertMeanings(meanings);
    }

    /**
     * 将正整数字段解析为 SQLite 中使用的稳定顺序。
     *
     * @param value 本次需要读取、转换或写入的值
     * @param lineNumber 资源种子中当前解析行的行号
     */
    private static int parsePosition(String value, int lineNumber) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0) throw new NumberFormatException("negative");
            return parsed;
        } catch (NumberFormatException invalid) {
            throw new IllegalStateException("Invalid position at line " + lineNumber, invalid);
        }
    }

    /**
     * 校验种子文本字段非空并清理首尾空白。
     *
     * @param value 本次需要读取、转换或写入的值
     * @param lineNumber 资源种子中当前解析行的行号
     */
    private static String required(String value, int lineNumber) {
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalStateException("Empty word seed field at line " + lineNumber);
        }
        return trimmed;
    }

    /**
     * 从 SQLite 的单词与义项表组装不可变词条列表。
     *
     * @param dao 负责当前数据表查询与写入的 DAO
     */
    private static List<WordEntry> load(WordDao dao) {
        Map<String, List<WordEntry.Meaning>> meaningsByWord = new LinkedHashMap<>();
        for (WordMeaningEntity row : dao.meanings()) {
            WordEntry.PartOfSpeech partOfSpeech;
            try {
                partOfSpeech = WordEntry.PartOfSpeech.valueOf(row.partOfSpeech);
            } catch (IllegalArgumentException invalid) {
                throw new IllegalStateException("Unknown part of speech: " + row.partOfSpeech,
                        invalid);
            }
            meaningsByWord.computeIfAbsent(row.english, ignored -> new ArrayList<>())
                    .add(WordEntry.meaning(partOfSpeech, row.chinese));
        }
        List<WordEntry> entries = new ArrayList<>();
        for (WordEntity row : dao.words()) {
            List<WordEntry.Meaning> meanings = meaningsByWord.get(row.english);
            if (meanings == null || meanings.isEmpty()) {
                throw new IllegalStateException("Word has no meanings: " + row.english);
            }
            entries.add(new WordEntry(row.english,
                    meanings.toArray(new WordEntry.Meaning[0])));
        }
        return Collections.unmodifiableList(entries);
    }
}
