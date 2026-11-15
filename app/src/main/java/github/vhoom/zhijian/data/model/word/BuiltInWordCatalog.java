package github.vhoom.zhijian.data.model.word;

import github.vhoom.zhijian.R;

/**
 * 内置词库目录；稳定 ID 用于设置和会话快照，展示名称与资源路径彼此独立。
 *
 * 维护日期：2026年11月08日
 */
public enum BuiltInWordCatalog {
    JUNIOR_HIGH("junior_high", "junior_high.csv", R.string.word_catalog_junior_high),
    SENIOR_HIGH("senior_high", "senior_high.csv", R.string.word_catalog_senior_high),
    CET4("cet4", "CET4.csv", R.string.word_catalog_cet4),
    CET6("cet6", "CET6.csv", R.string.word_catalog_cet6),
    POSTGRADUATE("postgraduate", "postgraduate.csv", R.string.word_catalog_postgraduate),
    SAT("sat", "SAT.csv", R.string.word_catalog_sat),
    TOEFL("toefl", "TOEFL.csv", R.string.word_catalog_toefl);

    public final String id;
    public final String assetPath;
    public final int nameRes;

    /**
     * 注册一个词库，不以枚举序号保存用户选择。
     * @param id 持久化使用的稳定标识
     * @param fileName English 资源目录下的文件名
     * @param nameRes 词库名称字符串资源
     */
    BuiltInWordCatalog(String id, String fileName, int nameRes) {
        this.id = id;
        this.assetPath = "English/" + fileName;
        this.nameRes = nameRes;
    }

    /**
     * 解析保存的标识，缺失或已移除的词库默认使用初中。
     * @param id 设置或会话中的词库标识
     * @return 可用词库，默认初中
     */
    public static BuiltInWordCatalog fromId(String id) {
        for (BuiltInWordCatalog catalog : values()) {
            if (catalog.id.equals(id)) return catalog;
        }
        return JUNIOR_HIGH;
    }
}
