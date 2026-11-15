package github.vhoom.zhijian.data.db.entity.word;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;

/**
 * SQLite 中的单个词性释义；一个英文单词可以按 position 拥有多个义项。
 *
 * 维护日期：2026年11月08日
 */
@Entity(tableName = "word_meaning", primaryKeys = {"english", "position"},
        foreignKeys = @ForeignKey(entity = WordEntity.class, parentColumns = "english",
                childColumns = "english", onDelete = ForeignKey.CASCADE),
        indices = {@Index("english")})
public class WordMeaningEntity {
    @NonNull
    public String english;
    public int position;
    @NonNull
    public String partOfSpeech;
    @NonNull
    public String chinese;

    /**
     * 构造一个归属于指定单词的有序中文义项。
     *
     * @param english 词条的英文文本
     * @param position 条目在当前列表中的位置
     * @param partOfSpeech 该义项对应的词性
     * @param chinese 词条对应的中文释义
     */
    public WordMeaningEntity(@NonNull String english, int position,
            @NonNull String partOfSpeech, @NonNull String chinese) {
        this.english = english;
        this.position = position;
        this.partOfSpeech = partOfSpeech;
        this.chinese = chinese;
    }
}
