package github.vhoom.zhijian.ui.activity;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;

import github.vhoom.zhijian.ZhijianApplication;
import github.vhoom.zhijian.R;
import github.vhoom.zhijian.data.export.DiagnosticReportExporter;
import github.vhoom.zhijian.ui.about.AboutDocument;
import github.vhoom.zhijian.ui.about.AboutDocumentReader;
import github.vhoom.zhijian.util.diagnostics.DiagnosticLog;

import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.pressBack;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assert.*;

/**
 * 验证实际设置导航及 Android 日志队列、文档输出流组成的完整导出链路。
 *
 * 维护日期：2026年11月14日
 */
public class AboutDiagnosticsTest {
    /** 等待目标进程资源，导航进入用户实际可用的设置页面。 */
    @Before public void ready() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ((ZhijianApplication) context.getApplicationContext()).ready().get(30L, TimeUnit.SECONDS);
    }

    /** 关于入口实际打开对应页面，返回后仍可使用同组的其他入口。 */
    @Test public void aboutRowsOpenDocumentsAndDiagnostics() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (ActivityScenario<SettingsActivity> ignored = ActivityScenario.launch(
                new Intent(context, SettingsActivity.class))) {
            onView(withText(context.getString(R.string.app_name))).perform(scrollTo())
                    .check(matches(isDisplayed()));
            onView(withId(R.id.row_author_letter)).perform(scrollTo(), click());
            onView(withId(R.id.page_title)).check(matches(withText(R.string.about_author_title)));
            onView(withId(R.id.document_body)).check(matches(isDisplayed()));
            pressBack();
            onView(withId(R.id.row_service_agreement)).perform(scrollTo(), click());
            onView(withId(R.id.page_title)).check(matches(withText(R.string.about_agreement_title)));
            pressBack();
            onView(withId(R.id.row_diagnostics)).perform(scrollTo(), click());
            onView(withId(R.id.btn_export_logs)).check(matches(isDisplayed()));
            onView(withId(R.id.btn_clear_logs)).check(matches(isDisplayed()));
            pressBack();
        }
    }

    /** 系统标签与正文共用品牌名称，替换应用名后仍保留作者原文的换行。 */
    @Test public void systemLabelAndDocumentsUseCurrentBrand() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String appName = context.getString(R.string.app_name);
        assertEquals(appName, context.getPackageManager()
                .getApplicationLabel(context.getApplicationInfo()).toString());
        for (AboutDocument document : AboutDocument.values()) {
            String original = readDocumentAsset(context, document);
            String rendered = AboutDocumentReader.read(context, document).get(15L, TimeUnit.SECONDS);
            assertFalse(rendered.contains("{{app_name}}"));
            if (original.contains("{{app_name}}")) assertTrue(rendered.contains(appName));
            assertEquals(original.chars().filter(value -> value == '\n').count(),
                    rendered.chars().filter(value -> value == '\n').count());
        }
    }

    /**
     * 读取真实资源作为正文的换行参照，不经过生产环境的名称替换逻辑。
     * @param context 目标应用上下文
     * @param document 已登记的关于文档
     * @return 原始 UTF-8 文本
     */
    private String readDocumentAsset(Context context, AboutDocument document) throws Exception {
        try (InputStream input = context.getAssets().open(document.asset);
                java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /** 真实后台队列先写入事件，再通过 Android 文档流导出可读取的 ZIP。 */
    @Test public void queuedEventsAndExceptionsReachTheExportedArchive() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File destination = File.createTempFile("diagnostic-test-", ".zip", context.getCacheDir());
        String marker = "fixture-" + System.nanoTime();
        try {
            DiagnosticLog.info("fixture", marker, "window_confirmed 中文事件");
            DiagnosticLog.error("fixture", marker, "expected_test_failure",
                    new IllegalStateException("fixture exception"));
            DiagnosticReportExporter.get(context).exportTo(Uri.fromFile(destination)).get(15L, TimeUnit.SECONDS);
            boolean foundEvent = false;
            boolean foundException = false;
            boolean foundEnvironment = false;
            try (ZipInputStream zip = new ZipInputStream(new FileInputStream(destination))) {
                ZipEntry entry;
                byte[] buffer = new byte[4096];
                while ((entry = zip.getNextEntry()) != null) {
                    java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                    int count;
                    while ((count = zip.read(buffer)) != -1) bytes.write(buffer, 0, count);
                    String text = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
                    if (entry.getName().equals("environment.txt")) {
                        foundEnvironment = text.contains("usage_access=") && text.contains("version=")
                                && text.contains("app_name=" + context.getString(R.string.app_name));
                    } else {
                        foundEvent |= text.contains(marker) && text.contains("中文事件");
                        foundException |= text.contains(marker) && text.contains("fixture exception");
                    }
                }
            }
            assertTrue(foundEnvironment);
            assertTrue(foundEvent);
            assertTrue(foundException);
        } finally { assertTrue(destination.delete()); }
    }
}
