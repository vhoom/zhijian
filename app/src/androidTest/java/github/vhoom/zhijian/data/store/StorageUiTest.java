package github.vhoom.zhijian.data.store;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import github.vhoom.zhijian.R;
import github.vhoom.zhijian.data.model.policy.RuleType;
import github.vhoom.zhijian.ui.activity.AppPolicyActivity;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * 覆盖 StorageUi 的边界输入、状态变化与回归行为。
 *
 * 维护日期：2026年11月14日
 */
@RunWith(AndroidJUnit4.class)
public class StorageUiTest {
    private Context context;
    private ZhijianStorage storage;
    private String packageName;

    /**
     * 为 UI 测试创建独立规则身份，隔离用户规则。
     */
    @Before
    public void createTemporaryPolicyIdentity() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        storage = ZhijianStorage.get(context);
        storage.ready().get(30, TimeUnit.SECONDS);
        packageName = "zhijian.storage.test." + UUID.randomUUID();
    }

    /**
     * 只删除测试创建的规则，保留其他配置。
     */
    @After
    public void removeOnlyTemporaryPolicy() throws Exception {
        if (storage != null && packageName != null) {
            storage.removePolicy(PolicyCodec.appKey(packageName)).get(30, TimeUnit.SECONDS);
        }
    }

    /**
     * 验证返回编辑页时提交、重开恢复数值且重置结果可持久化。
     */
    @Test
    public void editorBackCommitsAndReopeningRestoresValuesThenResetPersists() throws Exception {
        Intent intent = new Intent(context, AppPolicyActivity.class)
                .putExtra(AppPolicyActivity.EXTRA_PACKAGE, packageName)
                .putExtra(AppPolicyActivity.EXTRA_LABEL, "Storage test");
        try (ActivityScenario<AppPolicyActivity> scenario = ActivityScenario.launch(intent)) {
            scenario.onActivity(activity -> {
                ((Switch) activity.findViewById(R.id.switch_policy)).setChecked(true);
                LinearLayout types = activity.findViewById(R.id.type_rows);
                ((Switch) types.getChildAt(RuleType.DAILY_LIMIT.ordinal())
                        .findViewById(R.id.type_check)).setChecked(true);
                ((EditText) types.getChildAt(RuleType.DAILY_LIMIT.ordinal())
                        .findViewById(R.id.param_field)).setText("37");
                ((EditText) activity.findViewById(R.id.field_pass)).setText("6");
                activity.findViewById(R.id.btn_back).performClick();
            });
            barrier();
            assertEquals(37 * 60_000L,
                    storage.snapshot().appPolicy(packageName).dailyLimitMillis);
            assertEquals(6 * 60_000L,
                    storage.snapshot().appPolicy(packageName).passDurationMillis);
        }
        try (ActivityScenario<AppPolicyActivity> scenario = ActivityScenario.launch(intent)) {
            scenario.onActivity(activity -> {
                assertTrue(((Switch) activity.findViewById(R.id.switch_policy)).isChecked());
                assertEquals("6", ((EditText) activity.findViewById(R.id.field_pass)).getText().toString());
                LinearLayout types = activity.findViewById(R.id.type_rows);
                assertEquals("37", ((EditText) types.getChildAt(RuleType.DAILY_LIMIT.ordinal())
                        .findViewById(R.id.param_field)).getText().toString());
                activity.findViewById(R.id.btn_reset).performClick();
            });
            barrier();
            assertFalse(storage.snapshot().hasOverride(packageName));
            scenario.onActivity(activity -> assertEquals(View.GONE,
                    activity.findViewById(R.id.policy_body).getVisibility()));
        }
    }

    /**
     * 等待存储队列已提交所有此前操作。
     */
    private void barrier() throws Exception {
        storage.submit(db -> null).get(30, TimeUnit.SECONDS);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    /**
     * 验证多时段草稿尚未保存时旋转也能恢复。
     */
    @Test
    public void multipleWindowDraftRestoresAfterRotationBeforeSaving() throws Exception {
        Intent intent = new Intent(context, AppPolicyActivity.class)
                .putExtra(AppPolicyActivity.EXTRA_PACKAGE, packageName);
        try (ActivityScenario<AppPolicyActivity> scenario = ActivityScenario.launch(intent)) {
            scenario.onActivity(activity -> {
                ((Switch) activity.findViewById(R.id.switch_policy)).setChecked(true);
                ((Switch) ruleCard(activity, RuleType.WINDOW_LIMIT)
                        .findViewById(R.id.type_check)).setChecked(true);
                LinearLayout limits = windowBody(activity, RuleType.WINDOW_LIMIT);
                ((EditText) limits.getChildAt(0).findViewById(R.id.param_field)).setText("30");
                limits.findViewById(R.id.btn_add_window).performClick();
                ((EditText) limits.getChildAt(1).findViewById(R.id.param_field)).setText("60");
                ((Switch) ruleCard(activity, RuleType.WINDOW_BAN)
                        .findViewById(R.id.type_check)).setChecked(true);
                windowBody(activity, RuleType.WINDOW_BAN).findViewById(R.id.btn_add_window).performClick();
                ((EditText) activity.findViewById(R.id.field_pass)).setText("6");
            });
            assertFalse(storage.snapshot().hasOverride(packageName));
            scenario.recreate();
            scenario.onActivity(activity -> {
                LinearLayout limits = windowBody(activity, RuleType.WINDOW_LIMIT);
                assertEquals("30", ((EditText) limits.getChildAt(0).findViewById(R.id.param_field)).getText().toString());
                assertEquals("60", ((EditText) limits.getChildAt(1).findViewById(R.id.param_field)).getText().toString());
                assertEquals(3, windowBody(activity, RuleType.WINDOW_BAN).getChildCount());
                assertEquals("6", ((EditText) activity.findViewById(R.id.field_pass)).getText().toString());
            });
            // 新增时段采用相同缺省区间，重叠草稿允许编辑和重建，但不能提交。
            assertFalse(storage.snapshot().hasOverride(packageName));
        }
    }

    /**
     * 读取测试目标时段对应的动态参数容器。
     *
     * @param activity 执行本次界面操作的 Activity
     * @param type 当前规则条件的类型
     */
    private static LinearLayout windowBody(AppPolicyActivity activity, RuleType type) {
        return ruleCard(activity, type).findViewById(R.id.condition_body);
    }

    /** @param activity 规则编辑页面 @param type 规则身份 @return 按身份查找的卡片 */
    private static View ruleCard(AppPolicyActivity activity, RuleType type) {
        LinearLayout types = activity.findViewById(R.id.type_rows);
        for (int i = 0; i < types.getChildCount(); i++) {
            View card = types.getChildAt(i);
            if (card.getTag() == type) return card;
        }
        throw new AssertionError("Missing rule: " + type);
    }
}
