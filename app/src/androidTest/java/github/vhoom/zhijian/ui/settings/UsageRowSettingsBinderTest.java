package github.vhoom.zhijian.ui.settings;

import android.content.Context;
import android.content.Intent;
import android.widget.EditText;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;

import github.vhoom.zhijian.ZhijianApplication;
import github.vhoom.zhijian.R;
import github.vhoom.zhijian.data.model.AppInfo;
import github.vhoom.zhijian.data.store.apps.AppCatalogCache;
import github.vhoom.zhijian.data.store.settings.GlobalSettings;
import github.vhoom.zhijian.ui.activity.SettingsActivity;
import github.vhoom.zhijian.util.config.AppConfig;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 首页条数表单的异步容量、保存复核和过期弹窗读取回归，不访问正式扫描文件。
 *
 * 维护日期：2026年11月14日
 */
public class UsageRowSettingsBinderTest {
    private GlobalSettings settings;
    private int originalRows;
    private boolean originalOverride;

    /** 等待真实应用初始化，保留用户的手动覆盖状态。 */
    @Before public void prepare() throws Exception {
        Context context = context();
        ((ZhijianApplication) context.getApplicationContext()).ready().get(30, TimeUnit.SECONDS);
        settings = new GlobalSettings(context, new AppConfig(context));
        originalRows = settings.usageRowLimit();
        originalOverride = settings.hasUsageRowLimitOverride();
        settings.setUsageRowLimit(3);
    }

    /** 恢复用户条数及是否使用实测默认值。 */
    @After public void restore() { settings.setUsageRowLimit(originalOverride ? originalRows : 0); }

    /** 加载期间及无有效容量时禁止保存，错误只位于所属输入下方，原设置保持。 */
    @Test public void missingEmptyAndUnreadableSnapshotsCannotSaveInventedCapacity() {
        for (int state : new int[]{-1, 0, -2}) {
            FakeCatalog catalog = new FakeCatalog(state);
            try (Session session = new Session(catalog)) {
                onView(withId(R.id.field_rows)).check((view, missing) -> assertFalse(view.isEnabled()));
                session.completeRead();
                onView(withId(R.id.field_rows)).check((view, missing) -> {
                    EditText field = (EditText) view;
                    assertEquals("3", field.getText().toString());
                    assertFalse(field.isEnabled());
                    assertNull(field.getError());
                    TextView error = (TextView) field.getTag(R.id.input_error_binding);
                    assertTrue(error.isShown());
                });
                onView(withId(android.R.id.button1)).check((view, missing) -> assertFalse(view.isEnabled()));
                assertEquals(0, session.saved.get());
                assertEquals(3, settings.usageRowLimit());
                assertEquals(1, catalog.reads);
            }
        }
    }

    /** 保存前重新读容量，缩减后的超限草稿保留，边界合法值才提交。 */
    @Test public void saveRechecksNewestCapacityWithoutClampingTheDraft() {
        FakeCatalog catalog = new FakeCatalog(5);
        try (Session session = new Session(catalog)) {
            session.completeRead();
            onView(withId(R.id.field_rows)).perform(replaceText("5"), closeSoftKeyboard());
            catalog.count = 4;
            onView(withId(android.R.id.button1)).perform(click());
            session.completeRead();
            onView(withId(R.id.field_rows)).check((view, missing) -> {
                EditText field = (EditText) view;
                assertEquals("5", field.getText().toString());
                assertNull(field.getError());
                assertEquals(context().getString(R.string.settings_rows_invalid, 4),
                        ((TextView) field.getTag(R.id.input_error_binding)).getText().toString());
            });
            assertEquals(0, session.saved.get());
            assertEquals(3, settings.usageRowLimit());
            onView(withId(R.id.field_rows)).perform(replaceText("4"), closeSoftKeyboard());
            onView(withId(android.R.id.button1)).perform(click());
            session.completeRead();
            assertEquals(1, session.saved.get());
            assertEquals(4, settings.usageRowLimit());
            assertEquals(3, catalog.reads);
            onView(withId(R.id.field_rows)).check(doesNotExist());
        }
    }

