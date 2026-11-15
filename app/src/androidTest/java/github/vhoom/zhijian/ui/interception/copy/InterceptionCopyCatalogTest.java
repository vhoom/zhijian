package github.vhoom.zhijian.ui.interception.copy;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.res.Resources;

import androidx.test.platform.app.InstrumentationRegistry;

import github.vhoom.zhijian.R;
import github.vhoom.zhijian.fixture.InterceptionAssertions;
import github.vhoom.zhijian.ui.interception.copy.InterceptionCopyCatalog.Page;
import github.vhoom.zhijian.ui.interception.copy.InterceptionCopyCatalog.Pool;
import github.vhoom.zhijian.ui.interception.copy.InterceptionCopyCatalog.State;

import org.junit.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.*;

/**
 * 动态文案、普通与闭关共用主句、固定题干及临时放行文案复用回归。
 *
 * 维护日期：2026年11月14日
 */
public class InterceptionCopyCatalogTest {
    /** 按压状态只改变固定按钮提示，主句保持原文。 */
    @Test public void holdingKeepsIdleHeadlineAndChangesOnlyHint() {
        InterceptionCopyCatalog catalog = catalog();
        catalog.preload();
        for (int variant = -12; variant < 12; variant++) {
            InterceptionCopyCatalog.Scenario idle = catalog.scenario(Page.HOLD, State.IDLE, variant);
            InterceptionCopyCatalog.Scenario active = catalog.scenario(Page.HOLD, State.ACTIVE, variant);
            assertEquals(idle.headline, active.headline);
            assertNotEquals(idle.interactionAction, active.interactionAction);
        }
        assertMissing("interception_hold_active_headlines", "array");
    }

    /** 现用页面与状态均有主句，负变体及整数边界也能稳定恢复。 */
    @Test public void everyPageKeepsItsCopyAcrossCatalogRecreation() {
        InterceptionCopyCatalog first = catalog(), recreated = catalog();
        first.preload();
        for (int variant : new int[]{Integer.MIN_VALUE, -13, -1, 0, 1, 12, Integer.MAX_VALUE}) {
            for (Page page : Page.values()) {
                String headline = first.scenario(page, State.IDLE, variant).headline;
                assertFalse(headline.isEmpty());
                assertEquals(headline, recreated.scenario(page, State.IDLE, variant).headline);
            }
            assertEquals(first.scenario(Page.HOLD, State.ACTIVE, variant).headline,
                    recreated.scenario(Page.HOLD, State.ACTIVE, variant).headline);
        }
    }

    /** 各文案库均有效，固定题干与按压提示保留换行、占位符及参数填入。 */
    @Test public void xmlPreservesLineBreaksAndTemplateArguments() {
        InterceptionCopyCatalog catalog = catalog();
        catalog.preload();
        assertEquals(10, Pool.values().length);
        assertEquals("先把注意力收回来。\n从按住按钮开始。",
                catalog.scenario(Page.HOLD, State.IDLE, 0).headline);
        assertEquals("按住 7 秒", String.format(catalog.scenario(Page.HOLD, State.IDLE, 0).interactionAction, 7L));
        assertEquals("还需按住 3 秒", String.format(catalog.scenario(Page.HOLD, State.ACTIVE, 0).interactionAction, 3L));
        assertEquals("选出“word”的中文含义", context().getString(R.string.interception_word_prompt_en_zh, "word"));
        assertEquals("选出“含义”的英文表达", context().getString(R.string.interception_word_prompt_zh_en, "含义"));
        for (Pool pool : Pool.values()) {
            assertTrue(catalog.count(pool) > 0);
            for (int index = 0; index < catalog.count(pool); index++) {
                assertFalse(catalog.value(pool, index).trim().isEmpty());
            }
        }
        assertMissing("interception_word_prompt_en_zh", "array");
        assertMissing("interception_word_prompt_zh_en", "array");
    }

    /** 某一库增删句子后其他库独立轮换，单条库也可加载。 */
    @Test public void banksAcceptDifferentLengthsAndRotateIndependently() {
        Context context = context();
        Resources original = context.getResources();
        Resources varied = new Resources(original.getAssets(), original.getDisplayMetrics(), original.getConfiguration()) {
            /** @param id 数组资源，仅替换本用例检查的分类 */
            @Override public String[] getStringArray(int id) {
                if (id == R.array.interception_general_headlines) return new String[]{"甲", "乙"};
                if (id == R.array.interception_hold_headlines) return new String[]{"一", "二", "三"};
                if (id == R.array.interception_confirmation_headlines) return new String[]{"再想一下"};
                return super.getStringArray(id);
            }
        };
        InterceptionCopyCatalog catalog = new InterceptionCopyCatalog(new ContextWrapper(context) {
            /** 返回本用例的独立资源实例。 */
            @Override public Resources getResources() { return varied; }
        });
        catalog.preload();
        assertEquals("甲", catalog.scenario(Page.GENERAL, State.IDLE, 2).headline);
        assertEquals("三", catalog.scenario(Page.HOLD, State.IDLE, 2).headline);
        assertEquals("乙", catalog.scenario(Page.GENERAL, State.IDLE, -1).headline);
        assertEquals("三", catalog.scenario(Page.HOLD, State.IDLE, -1).headline);
        assertEquals("再想一下", catalog.scenario(Page.CONFIRMATION, State.IDLE, Integer.MIN_VALUE).headline);
        assertEquals("甲", catalog.scenario(Page.FORBIDDEN, State.IDLE, 0).headline);
        assertEquals(original.getStringArray(R.array.interception_word_headlines)[0],
                catalog.scenario(Page.WORD, State.IDLE, 0).headline);
    }

