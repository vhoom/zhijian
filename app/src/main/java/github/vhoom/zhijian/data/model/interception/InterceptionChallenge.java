package github.vhoom.zhijian.data.model.interception;

/**
 * 独立挑战目录，类型只绑定页面阶段，不引用状态机或设置存储。
 *
 * 维护日期：2026年11月14日
 */
public enum InterceptionChallenge {
    BUTTON_HOLD(InterceptionStage.HOLD),
    WORD_TRANSLATION(InterceptionStage.WORD_TRANSLATION),
    SUDOKU(InterceptionStage.SUDOKU);

    private final InterceptionStage stage;

    /**
     * 创建 Challenge 实例，并初始化本次操作依赖或不可变字段。
     *
     * @param stage 当前或目标交互阶段
     */
    InterceptionChallenge(InterceptionStage stage) {
        this.stage = stage;
    }

    /** 返回该摩擦方式对应的页面阶段。 */
    public InterceptionStage stage() {
        return stage;
    }

    /**
     * 按会话稳定选择一种摩擦方式，同一次拦截重建时不会跳变。
     *
     * @param seed 同次会话内保持随机结果稳定的种子
     */
    public static InterceptionChallenge selectFor(String seed) {
        InterceptionChallenge[] values = values();
        return values[Math.floorMod(seed == null ? 0 : seed.hashCode(), values.length)];
    }
}
