package github.vhoom.zhijian.data.model.settings;

import github.vhoom.zhijian.data.model.interception.InterceptionChallenge;
import github.vhoom.zhijian.data.model.interception.InterceptionMode;
import org.junit.Test;
import java.util.EnumSet;
import static org.junit.Assert.assertEquals;

/**
 * 修心允许独立选择挑战并保留最后一个选项，非交互强度不生成挑战。
 *
 * 维护日期：2026年11月14日
 */
public class ChallengeSelectionPolicyTest {
    /** 最后一个选项不能关闭或换成另一种；异常空集合仍回退按压。 */
    @Test public void repairKeepsLastChallengeAndRecoversEmptySelection() {
        for (InterceptionChallenge challenge : InterceptionChallenge.values()) {
            EnumSet<InterceptionChallenge> only = EnumSet.of(challenge);
            assertEquals(only, ChallengeSelectionPolicy.normalize(InterceptionMode.REPAIR, only));
            assertEquals(only, ChallengeSelectionPolicy.change(InterceptionMode.REPAIR, only, challenge, false));
        }
        assertEquals(EnumSet.of(InterceptionChallenge.BUTTON_HOLD), ChallengeSelectionPolicy.change(
                InterceptionMode.REPAIR, EnumSet.noneOf(InterceptionChallenge.class),
                InterceptionChallenge.BUTTON_HOLD, false));
    }

    /** 两种都开启时可关闭任意一种，再次开启可恢复两种；原集合保持不变。 */
    @Test public void eitherChallengeCanBeDisabledWhenAnotherRemains() {
        EnumSet<InterceptionChallenge> original = EnumSet.allOf(InterceptionChallenge.class);
        for (InterceptionChallenge challenge : InterceptionChallenge.values()) {
            EnumSet<InterceptionChallenge> expected = EnumSet.allOf(InterceptionChallenge.class);
            expected.remove(challenge);
            EnumSet<InterceptionChallenge> selected = ChallengeSelectionPolicy.change(
                    InterceptionMode.REPAIR, original, challenge, false);
            assertEquals(expected, selected);
            assertEquals(EnumSet.allOf(InterceptionChallenge.class), original);
            assertEquals(original, ChallengeSelectionPolicy.change(
                    InterceptionMode.REPAIR, selected, challenge, true));
        }
    }

    /** 无为和闭关不因残留配置或开关动作出现挑战。 */
    @Test public void otherLevelsHaveNoChallenges() {
        for (InterceptionMode mode : new InterceptionMode[]{InterceptionMode.WUWEI, InterceptionMode.SEAL}) {
            assertEquals(EnumSet.noneOf(InterceptionChallenge.class),
                    ChallengeSelectionPolicy.normalize(mode, EnumSet.allOf(InterceptionChallenge.class)));
            assertEquals(EnumSet.noneOf(InterceptionChallenge.class), ChallengeSelectionPolicy.change(
                    mode, EnumSet.noneOf(InterceptionChallenge.class), InterceptionChallenge.WORD_TRANSLATION, true));
        }
    }
}