    /** 普通首屏和闭关页面读取同一主句库，实际触发原因不再参与文案选择。 */
    @Test public void initialPagesShareGeneralHeadlineBank() {
        assertBank(Page.GENERAL, R.array.interception_general_headlines);
        assertBank(Page.FORBIDDEN, R.array.interception_general_headlines);
    }

    /** 正式主句按会话保持正文，序号、按压状态及目录重建不会再次抽取。 */
    @Test public void productionHeadlinesStayFixedForTheSameRuleSession() {
        Context isolated = isolatedContext();
        try {
            InterceptionCopyCatalog first = new InterceptionCopyCatalog(isolated);
            InterceptionCopyCatalog recreated = new InterceptionCopyCatalog(isolated);
            String session = "stable-rule-session";
            for (Page page : Page.values()) {
                String headline = first.scenario(page, State.IDLE, 0, session).headline;
                assertFalse(headline.isEmpty());
                assertEquals(headline, recreated.scenario(page, State.IDLE, 999, session).headline);
            }
            assertEquals(first.scenario(Page.GENERAL, State.IDLE, 0, session).headline,
                    recreated.scenario(Page.FORBIDDEN, State.IDLE, 999, session).headline);
            assertEquals(first.scenario(Page.HOLD, State.IDLE, 0, session).headline,
                    recreated.scenario(Page.HOLD, State.ACTIVE, 999, session).headline);
        } finally { isolated.getSharedPreferences("interception_headlines", Context.MODE_PRIVATE).edit().clear().commit(); }
    }

    /** 预览及资源预加载不推进正式文案队列，也不覆盖已经选定的主句。 */
    @Test public void previewsDoNotConsumeProductionHeadlineQueues() {
        Context isolated = isolatedContext();
        SharedPreferences preferences = isolated.getSharedPreferences("interception_headlines", Context.MODE_PRIVATE);
        try {
            InterceptionCopyCatalog catalog = new InterceptionCopyCatalog(isolated);
            String selected = catalog.scenario(Page.GENERAL, State.IDLE, 0, "production-session").headline;
            Map<String, ?> before = preferences.getAll();
            catalog.preload();
            for (Page page : Page.values()) {
                for (int variant = 0; variant < 20; variant++) catalog.scenario(page, State.IDLE, variant);
            }
            assertEquals(before, preferences.getAll());
            assertEquals(selected, catalog.scenario(Page.GENERAL, State.IDLE, 20, "production-session").headline);
        } finally { preferences.edit().clear().commit(); }
    }

    /** 同一变体的直接继续与临时放行使用相同控件文字，包括负值和整数边界。 */
    @Test public void continueAndPassShareControlCopy() {
        String[] choices = context().getResources().getStringArray(R.array.interception_continue_actions);
        for (int variant : new int[]{Integer.MIN_VALUE, -13, -1, 0, 1, 12, Integer.MAX_VALUE}) {
            String expected = choices[Math.floorMod(variant, choices.length)];
            assertEquals(expected, ActionCopy.text(context(), ActionCopy.Route.CONTINUE, variant));
            assertEquals(expected, ActionCopy.text(context(), ActionCopy.Route.PASS, variant));
        }
    }

    /** APK 移除旧分类数组及资产，固定题干保留字符串形式，原页头与休息布局仍已清理。 */
    @Test public void obsoleteInterceptionResourcesAreNotPackaged() throws Exception {
        assertEquals(0, context().getAssets().list("interception").length);
        for (String name : new String[]{"interception_themes", "interception_seal_themes",
                "interception_rest_headlines", "interception_rest_ready_headlines",
                "interception_shared_block_headlines", "interception_shared_reminder_headlines",
                "interception_seal_block_headlines", "interception_seal_reminder_headlines",
                "interception_hold_idle_headlines", "interception_hold_active_headlines",
                "interception_pass_actions", "interception_word_prompt_en_zh",
                "interception_word_prompt_zh_en", "interception_sudoku_headlines"}) assertMissing(name, "array");
        for (String mode : new String[]{"general", "confirmation", "hold", "word", "seal", "rest"}) {
            assertMissing("interception_mode_" + mode, "string");
        }
        assertMissing("view_interception_rest", "layout");
        assertMissing("interception_page_rest", "id");
        InterceptionAssertions.assertNoHeader(context());
    }

    /**
     * 检查实际页面完整读取其主句数组。
     * @param page 页面位置
     * @param resource 应使用的主句数组
     */
    private static void assertBank(Page page, int resource) {
        String[] headlines = context().getResources().getStringArray(resource);
        InterceptionCopyCatalog catalog = catalog();
        for (int index = 0; index < headlines.length; index++) {
            assertEquals(headlines[index], catalog.scenario(page, State.IDLE, index).headline);
        }
    }

    /** @param name 废弃资源名 @param type 资源类型 */
    private static void assertMissing(String name, String type) {
        assertEquals(name, 0, context().getResources().getIdentifier(name, type, context().getPackageName()));
    }

    /** @return 读取真实资源但将所有偏好文件放入独立命名空间的上下文 */
    private static Context isolatedContext() {
        String prefix = "headline-catalog-test-" + UUID.randomUUID() + '-';
        return new ContextWrapper(context()) {
            /** @param name 存储名称 @param mode Android 私有存储模式 @return 本用例的偏好文件 */
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return super.getSharedPreferences(prefix + name, mode);
            }
        };
    }

    /** 返回实际 APK 的资源上下文。 */
    private static Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }

    /** 返回连接实际 Android XML 资源的目录。 */
    private static InterceptionCopyCatalog catalog() { return new InterceptionCopyCatalog(context()); }
}
