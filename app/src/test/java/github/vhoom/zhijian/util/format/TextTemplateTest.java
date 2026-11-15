package github.vhoom.zhijian.util.format;

import org.junit.Test;

/**
 * 必要资源检查不允许坏模板产生巨大字符串或错误参数替换。
 *
 * 维护日期：2026年11月08日
 */
public class TextTemplateTest {
    /** 当前文案的编号参数、常规参数和转义形式都可正常验证。 */
    @Test public void acceptsSupportedTemplates() {
        TextTemplate.validate("按住 %1$d 秒", 1L);
        TextTemplate.validate("选择 %s 的含义 %% %n", "word");
    }

    /** 巨大参数宽度在调用 Formatter 之前被拒绝。 */
    @Test(expected = IllegalArgumentException.class)
    public void rejectsHugeFormatWidth() { TextTemplate.validate("%1000000000s", "word"); }

    /** 不存在的参数编号不能留到正式展示时才报错。 */
    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnknownArgumentIndex() { TextTemplate.validate("%2$s", "word"); }

    /** 需要整数的交互模板不能被传入字符串。 */
    @Test(expected = IllegalArgumentException.class)
    public void rejectsWrongParameterType() { TextTemplate.validate("%1$d", "word"); }
}
