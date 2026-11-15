package github.vhoom.zhijian.data.store.word;

import github.vhoom.zhijian.data.model.word.BuiltInWordCatalog;
import org.junit.Test;
import java.io.IOException;
import java.io.StringReader;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 启动资源检查在必要文件缺失或损坏时失败，不能静默进入错误词库。
 *
 * 维护日期：2026年11月08日
 */
public class BuiltInWordCatalogStoreTest {
    /** 重复初始化复用已发布的完整缓存，不重开所有资源流。 */
    @Test public void repeatedInitializationDoesNotReloadResources() {
        java.util.concurrent.atomic.AtomicInteger opened = new java.util.concurrent.atomic.AtomicInteger();
        BuiltInWordCatalogStore store = new BuiltInWordCatalogStore(path -> {
            opened.incrementAndGet();
            return new StringReader(VALID);
        });
        store.initialize();
        store.initialize();
        assertEquals(BuiltInWordCatalog.values().length, opened.get());
    }

    /** 解析失败仍关闭资源流，不把必要资源句柄留在进程中。 */
    @Test public void malformedResourcesAreClosed() {
        java.util.concurrent.atomic.AtomicInteger closed = new java.util.concurrent.atomic.AtomicInteger();
        BuiltInWordCatalogStore store = new BuiltInWordCatalogStore(path -> new StringReader("wrong\n") {
            /** 记录仓库是否关闭失败资源。 */
            @Override public void close() { closed.incrementAndGet(); super.close(); }
        });
        assertStartupFailure(store, BuiltInWordCatalog.JUNIOR_HIGH.assetPath, "HEADER");
        assertEquals(BuiltInWordCatalog.values().length, closed.get());
    }

    /** 初始化前读取给出明确状态异常，不产生空指针。 */
    @Test(expected = IllegalStateException.class)
    public void entriesRequireCompletedInitialization() {
        new BuiltInWordCatalogStore(path -> new StringReader(VALID)).entries(BuiltInWordCatalog.JUNIOR_HIGH);
    }

    private static final String VALID = "english,part_of_speech,chinese\n"
            + "one,n.,一\ntwo,n.,二\nthree,n.,三\nfour,n.,四\nfive,n.,五\nsix,n.,六\n";

    /** 所有词库都有效时才发布可供界面读取的快照。 */
    @Test public void validResourcesPublishCompleteCatalogs() {
        BuiltInWordCatalogStore store = new BuiltInWordCatalogStore(path -> new StringReader(VALID));
        store.initialize();
        for (BuiltInWordCatalog catalog : BuiltInWordCatalog.values()) assertEquals(6, store.entries(catalog).size());
    }

    /** 文件不存在时启动检查明确标出必要资源路径。 */
    @Test public void missingRequiredResourceFailsStartup() {
        BuiltInWordCatalogStore store = new BuiltInWordCatalogStore(path -> {
            if (path.equals(BuiltInWordCatalog.CET4.assetPath)) throw new IOException("Missing asset");
            return new StringReader(VALID);
        });
        assertStartupFailure(store, "English/CET4.csv", "READ_ERROR");
    }

    /** 未选中的词库损坏同样在启动时被发现，并保留问题行号。 */
    @Test public void malformedUnselectedCatalogFailsStartup() {
        BuiltInWordCatalogStore store = new BuiltInWordCatalogStore(path -> new StringReader(
                path.equals(BuiltInWordCatalog.TOEFL.assetPath) ? "wrong,header\n" : VALID));
        assertStartupFailure(store, "English/TOEFL.csv:1", "HEADER");
    }

    /**
     * 检查初始化失败带有可诊断的文件与原因。
     * @param store 注入缺失或错误资源的仓库
     * @param path 应报告的资源路径和可选行号
     * @param problem 稳定检查原因
     */
    private static void assertStartupFailure(BuiltInWordCatalogStore store, String path, String problem) {
        try {
            store.initialize();
            fail("Invalid required resource should fail startup");
        } catch (IllegalStateException failure) {
            assertTrue(failure.getMessage().contains(path));
            assertTrue(failure.getMessage().contains(problem));
        }
    }
}
