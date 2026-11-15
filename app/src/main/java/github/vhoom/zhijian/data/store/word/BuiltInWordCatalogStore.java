package github.vhoom.zhijian.data.store.word;

import android.content.res.AssetManager;

import github.vhoom.zhijian.data.model.word.BuiltInWordCatalog;
import github.vhoom.zhijian.domain.interception.word.WordEntry;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 内置资产词库仓库；由存储后台队列加载，页面只读取缓存的不可变快照。
 *
 * 维护日期：2026年11月08日
 */
public final class BuiltInWordCatalogStore {
    private final Source source;
    private volatile Map<BuiltInWordCatalog, CsvWordCatalogReader.Result> catalogs;

    /** 资源流工厂，允许检查缺失和损坏资源的启动行为而不依赖真实 APK。 */
    public interface Source {
        /**
         * 打开一个词库资源。
         * @param path 目录注册的资源路径
         * @return 由仓库负责关闭的 UTF-8 解码流
         * @throws IOException 资源不存在或无法读取
         */
        Reader open(String path) throws IOException;
    }

    /**
     * 接收资源访问依赖，仓库不依赖数据库或页面。
     * @param assets 应用资产管理器
     */
    public BuiltInWordCatalogStore(AssetManager assets) {
        this(path -> new InputStreamReader(assets.open(path), StandardCharsets.UTF_8.newDecoder()));
    }

    /**
     * 注入资源流工厂，正式初始化与故障测试使用同一解析路径。
     * @param source 词库资源流工厂
     */
    public BuiltInWordCatalogStore(Source source) { this.source = source; }

    /** 在发布存储就绪信号之前，由后台初始化队列一次加载全部词库。 */
    public synchronized void initialize() {
        if (catalogs != null) return;
        Map<BuiltInWordCatalog, CsvWordCatalogReader.Result> loaded = checkAll();
        for (Map.Entry<BuiltInWordCatalog, CsvWordCatalogReader.Result> catalog : loaded.entrySet()) {
            if (!catalog.getValue().isValid()) {
                CsvWordCatalogReader.Issue issue = catalog.getValue().issues.get(0);
                throw new IllegalStateException("Required word catalog is invalid: "
                        + catalog.getKey().assetPath + ':' + issue.line + " " + issue.problem + " " + issue.detail);
            }
        }
        catalogs = loaded;
    }

    /**
     * 返回已经检查的缓存，调用前须等待存储就绪。
     * @param catalog 当前所选词库
     * @return 本词库的不可变检查和加载结果
     */
    private CsvWordCatalogReader.Result result(BuiltInWordCatalog catalog) {
        Map<BuiltInWordCatalog, CsvWordCatalogReader.Result> loaded = catalogs;
        if (loaded == null) throw new IllegalStateException("Word catalogs are not initialized");
        if (catalog == null) throw new IllegalArgumentException("Word catalog is required");
        return loaded.get(catalog);
    }

    /**
     * 返回所选词库的完整词条，拒绝把错误格式带入出题模块。
     * @param catalog 当前会话选择的词库
     * @return 已检查的完整词条
     */
    public List<WordEntry> entries(BuiltInWordCatalog catalog) {
        CsvWordCatalogReader.Result result = result(catalog);
        if (!result.isValid()) throw new IllegalStateException("Invalid word catalog: " + catalog.assetPath);
        return result.entries;
    }

    /** 返回重新读取全部内置文件的检查结果；调用方必须在后台执行。 */
    private Map<BuiltInWordCatalog, CsvWordCatalogReader.Result> checkAll() {
        Map<BuiltInWordCatalog, CsvWordCatalogReader.Result> results = new EnumMap<>(BuiltInWordCatalog.class);
        for (BuiltInWordCatalog catalog : BuiltInWordCatalog.values()) {
            try (Reader reader = source.open(catalog.assetPath)) {
                results.put(catalog, CsvWordCatalogReader.read(reader));
            } catch (IOException failure) {
                results.put(catalog, new CsvWordCatalogReader.Result(Collections.emptyList(),
                        Collections.singletonList(new CsvWordCatalogReader.Issue(0,
                                CsvWordCatalogReader.Problem.READ_ERROR, ""))));
            }
        }
        return Collections.unmodifiableMap(results);
    }
}
