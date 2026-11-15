package github.vhoom.zhijian.ui.component.apps;

import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import github.vhoom.zhijian.R;
import github.vhoom.zhijian.data.model.AppInfo;
import github.vhoom.zhijian.data.model.policy.RuleSource;
import github.vhoom.zhijian.data.store.AppPolicyStore;
import github.vhoom.zhijian.data.source.InstalledAppSource;
import github.vhoom.zhijian.data.store.PolicySnapshot;

import java.util.ArrayList;
import java.util.List;

/**
 * 管控中心的应用行：真实图标 + 名称 + 标签。
 *
 * 维护日期：2026年11月08日
 */
public final class AppListAdapter extends BaseAdapter {

    private final AppPolicyStore policies;
    private final InstalledAppSource appSource;
    private final List<AppInfo> allApps = new ArrayList<>();

    /** 搜索过滤后的可见项，列表与计数都只认这份。 */
    private final List<AppInfo> visibleApps = new ArrayList<>();

    private String query = "";
    private PolicySnapshot policySnapshot;

    /**
     * 创建 AppListAdapter 实例，并初始化本次操作依赖或不可变字段。
     *
     * @param policies 同次提交中读取的策略集合或快照
     * @param appSource 读取已安装应用的系统数据源
     */
    public AppListAdapter(AppPolicyStore policies, InstalledAppSource appSource) {
        this.policies = policies;
        this.appSource = appSource;
    }

    /**
     * 更新应用列表数据并重新应用当前筛选词。
     *
     * @param apps 应用列表或策略绑定的应用集合
     */
    public void submit(List<AppInfo> apps) {
        allApps.clear();
        allApps.addAll(apps);
        applyQuery(query);
    }

    /**
     * 应用当前搜索词并更新列表的可见条目。
     *
     * @param query 用户输入的筛选关键词
     */
    public void applyQuery(String query) {
        this.query = query;
        policySnapshot = policies.snapshot();
        visibleApps.clear();
        visibleApps.addAll(AppListOrder.filter(allApps, query, policySnapshot::ruleSourceFor));
        notifyDataSetChanged();
    }

    /**
     * 返回筛选后可见应用的数量。
     */
    public int visibleCount() {
        return visibleApps.size();
    }

    /**
     * 返回指定位置的可见应用。
     *
     * @param position 条目在当前列表中的位置
     */
    public AppInfo itemAt(int position) {
        return visibleApps.get(position);
    }

    /**
     * 返回适配器当前可见条目数。
     */
    @Override
    public int getCount() {
        return visibleApps.size();
    }

    /**
     * 读取指定位置对应的数据项。
     *
     * @param position 条目在当前列表中的位置
     */
    @Override
    public AppInfo getItem(int position) {
        return visibleApps.get(position);
    }

    /**
     * 返回条目稳定标识，供列表复用使用。
     *
     * @param position 条目在当前列表中的位置
     */
    @Override
    public long getItemId(int position) {
        return position;
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
        View row = convertView != null
                ? convertView
                : LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
        AppInfo app = visibleApps.get(position);

        // 图标按行现取：ListView 复用使其数量维持在屏幕可见行左右。
        Drawable icon = appSource.iconOf(app.packageName);
        ((ImageView) row.findViewById(R.id.app_icon)).setImageDrawable(icon);
        ((TextView) row.findViewById(R.id.app_name)).setText(app.label);

        TextView tag = row.findViewById(R.id.app_tag);
        RuleSource source = policySnapshot.ruleSourceFor(app.packageName);
        tag.setVisibility(source.isActive() ? View.VISIBLE : View.GONE);
        if (source.isActive()) {
            String label = source == RuleSource.INDEPENDENT ? parent.getContext().getString(R.string.tag_independent)
                    : policySnapshot.groupNameFor(app.packageName);
            tag.setText(label);
            tag.setContentDescription(source == RuleSource.GROUP
                    ? parent.getContext().getString(R.string.tag_group_name_description, label) : label);
        }
        return row;
    }
}
