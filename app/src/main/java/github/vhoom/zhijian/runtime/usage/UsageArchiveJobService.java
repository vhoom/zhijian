package github.vhoom.zhijian.runtime.usage;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import github.vhoom.zhijian.util.diagnostics.DiagnosticLog;

/**
 * 系统周期任务补录近期使用数据，首页关闭后仍可归档，重启后由系统恢复任务。
 *
 * 维护日期：2026年11月08日
 */
public final class UsageArchiveJobService extends JobService {
    private static final int JOB_ID = 0x464c06;
    private static final long INTERVAL_MS = 15 * 60_000L;
    private final Handler main = new Handler(Looper.getMainLooper());
    private JobParameters active;

    /** @param context 应用上下文；相同任务已注册时保留原周期，不因每次启动推迟执行 */
    public static void schedule(Context context) {
        try {
            JobScheduler scheduler = context.getSystemService(JobScheduler.class);
            if (scheduler == null || scheduler.getPendingJob(JOB_ID) != null) return;
            int result = scheduler.schedule(new JobInfo.Builder(JOB_ID,
                    new ComponentName(context, UsageArchiveJobService.class))
                    .setPeriodic(INTERVAL_MS).setPersisted(true).build());
            if (result != JobScheduler.RESULT_SUCCESS) {
                DiagnosticLog.warn("usage_archive", "", "job_not_scheduled");
            }
        } catch (RuntimeException failure) {
            DiagnosticLog.error("usage_archive", "", "job_schedule_failed", failure);
        }
    }

    /** @param params 本次系统任务 @return 异步归档完成前保持任务运行 */
    @Override public boolean onStartJob(JobParameters params) {
        active = params;
        UsageArchiveCoordinator.synchronize(this, true).whenComplete((ignored, failure) -> main.post(() -> {
            if (active != params) return;
            active = null;
            jobFinished(params, failure != null);
        }));
        return true;
    }

    /** @param params 被系统停止的任务 @return 请求重试；已提交的幂等记录不会重复累计 */
    @Override public boolean onStopJob(JobParameters params) {
        if (active == params) active = null;
        return true;
    }
}
