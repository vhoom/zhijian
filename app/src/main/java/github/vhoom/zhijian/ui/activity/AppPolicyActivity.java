package github.vhoom.zhijian.ui.activity;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import github.vhoom.zhijian.R;
import github.vhoom.zhijian.ui.component.input.InputError;
import github.vhoom.zhijian.data.model.policy.RuleGroup;
import github.vhoom.zhijian.data.model.policy.RuleGroupValidator;
import github.vhoom.zhijian.data.model.policy.RuleInputLimits;
import github.vhoom.zhijian.data.store.AppPolicyStore;
import github.vhoom.zhijian.data.store.RuleGroupStore;
import github.vhoom.zhijian.ui.component.rule.CompactRuleField;
import github.vhoom.zhijian.ui.component.rule.NumberRow;
import github.vhoom.zhijian.ui.component.rule.RuleInputBinding;
import github.vhoom.zhijian.ui.component.rule.RuleParameterFactory;
import github.vhoom.zhijian.ui.component.rule.RuleValidationFeedback;
import github.vhoom.zhijian.ui.component.rule.RuleConditionsEditor;
import github.vhoom.zhijian.ui.component.rule.RuleDraftState;
import github.vhoom.zhijian.ui.component.rule.RuleInputFocus;
import github.vhoom.zhijian.ui.component.rule.RuleResetConfirmation;
import github.vhoom.zhijian.ui.component.rule.WeekdayRuleEditor;
import github.vhoom.zhijian.util.ui.SystemBars;

/**
 * 单应用配置优先于规则组；编辑草稿经校验和后台提交后才生效。
 *
 * 维护日期：2026年11月13日
 */
public final class AppPolicyActivity extends ThemedActivity {
    public static final String EXTRA_PACKAGE = "package_name";
    public static final String EXTRA_LABEL = "app_label";
    private static final String STATE_DRAFT = "policy_draft";

    private AppPolicyStore store;
    private String packageName;
    private RuleGroup policy;
    private boolean dirty;
    private boolean bindingPolicy;
    private Switch switchPolicy;
    private View policyBody;
    private TextView modeLine;
    private EditText passField;
    private NumberRow passRow;
    private RuleInputBinding passInput;
    private RuleValidationFeedback validationFeedback;
    private RuleConditionsEditor conditions;
    private AlertDialog resetDialog;
    private WeekdayRuleEditor weekdays;

