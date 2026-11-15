package github.vhoom.zhijian.ui.activity;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.ListView;
import android.widget.TextView;

import github.vhoom.zhijian.R;
import github.vhoom.zhijian.ui.component.dialog.AppDialogs;
import github.vhoom.zhijian.data.model.policy.RuleGroup;
import github.vhoom.zhijian.data.store.RuleGroupStore;
import github.vhoom.zhijian.util.ui.SystemBars;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 时间管理规则页：列出全部规则组，点击进编辑页，长按删除。
 * 生效优先级（单独配置 &gt; 最早创建的组）的解析在 RuleGroupStore 与拦截引擎里，这里只管配置入口。
 *
 * 维护日期：2026年11月08日
 */
public final class RulesGroupActivity extends ThemedActivity {

    private RuleGroupStore store;
    private GroupAdapter adapter;

    /**
     * 创建页面或进程依赖，并绑定本次启动所需的状态。
     *
     * @param savedInstanceState 系统保存的上次页面状态，首次创建时为 null
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        whenStorageReady(this::initialize);
    }

    /**
     * 存储就绪后绑定当前页面依赖、控件和数据。
     */
    private void initialize() {
        setContentView(R.layout.activity_rules_group);
        SystemBars.applyPadding(findViewById(R.id.root));

        store = new RuleGroupStore(this);
        adapter = new GroupAdapter();

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_new_rule).setOnClickListener(v ->
                startActivity(RuleGroupEditActivity.newIntent(this)));

        ListView list = findViewById(R.id.rule_groups);
        list.setAdapter(adapter);
        list.setEmptyView(findViewById(R.id.rules_empty));
        list.setOnItemClickListener((parent, view, position, id) -> openEditor(adapter.groups.get(position).id));
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            confirmDelete(adapter.groups.get(position));
            return true;
        });
        reload();
    }

    /**
     * 页面恢复可见后刷新配置和界面状态。
     */
    @Override
    protected void onResume() {
        super.onResume();
        if (adapter != null) persist(store.refreshBuiltInGroups(), this::reload);
    }

    /**
     * 从已提交存储快照重新加载规则组列表。
     */
    private void reload() {
        adapter.groups = store.list();
        adapter.notifyDataSetChanged();
    }

    /**
     * 打开指定规则组的编辑页。
     *
     * @param groupId 需要编辑的规则组稳定标识
     */
    private void openEditor(long groupId) {
        startActivity(RuleGroupEditActivity.editIntent(this, groupId));
    }

    /**
     * 展示删除确认，确认后再提交对应删除操作。
     *
     * @param group 待读取、校验或编辑的规则组
     */
    private void confirmDelete(RuleGroup group) {
        AppDialogs.builder(this)
                .setMessage(getString(R.string.rule_delete_confirm, group.name))
                .setPositiveButton(R.string.rule_delete, (dialog, which) -> {
                    persist(store.remove(group.id), this::reload);
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    /**
     * 从已提交快照绑定规则组列表和编辑入口。
     */
    private final class GroupAdapter extends BaseAdapter {

        private List<RuleGroup> groups = Collections.emptyList();

        /**
         * 返回适配器当前可见条目数。
         */
        @Override
        public int getCount() {
            return groups.size();
        }

        /**
         * 读取指定位置对应的数据项。
         *
         * @param position 条目在当前列表中的位置
         */
        @Override
        public Object getItem(int position) {
            return groups.get(position);
        }

        /**
         * 返回条目稳定标识，供列表复用使用。
         *
         * @param position 条目在当前列表中的位置
         */
        @Override
        public long getItemId(int position) {
            return groups.get(position).id;
        }

        /**
         * 复用或创建列表行，并绑定当前位置的数据和点击动作。
         *
         * @param position 条目在当前列表中的位置
         * @param convertView 可复用的旧列表行，首次创建时为 null
         * @param parent 列表行或动态视图所属的父容器
         */
        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView;
            if (row == null) {
                row = getLayoutInflater().inflate(R.layout.item_rule_group, parent, false);
            }
            RuleGroup group = groups.get(position);
            String title = getString(R.string.rule_group_name_with_count, group.name, group.apps.size());
            ((TextView) row.findViewById(R.id.group_name)).setText(title);
            row.setContentDescription(title);

            CheckBox box = row.findViewById(R.id.group_enabled);
            box.setOnCheckedChangeListener(null);
            box.setChecked(group.enabled);
            box.setText(group.enabled ? R.string.rule_state_enabled : R.string.rule_state_disabled);
            box.setTextColor(getColor(group.enabled ? R.color.level_selected_text : R.color.body));
            box.setContentDescription(getString(R.string.rule_state_for, group.name,
                    getString(group.enabled ? R.string.rule_state_enabled : R.string.rule_state_disabled)));
            box.setOnCheckedChangeListener((buttonView, isChecked) -> {
                RuleGroup changed = group.copy();
                changed.enabled = isChecked;
                box.setOnCheckedChangeListener(null);
                box.setChecked(group.enabled);
                CompletableFuture<Void> save = store.save(changed);
                persist(save, RulesGroupActivity.this::reload);
                save.whenComplete((ignored, failure) -> {
                    if (failure != null) runOnUiThread(() -> {
                        if (!isDestroyed()) reload();
                    });
                });
            });
            return row;
        }
    }
}
