package com.linkrouter.app;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/** Refreshes yt-dlp every 12 hours when there is a network. Survives reboots. */
public class UpdateJob extends JobService {
    private static final int ID = 7;

    static void schedule(Context c) {
        try {
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js.getPendingJob(ID) != null) return;
            js.schedule(new JobInfo.Builder(ID, new ComponentName(c, UpdateJob.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(12L * 3600 * 1000)
                    .setPersisted(true)
                    .build());
        } catch (Throwable ignored) { }
    }

    @Override
    public boolean onStartJob(final JobParameters p) {
        new Thread(() -> {
            try { Downloader.updateEngine(this); } catch (Throwable ignored) { }
            jobFinished(p, false);
        }).start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters p) { return true; }
}