    /**
     * 创建页面或进程依赖，并绑定本次启动所需的状态。
     *
     * @param savedInstanceState 系统保存的上次页面状态，首次创建时为 null
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        whenStorageReady(() -> initialize(savedInstanceState));
    }

    /**
     * 外部触摸结束规则输入，原生分发继续处理按钮、开关和滚动。
     * @param event 当前窗口的触摸事件
     * @return 原生控件是否处理了事件
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        RuleInputFocus.clearOnOutsideTouch(this, event);
        return super.dispatchTouchEvent(event);
    }

    /**
     * 存储就绪后绑定当前页面依赖、控件和数据。
     *
     * @param state 待恢复或处理的状态快照
     */
    private void initialize(Bundle state) {
        setContentView(R.layout.activity_app_policy);
        validationFeedback = new RuleValidationFeedback(this);
        SystemBars.applyPadding(findViewById(R.id.root));
        packageName = getIntent().getStringExtra(EXTRA_PACKAGE);
        if (packageName == null) { finish(); return; }
        String label = getIntent().getStringExtra(EXTRA_LABEL);
        ((TextView) findViewById(R.id.page_title)).setText(getString(R.string.app_policy_title,
                label == null ? packageName : label));
        store = new AppPolicyStore(this);
        policy = RuleDraftState.read(state, STATE_DRAFT);
        if (policy == null) policy = store.policyOf(packageName);
        if (policy == null) policy = newPolicy();
        dirty = state != null && state.getBoolean("dirty");
        bindingPolicy = true;

        switchPolicy = findViewById(R.id.switch_policy);
        findViewById(R.id.policy_header).setOnClickListener(
                v -> switchPolicy.setChecked(!switchPolicy.isChecked()));
        policyBody = findViewById(R.id.policy_body);
        modeLine = findViewById(R.id.policy_mode_line);
        passField = findViewById(R.id.field_pass);
        CompactRuleField.bind(passField);
        passRow = RuleParameterFactory.bindDuration(passField, policy.passDurationMillis, value -> {
            policy.passDurationMillis = value;
            InputError.clear(passField);
            onRuleDraftChanged();
        });
        passInput = new RuleInputBinding(passField, passRow, RuleInputLimits::pass, () -> true);
        bindConditions();
        bindWeekdays(state);
        conditions.restoreManualInputs(state);
        passInput.restore(state, "rule_raw_pass");

        switchPolicy.setChecked(state != null ? state.getBoolean("independent") : store.hasOverride(packageName));
        switchPolicy.setOnCheckedChangeListener((button, checked) -> {
            if (bindingPolicy) return;
            if (checked) {
                dirty = true;
                applyEnabledUi();
            } else {
                bindingPolicy = true;
                switchPolicy.setChecked(true);
                bindingPolicy = false;
                persist(store.reset(packageName), () -> {
                    dirty = false;
                    bindingPolicy = true;
                    switchPolicy.setChecked(false);
                    bindingPolicy = false;
                    applyEnabledUi();
                });
            }
        });
        bindingPolicy = false;
        findViewById(R.id.btn_back).setOnClickListener(v -> attemptClose());
        findViewById(R.id.btn_save).setOnClickListener(v -> {
            if (switchPolicy.isChecked()) savePolicy(true);
            else if (!isStorageBusy()) finish();
        });
        findViewById(R.id.btn_reset).setOnClickListener(v -> {
            if (isStorageBusy()) return;
            if (resetDialog != null) resetDialog.dismiss();
            resetDialog = RuleResetConfirmation.show(this, R.string.policy_reset_confirm, () -> {
                if (!isStorageBusy()) persist(store.reset(packageName), this::showResetPolicy);
            });
        });
        applyEnabledUi();
    }

    /**
     * 构造尚未保存的应用独立策略草稿。
     */
    private RuleGroup newPolicy() {
        RuleGroup fresh = new RuleGroup(-1, packageName);
        fresh.apps.add(packageName);
        fresh.passDurationMillis = RuleGroupStore.DEFAULT_PASS_DURATION_MILLIS;
        return fresh;
    }

    /**
     * 连接规则条件编辑器与当前草稿。
     */
    private void bindConditions() {
        if (conditions != null) conditions.close();
        conditions = new RuleConditionsEditor(this, findViewById(R.id.type_rows),
                policy, this::onRuleDraftChanged);
    }

    /** @param state 保存日期选择及取消基线的页面状态，首次进入或重置时可为 null */
    private void bindWeekdays(Bundle state) {
        if (weekdays != null) weekdays.close();
        weekdays = new WeekdayRuleEditor(this, findViewById(R.id.weekday_editor), policy, state,
                this::refreshRuleDraftUi, this::onRuleDraftChanged,
                () -> savePolicy(false), () -> savePolicy(false, true),
                this::showRuleValidationError);
        weekdays.bindCurrentDay();
    }

    /** 用户修改仅进入当前星期草稿，保存成功后才更新独立日配置。 */
    private void onRuleDraftChanged() {
        if (bindingPolicy) return;
        if (weekdays == null) dirty = true;
        else if (!weekdays.onParametersChanged()) return;
        if (switchPolicy != null && !switchPolicy.isChecked()) switchPolicy.setChecked(true);
    }

    /** 按查看或取消后的参数重建规则行，动态输入不共享控件状态。 */
    private void refreshRuleDraftUi() {
        if (policy.passDurationMillis > 0) passRow.apply(policy.passDurationMillis);
        else passRow.clear();
        InputError.clear(passField);
        bindConditions();
    }

    /** @param result 当前单日或七天完整校验的错误位置 */
    private void showRuleValidationError(RuleGroupValidator.Result result) {
        validationFeedback.show(result.tooLarge, () -> {
            boolean currentDay = weekdays == null || weekdays.showValidationDay(result.weekday);
            if (result.field == RuleGroupValidator.Field.PASS) {
                if (!currentDay) return weekdays.validationDayTarget(result.weekday);
                return RuleValidationFeedback.markInputError(passField,
                        RuleValidationFeedback.message(result.tooLarge));
            }
            return conditions.showError(result, currentDay);
        });
    }

