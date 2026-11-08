package com.zzu.checkin;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

import java.util.Calendar;

/**
 * 巡检任务（第三道保险）。
 *
 * 闹钟有可能被系统的「深度休眠」清掉；JobScheduler 的周期任务由系统自己保管，
 * 被清掉后系统还会按周期重新拉起，所以拿它做兜底：
 * 每 15 分钟醒一次，只在 20:00~23:59 之间、且今天还没打上卡时动手。
 */
public class CheckinJob extends JobService {

    private static final int JOB_ID = 4101;
    private static final long PERIOD_MS = 15L * 60 * 1000;

    /** 登记（或刷新）周期任务。可重复调用。 */
    public static void schedule(Context ctx) {
        try {
            JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) {
                return;
            }
            ComponentName cn = new ComponentName(ctx, CheckinJob.class);
            JobInfo.Builder b = new JobInfo.Builder(JOB_ID, cn)
                    .setPeriodic(PERIOD_MS)
                    .setPersisted(true);
            js.schedule(b.build());
            CheckinStatus.appendLog("巡检任务已登记（每 15 分钟）");
        } catch (Throwable t) {
            CheckinStatus.appendLog("巡检任务登记失败：" + t.getMessage());
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        try {
            Calendar c = Calendar.getInstance();
            int hour = c.get(Calendar.HOUR_OF_DAY);
            boolean inWindow = hour >= 20 || hour <= 0;
            boolean done = CheckinStatus.isDoneToday(this);
            boolean running = CheckinStatus.isRunning();
            if (inWindow && !done && !running) {
                CheckinStatus.appendLog("巡检任务：今天还没打上，尝试补打");
                CheckinService.start(this);
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
