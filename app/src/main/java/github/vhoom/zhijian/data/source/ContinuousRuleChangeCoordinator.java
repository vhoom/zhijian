package github.vhoom.zhijian.data.source;

import github.vhoom.zhijian.data.model.policy.RuleGroup;
import github.vhoom.zhijian.data.model.runtime.PendingInterception;
import github.vhoom.zhijian.data.store.PolicySnapshot;
import github.vhoom.zhijian.data.store.interception.PendingInterceptionStore;
import github.vhoom.zhijian.domain.interception.WeekdayRuleSchedule;
import github.vhoom.zhijian.domain.interception.InterceptionDecision;
import github.vhoom.zhijian.domain.interception.ReminderSchedule;
import github.vhoom.zhijian.data.model.policy.RuleType;
import github.vhoom.zhijian.data.model.policy.RuleWindow;
import java.util.List;
import java.util.Map;

/**
 * 在配置提交边界结算连续周期，旧规则负责提交前时间，新规则只接管后续时间和本轮要求。
 *
 * 维护日期：2026年11月16日
 */
public final class ContinuousRuleChangeCoordinator {
    private final ReminderUsageReader reader;
    private final PendingInterceptionStore pending;

    /** @param reader 与提交快照一致的周期适配器 @param pending 独立提醒事实存储 */
    public ContinuousRuleChangeCoordinator(ReminderUsageReader reader, PendingInterceptionStore pending) {
        this.reader = reader;
        this.pending = pending;
    }

    /**
     * 必须在后台及周期锁内调用；提交前后使用同一时间边界，不伪造前台观察。
     * @param policies 正在生效的已提交快照 @param bootId 启动身份 @param wallMs 墙钟边界
     * @param elapsedMs 单调边界 @param zoneId 设备时区
     */
    public void checkpoint(PolicySnapshot policies, String bootId, long wallMs, long elapsedMs, String zoneId) {
        for (Map.Entry<String, RuleGroup> entry : policies.configuredPolicies().entrySet()) {
            RuleGroup source = entry.getValue();
            List<PendingInterception> rests = pending.rests(entry.getKey());
            if (!source.enabled && rests.isEmpty()) continue;
            RuleGroup policy = WeekdayRuleSchedule.resolve(source, wallMs, zoneId);
            ReminderUsageReader.Snapshot usage = reader.read(policies, entry.getKey(), source, policy,
                    null, 0L, bootId, wallMs, elapsedMs, zoneId, rests);
            for (PendingInterception rest : rests) {
                if (usage.rested(rest)) continue;
                RuleWindow window = ReminderSchedule.window(policy, rest.decision.windowId);
                long configuredRest = rest.decision.trigger == InterceptionDecision.Trigger.WINDOW_CONTINUOUS
                        ? window == null ? 0L : window.interruptionPauseMillis
                        : policy.has(RuleType.CONTINUOUS) ? policy.interruptionPauseMillis : 0L;
                if (configuredRest > rest.decision.minimumInterruptionPauseDurationMs) {
                    pending.saveRest(rest.increaseRestRequirement(configuredRest));
                }
            }
        }
    }
}
