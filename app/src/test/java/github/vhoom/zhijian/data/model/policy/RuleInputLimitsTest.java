package github.vhoom.zhijian.data.model.policy;

import org.junit.Test;

import static github.vhoom.zhijian.data.model.policy.RuleInputLimits.Status.INVALID;
import static github.vhoom.zhijian.data.model.policy.RuleInputLimits.Status.TOO_LARGE;
import static github.vhoom.zhijian.data.model.policy.RuleInputLimits.Status.VALID;
import static org.junit.Assert.assertEquals;

/**
 * 手动规则输入的格式、绝对上界、时段上界和明确零值回归。
 *
 * 维护日期：2026年11月13日
 */
public class RuleInputLimitsTest {
    /** 空名称沿用自动命名，名称长度按 Unicode 字符计算并忽略首尾空格。 */
    @Test
    public void nameBoundsUseTrimmedUnicodeCharacters() {
        assertEquals(VALID, RuleInputLimits.checkName("   "));
        assertEquals(VALID, RuleInputLimits.checkName("  " + repeatedEmoji(20) + "  "));
        assertEquals(INVALID, RuleInputLimits.checkName(repeatedEmoji(21)));
        assertEquals(INVALID, RuleInputLimits.checkName(null));
    }

    /** 全天限时明确填写零可保存，启用后的空输入仍需要修改。 */
    @Test
    public void dailyLimitDistinguishesExplicitZeroFromEmptyInput() {
        RuleInputLimits.Range range = RuleInputLimits.usage(RuleType.DAILY_LIMIT, 0L);
        assertEquals(VALID, range.checkText("0", true));
        assertEquals(VALID, range.checkText("0.0", true));
        assertEquals(INVALID, range.checkText("", true));
        assertEquals(INVALID, range.checkText("  ", true));
        assertEquals(VALID, range.checkText("", false));
        assertEquals(VALID, range.checkText("720", true));
        assertEquals(TOO_LARGE, range.checkText("720.1", true));
    }

    /** 次数只接受整数；超大整数归为过大，格式错误保留无效分类。 */
    @Test
    public void countLimitsRejectDecimalsAndClassifyOversizedIntegers() {
        RuleInputLimits.Range range = RuleInputLimits.count();
        assertEquals(VALID, range.checkText("1", true));
        assertEquals(VALID, range.checkText("99", true));
        for (String text : new String[]{"", "0", "-1", "+1", "1.0", "1,5", "NaN"}) {
            assertEquals(text, INVALID, range.checkText(text, true));
        }
        for (String text : new String[]{"100", "2147483648", "999999999999999999999999999"}) {
            assertEquals(text, TOO_LARGE, range.checkText(text, true));
        }
    }

    /** 分钟允许一位小数与本地化逗号；粘贴的额外精度、符号和指数不能被默默转化。 */
    @Test
    public void minuteFormatIsValidatedBeforeItsMagnitude() {
        RuleInputLimits.Range range = RuleInputLimits.rest();
        for (String text : new String[]{"0.1", "1.5", "1,5", " 1.5 "}) {
            assertEquals(text, VALID, range.checkText(text, true));
        }
        for (String text : new String[]{".5", "1.", "1.00", "12.34", "9999.99", "-1", "+1", "1e2", "Infinity"}) {
            assertEquals(text, INVALID, range.checkText(text, true));
        }
        assertEquals(TOO_LARGE, range.checkText("999999999999999999999999.9", true));
    }

    /** 频率窗口有独立上界，启用时零值无效。 */
    @Test
    public void frequencyWindowRequiresPositiveMinutesAndUsesItsOwnCeiling() {
        RuleInputLimits.Range range = RuleInputLimits.usage(RuleType.ROLLING_COUNT, 0L);
        assertEquals(INVALID, range.checkText("0", true));
        assertEquals(VALID, range.checkText("0.1", true));
        assertEquals(VALID, range.checkText("60", true));
        assertEquals(TOO_LARGE, range.checkText("60.1", true));
    }

    /** 连续使用和休息可取统一上界，破例放行使用严格上界。 */
    @Test
    public void restAndUsageIncludeTheCeilingWhileTemporaryPassExcludesIt() {
        for (RuleInputLimits.Range range : new RuleInputLimits.Range[]{
                RuleInputLimits.rest(), RuleInputLimits.usage(RuleType.CONTINUOUS, 0L)}) {
            assertEquals(INVALID, range.checkText("0", true));
            assertEquals(VALID, range.checkText("0.1", true));
            assertEquals(VALID, range.checkText("720", true));
            assertEquals(TOO_LARGE, range.checkText("720.1", true));
        }
        assertEquals(INVALID, RuleInputLimits.pass().checkText("0", true));
        assertEquals(VALID, RuleInputLimits.pass().checkText("719.9", true));
        assertEquals(TOO_LARGE, RuleInputLimits.pass().checkText("720", true));
    }

    /** 时段限时可用满时段，连续提醒必须提前触发，长时段仍受绝对上界约束。 */
    @Test
    public void windowLimitsCombineRelativeAndAbsoluteCeilings() {
        long shortWindow = 90L * 60_000L;
        assertEquals(VALID, RuleInputLimits.usage(RuleType.WINDOW_LIMIT, shortWindow).checkText("90", true));
        assertEquals(TOO_LARGE, RuleInputLimits.usage(RuleType.WINDOW_LIMIT, shortWindow).checkText("90.1", true));
        assertEquals(VALID, RuleInputLimits.usage(RuleType.WINDOW_CONTINUOUS, shortWindow).checkText("89.9", true));
        assertEquals(TOO_LARGE, RuleInputLimits.usage(RuleType.WINDOW_CONTINUOUS, shortWindow).checkText("90", true));
        assertEquals(TOO_LARGE, RuleInputLimits.usage(RuleType.WINDOW_CONTINUOUS,
                720L * 60_000L).checkText("720", true));
        for (RuleType type : new RuleType[]{RuleType.WINDOW_LIMIT, RuleType.WINDOW_CONTINUOUS}) {
            RuleInputLimits.Range range = RuleInputLimits.usage(type, 780L * 60_000L);
            assertEquals(VALID, range.checkText("720", true));
            assertEquals(TOO_LARGE, range.checkText("720.1", true));
        }
    }

    /** 关闭的参数可保留零或空值，已经填写的过大值仍不能保存。 */
    @Test
    public void inactiveInputsAllowUnsetValuesWithoutRemovingTheirCeilings() {
        for (RuleInputLimits.Range range : new RuleInputLimits.Range[]{RuleInputLimits.count(),
                RuleInputLimits.rest(), RuleInputLimits.pass(), RuleInputLimits.usage(RuleType.ROLLING_COUNT, 0L)}) {
            assertEquals(VALID, range.checkText("", false));
            assertEquals(VALID, range.checkText("0", false));
            assertEquals(TOO_LARGE, range.checkText("9999", false));
        }
        assertEquals(INVALID, RuleInputLimits.rest().checkValue(1_000L, true));
        assertEquals(VALID, RuleInputLimits.rest().checkValue(6_000L, true));
    }

    /** @param count 需要生成的 Unicode 字符数 @return 用于验证代理对计数的名称 */
    private static String repeatedEmoji(int count) {
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < count; index++) text.append("\uD83C\uDF3F");
        return text.toString();
    }
}
