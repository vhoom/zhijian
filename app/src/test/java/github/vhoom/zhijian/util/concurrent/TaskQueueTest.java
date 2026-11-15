package github.vhoom.zhijian.util.concurrent;

import org.junit.Test;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/**
 * 队列拥塞、异常和关闭均应有终态，不得把后台工作退回主线程或提前释放资源。
 *
 * 维护日期：2026年11月08日
 */
public class TaskQueueTest {
    /** 拥塞任务失败，已接受任务保持顺序且不会在调用线程执行。 */
    @Test public void fullQueueFailsFutureWithoutRunningOnCaller() throws Exception {
        TaskQueue queue = new TaskQueue("test-bounded", 1, 1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        try {
            CompletableFuture<Integer> first = queue.submit(() -> {
                started.countDown();
                release.await();
                return calls.incrementAndGet();
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            CompletableFuture<Integer> second = queue.submit(calls::incrementAndGet);
            CompletableFuture<Integer> rejected = queue.submit(calls::incrementAndGet);
            assertRejected(rejected);
            assertEquals(0, calls.get());
            release.countDown();
            assertEquals(1, (int) first.get(2, TimeUnit.SECONDS));
            assertEquals(2, (int) second.get(2, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            queue.shutdown().get(2, TimeUnit.SECONDS);
        }
    }

    /** 工作本身失败不会让后续任务或等待结果的页面永远卡住。 */
    @Test public void failedWorkDoesNotPoisonFollowingTasks() throws Exception {
        TaskQueue queue = new TaskQueue("test-failure", 1, 2);
        try {
            CompletableFuture<Void> failed = queue.submit(() -> { throw new IllegalStateException("failure"); });
            try { failed.get(2, TimeUnit.SECONDS); fail("Expected failed work"); }
            catch (ExecutionException failure) { assertTrue(failure.getCause() instanceof IllegalStateException); }
            assertEquals("next", queue.submit(() -> "next").get(2, TimeUnit.SECONDS));
        } finally {
            queue.shutdown().get(2, TimeUnit.SECONDS);
        }
    }

    /** 关闭完成信号等待运行与排队任务全部完成，关闭后的调用明确失败。 */
    @Test public void shutdownDrainsAcceptedTasksAndRejectsNewWork() throws Exception {
        TaskQueue queue = new TaskQueue("test-close", 1, 2);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            CompletableFuture<Void> running = queue.submit(() -> { started.countDown(); release.await(); return null; });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            CompletableFuture<String> queued = queue.submit(() -> "completed");
            CompletableFuture<Void> closed = queue.shutdown();
            assertFalse(closed.isDone());
            assertRejected(queue.submit(() -> "rejected"));
            release.countDown();
            running.get(2, TimeUnit.SECONDS);
            assertEquals("completed", queued.get(2, TimeUnit.SECONDS));
            closed.get(2, TimeUnit.SECONDS);
            queue.shutdown().get(2, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            queue.shutdown().get(2, TimeUnit.SECONDS);
        }
    }

    /** @param result 应已失败而不是挂起或同步抛出的任务结果 */
    private static void assertRejected(CompletableFuture<?> result) throws Exception {
        try { result.get(2, TimeUnit.SECONDS); fail("Expected rejected work"); }
        catch (ExecutionException failure) { assertTrue(failure.getCause() instanceof RejectedExecutionException); }
    }
}
