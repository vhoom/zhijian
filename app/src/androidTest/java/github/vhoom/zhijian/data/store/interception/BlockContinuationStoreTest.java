package github.vhoom.zhijian.data.store.interception;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;

import androidx.test.platform.app.InstrumentationRegistry;

import github.vhoom.zhijian.data.model.policy.ControlLevel;
import github.vhoom.zhijian.data.model.runtime.BlockContinuation;
import github.vhoom.zhijian.data.model.runtime.PendingInterception;
import github.vhoom.zhijian.domain.interception.InterceptionDecision;
import github.vhoom.zhijian.domain.usage.UsageTimeline;

import org.junit.Test;

import java.util.UUID;
import java.util.List;

import static org.junit.Assert.*;

/**
 * 使用独立偏好验证忽略落盘、重新进入边界及过期页面隔离。
 *
 * 维护日期：2026年11月14日
 */
public class BlockContinuationStoreTest {
    /** 重建读取保留忽略，止间窗口不清除，离开原应用后返回恢复禁用。 */
    @Test public void persistedContinuationEndsOnRealApplicationDeparture() {
        Context context = isolatedContext();
        BlockContinuationStore store = new BlockContinuationStore(context);
        PendingInterception pending = pending("test.app");
        store.observeForeground("test.app");
        assertTrue(store.ignore(pending, pending.decision, 2_000L));
        store.observeForeground(context.getPackageName());
        assertEquals(1, new BlockContinuationStore(context).read("test.app", "boot").size());
        store.observeForeground("home");
        store.observeForeground("test.app");
        assertTrue(store.read("test.app", "boot").isEmpty());
    }

    /** 已离开的旧页面不能新建忽略；有效忽略也不能跨设备重启。 */
    @Test public void stalePageAndDifferentBootCannotContinueBlocks() {
        Context context = isolatedContext();
        BlockContinuationStore store = new BlockContinuationStore(context);
        store.observeForeground("test.other");
        PendingInterception pending = pending("test.app");
        assertFalse(store.ignore(pending, pending.decision, 2_000L));
        assertTrue(store.read("test.app", "boot").isEmpty());
        store.observeForeground("test.app");
        assertTrue(store.ignore(pending, pending.decision, 2_000L));
        assertTrue(store.read("test.app", "next-boot").isEmpty());
    }

    /** 损坏的存储记录不能被读取成有效忽略。 */
    @Test public void invalidStoredFormatCannotSuppressRules() {
        Context context = isolatedContext();
        context.getSharedPreferences("block_continuations", Context.MODE_PRIVATE).edit()
                .putString("app:test.app", "[{\"policy\":\"broken\"}]").commit();
        assertTrue(new BlockContinuationStore(context).read("test.app", "boot").isEmpty());
    }

    /** 复用很早的触发身份时，落盘边界使用本次继续，而非原触发时间。 */
    @Test public void reusedPageUsesCurrentConfirmationBoundary() {
        Context context = isolatedContext();
        BlockContinuationStore store = new BlockContinuationStore(context);
        PendingInterception original = pending("test.app");
        PendingInterception shown = new PendingInterception(original.decision, original.sessionId,
                original.bootId, 3_000L, 3_000L, -1L, original.occurredAtMs);
        assertTrue(store.ignore(shown, original.decision, 4_000L));
        assertEquals(4_000L, new BlockContinuationStore(context).read("test.app", "boot").get(0).startedAtMs);
        assertEquals(1_000L, shown.occurredAtMs);
    }

    /** 会话被复用但触发原因、窗口或版本变化时，旧页面的确认不能放行新规则。 */
    @Test public void changedRuleCannotBeContinuedByOldConfirmation() {
        BlockContinuationStore store = new BlockContinuationStore(isolatedContext());
        PendingInterception original = pending("test.app");
        InterceptionDecision confirmed = original.decision;
        InterceptionDecision[] changed = {
                decision(InterceptionDecision.Trigger.DAILY_LIMIT, "", 1L),
                decision(InterceptionDecision.Trigger.WINDOW_BAN, "next", 1L),
                decision(InterceptionDecision.Trigger.WINDOW_BAN, "work", 2L)
        };
        for (InterceptionDecision current : changed) {
            PendingInterception shown = new PendingInterception(current, original.sessionId,
                    "boot", 1_500L, 1_500L, -1L, original.occurredAtMs);
            assertFalse(store.ignore(shown, confirmed, 2_000L));
            assertTrue(store.read("test.app", "boot").isEmpty());
        }
    }

    /** 已在确认之后创建的新展示即使沿用会话，也不接受前一次继续的异步回调。 */
    @Test public void newerPresentationCannotBeContinuedByOldCallback() {
        BlockContinuationStore store = new BlockContinuationStore(isolatedContext());
        PendingInterception original = pending("test.app");
        PendingInterception newer = new PendingInterception(original.decision, original.sessionId,
                "boot", 4_000L, 4_000L, -1L, original.occurredAtMs);
        assertFalse(store.ignore(newer, original.decision, 3_000L));
        assertTrue(store.read("test.app", "boot").isEmpty());
    }

