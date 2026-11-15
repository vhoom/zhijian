package github.vhoom.zhijian.data.store.interception;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.junit.Assert.*;

/**
 * 独立主句存储的会话复用、分类隔离、对象重建、资源变更及缓存清理回归。
 *
 * 维护日期：2026年11月13日
 */
public class InterceptionHeadlineStoreTest {
    private static final List<String> CANDIDATES = Arrays.asList("甲", "乙", "丙");
    private SharedPreferences preferences;
    private final Set<String> activeSessions = new HashSet<>();

    /** 每个用例使用独立偏好文件和可控制的未结束会话集合。 */
    @Before public void setUp() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        preferences = context.getSharedPreferences("headline-test-" + UUID.randomUUID(), Context.MODE_PRIVATE);
        activeSessions.clear();
    }

    /** 清空本用例私有偏好，不影响正式会话与文案轮换。 */
    @After public void tearDown() { preferences.edit().clear().commit(); }

    /** 同一轮规则反复显示不会额外消耗主句，新会话仍能完整覆盖本轮文案库。 */
    @Test public void reopeningSessionDoesNotConsumeAnotherHeadline() {
        InterceptionHeadlineStore store = store();
        String first = store.getOrCreate("general", "first", CANDIDATES);
        for (int index = 0; index < 20; index++) {
            assertEquals(first, store.getOrCreate("general", "first", CANDIDATES));
        }
        Set<String> seen = new HashSet<>();
        seen.add(first);
        assertTrue(seen.add(store.getOrCreate("general", "second", CANDIDATES)));
        assertTrue(seen.add(store.getOrCreate("general", "third", CANDIDATES)));
        assertEquals(new HashSet<>(CANDIDATES), seen);
    }

    /** 另一类主句触发多次，不会提前消耗当前库的剩余正文。 */
    @Test public void differentPoolsRotateIndependently() {
        InterceptionHeadlineStore store = store();
        Set<String> seen = new HashSet<>();
        seen.add(store.getOrCreate("general", "first", CANDIDATES));
        for (int index = 0; index < 20; index++) {
            store.getOrCreate("hold", "hold-" + index, Arrays.asList("一", "二"));
        }
        assertTrue(seen.add(store.getOrCreate("general", "second", CANDIDATES)));
        assertTrue(seen.add(store.getOrCreate("general", "third", CANDIDATES)));
        assertEquals(new HashSet<>(CANDIDATES), seen);
    }

    /** 插入其他规则的新会话后，尚未结束的原规则恢复时继续显示原正文。 */
    @Test public void activeSessionKeepsItsHeadlineAcrossOtherTriggers() {
        activeSessions.add("original");
        InterceptionHeadlineStore store = store();
        String original = store.getOrCreate("general", "original", CANDIDATES);
        store.getOrCreate("general", "other", CANDIDATES);
        assertEquals(original, store().getOrCreate("general", "original", CANDIDATES));
    }

    /** 重新创建存储适配器时复用正文及未消耗的队列。 */
    @Test public void recreatedStoreRestoresSelectionAndRemainingQueue() {
        activeSessions.add("first");
        String first = store().getOrCreate("general", "first", CANDIDATES);
        InterceptionHeadlineStore recreated = store();
        assertEquals(first, recreated.getOrCreate("general", "first", CANDIDATES));
        Set<String> seen = new HashSet<>();
        seen.add(first);
        assertTrue(seen.add(recreated.getOrCreate("general", "second", CANDIDATES)));
        assertTrue(seen.add(recreated.getOrCreate("general", "third", CANDIDATES)));
    }

    /** 清理已经结束的会话正文，保留分类轮换进度与当前会话。 */
    @Test public void inactiveSessionCacheIsRemovedWithoutResettingThePool() {
        InterceptionHeadlineStore store = store();
        String first = store.getOrCreate("general", "first", CANDIDATES);
        String second = store.getOrCreate("general", "second", CANDIDATES);
        assertNotEquals(first, second);
        assertFalse(preferences.contains("session:first"));
        assertTrue(preferences.contains("session:second"));
        assertTrue(preferences.contains("pool:general"));
    }

    /** 文案资源变化不改写同一轮已显示的正文，新会话使用当前资源。 */
    @Test public void resourceEditsKeepCurrentSessionAndRefreshNewSessions() {
        InterceptionHeadlineStore store = store();
        String first = store.getOrCreate("general", "first", CANDIDATES);
        List<String> edited = Collections.singletonList("新正文");
        assertEquals(first, store.getOrCreate("general", "first", edited));
        assertEquals("新正文", store.getOrCreate("general", "second", edited));
    }

    /** 修改一个文案库时仅重建此库，其他分类仍继续原队列。 */
    @Test public void resourceEditsDoNotResetOtherPools() {
        InterceptionHeadlineStore store = store();
        Set<String> seen = new HashSet<>();
        seen.add(store.getOrCreate("hold", "first", CANDIDATES));
        store.getOrCreate("general", "first", Arrays.asList("一", "二"));
        assertEquals("更新", store.getOrCreate("general", "second", Collections.singletonList("更新")));
        assertTrue(seen.add(store.getOrCreate("hold", "second", CANDIDATES)));
        assertTrue(seen.add(store.getOrCreate("hold", "third", CANDIDATES)));
    }

    /** 损坏的队列可以从当前文案库重新选择，同一轮正文保存后仍可复用。 */
    @Test public void damagedPoolStateRecoversFromCurrentResources() {
        preferences.edit().putString("pool:general", "broken-json").commit();
        String selected = store().getOrCreate("general", "first", CANDIDATES);
        assertTrue(CANDIDATES.contains(selected));
        assertEquals(selected, store().getOrCreate("general", "first", CANDIDATES));
    }

    /** @return 使用本用例偏好和会话快照的新适配器 */
    private InterceptionHeadlineStore store() {
        return new InterceptionHeadlineStore(preferences, new Random(79), () -> new HashSet<>(activeSessions));
    }
}
