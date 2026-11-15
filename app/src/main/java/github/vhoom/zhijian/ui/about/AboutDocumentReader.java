package github.vhoom.zhijian.ui.about;

import android.content.Context;

import github.vhoom.zhijian.R;
import github.vhoom.zhijian.util.io.BoundedTextReader;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * 本地文本资源 Adapter；有界后台读取，不与设置或运行数据库耦合。
 *
 * 维护日期：2026年11月08日
 */
public final class AboutDocumentReader {
    /** 只替换当前应用名；普通正文、百分号及其他文本不参与递归解析。 */
    private static final String APP_NAME_TOKEN = "{{app_name}}";

    /** 静态读取工具不允许实例化。 */
    private AboutDocumentReader() { }

    /**
     * 后台读取指定文档，旋转或离开页面不持有 Activity。
     * @param context 用于读取应用资源
     * @param document 已通过目录校验的文档
     * @return 已替换当前应用名并保留换行的正文，作者信中的名称不加括号
     */
    public static CompletableFuture<String> read(Context context, AboutDocument document) {
        Context application = context.getApplicationContext();
        return CompletableFuture.supplyAsync(() -> {
            try (BoundedTextReader reader = new BoundedTextReader(new InputStreamReader(
                    application.getAssets().open(document.asset), StandardCharsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)), 64 * 1024L, 8192)) {
                String appName = application.getString(R.string.app_name);
                String displayName = document == AboutDocument.AUTHOR ? appName : "「" + appName + "」";
                // 归一已有占位符括号，作者信直接显示名称，协议沿用引用格式。
                return reader.readAll().replace("「" + APP_NAME_TOKEN + "」", APP_NAME_TOKEN)
                        .replace(APP_NAME_TOKEN, displayName);
            } catch (IOException failure) {
                throw new CompletionException(failure);
            }
        });
    }
}

