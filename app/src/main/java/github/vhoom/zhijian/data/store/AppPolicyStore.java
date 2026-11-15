package github.vhoom.zhijian.data.store;

import android.content.Context;

import github.vhoom.zhijian.data.model.policy.RuleGroup;

import java.util.concurrent.CompletableFuture;

/**
 * 单应用覆盖与规则组使用相同数据库结构，优先级由 PolicySnapshot 统一解析。
 *
 * 维护日期：2026年11月14日
 */
public final class AppPolicyStore {
    private final ZhijianStorage storage;

    /**
     * 创建 AppPolicyStore 实例，并初始化本次操作依赖或不可变字段。
     *
     * @param context 访问系统服务、资源或持久化文件的上下文
     */
    public AppPolicyStore(Context context) {
        storage = ZhijianStorage.get(context);
    }

    /**
     * 检查指定应用是否存在已启用的独立策略。
     *
     * @param packageName 需要查询或管控的应用包名
     */
    public boolean hasOverride(String packageName) {
        return storage.snapshot().hasOverride(packageName);
    }

    /** 列表一次读取同一份快照，标签和排序不会混用不同提交版本。 */
    public PolicySnapshot snapshot() { return storage.snapshot(); }

    /**
     * 读取应用独立策略副本；不存在时返回 null。
     *
     * @param packageName 需要查询或管控的应用包名
     */
    public RuleGroup policyOf(String packageName) {
        return storage.snapshot().appPolicy(packageName);
    }

    /**
     * 保存已经通过校验的当前配置，提交完成后再通知界面。
     *
     * @param packageName 需要查询或管控的应用包名
     * @param policy 待判断或持久化的策略
     */
    public CompletableFuture<Void> save(String packageName, RuleGroup policy) {
        return storage.saveApp(packageName, policy);
    }

    /**
     * 删除应用独立策略覆盖，使规则组或全局配置恢复生效。
     *
     * @param packageName 需要查询或管控的应用包名
     */
    public CompletableFuture<Void> reset(String packageName) {
        return storage.removePolicy(PolicyCodec.appKey(packageName));
    }
}
