package github.vhoom.zhijian.data.db.entity.word;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * SQLite 中的单词主体；释义按外键保存在 word_meaning 表。
 *
 * 维护日期：2026年11月08日
 */
@Entity(tableName = "word_entry", indices = {@Index(value = "position", unique = true)})
public class WordEntity {
    @PrimaryKey
    @NonNull
    public String english;
    public int position;

    /**
     * 构造一个具有稳定展示顺序的英文单词行。
     *
     * @param english 词条的英文文本
     * @param position 条目在当前列表中的位置
     */
    public WordEntity(@NonNull String english, int position) {
        this.english = english;
        this.position = position;
    }
}
