package github.vhoom.zhijian.data.model.runtime;

import java.util.Objects;

/**
 * 绝对期限，刷新倒计时不产生写入。bootId 由 Android 适配层提供。
 *
 * 维护日期：2026年11月08日
 */
public final class PassGrant {
    public final String packageName;
    public final String policyId;
    public final long policyRevision;
    public final String bootId;
    public final long issuedAtMs;
    public final long durationMs;
    public final long deadlineElapsedMs;

    /**
     * 创建 PassGrant 实例，并初始化本次操作依赖或不可变字段。
     *
     * @param packageName 需要查询或管控的应用包名
     * @param policyId 数据库中的稳定策略主键
     * @param policyRevision 签发时修订号，用于审计和提交校验；有效期限内按原授权范围执行
     * @param bootId 本次系统启动的稳定身份
     * @param issuedAtMs 临时放行签发的墙钟时间戳，单位为毫秒
     * @param durationMs 时长，单位为毫秒
     * @param deadlineElapsedMs 放行到期的系统启动后单调计时值
     */
    public PassGrant(String packageName, String policyId, long policyRevision, String bootId,
            long issuedAtMs, long durationMs, long deadlineElapsedMs) {
        this.packageName = Objects.requireNonNull(packageName);
        this.policyId = Objects.requireNonNull(policyId);
        this.policyRevision = policyRevision;
        this.bootId = Objects.requireNonNull(bootId);
        this.issuedAtMs = issuedAtMs;
        this.durationMs = durationMs;
        this.deadlineElapsedMs = deadlineElapsedMs;
        if (packageName.isEmpty() || policyId.isEmpty() || bootId.isEmpty() || policyRevision < 1
                || issuedAtMs < 0 || durationMs <= 0 || deadlineElapsedMs < durationMs) {
            throw new IllegalArgumentException("Invalid pass grant");
        }
        Math.addExact(issuedAtMs, durationMs);
    }

    /**
     * 同次开机依单调时钟；跨开机撤销旧放行，避免回拨日期并反复重启延长期限。
     *
     * @param currentBootId 当前系统启动身份，供判断放行是否跨重启
     * @param nowWallMs 当前墙钟时间戳，单位为毫秒
     * @param nowElapsedMs 当前系统启动后单调计时值，单位为毫秒
     */
    public long remainingMs(String currentBootId, long nowWallMs, long nowElapsedMs) {
        if (bootId.equals(currentBootId)) {
            long issuedElapsedMs = deadlineElapsedMs - durationMs;
            if (nowElapsedMs < issuedElapsedMs) return 0;
            return Math.max(0, deadlineElapsedMs - nowElapsedMs);
        }
        return 0L;
    }

    /** @param target 当前目标 @param effectivePolicyId 当前实际生效策略 @return 是否属于原授权范围 */
    public boolean covers(String target, String effectivePolicyId) {
        return policyId.equals(effectivePolicyId) && (packageName.equals(target) || policyId.startsWith("group:"));
    }
}
