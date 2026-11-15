package github.vhoom.zhijian.data.store;

import github.vhoom.zhijian.data.db.ZhijianDatabase;
import github.vhoom.zhijian.data.db.dao.PolicyDao;
import github.vhoom.zhijian.data.db.entity.StorageMetaEntity;
import github.vhoom.zhijian.data.db.entity.policy.PolicyEntity;
import github.vhoom.zhijian.data.model.policy.RuleGroup;
import github.vhoom.zhijian.data.model.policy.BuiltInGroupPreset;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.List;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * 内置组初始化与成员同步策略；只在存储写入队列及调用方事务内写库。
 *
 * 维护日期：2026年11月14日
 */
final class BuiltInRuleGroupStore {
    /** 用户移除的目录成员，安装刷新时继续排除。 */
    private static final String REMOVED = "built_in_group_removed:";
    private final IntFunction<String> names;
    private final Supplier<List<String>> installedApps;

    /** @param names 名称资源读取器 @param installedApps 后台读取安装交集的适配器 */
    BuiltInRuleGroupStore(IntFunction<String> names, Supplier<List<String>> installedApps) {
        this.names = names;
        this.installedApps = installedApps;
    }

    /** @return 本次后台读取的目录交集，不与系统适配器共享可变列表 */
    List<String> members() { return new ArrayList<>(installedApps.get()); }

    /**
     * 首次创建使用当前预设，已有组只同步成员；零成员仍保留。
     * @param database 调用方持有事务的数据库
     * @param members 本次系统读取的实际目录成员
     */
    void synchronize(ZhijianDatabase database, List<String> members) {
        PolicyDao dao = database.policies();
        PolicySnapshot snapshot = PolicySnapshot.load(dao);
        for (BuiltInGroupPreset preset : BuiltInGroupPreset.values()) {
            if ("true".equals(dao.meta(preset.dismissedKey))) continue;
            String key = PolicyCodec.groupKey(preset.groupId);
            PolicyEntity row = dao.policy(key);
            List<String> actual = members(dao, preset, members);
            RuleGroup group;
            if (row == null) {
                group = preset.create(names.apply(preset.nameRes), actual,
                        RuleGroupStore.DEFAULT_PASS_DURATION_MILLIS);
            } else {
                group = snapshot.group(preset.groupId);
                if (group.apps.equals(actual)) continue;
                group.apps.clear();
                group.apps.addAll(actual);
            }
            PolicyCodec.write(dao, PolicyCodec.entity(group, key, "group",
                    row == null ? 1L : Math.addExact(row.revision, 1L),
                    row == null ? dao.nextCreationOrder() : row.creationOrder), group);
        }
        // 成员刷新保持原授权期限；运行层仅向当前实际归属成员提供共享放行。
    }

    /** @param dao 当前事务的 DAO @param preset 默认组 @param installed 当前安装目录
     * @return 排除用户已移除应用后的成员，顺序保持目录顺序 */
    List<String> members(PolicyDao dao, BuiltInGroupPreset preset, List<String> installed) {
        List<String> members = preset.members(installed);
        members.removeAll(removed(dao, preset));
        return members;
    }

    /**
     * 仅记录明确的移除动作，过期草稿中缺少新安装应用不算用户移除。
     * @param dao 当前保存事务的 DAO @param preset 默认组 @param packages 明确移除的包名
     */
    void removeMembers(PolicyDao dao, BuiltInGroupPreset preset, Collection<String> packages) {
        if (packages.isEmpty()) return;
        if (!preset.packages.containsAll(packages)) throw new IllegalArgumentException("Apps outside preset");
        Set<String> removed = removed(dao, preset);
        if (removed.addAll(packages)) {
            dao.putMeta(new StorageMetaEntity(REMOVED + preset.groupId, new JSONArray(removed).toString()));
        }
    }

    /** @param dao 当前事务的 DAO @param preset 默认组 @return 已明确移除的包名集合 */
    private static Set<String> removed(PolicyDao dao, BuiltInGroupPreset preset) {
        String stored = dao.meta(REMOVED + preset.groupId);
        Set<String> removed = new LinkedHashSet<>();
        if (stored == null) return removed;
        try {
            JSONArray values = new JSONArray(stored);
            for (int index = 0; index < values.length(); index++) removed.add(values.getString(index));
        } catch (JSONException failure) {
            throw new IllegalStateException("Invalid removed default-group apps", failure);
        }
        return removed;
    }

    /** @param dao 删除事务使用的 DAO @param policyKey 删除的策略身份，各组独立记录 */
    static void dismiss(PolicyDao dao, String policyKey) {
        for (BuiltInGroupPreset preset : BuiltInGroupPreset.values()) {
            if (PolicyCodec.groupKey(preset.groupId).equals(policyKey)) {
                dao.putMeta(new StorageMetaEntity(preset.dismissedKey, "true"));
                return;
            }
        }
    }
}
