package github.vhoom.zhijian.domain.interception.word;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * 验证全词义项计数、可配置上限和原词库保留行为。
 *
 * 维护日期：2026年11月08日
 */
public class WordMeaningFormatterTest {
    /** 跨词性和单元格统一限制三个独立含义，原始五个含义保持完整。 */
    @Test
    public void limitsMeaningsAcrossPartsOfSpeechWithoutChangingTheEntry() {
        WordEntry entry = light();

        assertEquals("n. 光；灯；adj. 轻的", new WordMeaningFormatter(3).format(entry));
        assertEquals(3, entry.meanings.size());
        assertEquals("光；灯", entry.meanings.get(0).chinese);
        assertEquals("轻的；浅色的", entry.meanings.get(1).chinese);
        assertEquals("点燃", entry.meanings.get(2).chinese);
    }

    /** 修改上限只影响独立展示规则，不需要修改词条或存储。 */
    @Test
    public void supportsDifferentMeaningLimits() {
        assertEquals("n. 光", new WordMeaningFormatter(1).format(light()));
        assertEquals("n. 光；灯；adj. 轻的；浅色的；v. 点燃",
                new WordMeaningFormatter(5).format(light()));
    }

    /** 重复含义、空分号项不占额度，两种分号可混用且逗号保留。 */
    @Test
    public void skipsDuplicateAndEmptyMeaningsWhilePreservingOtherPunctuation() {
        WordEntry entry = new WordEntry("light",
                WordEntry.meaning(WordEntry.PartOfSpeech.NOUN, " 光 ;；灯；光 "),
                WordEntry.meaning(WordEntry.PartOfSpeech.NOUN, "灯"),
                WordEntry.meaning(WordEntry.PartOfSpeech.ADJECTIVE, "轻的，重量小的；浅色的"));

        assertEquals("n. 光；灯；adj. 轻的，重量小的", new WordMeaningFormatter(3).format(entry));
    }

    /** 同样的中文在不同词性下分别构成独立义项。 */
    @Test
    public void countsTheSameTranslationUnderDifferentPartsOfSpeechSeparately() {
        WordEntry entry = new WordEntry("rest",
                WordEntry.meaning(WordEntry.PartOfSpeech.NOUN, "休息"),
                WordEntry.meaning(WordEntry.PartOfSpeech.VERB, "休息"));

        assertEquals("n. 休息；v. 休息", new WordMeaningFormatter(3).format(entry));
    }

    /** 禁止零上限，避免产生无法出题的空标签。 */
    @Test(expected = IllegalArgumentException.class)
    public void rejectsAnEmptyMeaningLimit() {
        new WordMeaningFormatter(0);
    }

    /** 创建包含三个词性、五个独立含义的完整测试词条。 */
    private static WordEntry light() {
        return new WordEntry("light",
                WordEntry.meaning(WordEntry.PartOfSpeech.NOUN, "光；灯"),
                WordEntry.meaning(WordEntry.PartOfSpeech.ADJECTIVE, "轻的；浅色的"),
                WordEntry.meaning(WordEntry.PartOfSpeech.VERB, "点燃"));
    }
}
