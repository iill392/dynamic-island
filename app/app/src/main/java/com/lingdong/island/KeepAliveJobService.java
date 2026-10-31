package com.lingdong.island;

import android.app.job.JobParameters;
import android.app.job.JobService;

/**
 * v8.82: second, independent keep-alive path using JobScheduler.
 *
 * The job is persisted across reboots and runs every 15 minutes. If the overlay service was killed
 * it is started again; the alarm path remains as a separate wake-up source.
 */
public class KeepAliveJobService extends JobService {
    @Override public boolean onStartJob(JobParameters params) {
        if (AlarmScheduler.isEnabled(this) && !IslandService.isRunning()) {
            try {
                IslandService.start(this, null);
            } catch (Throwable ignored) {}
        }
        AlarmScheduler.schedule(this);
        jobFinished(params, false);
        return false;
    }

    @Override public boolean onStopJob(JobParameters params) {
        return AlarmScheduler.isEnabled(this);
    }
}
