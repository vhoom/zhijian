package github.vhoom.zhijian.data.model.policy;

/**
 * 闭关中的业务拒绝；事务回滚后由界面给出明确提示。
 *
 * 维护日期：2026年11月08日
 */
public final class RetreatRestrictionException extends IllegalStateException {
    public final boolean levelChange;

    /** @param levelChange 本次拒绝的是强度切换还是规则变更 */
    public RetreatRestrictionException(boolean levelChange) {
        super(levelChange ? "Retreat restriction prevents switching level" : "Retreat restriction protects active rules");
        this.levelChange = levelChange;
    }
}
