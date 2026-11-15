package github.vhoom.zhijian.ui.activity;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import github.vhoom.zhijian.R;
import github.vhoom.zhijian.ui.component.input.InputError;
import github.vhoom.zhijian.ui.component.dialog.AppDialogs;
import github.vhoom.zhijian.data.model.AppInfo;
import github.vhoom.zhijian.data.model.policy.RuleGroup;
import github.vhoom.zhijian.data.model.policy.RuleGroupValidator;
import github.vhoom.zhijian.data.model.policy.RuleInputLimits;
import github.vhoom.zhijian.data.model.policy.RuleConfigurationDefaults;
import github.vhoom.zhijian.data.model.policy.RuleGroupMembershipPolicy;
import github.vhoom.zhijian.data.model.policy.BuiltInGroupPreset;
import github.vhoom.zhijian.data.source.InstalledAppSource;
import github.vhoom.zhijian.data.store.RuleGroupStore;
import github.vhoom.zhijian.ui.component.rule.CompactRuleField;
import github.vhoom.zhijian.ui.component.rule.NumberRow;
import github.vhoom.zhijian.ui.component.rule.RuleConditionsEditor;
import github.vhoom.zhijian.ui.component.rule.RuleInputBinding;
import github.vhoom.zhijian.ui.component.rule.RuleParameterFactory;
import github.vhoom.zhijian.ui.component.rule.RuleValidationFeedback;
import github.vhoom.zhijian.ui.component.rule.RuleDraftState;
import github.vhoom.zhijian.ui.component.rule.RuleInputFocus;
import github.vhoom.zhijian.ui.component.rule.RuleResetConfirmation;
import github.vhoom.zhijian.ui.component.rule.WeekdayRuleEditor;
import github.vhoom.zhijian.util.config.AppConfig;
import github.vhoom.zhijian.util.ui.SystemBars;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 规则组草稿：名称和应用在此装配，规则参数交给共享编辑器，明确保存后提交。
 *
 * 维护日期：2026年11月13日
 */
public final class RuleGroupEditActivity extends ThemedActivity {
    private static final String EXTRA_ID = "group_id";
    private static final String STATE_DRAFT = "group_draft";
    private static final String STATE_REMOVED_APPS = "removed_default_apps";
    private static final int APP_PREVIEW_LIMIT = 3;

    /**
     * 构造新建规则组页面的启动参数。
     *
     * @param context 访问系统服务、资源或持久化文件的上下文
     */
    public static Intent newIntent(Context context) {
        return new Intent(context, RuleGroupEditActivity.class);
    }

    /**
     * 构造编辑指定规则组的启动参数。
     *
     * @param context 访问系统服务、资源或持久化文件的上下文
     * @param groupId 需要编辑的规则组稳定标识
     */
    public static Intent editIntent(Context context, long groupId) {
        return new Intent(context, RuleGroupEditActivity.class).putExtra(EXTRA_ID, groupId);
    }