    /**
     * 根据规则启用状态调整编辑控件。
     */
    private void applyEnabledUi() {
        boolean enabled = switchPolicy.isChecked();
        policyBody.setVisibility(View.VISIBLE);
        policyBody.setAlpha(enabled ? 1f : 0.68f);
        String group = store.snapshot().groupNameFor(packageName);
        modeLine.setText(enabled ? getString(R.string.policy_mode_independent)
                : group.isEmpty() ? getString(R.string.policy_mode_follow)
                : getString(R.string.policy_mode_group, group));
    }

    /**
     * 提交重置后恢复默认草稿及跟随状态。
     */
    private void showResetPolicy() {
        policy = newPolicy();
        dirty = false;
        bindingPolicy = true;
        switchPolicy.setChecked(false);
        passRow.apply(policy.passDurationMillis);
        InputError.clear(passField);
        bindConditions();
        bindWeekdays(null);
        bindingPolicy = false;
        applyEnabledUi();
        Toast.makeText(this, R.string.policy_reset_done, Toast.LENGTH_SHORT).show();
    }

    /**
     * 处理系统返回动作，必要时先校验并保存当前草稿。
     */
    @Override
    protected void onBackRequested() { attemptClose(); }

    /**
     * 校验草稿并尝试保存，失败时保留编辑页面。
     */
    private void attemptClose() {
        if (isStorageBusy()) return;
        if (switchPolicy == null || !switchPolicy.isChecked()) { finish(); return; }
        if (!dirty && !weekdays.hasPendingChanges()) { finish(); return; }
        savePolicy(true);
    }

    /** @param closeAfterSave 是否在事务成功后关闭页面；星期区域的保存保留当前页面 */
    private void savePolicy(boolean closeAfterSave) {
        savePolicy(closeAfterSave, false);
    }

    /** @param closeAfterSave 提交成功后是否退出 @param clearDay 是否只清除当前星期 */
    private void savePolicy(boolean closeAfterSave, boolean clearDay) {
        if (isStorageBusy() || !switchPolicy.isChecked()) return;
        conditions.clearErrors();
        InputError.clear(passField);
        if (!clearDay) {
            RuleInputBinding.Issue issue = conditions.validateManualInputs();
            if (issue == null) issue = passInput.validate();
            if (issue != null) {
                validationFeedback.show(issue);
                return;
            }
        }
        RuleGroup candidate = clearDay ? weekdays.prepareClear() : weekdays.prepareSave();
        if (candidate == null) return;
        RuleGroupValidator.Result result = RuleGroupValidator.validate(candidate);
        if (!result.isValid()) {
            showRuleValidationError(result);
            return;
        }
        persist(store.save(packageName, candidate), () -> {
            if (clearDay) weekdays.onCleared(candidate);
            else weekdays.onSaved(candidate);
            dirty = false;
            Toast.makeText(this, R.string.rule_saved, Toast.LENGTH_SHORT).show();
            if (closeAfterSave) finish();
        });
    }

    /**
     * 保存当前草稿和交互进度，供页面重建恢复。
     *
     * @param outState 写入页面重建所需数据的状态容器
     */
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (policy == null) return;
        RuleDraftState.write(outState, STATE_DRAFT, policy);
        if (weekdays != null) weekdays.saveState(outState);
        if (conditions != null) conditions.saveManualInputs(outState);
        if (passInput != null) passInput.save(outState, "rule_raw_pass");
        outState.putBoolean("dirty", dirty);
        outState.putBoolean("independent", switchPolicy != null && switchPolicy.isChecked());
    }

    /**
     * 释放本实例持有的监听、弹窗或后台回调。
     */
    @Override
    protected void onDestroy() {
        if (validationFeedback != null) validationFeedback.close();
        if (resetDialog != null) resetDialog.dismiss();
        if (weekdays != null) weekdays.close();
        if (conditions != null) conditions.close();
        super.onDestroy();
    }
}
