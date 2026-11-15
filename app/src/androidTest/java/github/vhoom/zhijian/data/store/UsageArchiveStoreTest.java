package github.vhoom.zhijian.data.store;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import github.vhoom.zhijian.data.model.AppInfo;
import github.vhoom.zhijian.data.model.usage.ArchivedUsage;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.StringWriter;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/**
 * 独立数据库验证门槛、跨日永久保存和幂等更新；不改手机真实历史或权限。
 *
 * 维护日期：2026年11月14日
 */
@RunWith(AndroidJUnit4.class)
public class UsageArchiveStoreTest {
    /** 短记录不入库，达标后保存；重复同步、缺失查询和重启均保留累计值与名称。 */
    @Test public void archivePreservesThresholdAndHistoryAcrossReopen() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String name = "archive-test-" + UUID.randomUUID() + ".db";
        ZhijianStorage storage = new ZhijianStorage(context, name);
        LocalDate date = LocalDate.of(2026, 1, 1);
        try {
            storage.ready().get(20, TimeUnit.SECONDS);
            UsageArchiveStore archive = new UsageArchiveStore(storage);
            archive.save(Collections.singletonList(day(date, 59_999))).get(10, TimeUnit.SECONDS);
            assertTrue(archive.load(date).get(10, TimeUnit.SECONDS).entries.isEmpty());
            archive.save(Collections.singletonList(day(date, 60_000))).get(10, TimeUnit.SECONDS);
            archive.save(Collections.singletonList(day(date, 120_000))).get(10, TimeUnit.SECONDS);
            archive.save(Arrays.asList(day(date, 120_000), day(date, 0),
                    day(date.plusYears(2), 180_000))).get(10, TimeUnit.SECONDS);
            ArchivedUsage saved = archive.load(date).get(10, TimeUnit.SECONDS);
            assertEquals(1, saved.entries.size());
            assertEquals(120_000, saved.entries.get(0).millis);
            StringWriter output = new StringWriter();
            assertEquals(Long.valueOf(2), new UsageHistoryStore(storage)
                    .exportCompletedSessions(output).get(10, TimeUnit.SECONDS));
            assertTrue(output.toString().contains("daily_archive"));
            storage.closeForTest().get(10, TimeUnit.SECONDS);
            storage = new ZhijianStorage(context, name);
            storage.ready().get(20, TimeUnit.SECONDS);
            saved = new UsageArchiveStore(storage).load(date).get(10, TimeUnit.SECONDS);
            assertEquals(120_000, saved.entries.get(0).millis);
            assertEquals("已卸载应用", saved.entries.get(0).app.label);
        } finally {
            storage.closeForTest().get(10, TimeUnit.SECONDS);
            context.deleteDatabase(name);
        }
    }

    /** @param date 本地日期 @param millis 当天累计用量 @return 不依赖系统的输入快照 */
    private ArchivedUsage day(LocalDate date, long millis) {
        return new ArchivedUsage(date, "Asia/Shanghai", 300_000, 1_800_000_000_000L, true,
                Collections.singletonList(new ArchivedUsage.Entry(new AppInfo("app.removed", "已卸载应用"), millis)));
    }
}