    /** 各条规则按各自确认边界重放，旧规则的新访问证据不能清掉后来确认的另一条规则。 */
    @Test public void oldVisitEvidencePreservesNewlyConfirmedRule() {
        Context context = isolatedContext();
        BlockContinuationStore store = new BlockContinuationStore(context);
        PendingInterception first = pending("test.app");
        assertTrue(store.ignore(first, first.decision, 2_000L));
        InterceptionDecision laterRule = decision(InterceptionDecision.Trigger.DAILY_LIMIT, "", 1L);
        PendingInterception later = new PendingInterception(laterRule, "later", "boot", 4_500L, 4_500L);
        assertTrue(store.ignore(later, laterRule, 5_000L));
        UsageTimeline timeline = new UsageTimeline(context.getPackageName());
        timeline.resumed("test.app", "Main", 2_000L);
        timeline.resumed("home", "Launcher", 3_000L);
        timeline.resumed("test.app", "Main", 4_000L);
        timeline.resumed(context.getPackageName(), "Interception", 4_500L);
        timeline.resumed("test.app", "Main", 5_500L);
        List<BlockContinuation> remaining = store.readForVisit("test.app", "boot",
                start -> timeline.openCount("test.app", start + 1L, 6_000L) > 0L);
        assertEquals(1, remaining.size());
        assertTrue(remaining.get(0).matches(laterRule));
        assertEquals(5_000L, remaining.get(0).startedAtMs);
    }

    /** 重放期间写入新的确认边界，旧快照的清理只能删除旧值。 */
    @Test public void oldReplayCannotClearConcurrentNewContinuation() {
        BlockContinuationStore store = new BlockContinuationStore(isolatedContext());
        PendingInterception shown = pending("test.app");
        assertTrue(store.ignore(shown, shown.decision, 2_000L));
        List<BlockContinuation> remaining = store.readForVisit("test.app", "boot", start -> {
            assertEquals(2_000L, start);
            assertTrue(store.ignore(shown, shown.decision, 4_000L));
            return true;
        });
        assertEquals(1, remaining.size());
        assertEquals(4_000L, remaining.get(0).startedAtMs);
    }

    /** 修心确认实际落盘并保留档位；离开原应用仍结束本次确认。 */
    @Test public void repairContinuationPersistsItsControlLevelAndEndsOnDeparture() {
        Context context = isolatedContext();
        BlockContinuationStore store = new BlockContinuationStore(context);
        InterceptionDecision decision = repairDecision();
        PendingInterception shown = new PendingInterception(decision, "repair", "boot", 1_000L, 1_000L);
        assertTrue(store.ignore(shown, decision, 2_000L));
        BlockContinuation restored = new BlockContinuationStore(context).read("test.app", "boot").get(0);
        assertEquals(ControlLevel.REPAIR, restored.level);
        assertTrue(restored.matches(decision));
        assertFalse(restored.matches(pending("test.app").decision));
        store.observeForeground(context.getPackageName());
        assertEquals(1, store.read("test.app", "boot").size());
        store.observeForeground("home");
        assertTrue(store.read("test.app", "boot").isEmpty());
    }

    /** 页面档位已变化时拒绝旧确认；旧快照也不能清理另一档的新确认。 */
    @Test public void oldControlLevelCannotConfirmOrClearAnotherLevel() {
        BlockContinuationStore store = new BlockContinuationStore(isolatedContext());
        PendingInterception original = pending("test.app");
        assertTrue(store.ignore(original, original.decision, 2_000L));
        List<BlockContinuation> old = store.read("test.app", "boot");
        InterceptionDecision repair = repairDecision();
        PendingInterception changed = new PendingInterception(repair, original.sessionId, "boot", 1_000L, 1_000L);
        assertFalse(store.ignore(changed, original.decision, 2_000L));
        assertTrue(store.ignore(changed, repair, 2_000L));
        store.clearVisit("test.app", "boot", old);
        List<BlockContinuation> remaining = store.read("test.app", "boot");
        assertEquals(1, remaining.size());
        assertTrue(remaining.get(0).matches(repair));
    }

    /** @return 与无为测试同一规则身份的修心决定 */
    private static InterceptionDecision repairDecision() {
        return new InterceptionDecision("test.app", "group:1", 1L,
                InterceptionDecision.Kind.BLOCK, InterceptionDecision.Trigger.WINDOW_BAN,
                ControlLevel.REPAIR, "work", 1L, 1L, 10_000L, 180_000L, 0L);
    }

    /** @param trigger 禁用类型 @param window 时段身份 @param revision 策略版本 @return 同目标下的候选规则 */
    private static InterceptionDecision decision(InterceptionDecision.Trigger trigger, String window, long revision) {
        return new InterceptionDecision("test.app", "group:1", revision,
                InterceptionDecision.Kind.BLOCK, trigger, ControlLevel.MORTAL,
                window, 1L, 1L, 10_000L, 180_000L, 0L);
    }

    /** @return 不接触应用真实偏好的上下文，测试各使用独立命名空间 */
    private static Context isolatedContext() {
        String suffix = UUID.randomUUID().toString();
        return new ContextWrapper(InstrumentationRegistry.getInstrumentation().getTargetContext()) {
            /** @param name 原偏好名 @param mode 私有访问模式 @return 此用例专属偏好 */
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return super.getSharedPreferences("test." + suffix + "." + name, mode);
            }
        };
    }

    /** @param app 目标应用 @return 固定启动身份的合法禁用会话 */
    private static PendingInterception pending(String app) {
        InterceptionDecision decision = new InterceptionDecision(app, "group:1", 1L,
                InterceptionDecision.Kind.BLOCK, InterceptionDecision.Trigger.WINDOW_BAN,
                ControlLevel.MORTAL, "work", 1L, 1L, 10_000L, 180_000L, 0L);
        return new PendingInterception(decision, "session", "boot", 1_000L, 1_000L);
    }
}
