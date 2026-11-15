package github.vhoom.zhijian.ui.about;

import github.vhoom.zhijian.R;

/**
 * 关于文本的装配目录；页面、标题与独立资源使用同一稳定标识。
 *
 * 维护日期：2026年11月08日
 */
public enum AboutDocument {
    AUTHOR("author", "about/author_letter.txt", R.string.about_author_title,
            R.string.about_author_subtitle, true),
    AGREEMENT("agreement", "about/service_agreement.txt", R.string.about_agreement_title,
            R.string.about_agreement_subtitle, false);

    public final String code;
    public final String asset;
    public final int titleRes;
    public final int subtitleRes;
    public final boolean letter;

    /**
     * 声明一种本地阅读内容。
     * @param code Intent 中使用的稳定标识
     * @param asset 应用内独立文本文件
     * @param titleRes 页面标题
     * @param subtitleRes 页面副标题
     * @param letter 是否采用书信字体与排版
     */
    AboutDocument(String code, String asset, int titleRes, int subtitleRes, boolean letter) {
        this.code = code;
        this.asset = asset;
        this.titleRes = titleRes;
        this.subtitleRes = subtitleRes;
        this.letter = letter;
    }

    /**
     * 解析受限目录中的文档，不允许任意资源路径。
     * @param code 页面请求中的稳定标识
     * @return 匹配的文档，未知标识返回空值
     */
    public static AboutDocument forCode(String code) {
        for (AboutDocument document : values()) if (document.code.equals(code)) return document;
        return null;
    }
}

