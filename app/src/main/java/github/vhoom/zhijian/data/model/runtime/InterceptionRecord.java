package github.vhoom.zhijian.data.model.runtime;

import java.util.Objects;

/**
 * 结束后的拦截事实。原因和交互采用稳定文本标识，原规则删除后仍可复盘。
 *
 * 维护日期：2026年11月08日
 */
public final class InterceptionRecord {
    public final String id;
    public final String packageName;
    public final String policyId;
    public final long policyRevision;
    public final String kind;
    public final String controlLevel;
    public final String reason;
    public final String challenge;
    public final String outcome;
    public final long occurredAtMs;
    public final long finishedAtMs;

    /**
     * 创建 InterceptionRecord 实例，并初始化本次操作依赖或不可变字段。
     *
     * @param id 当前记录、规则或视图的稳定标识
     * @param packageName 需要查询或管控的应用包名
     * @param policyId 数据库中的稳定策略主键
     * @param policyRevision 签发或完成时的策略修订号，用于提交校验和历史审计
     * @param kind 普通拦截或打断拦截的类别
     * @param controlLevel 该审计记录对应的管控档位
     * @param reason 记录结束或触发的原因
     * @param challenge 本次会话选择的摩擦挑战
     * @param outcome 本次拦截最终的允许或放弃结果
     * @param occurredAtMs 拦截触发的墙钟时间戳，单位为毫秒
     * @param finishedAtMs 拦截结束的墙钟时间戳，单位为毫秒
     */
    public InterceptionRecord(String id, String packageName, String policyId, long policyRevision,
            String kind, String controlLevel, String reason, String challenge, String outcome,
            long occurredAtMs, long finishedAtMs) {
        this.id = Objects.requireNonNull(id);
        this.packageName = Objects.requireNonNull(packageName);
        this.policyId = Objects.requireNonNull(policyId);
        this.policyRevision = policyRevision;
        this.kind = Objects.requireNonNull(kind);
        this.controlLevel = Objects.requireNonNull(controlLevel);
        this.reason = Objects.requireNonNull(reason);
        this.challenge = Objects.requireNonNull(challenge);
        this.outcome = Objects.requireNonNull(outcome);
        this.occurredAtMs = occurredAtMs;
        this.finishedAtMs = finishedAtMs;
        if (id.isEmpty() || packageName.isEmpty() || policyId.isEmpty() || kind.isEmpty()
                || controlLevel.isEmpty() || reason.isEmpty() || outcome.isEmpty() || policyRevision < 1
                || occurredAtMs < 0 || finishedAtMs < occurredAtMs) {
            throw new IllegalArgumentException("Invalid interception record");
        }
    }
}
