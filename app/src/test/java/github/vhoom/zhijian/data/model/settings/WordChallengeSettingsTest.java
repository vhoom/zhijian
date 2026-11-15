package github.vhoom.zhijian.data.model.settings;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 单词摩擦配置的纯数据回归测试。
 *
 * 维护日期：2026年11月08日
 */
public class WordChallengeSettingsTest {
    /** 出厂配置是十五题，每题两秒，设置页与拦截页必须都能接受。 */
    @Test
    public void factoryFifteenQuestionsAreSupported() {
        WordChallengeSettings settings = new WordChallengeSettings(15, 2_000L);

        assertEquals(30_000L, settings.minimumDurationMillis());
    }

    /** 总最低摩擦时长应由题量与每题停留时间相乘得到。 */
    @Test
    public void minimumDurationMultipliesCountAndDwell() {
        WordChallengeSettings settings = new WordChallengeSettings(3, 5_000L);

        assertEquals(15_000L, settings.minimumDurationMillis());
    }

    /** 非正题量应立即拒绝，不能构造空挑战。 */
    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonPositiveQuestionCount() {
        new WordChallengeSettings(0, 5_000L);
    }

    /** 默认十五题不限制用户题量，保底时间使用长整数计算。 */
    @Test
    public void supportsQuestionCountsAboveFactoryDefault() {
        WordChallengeSettings settings = new WordChallengeSettings(2_000, 1_500L);
        assertEquals(3_000_000L, settings.minimumDurationMillis());
    }

    /** 题量上限来自所选词库，超限输入包括超过 int 范围的值。 */
    @Test
    public void questionCountUsesSelectedCatalogCapacity() {
        assertTrue(WordChallengeSettings.isValidQuestionCount(1, 25));
        assertTrue(WordChallengeSettings.isValidQuestionCount(25, 25));
        assertFalse(WordChallengeSettings.isValidQuestionCount(26, 25));
        assertFalse(WordChallengeSettings.isValidQuestionCount(0, 25));
        assertFalse(WordChallengeSettings.isValidQuestionCount(1, 0));
        assertFalse(WordChallengeSettings.isValidQuestionCount(2_147_483_648L, 25));
    }
}
