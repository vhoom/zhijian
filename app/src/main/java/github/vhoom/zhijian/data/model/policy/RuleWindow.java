package github.vhoom.zhijian.data.model.policy;

import java.util.UUID;

/**
 * 一个独立时段，稳定 id 用于编辑、存储及后续额度计量。结束早于开始表示跨午夜。
 *
 * 维护日期：2026年11月12日
 */
public final class RuleWindow {
    public final String id;
    public final RuleType type;
    public int startMinute;
    public int endMinute;
    public long limitDurationMillis;
    /** 时段限次的整数额度，与时长参数分别保存。 */
    public int limitCount;
    /** 时段连续提醒要求的连续离组休息；非提醒窗口不使用此字段。 */
    public long interruptionPauseMillis = RuleGroup.DEFAULT_INTERRUPTION_PAUSE_MILLIS;
    /** 运行时限定窗口的开始星期；0 表示每天，1 至 7 表示星期一至星期日。 */
    public int weekday;
    /** 星期窗口对应的放行时长；0 表示沿用调用方的默认值。 */
    public long scheduledPassDurationMillis;

    /**
     * 创建 RuleWindow 实例，并初始化本次操作依赖或不可变字段。
     *
     * @param id 当前记录、规则或视图的稳定标识
     * @param type 当前规则条件的类型
     * @param startMinute 一天内的开始分钟，范围为零至一千四百三十九
     * @param endMinute 一天内的结束分钟，跨午夜时可小于开始分钟
     * @param limitDurationMillis 该时段独立允许的用量，单位为毫秒
     */
    public RuleWindow(String id, RuleType type, int startMinute, int endMinute,
            long limitDurationMillis) {
        if (id == null || id.isEmpty() || type == null || !type.isWindow()) {
            throw new IllegalArgumentException("Invalid window identity");
        }
        this.id = id;
        this.type = type;
        this.startMinute = startMinute;
        this.endMinute = endMinute;
        this.limitDurationMillis = limitDurationMillis;
    }

    /**
     * 为时段创建稳定 ID，并保留类型、端点和独立额度。
     *
     * @param type 当前规则条件的类型
     * @param startMinute 一天内的开始分钟，范围为零至一千四百三十九
     * @param endMinute 一天内的结束分钟，跨午夜时可小于开始分钟
     */
    public static RuleWindow create(RuleType type, int startMinute, int endMinute) {
        return new RuleWindow(UUID.randomUUID().toString(), type, startMinute, endMinute, 0);
    }

    /**
     * 创建可独立修改的副本，避免草稿改动影响已提交快照。
     */
    public RuleWindow copy() {
        RuleWindow copy = new RuleWindow(id, type, startMinute, endMinute, limitDurationMillis);
        copy.weekday = weekday;
        copy.limitCount = limitCount;
        copy.interruptionPauseMillis = interruptionPauseMillis;
        copy.scheduledPassDurationMillis = scheduledPassDurationMillis;
        return copy;
    }
}
