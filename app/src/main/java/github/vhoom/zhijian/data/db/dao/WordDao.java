package github.vhoom.zhijian.data.db.dao;

import github.vhoom.zhijian.data.db.entity.word.WordEntity;
import github.vhoom.zhijian.data.db.entity.word.WordMeaningEntity;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

/**
 * 单词与释义的 SQLite 读写接口。
 *
 * 维护日期：2026年11月08日
 */
@Dao
public interface WordDao {
    /** 返回数据库中的单词数量，用于判断是否需要首次导入。 */
    @Query("SELECT COUNT(*) FROM word_entry")
    int wordCount();

    /** 按稳定位置读取全部单词主体。 */
    @Query("SELECT * FROM word_entry ORDER BY position, english")
    List<WordEntity> words();

    /** 按单词和义项位置读取全部释义。 */
    @Query("SELECT * FROM word_meaning ORDER BY english, position")
    List<WordMeaningEntity> meanings();

    /**
     * 批量写入首次导入的单词主体。
     *
     * @param words 按稳定顺序排列的词条列表
     */
    @Insert
    void insertWords(List<WordEntity> words);

    /**
     * 批量写入首次导入的词性释义。
     *
     * @param meanings 词条对应的释义集合
     */
    @Insert
    void insertMeanings(List<WordMeaningEntity> meanings);
}
