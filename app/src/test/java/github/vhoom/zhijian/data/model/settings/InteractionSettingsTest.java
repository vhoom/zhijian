package github.vhoom.zhijian.data.model.settings;

import github.vhoom.zhijian.data.model.interception.InterceptionChallenge;
import github.vhoom.zhijian.data.model.interception.InterceptionMode;
import org.junit.Test;
import java.util.EnumSet;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * 验证挑战选择只使用启用集合，快照不会被调用方修改。
 *
 * 维护日期：2026年11月08日
 */
public class InteractionSettingsTest {
    /** 无论种子如何变化，都只能选择唯一启用的挑战。 */
    @Test public void selectsOnlyEnabledChallenge() {
        for (InterceptionChallenge challenge : InterceptionChallenge.values()) {
            InteractionSettings settings = create(EnumSet.of(challenge));
            for (int index = 0; index < 30; index++) {
                assertEquals(challenge, settings.selectChallenge("session-" + index));
            }
        }
    }

    /** 或异常的空启用集合必须回退按压挑战。 */
    @Test public void allDisabledDefaultsToHoldChallenge() {
        assertEquals(InterceptionChallenge.BUTTON_HOLD,
                create(EnumSet.noneOf(InterceptionChallenge.class)).selectChallenge("empty"));
    }

    /** 创建快照后修改源集合不得改变本会话的挑战。 */
    @Test public void snapshotCopiesEnabledSet() {
        EnumSet<InterceptionChallenge> original = EnumSet.of(InterceptionChallenge.BUTTON_HOLD);
        InteractionSettings snapshot = create(original);
        original.clear();
        assertEquals(InterceptionChallenge.BUTTON_HOLD, snapshot.selectChallenge("stable"));
    }

    /** 关闭整个阻碍流程时不选挑战，已保存的挑战集合和参数仍可供下次开启使用。 */
    @Test public void disabledObstaclesRetainConfigurationWithoutSelectingAChallenge() {
        InteractionSettings original = create(EnumSet.allOf(InterceptionChallenge.class));
        InteractionSettings disabled = new InteractionSettings(original.enabledChallenges, original.holdRange,
                original.word, new InterceptionOptions(false, false));
        assertNull(disabled.selectChallenge("disabled"));
        assertEquals(original.enabledChallenges, disabled.enabledChallenges);
        assertEquals(original.holdRange, disabled.holdRange);
        assertEquals(original.word, disabled.word);
    }

    /**
     * 创建最短有效配置。
     * @param enabled 当前启用挑战集合
     * @return 不可变交互配置
     */
    private InteractionSettings create(EnumSet<InterceptionChallenge> enabled) {
        return new InteractionSettings(enabled, new SecondsRange(100L, 100L),
                new WordChallengeSettings(1, 100L), InterceptionOptions.defaultsFor(InterceptionMode.REPAIR));
    }
}