    private RuleGroupStore store;
    private InstalledAppSource appSource;
    private RuleGroup group;
    private RuleConditionsEditor conditions;
    private WeekdayRuleEditor weekdays;
    private boolean isNew;
    private boolean dirty;
    private String suggestedName;
    private EditText nameField;
    private EditText passField;
    private NumberRow passRow;
    private RuleInputBinding passInput;
    private RuleValidationFeedback validationFeedback;
    private Button addAppButton;
    private TextView appsError;
    private TextView appsCount;
    private LinearLayout appRows;
    private AlertDialog dialog;
    private boolean appsLoaded;
    private boolean appsLoading;
    private final Map<String, AppInfo> appIndex = new HashMap<>();
    private final List<AppInfo> allApps = new ArrayList<>();
    /** 只保存用户点击移除的包名，避免过期草稿排除后来安装的应用。 */
    private final Set<String> removedApps = new LinkedHashSet<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor();

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
     * 外部触摸结束名称或规则输入，保留原生按钮、开关及滚动行为。
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
        setContentView(R.layout.activity_rule_group_edit);
        SystemBars.applyPadding(findViewById(R.id.root));
        store = new RuleGroupStore(this);
        appSource = new InstalledAppSource(this, new AppConfig(this));
        group = RuleDraftState.read(state, STATE_DRAFT);
        if (group != null) {
            isNew = state.getBoolean("new");
            dirty = state.getBoolean("dirty");
            suggestedName = state.getString("suggested_name", "");
            List<String> removed = state.getStringArrayList(STATE_REMOVED_APPS);
            if (removed != null) removedApps.addAll(removed);
        } else {
            long groupId = getIntent().getLongExtra(EXTRA_ID, -1);
            isNew = groupId == -1;
            group = isNew ? store.create() : store.byId(groupId);
            suggestedName = isNew ? group.name : "";
        }
        if (group == null) {
            Toast.makeText(this, R.string.rule_missing, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        nameField = findViewById(R.id.field_name);
        validationFeedback = new RuleValidationFeedback(this);
        CompactRuleField.bind(nameField);
        passField = findViewById(R.id.field_pass);
        CompactRuleField.bind(passField);
        appRows = findViewById(R.id.app_rows);
        appsError = findViewById(R.id.apps_error);
        appsCount = findViewById(R.id.selected_apps_count);
        addAppButton = findViewById(R.id.btn_add_app);
        addAppButton.setVisibility(
                BuiltInGroupPreset.isBuiltIn(group.id) ? View.GONE : View.VISIBLE);
        appsError.setOnClickListener(v -> loadApps());
        ((TextView) findViewById(R.id.rule_editor_title)).setText(
                isNew ? R.string.rule_new_title : R.string.rule_edit_title);
        nameField.setText(group.name);
        nameField.addTextChangedListener(new TextWatcher() {
            /**
             * 接收文本修改前通知；实际变更由修改完成回调处理。
             *
             * @param s 编辑器当前文本
             * @param start 变化起点或区间开始值
             * @param count 处理的条目数或本次变更的字符数量
             * @param after 变更后的文本数量或导出起始行号
             */
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            /**
             * 接收文本修改通知，具体保存由编辑完成回调处理。
             *
             * @param s 编辑器当前文本
             * @param start 变化起点或区间开始值
             * @param before 变更前的文本数量或待比较的旧记录
             * @param count 处理的条目数或本次变更的字符数量
             */
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            /**
             * 文本修改完成后更新草稿或筛选结果。
             *
             * @param s 编辑器当前文本
             */
            @Override public void afterTextChanged(Editable s) {
                InputError.clear(nameField);
                String name = s.toString().trim();
                if (!name.equals(group.name)) { group.name = name; dirty = true; }
            }
        });
        passRow = RuleParameterFactory.bindDuration(passField, group.passDurationMillis, value -> {
            group.passDurationMillis = value;
            InputError.clear(passField);
            onRuleDraftChanged();
        });
        passInput = new RuleInputBinding(passField, passRow, RuleInputLimits::pass, () -> true);
        conditions = new RuleConditionsEditor(this, findViewById(R.id.type_rows),
                group, this::onRuleDraftChanged);
        bindWeekdays(state);
        conditions.restoreManualInputs(state);
        passInput.restore(state, "rule_raw_pass");
        if (state != null && state.containsKey("rule_raw_name")) {
            nameField.setText(state.getString("rule_raw_name", ""));
        }
        findViewById(R.id.btn_back).setOnClickListener(v -> attemptClose());
        findViewById(R.id.btn_save).setOnClickListener(v -> saveAndClose());
        findViewById(R.id.btn_reset).setOnClickListener(v -> confirmReset());
        addAppButton.setOnClickListener(v -> {
            if (appsLoaded) showAppPicker();
            else loadApps();
        });
        View delete = findViewById(R.id.btn_delete);
        delete.setVisibility(isNew ? View.GONE : View.VISIBLE);
        delete.setOnClickListener(v -> confirmDelete());
        rebuildAppRows();
        loadApps();
    }

    /** 二次确认后只重置规则草稿，保留名称和已选应用，明确保存后才提交。 */
    private void confirmReset() {
        if (isStorageBusy()) return;
        dismissDialog();
        dialog = RuleResetConfirmation.show(this, R.string.rule_reset_confirm, () -> {
            if (isStorageBusy()) return;
            group = RuleConfigurationDefaults.resetCopy(group,
                    RuleGroupStore.DEFAULT_PASS_DURATION_MILLIS);
            dirty = true;
            bindWeekdays(null);
        });
    }

    /** @param state 页面重建状态；重置后传 null，以清除旧星期输入和复制来源 */
    private void bindWeekdays(Bundle state) {
        if (weekdays != null) weekdays.close();
        weekdays = new WeekdayRuleEditor(this, findViewById(R.id.weekday_editor), group, state,
                this::refreshRuleDraftUi, this::onRuleDraftChanged,
                () -> saveRules(false), () -> saveRules(false, true),
                this::showRuleValidationError);
        weekdays.bindCurrentDay();
    }

    /** 用户修改规则时先进入星期暂存区，名称及成员的修改继续使用页面草稿。 */
    private void onRuleDraftChanged() {
        if (weekdays == null) dirty = true;
        else weekdays.onParametersChanged();
    }

    /** 查看或取消日期选择后重建对应规则控件，保留名称及成员区域。 */
    private void refreshRuleDraftUi() {
        if (group.passDurationMillis > 0) passRow.apply(group.passDurationMillis);
        else passRow.clear();
        InputError.clear(passField);
        conditions.close();
        conditions = new RuleConditionsEditor(this, findViewById(R.id.type_rows),
                group, this::onRuleDraftChanged);
    }

    /** @param result 当前单日或七天完整校验的错误位置 */
    private void showRuleValidationError(RuleGroupValidator.Result result) {
        validationFeedback.show(result.tooLarge, () -> {
            boolean currentDay = weekdays == null || weekdays.showValidationDay(result.weekday);
            if (result.error == RuleGroupValidator.Error.APPS_REQUIRED) {
                appsError.setText(R.string.rule_value_invalid);
                appsError.setVisibility(View.VISIBLE);
                return addAppButton;
            }
            if (result.field == RuleGroupValidator.Field.PASS) {
                if (!currentDay) return weekdays.validationDayTarget(result.weekday);
                return RuleValidationFeedback.markInputError(passField,
                        RuleValidationFeedback.message(result.tooLarge));
            }
            return conditions.showError(result, currentDay);
        });
    }

    /**
     * 按当前草稿重建已选应用行。
     */
    private void rebuildAppRows() {
        appRows.removeAllViews();
        boolean builtIn = BuiltInGroupPreset.isBuiltIn(group.id);
        if (builtIn && !appsLoaded) {
            appsCount.setText(R.string.rule_apps_loading);
            return;
        }
        List<String> visibleApps = new ArrayList<>(group.apps);
        if (builtIn) visibleApps.removeIf(pkg -> !appIndex.containsKey(pkg));
        appsCount.setText(builtIn ? getString(R.string.rule_builtin_apps_count, visibleApps.size())
                : group.apps.isEmpty() ? getString(R.string.rule_apps_empty_hint)
                : getString(R.string.rule_apps_selected, group.apps.size()));
        int limit = builtIn ? visibleApps.size() : Math.min(APP_PREVIEW_LIMIT, visibleApps.size());
        for (int i = 0; i < limit; i++) {
            String pkg = visibleApps.get(i);
            View row = getLayoutInflater().inflate(R.layout.item_group_app, appRows, false);
            AppInfo info = appIndex.get(pkg);
            String label = info == null ? pkg : info.label;
            ((ImageView) row.findViewById(R.id.app_icon)).setImageDrawable(appSource.iconOf(pkg));
            TextView text = row.findViewById(R.id.app_label);
            text.setText(label);
            text.setMaxLines(1);
            text.setEllipsize(TextUtils.TruncateAt.END);
            View remove = row.findViewById(R.id.btn_remove);
            remove.setContentDescription(getString(R.string.rule_remove_app_a11y, label));
            remove.setOnClickListener(v -> {
                if (isStorageBusy()) return;
                group.apps.remove(pkg);
                if (builtIn) removedApps.add(pkg);
                dirty = true;
                appsError.setVisibility(View.GONE);
                rebuildAppRows();
            });
            appRows.addView(row);
        }
        if (!builtIn && group.apps.size() > APP_PREVIEW_LIMIT) {
            TextView more = new TextView(this, null, 0, R.style.Text_App_Caption);
            more.setText(getString(R.string.rule_apps_more, group.apps.size() - APP_PREVIEW_LIMIT));
            more.setTextColor(getColor(R.color.body));
            more.setPadding(0, getResources().getDimensionPixelSize(R.dimen.space_xs), 0, 0);
            appRows.addView(more);
        }
    }

    /**
     * 读取可启动应用，供规则组选择器使用。
     */
    private void loadApps() {
        if (appsLoading) return;
        boolean builtIn = BuiltInGroupPreset.isBuiltIn(group.id);
        List<String> members = new ArrayList<>(group.apps);
        appsLoading = true;
        addAppButton.setEnabled(false);
        addAppButton.setText(R.string.rule_apps_loading);
        io.execute(() -> {
            List<AppInfo> loaded;
            try {
                loaded = builtIn ? appSource.loadPackages(members) : appSource.loadLaunchableApps();
            } catch (RuntimeException failure) {
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    appsLoading = false;
                    addAppButton.setEnabled(true);
                    addAppButton.setText(R.string.rule_apps_retry);
                    if (builtIn) {
                        appsCount.setText(R.string.rule_apps_load_failed);
                        appsError.setText(R.string.rule_apps_retry);
                        appsError.setVisibility(View.VISIBLE);
                    }
                    Toast.makeText(this, R.string.rule_apps_load_failed, Toast.LENGTH_SHORT).show();
                });
                return;
            }
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                allApps.clear();
                allApps.addAll(loaded);
                appIndex.clear();
                for (AppInfo app : loaded) appIndex.put(app.packageName, app);
                appsLoading = false;
                appsLoaded = true;
                if (builtIn) appsError.setVisibility(View.GONE);
                addAppButton.setEnabled(true);
                addAppButton.setText(R.string.rule_manage_apps);
                rebuildAppRows();
            });
        });
    }

    /** 多选只改临时集合，完成后一次带回草稿；取消不会悄悄改变应用绑定。 */
    private void showAppPicker() {
        List<AppInfo> choices = new ArrayList<>(allApps);
        for (String pkg : group.apps) if (!appIndex.containsKey(pkg)) choices.add(new AppInfo(pkg, pkg));
        View content = getLayoutInflater().inflate(R.layout.dialog_app_picker, null, false);
        PickerAdapter adapter = new PickerAdapter(choices, group.apps);
        ListView list = content.findViewById(R.id.picker_list);
        list.setAdapter(adapter);
        list.setEmptyView(content.findViewById(R.id.picker_empty));
        ((EditText) content.findViewById(R.id.picker_search)).addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) { adapter.filter(s.toString()); }
        });
        dismissDialog();
        dialog = AppDialogs.builder(this)
                .setTitle(getString(R.string.rule_group_picker_count, adapter.selected.size(),
                        RuleGroupMembershipPolicy.MINIMUM_APPS))
                .setView(content)
                .setNegativeButton(R.string.dialog_cancel, null)
                .setPositiveButton(R.string.rule_picker_done, (d, which) -> {
                    List<String> chosen = new ArrayList<>(adapter.selected);
                    if (!chosen.equals(group.apps)) {
                        group.apps.clear();
                        group.apps.addAll(chosen);
                        dirty = true;
                        appsError.setVisibility(View.GONE);
                        rebuildAppRows();
                    }
                }).create();
        list.setOnItemClickListener((parent, view, position, id) -> {
            String pkg = adapter.visible.get(position).packageName;
            if (!adapter.selected.remove(pkg)) adapter.selected.add(pkg);
            adapter.notifyDataSetChanged();
            updatePickerSelection(adapter);
        });
        AppDialogs.show(dialog);
        updatePickerSelection(adapter);
    }

    /** @param adapter 保留搜索结果和选中应用的选择器；不足两个时不能完成添加 */
    private void updatePickerSelection(PickerAdapter adapter) {
        dialog.setTitle(getString(R.string.rule_group_picker_count, adapter.selected.size(),
                RuleGroupMembershipPolicy.MINIMUM_APPS));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(
                RuleGroupMembershipPolicy.hasMinimumApps(adapter.selected));
    }

    /**
     * 展示删除确认，确认后再提交对应删除操作。
     */
    private void confirmDelete() {
        dismissDialog();
        dialog = AppDialogs.builder(this)
                .setMessage(getString(R.string.rule_delete_confirm, group.name))
                .setPositiveButton(R.string.rule_delete, (d, which) -> persist(store.remove(group.id), () -> {
                    dirty = false;
                    finish();
                }))
                .setNegativeButton(R.string.dialog_cancel, null).show();
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
        if (!dirty && (weekdays == null || !weekdays.hasPendingChanges())) { finish(); return; }
        dismissDialog();
        dialog = AppDialogs.builder(this)
                .setMessage(R.string.rule_discard_confirm)
                .setPositiveButton(R.string.rule_discard, (d, which) -> finish())
                .setNegativeButton(R.string.rule_continue_editing, null).show();
    }

    /**
     * 保存通过校验的草稿，提交成功后关闭页面。
     */
    private void saveAndClose() {
        saveRules(true);
    }

    /** @param closeAfterSave 是否在事务成功后关闭页面；单日保存留在页面继续编辑其他星期 */
    private void saveRules(boolean closeAfterSave) {
        saveRules(closeAfterSave, false);
    }

    /** @param closeAfterSave 提交成功后是否退出 @param clearDay 是否只清除当前星期 */
    private void saveRules(boolean closeAfterSave, boolean clearDay) {
        if (isStorageBusy()) return;
        InputError.clear(nameField);
        InputError.clear(passField);
        conditions.clearErrors();
        RuleInputBinding.Issue issue = RuleInputBinding.validateName(nameField);
        if (!clearDay && issue == null) issue = conditions.validateManualInputs();
        if (!clearDay && issue == null) issue = passInput.validate();
        if (issue != null) {
            validationFeedback.show(issue);
            return;
        }
        if (!RuleGroupMembershipPolicy.hasValidGroupMembers(group)) {
            validationFeedback.show(false, () -> {
                appsError.setText(R.string.rule_value_invalid);
                appsError.setVisibility(View.VISIBLE);
                return addAppButton;
            });
            return;
        }
        appsError.setVisibility(View.GONE);
        RuleGroup candidate = clearDay ? weekdays.prepareClear() : weekdays.prepareSave();
        if (candidate == null) return;
        RuleGroupValidator.Result result = BuiltInGroupPreset.isBuiltIn(candidate.id)
                ? RuleGroupValidator.validateConfiguration(candidate) : RuleGroupValidator.validate(candidate);
        if (!result.isValid()) {
            showRuleValidationError(result);
            return;
        }
        boolean generatedName = group.name.isEmpty() || isNew && group.name.equals(suggestedName);
        candidate.name = generatedName ? store.nextDefaultName(group.id) : group.name.trim();
        persist(store.save(candidate, new ArrayList<>(removedApps)), () -> {
            group.name = candidate.name;
            if (generatedName) suggestedName = candidate.name;
            nameField.setText(candidate.name);
            removedApps.clear();
            RuleGroup saved = store.byId(candidate.id);
            if (saved != null) {
                group.apps.clear();
                group.apps.addAll(saved.apps);
            }
            if (clearDay) weekdays.onCleared(candidate);
            else weekdays.onSaved(candidate);
            dirty = false;
            isNew = false;
            ((TextView) findViewById(R.id.rule_editor_title)).setText(R.string.rule_edit_title);
            findViewById(R.id.btn_delete).setVisibility(View.VISIBLE);
            Toast.makeText(this, R.string.rule_saved, Toast.LENGTH_SHORT).show();
            if (closeAfterSave) finish();
            else loadApps();
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
        if (group == null) return;
        RuleDraftState.write(outState, STATE_DRAFT, group);
        if (weekdays != null) weekdays.saveState(outState);
        if (conditions != null) conditions.saveManualInputs(outState);
        if (passInput != null) passInput.save(outState, "rule_raw_pass");
        if (nameField != null) outState.putString("rule_raw_name", nameField.getText().toString());
        outState.putBoolean("new", isNew);
        outState.putBoolean("dirty", dirty);
        outState.putString("suggested_name", suggestedName);
        outState.putStringArrayList(STATE_REMOVED_APPS, new ArrayList<>(removedApps));
    }

    /**
     * 关闭当前弹窗并释放其引用。
     */
    private void dismissDialog() {
        if (dialog != null) dialog.dismiss();
        dialog = null;
    }

    /**
     * 释放本实例持有的监听、弹窗或后台回调。
     */
    @Override
    protected void onDestroy() {
        if (validationFeedback != null) validationFeedback.close();
        if (weekdays != null) weekdays.close();
        if (conditions != null) conditions.close();
        dismissDialog();
        io.shutdownNow();
        super.onDestroy();
    }

    /**
     * 按查询词筛选应用，并绑定规则组选择器条目。
     */
    private final class PickerAdapter extends BaseAdapter {
        private final List<AppInfo> source;
        private final List<AppInfo> visible = new ArrayList<>();
        private final Set<String> selected = new LinkedHashSet<>();

        /**
         * 创建 PickerAdapter 实例，并初始化本次操作依赖或不可变字段。
         *
         * @param apps 应用列表或策略绑定的应用集合
         * @param selected 当前选中的规则或档位
         */
        PickerAdapter(List<AppInfo> apps, List<String> selected) {
            source = apps;
            visible.addAll(apps);
            this.selected.addAll(selected);
        }

        /**
         * 返回适配器当前可见条目数。
         */
        @Override public int getCount() { return visible.size(); }
        /**
         * 读取指定位置对应的数据项。
         *
         * @param position 条目在当前列表中的位置
         */
        @Override public AppInfo getItem(int position) { return visible.get(position); }
        /**
         * 返回条目稳定标识，供列表复用使用。
         *
         * @param position 条目在当前列表中的位置
         */
        @Override public long getItemId(int position) { return position; }

        /**
         * 复用或创建列表行，并绑定当前位置的数据和点击动作。
         *
         * @param position 条目在当前列表中的位置
         * @param convertView 可复用的旧列表行，首次创建时为 null
         * @param parent 列表行或动态视图所属的父容器
         */
        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView == null ? getLayoutInflater().inflate(R.layout.item_app_choice, parent, false) : convertView;
            AppInfo app = visible.get(position);
            ((ImageView) row.findViewById(R.id.app_icon)).setImageDrawable(appSource.iconOf(app.packageName));
            ((TextView) row.findViewById(R.id.app_label)).setText(app.label);
            boolean checked = selected.contains(app.packageName);
            ((CheckBox) row.findViewById(R.id.app_choice)).setChecked(checked);
            row.setContentDescription(getString(checked ? R.string.rule_app_selected_description
                    : R.string.rule_app_unselected_description, app.label));
            return row;
        }

        /**
         * 按输入关键词筛选应用，并保留既定条目顺序。
         *
         * @param text 需要解析、筛选或显示的文本
         */
        void filter(String text) {
            String query = text.trim().toLowerCase(Locale.CHINA);
            visible.clear();
            for (AppInfo app : source) if (app.label.toLowerCase(Locale.CHINA).contains(query)) visible.add(app);
            notifyDataSetChanged();
        }
    }
}
