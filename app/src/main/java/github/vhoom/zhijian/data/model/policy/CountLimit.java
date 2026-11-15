package github.vhoom.zhijian.data.model.policy;

/**
 * 所有限次规则共用的次数范围；零只表示关闭条件或尚未填写。
 *
 * 维护日期：2026年11月13日
 */
public final class CountLimit {
    /** 单项规则允许配置的最大打开次数。 */
    public static final int MAX_COUNT = 99;

    /** 次数范围定义不持有规则或页面。 */
    private CountLimit() { }

    /** @param count 用户配置的次数 @return 是否可作为启用规则的额度 */
    public static boolean isValid(int count) { return count > 0 && count <= MAX_COUNT; }

    /** @param count 草稿或存储中的次数 @return 是否为合法额度或未填写的零值 */
    public static boolean isValidStored(long count) { return count >= 0L && count <= MAX_COUNT; }
}
