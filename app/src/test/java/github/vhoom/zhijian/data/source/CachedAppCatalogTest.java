package github.vhoom.zhijian.data.source;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import github.vhoom.zhijian.data.model.AppInfo;
import github.vhoom.zhijian.data.store.apps.AppCatalogCache;
import github.vhoom.zhijian.data.store.apps.AppCatalogFileStore;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.*;

/**
 * 应用清单缓存的读写边界：首次持久化、重建只读、主动刷新及失败后的快照保护。
 *
 * 维护日期：2026年11月10日
 */
public class CachedAppCatalogTest {
    @Rule public final TemporaryFolder files = new TemporaryFolder();

    /** 首次读取会保存，清单对象隔离源集合，重建读取器以后仍只读取磁盘。 */
    @Test public void firstLoadPersistsAndRecreatedReaderDoesNotScan() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        List<AppInfo> systemApps = new ArrayList<>(apps("sample.one", "应用 🌿"));
        CachedAppCatalog catalog = new CachedAppCatalog(store(), () -> {
            reads.incrementAndGet();
            return systemApps;
        });
        List<AppInfo> first = catalog.load(false);
        systemApps.clear();
        assertApp(first, "sample.one", "应用 🌿");
        try { first.clear(); fail("Snapshot must be immutable"); }
        catch (UnsupportedOperationException expected) { }
        CachedAppCatalog recreated = new CachedAppCatalog(store(), () -> {
            throw new AssertionError("Cached list must not scan installed applications");
        });
        assertApp(recreated.load(false), "sample.one", "应用 🌿");
        assertEquals(1, reads.get());
    }

    /** 已保存的零应用清单代表初始化完成，不会每次进入都重新扫描。 */
    @Test public void emptySavedCatalogIsReadWithoutScanning() throws Exception {
        store().write(Collections.emptyList());
        CachedAppCatalog catalog = new CachedAppCatalog(store(), () -> {
            throw new AssertionError("An empty saved catalog is valid");
        });
        assertTrue(catalog.load(false).isEmpty());
        assertTrue(catalog.load(false).isEmpty());
    }

    /** 主动刷新只增加一次真实读取，后续默认读取使用新快照。 */
    @Test public void explicitRefreshScansOnceAndReplacesSavedCatalog() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        CachedAppCatalog catalog = new CachedAppCatalog(store(), () -> reads.incrementAndGet() == 1
                ? apps("sample.old", "旧清单") : apps("sample.new", "新清单"));
        assertApp(catalog.load(false), "sample.old", "旧清单");
        assertApp(catalog.load(true), "sample.new", "新清单");
        assertApp(catalog.load(false), "sample.new", "新清单");
        assertApp(store().read(), "sample.new", "新清单");
        assertEquals(2, reads.get());
    }

    /** 系统读取失败不会清空上一次已保存且可展示的清单。 */
    @Test public void failedSystemRefreshKeepsCommittedCatalog() throws Exception {
        store().write(apps("sample.old", "旧清单"));
        CachedAppCatalog catalog = new CachedAppCatalog(store(), () -> {
            throw new IllegalStateException("System reader unavailable");
        });
        try { catalog.load(true); fail("Refresh failure must be reported"); }
        catch (IllegalStateException expected) { }
        assertApp(catalog.load(false), "sample.old", "旧清单");
        assertApp(store().read(), "sample.old", "旧清单");
    }

    /** 保存失败的候选清单不能作为成功结果发布给界面。 */
    @Test public void failedCommitDoesNotPublishCandidateCatalog() throws Exception {
        List<AppInfo> committed = apps("sample.old", "旧清单");
        AppCatalogCache failingCache = new AppCatalogCache() {
            /** @return 上一次成功提交的清单 */
            @Override public List<AppInfo> read() { return committed; }

            /** @param apps 尚未提交的候选清单 @throws IOException 模拟保存失败 */
            @Override public void write(List<AppInfo> apps) throws IOException {
                throw new IOException("Commit failed");
            }
        };
        CachedAppCatalog catalog = new CachedAppCatalog(failingCache, () -> apps("sample.new", "新清单"));
        try { catalog.load(true); fail("Uncommitted candidate must not be returned"); }
        catch (IOException expected) { }
        assertApp(catalog.load(false), "sample.old", "旧清单");
    }

    /** 临时文件已经写入头部后发生编码失败，旧文件仍保持完整且可读。 */
    @Test public void partialFileWriteDoesNotReplacePreviousSnapshot() throws Exception {
        AppCatalogFileStore cache = store();
        cache.write(apps("sample.old", "旧清单"));
        try {
            cache.write(apps("sample.new", "x".repeat(70_000)));
            fail("Oversized UTF record must fail before committing");
        } catch (IOException expected) { }
        assertApp(cache.read(), "sample.old", "旧清单");
    }

    /** 损坏快照通过读取错误报告，用户主动刷新后才能实际读取并修复清单。 */
    @Test public void corruptCacheRequiresExplicitRefreshToReadSystem() throws Exception {
        Files.write(file(), new byte[]{0, 1, 2});
        AtomicInteger reads = new AtomicInteger();
        CachedAppCatalog catalog = new CachedAppCatalog(store(), () -> {
            reads.incrementAndGet();
            return apps("sample.repaired", "已恢复");
        });
        try { catalog.load(false); fail("Invalid cache must be reported"); }
        catch (IOException expected) { }
        assertEquals(0, reads.get());
        assertApp(catalog.load(true), "sample.repaired", "已恢复");
        assertApp(catalog.load(false), "sample.repaired", "已恢复");
        assertEquals(1, reads.get());
    }

    /** @return 当前用例私有临时目录中的同一份缓存路径 */
    private Path file() { return files.getRoot().toPath().resolve("launchable_apps.cache"); }

    /** @return 每次重建都读取同一路径的实际文件存储 */
    private AppCatalogFileStore store() { return new AppCatalogFileStore(file()); }

    /**
     * 创建只包含一个应用的源清单。
     * @param packageName 样例应用包名
     * @param label 样例显示名称
     * @return 单条完整清单
     */
    private static List<AppInfo> apps(String packageName, String label) {
        return Collections.singletonList(new AppInfo(packageName, label));
    }

    /**
     * 检查包名和 Unicode 名称在真实文件读写后保持完整。
     * @param apps 实际读取或发布的清单
     * @param packageName 应当保留的包名
     * @param label 应当保留的显示名称
     */
    private static void assertApp(List<AppInfo> apps, String packageName, String label) {
        assertEquals(1, apps.size());
        assertEquals(packageName, apps.get(0).packageName);
        assertEquals(label, apps.get(0).label);
    }
}
