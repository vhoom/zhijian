package github.vhoom.zhijian.data.store;

import github.vhoom.zhijian.data.db.ZhijianDatabase;
import github.vhoom.zhijian.data.db.dao.PolicyDao;
import github.vhoom.zhijian.data.db.entity.policy.PolicyEntity;
import github.vhoom.zhijian.data.model.policy.ControlLevel;
import github.vhoom.zhijian.data.model.policy.RuleGroup;

/**
 * 首次以随心强度创建全局配置；已有配置保持原值。
 *
 * 维护日期：2026年11月15日
 */
final class PolicyBootstrap {
    /** 初始化器不持有进程状态。 */
    private PolicyBootstrap() { }

    /**
     * 仅在缺少全局配置时写入随心强度和默认规则，保留已提交的用户选择。
     * @param database 当前开发基线的数据库
     */
    static void initialize(ZhijianDatabase database) {
        database.runInTransaction(() -> {
            PolicyDao dao = database.policies();
            if (dao.policy(PolicyCodec.GLOBAL) != null) return;
            RuleGroup global = new RuleGroup(0L, "");
            global.passDurationMillis = RuleGroupStore.DEFAULT_PASS_DURATION_MILLIS;
            PolicyEntity row = PolicyCodec.entity(global, PolicyCodec.GLOBAL,
                    "global", 1L, 0L);
            row.controlLevel = ControlLevel.MORTAL.code;
            PolicyCodec.write(dao, row, global);
        });
    }
}
