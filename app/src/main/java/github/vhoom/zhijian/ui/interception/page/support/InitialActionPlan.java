package github.vhoom.zhijian.ui.interception.page.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 无为、修心共用的不可变首屏方案；无为页面互斥抽样，同会话重建保持原结果。
 *
 * 维护日期：2026年11月09日
 */
public final class InitialActionPlan {
    /** 控件交互方式，与它最终承担的路由无关。 */
    public enum Kind { BUTTON, SLIDER }

    /** 文案分类；盲盒文案和外观不能受预选路由影响。 */
    public enum Copy { EXIT, CONTINUE, EXIT_VARIANT, BLIND_BOX }

    /** 一个控件的交互、路由和稳定文案种子。 */
    public static final class Action {
        public final Kind kind;
        public final boolean exit;
        public final Copy copy;
        public final int copyVariant;

        /** @param kind 交互方式 @param exit 是否退出 @param copy 文案分类 @param copyVariant 文案种子 */
        private Action(Kind kind, boolean exit, Copy copy, int copyVariant) {
            this.kind = kind;
            this.exit = exit;
            this.copy = copy;
            this.copyVariant = copyVariant;
        }
    }

    /** 是否展示双退出页面；与单滑块盲盒互斥。 */
    public final boolean doubleExit;
    /** 是否展示单滑块盲盒页面。 */
    public final boolean blindBox;
    public final List<Action> actions;

    /** 固定首屏只提供两个普通按钮：继续在上，退出在下。 */
    private InitialActionPlan() {
        doubleExit = false;
        blindBox = false;
        actions = Collections.unmodifiableList(Arrays.asList(
                new Action(Kind.BUTTON, false, Copy.CONTINUE, 0),
                new Action(Kind.BUTTON, true, Copy.EXIT, 0)));
    }

    /** @param sessionId 会话身份 @param wuwei 无为是否允许特殊样式 @param dynamicEnabled 无为动态开关 @return 模式独立的首屏方案 */
    public static InitialActionPlan forSession(String sessionId, boolean wuwei, boolean dynamicEnabled) {
        return wuwei && !dynamicEnabled ? new InitialActionPlan() : forSession(sessionId, wuwei);
    }

    /** @param entropy 会话散列，各次抽样使用不重叠字节 @param allowSpecials 是否启用无为特殊事件 */
    private InitialActionPlan(byte[] entropy, boolean allowSpecials) {
        int combination = entropy[0] & 3;
        boolean exitFirst = (entropy[0] & 4) == 0;
        Kind first = combination == 1 || combination == 3 ? Kind.SLIDER : Kind.BUTTON;
        Kind second = combination == 1 || combination == 2 ? Kind.SLIDER : Kind.BUTTON;
        // 同一随机值划分普通 90%、双退出 5%、盲盒 5%，三类页面互斥。
        double pageSelection = Integer.toUnsignedLong(integer(entropy, 1)) / 0x1.0p32;
        doubleExit = allowSpecials && pageSelection >= 0.90 && pageSelection < 0.95;
        blindBox = allowSpecials && pageSelection >= 0.95;
        if (blindBox) {
            actions = Collections.singletonList(new Action(Kind.SLIDER, chance(entropy, 9, 0.70),
                    Copy.BLIND_BOX, integer(entropy, 21)));
        } else if (doubleExit) {
            actions = Collections.unmodifiableList(Arrays.asList(
                    new Action(first, true, Copy.EXIT_VARIANT, integer(entropy, 13)),
                    new Action(second, true, Copy.EXIT_VARIANT, integer(entropy, 17))));
        } else {
            actions = Collections.unmodifiableList(Arrays.asList(
                    new Action(first, exitFirst, exitFirst ? Copy.EXIT : Copy.CONTINUE, integer(entropy, 13)),
                    new Action(second, !exitFirst, exitFirst ? Copy.CONTINUE : Copy.EXIT, integer(entropy, 17))));
        }
    }

    /**
     * 组合、位置、页面类型、盲盒结果及文案各用独立随机位；无为页面按 90%／5%／5% 互斥抽样。
     * @param sessionId 稳定拦截会话身份
     * @param allowSpecials 无为为 true，修心为 false
     * @return 同会话稳定的普通选择、双退出或单滑块盲盒方案
     */
    public static InitialActionPlan forSession(String sessionId, boolean allowSpecials) {
        try {
            byte[] entropy = MessageDigest.getInstance("SHA-256").digest(
                    ("initial-actions-v2:" + Objects.requireNonNull(sessionId)).getBytes(StandardCharsets.UTF_8));
            return new InitialActionPlan(entropy, allowSpecials);
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is required", unavailable);
        }
    }

    /** @param sessionId 原会话身份 @return 二次询问的独立双出口方案，不启用特殊事件 */
    public static InitialActionPlan forConfirmation(String sessionId) {
        return forSession("confirmation:" + Objects.requireNonNull(sessionId), false);
    }

    /** @param entropy 散列字节 @param offset 起始位置 @return 四字节整数，保留全部随机位 */
    private static int integer(byte[] entropy, int offset) {
        return (entropy[offset] & 255) << 24 | (entropy[offset + 1] & 255) << 16
                | (entropy[offset + 2] & 255) << 8 | entropy[offset + 3] & 255;
    }

    /** @param entropy 散列字节 @param offset 本次独立抽样位置 @param probability 命中概率 @return 是否命中 */
    private static boolean chance(byte[] entropy, int offset, double probability) {
        return Integer.toUnsignedLong(integer(entropy, offset)) / 0x1.0p32 < probability;
    }
}
