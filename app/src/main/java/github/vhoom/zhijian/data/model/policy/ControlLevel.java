package github.vhoom.zhijian.data.model.policy;

import github.vhoom.zhijian.R;

/**
 * 全局执行强度档位，声明顺序即管控中心三段控件的顺序。
 *
 * 维护日期：2026年11月08日
 */
public enum ControlLevel {

    MORTAL("mortal", R.string.level_mortal, R.string.level_detail_mortal),
    REPAIR("repair", R.string.level_repair, R.string.level_detail_repair),
    SEAL("seal", R.string.level_seal, R.string.level_detail_seal);

    public final String code;
    public final int nameRes;
    /** 档位细则只在切换确认框里展开，页面不常驻展示。 */
    public final int detailRes;

    /**
     * 创建 ControlLevel 实例，并初始化本次操作依赖或不可变字段。
     *
     * @param code 供长期存储或诊断使用的稳定文本键
     * @param nameRes 界面显示名称对应的字符串资源 ID
     * @param detailRes 界面说明对应的字符串资源 ID
     */
    ControlLevel(String code, int nameRes, int detailRes) {
        this.code = code;
        this.nameRes = nameRes;
        this.detailRes = detailRes;
    }

    /**
     * 从输入值构造当前类型；枚举序号非法时使用规定的缺省值。
     *
     * @param ordinal 从持久化文件读取的枚举序号
     */
    public static ControlLevel of(int ordinal) {
        return ordinal >= 0 && ordinal < values().length ? values()[ordinal] : REPAIR;
    }
}
