package github.vhoom.zhijian.fixture;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 按注册顺序尝试全部恢复步骤，保留首个失败并汇总后续异常；只在测试线程使用。
 *
 * 维护日期：2026年11月14日
 */
public final class FixtureCleanup implements AutoCloseable {
    private final List<Action> actions = new ArrayList<>();
    private boolean initialized;
    private boolean closed;

    /** 允许恢复系统设置、文件或其他会抛出受检异常的测试资源。 */
    @FunctionalInterface
    public interface Action {
        /** 执行一个独立步骤，失败由恢复队列统一收集。 */
        void run() throws Exception;
    }

    /** 创建按显式顺序执行的测试恢复队列。 */
    public FixtureCleanup() { }

    /**
     * 在初始化之前登记恢复动作；动作的注册顺序就是清理顺序。
     * @param action 一个独立恢复步骤，不得为空
     */
    public void add(Action action) {
        if (initialized || closed) throw new IllegalStateException("Cleanup plan is already fixed");
        actions.add(Objects.requireNonNull(action));
    }

    /**
     * 执行可能产生部分修改的初始化，失败时立即执行已登记的恢复动作。
     * @param setup 初始化步骤，失败时保留其原始异常为主要错误
     */
    public void initialize(Action setup) throws Exception {
        if (initialized || closed) throw new IllegalStateException("Fixture cannot be initialized again");
        initialized = true;
        try {
            Objects.requireNonNull(setup).run();
        } catch (Exception | Error failure) {
            try {
                close();
            } catch (Exception | Error cleanupFailure) {
                if (cleanupFailure != failure) failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    /** 尝试全部步骤后抛出首个失败，后续失败作为 suppressed；重复关闭不重复执行。 */
    @Override public void close() throws Exception {
        if (closed) return;
        closed = true;
        Throwable firstFailure = null;
        for (Action action : actions) {
            try {
                action.run();
            } catch (Exception | Error failure) {
                if (firstFailure == null) firstFailure = failure;
                else if (firstFailure != failure) firstFailure.addSuppressed(failure);
            }
        }
        actions.clear();
        if (firstFailure instanceof Error) throw (Error) firstFailure;
        if (firstFailure != null) throw (Exception) firstFailure;
    }
}