    /** 被替换弹窗的读取不能解锁新表单，关闭后的读取不能恢复表单或提交。 */
    @Test public void replacedAndDismissedDialogsIgnoreOldReadCallbacks() {
        try (Session session = new Session(new FakeCatalog(5))) {
            session.scenario.onActivity(activity -> session.binder.show());
            session.completeRead();
            onView(withId(R.id.field_rows)).check((view, missing) -> assertFalse(view.isEnabled()));
            session.completeRead();
            onView(withId(R.id.field_rows)).check((view, missing) -> assertTrue(view.isEnabled()));
            onView(withId(R.id.field_rows)).perform(replaceText("4"), closeSoftKeyboard());
            onView(withId(android.R.id.button1)).perform(click());
            session.scenario.onActivity(activity -> session.binder.closeDialog());
            session.completeRead();
            assertEquals(0, session.saved.get());
            assertEquals(3, settings.usageRowLimit());
            onView(withId(R.id.field_rows)).check(doesNotExist());
        }
    }

    /** @return 被测应用上下文 */
    private static Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }

    /** 拥有真实设置页面和可控读取队列的用例会话。 */
    private final class Session implements AutoCloseable {
        final ActivityScenario<SettingsActivity> scenario = ActivityScenario.launch(
                new Intent(context(), SettingsActivity.class));
        final ManualQueue queue = new ManualQueue();
        final AtomicInteger saved = new AtomicInteger();
        UsageRowSettingsBinder binder;

        /** @param catalog 用例中的只读扫描快照 */
        Session(FakeCatalog catalog) {
            scenario.onActivity(activity -> {
                binder = new UsageRowSettingsBinder(activity, settings, saved::incrementAndGet, catalog, queue);
                binder.show();
            });
        }

        /** 完成一项后台工作，测试控制回调顺序而不依赖时间等待。 */
        void completeRead() { scenario.onActivity(activity -> queue.tasks.removeFirst().run()); }

        /** 关闭页面及页面持有的队列。 */
        @Override public void close() {
            scenario.onActivity(activity -> binder.close());
            scenario.close();
        }
    }

    /** 当前扫描数据夹具：负值分别表示未扫描和读取失败。 */
    private static final class FakeCatalog implements AppCatalogCache {
        int count;
        int reads;

        /** @param count 模拟的当前已扫描数量或失败状态 */
        FakeCatalog(int count) { this.count = count; }

        /** @return 当前快照 @throws IOException 模拟损坏快照 */
        @Override public List<AppInfo> read() throws IOException {
            reads++;
            if (count == -2) throw new IOException("Unreadable test snapshot");
            if (count == -1) return null;
            List<AppInfo> apps = new ArrayList<>();
            for (int index = 0; index < count; index++) apps.add(new AppInfo("test.app." + index, "应用" + index));
            return apps;
        }

        /** @param apps 非法写入；读取容量不得改写扫描结果 */
        @Override public void write(List<AppInfo> apps) { throw new AssertionError("Capacity lookup must be read-only"); }
    }

    /** 可控任务调度器；只在测试显式完成任务时运行。 */
    private static final class ManualQueue extends AbstractExecutorService {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        boolean stopped;

        /** @param command 待完成的容量读取任务 */
        @Override public void execute(Runnable command) { tasks.add(command); }
        /** 标记调度器关闭。 */
        @Override public void shutdown() { stopped = true; }
        /** @return 未运行任务；关闭时丢弃它们 */
        @Override public List<Runnable> shutdownNow() { stopped = true; tasks.clear(); return Collections.emptyList(); }
        /** @return 调度器已关闭 */
        @Override public boolean isShutdown() { return stopped; }
        /** @return 已关闭且没有待处理任务 */
        @Override public boolean isTerminated() { return stopped && tasks.isEmpty(); }
        /** @param timeout 未使用的等待上限 @param unit 时间单位 @return 当前终止状态 */
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return isTerminated(); }
    }
}
