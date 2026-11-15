package github.vhoom.zhijian.data.source;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import github.vhoom.zhijian.data.model.AppInfo;
import github.vhoom.zhijian.data.store.apps.AppCatalogCache;

/**
 * 可管控应用清单的缓存代理：首次读取系统，之后只读快照，主动刷新才重新扫描。
 *
 * 维护日期：2026年11月10日
 */
public final class CachedAppCatalog {
    /** 多个页面同时首次加载时，共用提交边界，避免重复扫描或覆盖较新的快照。 */
    private static final Object SNAPSHOT_LOCK = new Object();
    private final AppCatalogCache cache;
    private final Supplier<List<AppInfo>> source;

    /**
     * 注入快照存储和实际读取策略，构造时不查询系统或读取磁盘。
     * @param cache 已提交清单的持久化适配器
     * @param source 后台执行的系统应用扫描器
     */
    public CachedAppCatalog(AppCatalogCache cache, Supplier<List<AppInfo>> source) {
        this.cache = Objects.requireNonNull(cache);
        this.source = Objects.requireNonNull(source);
    }

    /**
     * 默认只读有效快照；只有首次缺失或用户主动刷新才查询系统。
     * @param forceRefresh 是否由刷新图标触发一次真实读取
     * @return 保存成功后发布的不可变清单
     * @throws IOException 本地读取或快照提交失败；调用方保留上一份展示
     */
    public List<AppInfo> load(boolean forceRefresh) throws IOException {
        synchronized (SNAPSHOT_LOCK) {
            if (!forceRefresh) {
                List<AppInfo> cached = cache.read();
                if (cached != null) return snapshot(cached);
            }
            List<AppInfo> loaded = snapshot(source.get());
            cache.write(loaded);
            return loaded;
        }
    }

    /**
     * 隔离读取器或存储实现的可变集合，应用记录本身为不可变对象。
     * @param apps 完整的源清单
     * @return 不允许调用方增删的独立列表快照
     */
    private static List<AppInfo> snapshot(List<AppInfo> apps) {
        return Collections.unmodifiableList(new ArrayList<>(apps));
    }
}
