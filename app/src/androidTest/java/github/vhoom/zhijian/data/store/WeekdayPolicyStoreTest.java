package github.vhoom.zhijian.data.store;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import github.vhoom.zhijian.data.db.ZhijianDatabase;
import github.vhoom.zhijian.data.db.dao.PolicyDao;
import github.vhoom.zhijian.data.db.entity.policy.PolicyEntity;
import github.vhoom.zhijian.data.model.policy.RuleDayParameterAdapter;
import github.vhoom.zhijian.data.model.policy.RuleDayParameters;
import github.vhoom.zhijian.data.model.policy.RuleGroup;
import github.vhoom.zhijian.data.model.policy.RuleType;
import github.vhoom.zhijian.data.model.policy.RuleWindow;

import org.json.JSONObject;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.*;

/**
 * Android JSON 与 Room 的星期配置回归，始终使用独立临时数据库。
 *
 * 维护日期：2026年11月14日
 */
@RunWith(AndroidJUnit4.class)
public class WeekdayPolicyStoreTest {
    /** 单应用和规则组通过相同编解码写入，数据库关闭重开后保留所有新次数及逐日差异。 */
    @Test
    public void countRulesRoundTripAcrossRoomReopenForBothScopes() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        for (String scope : new String[]{"app", "group"}) {
            String name = "count-policy-" + UUID.randomUUID() + ".db";
            ZhijianDatabase database = ZhijianDatabase.open(context, name);
            try {
                RuleGroup group = countConfigured();
                String key = scope.equals("app") ? PolicyCodec.appKey("app.example") : PolicyCodec.groupKey(group.id);
                PolicyDao dao = database.policies();
                PolicyEntity row = PolicyCodec.entity(group, key, scope, 1L, 1L);
                database.runInTransaction(() -> PolicyCodec.write(dao, row, group));
                database.close();
                database = ZhijianDatabase.open(context, name);
                PolicyDao reopened = database.policies();
                RuleGroup restored = PolicyCodec.group(reopened.policy(key), reopened.rules(),
                        reopened.apps(), reopened.windows());
                assertEquals(1, restored.count);
                assertEquals(6_000L, restored.frequencyWindowMillis);
                assertEquals(2, restored.frequencyCount);
                assertEquals(3, restored.windows.get(0).limitCount);
                assertEquals(0L, restored.windows.get(0).limitDurationMillis);
                assertEquals(99, restored.weekdayRules.get(1).count);
                assertEquals(12_000L, restored.weekdayRules.get(1).frequencyWindowMillis);
                assertEquals(99, restored.weekdayRules.get(1).frequencyCount);
                assertEquals(99, restored.weekdayRules.get(1).windows.get(0).limitCount);
                assertEquals(2, restored.weekdayRules.get(2).frequencyCount);
                assertEquals(3, restored.weekdayRules.get(2).windows.get(0).limitCount);
            } finally {
                database.close();
                context.deleteDatabase(name);
            }
        }
    }

    /** 关闭草稿中的超范围次数也不能覆盖已提交策略、规则行或时段额度。 */
    @Test
    public void invalidCountWritesDoNotReplaceCommittedPolicyRows() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String name = "invalid-count-" + UUID.randomUUID() + ".db";
        ZhijianDatabase database = ZhijianDatabase.open(context, name);
        try {
            PolicyDao dao = database.policies();
            RuleGroup original = countConfigured();
            PolicyEntity row = PolicyCodec.entity(original, "group:8", "group", 1L, 1L);
            database.runInTransaction(() -> PolicyCodec.write(dao, row, original));
            for (RuleType type : new RuleType[]{RuleType.DAILY_COUNT, RuleType.ROLLING_COUNT, RuleType.WINDOW_COUNT}) {
                RuleGroup invalid = original.copy();
                invalid.enabled = false;
                if (type == RuleType.DAILY_COUNT) invalid.count = 100;
                if (type == RuleType.ROLLING_COUNT) invalid.frequencyCount = 100;
                if (type == RuleType.WINDOW_COUNT) invalid.windows.get(0).limitCount = 100;
                PolicyEntity replacement = PolicyCodec.entity(invalid, row.id, "group", 2L, 1L);
                try {
                    database.runInTransaction(() -> PolicyCodec.write(dao, replacement, invalid));
                    fail("Invalid stored count must not replace the committed policy");
                } catch (IllegalArgumentException expected) { }
                assertEquals(1L, dao.policy(row.id).revision);
                RuleGroup restored = PolicyCodec.group(dao.policy(row.id), dao.rules(), dao.apps(), dao.windows());
                assertEquals(1, restored.count);
                assertEquals(2, restored.frequencyCount);
                assertEquals(3, restored.windows.get(0).limitCount);
            }
        } finally {
            database.close();
            context.deleteDatabase(name);
        }
    }

    /** 当前格式拒绝缺失次数、小数、字符串和溢出整数，不允许 getInt 静默截断。 */
    @Test
    public void weekdayCodecRejectsMissingOrNonIntegerCountFields() throws Exception {
        String encoded = WeekdayPolicyCodec.encode(countConfigured().weekdayRules);
        for (String field : new String[]{"count", "frequencyCount", "windowCount"}) {
            String key = field.equals("windowCount") ? "count" : field;
            for (Object value : new Object[]{-1, 100, 4_294_967_297L, 1.5D, "2"}) {
                JSONObject invalid = new JSONObject(encoded);
                JSONObject day = invalid.getJSONObject("days").getJSONObject("1");
                JSONObject parameters = field.equals("windowCount")
                        ? day.getJSONArray("windows").getJSONObject(0) : day;
                parameters.put(key, value);
                assertInvalidWeekday(invalid.toString());
            }
            JSONObject missing = new JSONObject(encoded);
            JSONObject day = missing.getJSONObject("days").getJSONObject("1");
            JSONObject parameters = field.equals("windowCount")
                    ? day.getJSONArray("windows").getJSONObject(0) : day;
            parameters.remove(key);
            assertInvalidWeekday(missing.toString());
        }
        JSONObject missingDuration = new JSONObject(encoded);
        missingDuration.getJSONObject("days").getJSONObject("1").remove("frequencyWindow");
        assertInvalidWeekday(missingDuration.toString());
    }

    /**
     * 确认损坏的星期参数不能被静默读取为其他额度。
     *
     * @param text 具有缺失或非法次数字段的当前格式 JSON
     */
    private static void assertInvalidWeekday(String text) {
        try {
            WeekdayPolicyCodec.decode(text);
            fail("Invalid weekday counts must be rejected without coercion");
        } catch (IllegalArgumentException expected) { }
    }

    /** @return 包含三种限次且星期一使用 99 次额度的完整七日策略 */
    private static RuleGroup countConfigured() {
        RuleGroup group = new RuleGroup(8L, "限次");
        group.apps.add("app.example");
        group.types.add(RuleType.DAILY_COUNT);
        group.types.add(RuleType.ROLLING_COUNT);
        group.types.add(RuleType.WINDOW_COUNT);
        group.count = 1;
        group.frequencyWindowMillis = 6_000L;
        group.frequencyCount = 2;
        group.passDurationMillis = 180_000L;
        RuleWindow window = new RuleWindow("count-window", RuleType.WINDOW_COUNT, 540, 600, 0L);
        window.limitCount = 3;
        group.windows.add(window);
        RuleDayParameters initial = RuleDayParameterAdapter.capture(group);
        RuleDayParameters monday = initial.copy();
        monday.count = 99;
        monday.frequencyWindowMillis = 12_000L;
        monday.frequencyCount = 99;
        monday.windows.get(0).limitCount = 99;
        RuleDayParameterAdapter.applyToDays(group, 1, monday, initial);
        return group;
    }

    /** 时段提醒参数在 JSON 与 Room 往返后保持独立，缺少休息字段时拒绝读取。 */
    @Test public void reminderWindowsRoundTripWithOwnRestAndRejectIncompleteData() throws Exception {
        RuleGroup group = configured();
        github.vhoom.zhijian.data.model.policy.RuleWindow window = new github.vhoom.zhijian.data.model.policy.RuleWindow(
                "reminder", RuleType.WINDOW_CONTINUOUS, 1140, 1200, 600_000L);
        window.interruptionPauseMillis = 300_000L;
        group.types.add(RuleType.WINDOW_CONTINUOUS);
        group.windows.add(window);
        group.weekdayRules.get(1).types.add(RuleType.WINDOW_CONTINUOUS);
        group.weekdayRules.get(1).windows.add(window.copy());
        String encoded = WeekdayPolicyCodec.encode(group.weekdayRules);
        assertEquals(300_000L, WeekdayPolicyCodec.decode(encoded).get(1).windows.get(0).interruptionPauseMillis);
        org.json.JSONObject incomplete = new org.json.JSONObject(encoded);
        incomplete.getJSONObject("days").getJSONObject("1").getJSONArray("windows").getJSONObject(0).remove("pause");
        try {
            WeekdayPolicyCodec.decode(incomplete.toString());
            fail("Missing rest must not silently shorten the configured pause");
        } catch (IllegalArgumentException expected) { }
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String name = "reminder-window-" + UUID.randomUUID() + ".db";
        ZhijianDatabase db = ZhijianDatabase.open(context, name);
        try {
            PolicyEntity row = PolicyCodec.entity(group, "group:8", "group", 1, 1);
            db.runInTransaction(() -> PolicyCodec.write(db.policies(), row, group));
            RuleGroup restored = PolicyCodec.group(db.policies().policy(row.id), db.policies().rules(),
                    db.policies().apps(), db.policies().windows());
            assertEquals(300_000L, restored.windows.get(0).interruptionPauseMillis);
            assertEquals(600_000L, restored.weekdayRules.get(1).windows.get(0).limitDurationMillis);
        } finally { db.close(); context.deleteDatabase(name); }
    }
    /** 编码后解码保留每个星期的独立毫秒参数，损坏版本必须报错。 */
    @Test public void codecPreservesDifferentWeekdaysAndRejectsUnknownVersion() throws Exception {
        RuleGroup group = configured();
        String encoded = WeekdayPolicyCodec.encode(group.weekdayRules);
        Map<Integer, RuleDayParameters> restored = WeekdayPolicyCodec.decode(encoded);
        assertEquals(7, restored.size());
        assertEquals(120_000, restored.get(1).dailyLimitMillis);
        assertEquals(60_000, restored.get(2).dailyLimitMillis);
        assertEquals(120_000, restored.get(5).dailyLimitMillis);
        for (int version : new int[]{1, 2, 99}) {
            JSONObject unsupported = new JSONObject(encoded);
            unsupported.put("version", version);
            try {
                WeekdayPolicyCodec.decode(unsupported.toString());
                fail("Unsupported version must not be silently accepted");
            } catch (IllegalArgumentException expected) { }
        }
    }

    /** 同一事务写入后重新读库，星期配置与父策略修订保持一致。 */
    @Test public void roomRoundTripKeepsEachWeekdayIndependent() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String name = "weekday-test-" + UUID.randomUUID() + ".db";
        ZhijianDatabase database = ZhijianDatabase.open(context, name);
        try {
            RuleGroup group = configured();
            PolicyDao dao = database.policies();
            PolicyEntity entity = PolicyCodec.entity(group, "group:8", "group", 3, 1);
            database.runInTransaction(() -> PolicyCodec.write(dao, entity, group));
            RuleGroup restored = PolicyCodec.group(dao.policy("group:8"), dao.rules(), dao.apps(), dao.windows());
            assertEquals(120_000, restored.weekdayRules.get(1).dailyLimitMillis);
            assertEquals(60_000, restored.weekdayRules.get(2).dailyLimitMillis);
            assertEquals(3, dao.policy("group:8").revision);
            restored.weekdayRules.get(1).dailyLimitMillis = 900_000;
            assertEquals(120_000, WeekdayPolicyCodec.decode(dao.policy("group:8").weekdayRulesJson)
                    .get(1).dailyLimitMillis);
        } finally {
            database.close();
            context.deleteDatabase(name);
        }
    }

    /** @return 仅星期一和星期五改变额度的完整七天策略 */
    private static RuleGroup configured() {
        RuleGroup group = new RuleGroup(8, "工作");
        group.apps.add("app.example");
        group.types.add(RuleType.DAILY_LIMIT);
        group.dailyLimitMillis = 60_000;
        group.passDurationMillis = 180_000;
        RuleDayParameters initialParameters = RuleDayParameterAdapter.capture(group);
        RuleDayParameters selected = initialParameters.copy();
        selected.dailyLimitMillis = 120_000;
        RuleDayParameterAdapter.applyToDays(group, 17, selected, initialParameters);
        return group;
    }
}
