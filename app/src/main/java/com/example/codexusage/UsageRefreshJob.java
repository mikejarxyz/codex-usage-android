package com.example.codexusage;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;

/** A network-constrained job owns the background lifetime; manual refresh still uses a foreground service. */
public final class UsageRefreshJob extends JobService {
    static final int JOB_ID = 15001;
    static final long INTERVAL = 15 * 60 * 1000L;
    private JobParameters running;
    private UsageService service;
    private boolean bound;
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            if (running == null) return;
            service = ((UsageService.LocalBinder) binder).service();
            // Join an existing app/widget request, rather than starting a second native process.
            if (!service.loading) service.refreshBackground();
            service.observeBackground(() -> {
                if (running != null && !service.loading) complete();
            });
        }
        @Override public void onServiceDisconnected(ComponentName name) { complete(); }
        @Override public void onNullBinding(ComponentName name) { complete(); }
        @Override public void onBindingDied(ComponentName name) { complete(); }
    };

    static void schedule(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (!hasWidgets(context)) { scheduler.cancel(JOB_ID); return; }
        if (scheduler.getPendingJob(JOB_ID) != null) return;
        scheduler.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(context, UsageRefreshJob.class))
                .setPeriodic(INTERVAL).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true).build());
    }
    static void cancel(Context context) { context.getSystemService(JobScheduler.class).cancel(JOB_ID); }
    private static boolean hasWidgets(Context context) {
        return context.getSystemService(AppWidgetManager.class)
                .getAppWidgetIds(new ComponentName(context, UsageWidget.class)).length > 0;
    }
    static boolean due(UsageSnapshot snapshot, long lastAttempt, long now) {
        return snapshot != null && now - snapshot.updatedAt >= INTERVAL
                && (lastAttempt == 0 || now < lastAttempt || now - lastAttempt >= INTERVAL);
    }
    @Override public boolean onStartJob(JobParameters params) {
        if (!hasWidgets(this)) { cancel(this); return false; }
        long now = System.currentTimeMillis();
        long lastAttempt = getSharedPreferences("widget_state", MODE_PRIVATE).getLong("autoAttemptAt", 0);
        if (!due(UsageSnapshot.load(this), lastAttempt, now)) return false;
        getSharedPreferences("widget_state", MODE_PRIVATE).edit().putLong("autoAttemptAt", now).apply();
        running = params;
        try {
            bound = bindService(new Intent(this, UsageService.class), connection, Context.BIND_AUTO_CREATE);
        } catch (RuntimeException ignored) { bound = false; }
        if (!bound) { running = null; return false; }
        return true;
    }
    private void complete() {
        JobParameters params = running;
        running = null;
        detach();
        if (params != null) jobFinished(params, false);
    }
    private void detach() {
        if (service != null) service.observeBackground(null);
        service = null;
        if (bound) { bound = false; unbindService(connection); }
    }
    @Override public boolean onStopJob(JobParameters params) {
        running = null;
        detach();
        return false; // The next periodic run retries; never spin on a failed request.
    }
    @Override public void onDestroy() {
        running = null;
        detach();
        super.onDestroy();
    }
}
