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
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;

/** A network-constrained job owns the background lifetime; manual refresh still uses a foreground service. */
public final class UsageRefreshJob extends JobService {
    static final int JOB_ID = 15001;
    static final long INTERVAL = 15 * 60 * 1000L;
    static final long FLEX = 5 * 60 * 1000L;
    // Android chooses the periodic run time. Leave room for jitter and request duration,
    // rather than requiring a full period since the previous response completed.
    static final long MIN_REFRESH_GAP = 5 * 60 * 1000L;
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
                if (running != null && !service.loading)
                    complete(service.error.isEmpty() && service.snapshot != null
                            ? "refresh_succeeded" : "refresh_failed");
            });
        }
        @Override public void onServiceDisconnected(ComponentName name) { complete("service_disconnected"); }
        @Override public void onNullBinding(ComponentName name) { complete("null_binding"); }
        @Override public void onBindingDied(ComponentName name) { complete("binding_died"); }
    };

    static void schedule(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (!hasWidgets(context)) { scheduler.cancel(JOB_ID); return; }
        JobInfo existing = scheduler.getPendingJob(JOB_ID);
        if (existing != null && existing.getIntervalMillis() == INTERVAL
                && existing.getFlexMillis() == FLEX && existing.isPersisted()) return;
        int result = scheduler.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(context, UsageRefreshJob.class))
                .setPeriodic(INTERVAL, FLEX).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true).build());
        record(context, result == JobScheduler.RESULT_SUCCESS ? "scheduled" : "schedule_failed");
    }
    static void cancel(Context context) { context.getSystemService(JobScheduler.class).cancel(JOB_ID); }
    private static boolean hasWidgets(Context context) {
        return context.getSystemService(AppWidgetManager.class)
                .getAppWidgetIds(new ComponentName(context, UsageWidget.class)).length > 0;
    }
    static boolean due(UsageSnapshot snapshot, boolean hasSession, long lastAttempt, long now) {
        return skipReason(snapshot, hasSession, lastAttempt, now) == null;
    }
    static String skipReason(UsageSnapshot snapshot, boolean hasSession, long lastAttempt, long now) {
        if (snapshot == null && !hasSession) return "skip_signed_out";
        if (snapshot != null && now >= snapshot.updatedAt && now - snapshot.updatedAt < MIN_REFRESH_GAP)
            return "skip_fresh";
        if (lastAttempt != 0 && now >= lastAttempt && now - lastAttempt < MIN_REFRESH_GAP)
            return "skip_cooldown";
        return null;
    }
    // Bounded local history contains only fixed outcomes, timestamps, and OS stop codes.
    static void record(Context context, String outcome) { record(context, outcome, -1); }
    private static void record(Context context, String outcome, int stopReason) {
        android.content.SharedPreferences diagnostics = context.getSharedPreferences("widget_refresh_diagnostics", MODE_PRIVATE);
        try {
            JSONArray previous = new JSONArray(diagnostics.getString("history", "[]"));
            JSONArray history = new JSONArray();
            for (int i = Math.max(0, previous.length() - 31); i < previous.length(); i++)
                history.put(previous.get(i));
            JSONObject event = new JSONObject().put("at", System.currentTimeMillis()).put("outcome", outcome);
            if (stopReason >= 0) event.put("stopReason", stopReason);
            history.put(event);
            diagnostics.edit().putString("history", history.toString()).apply();
        } catch (org.json.JSONException ignored) {
            diagnostics.edit().remove("history").apply();
        }
        android.util.Log.i("UsageRefreshJob", outcome + (stopReason >= 0 ? " stopReason=" + stopReason : ""));
    }
    @Override public boolean onStartJob(JobParameters params) {
        record(this, "job_started");
        if (!hasWidgets(this)) { record(this, "skip_no_widgets"); cancel(this); return false; }
        long now = System.currentTimeMillis();
        long lastAttempt = getSharedPreferences("widget_state", MODE_PRIVATE).getLong("autoAttemptAt", 0);
        String skip = skipReason(UsageSnapshot.load(this), new File(getFilesDir(), "codex/auth.json").isFile(), lastAttempt, now);
        if (skip != null) { record(this, skip); return false; }
        getSharedPreferences("widget_state", MODE_PRIVATE).edit().putLong("autoAttemptAt", now).apply();
        running = params;
        try {
            bound = bindService(new Intent(this, UsageService.class), connection, Context.BIND_AUTO_CREATE);
        } catch (RuntimeException ignored) { bound = false; }
        if (!bound) { record(this, "bind_failed"); running = null; return false; }
        return true;
    }
    private void complete(String outcome) {
        JobParameters params = running;
        running = null;
        if (params != null) record(this, outcome);
        detach();
        if (params != null) jobFinished(params, false);
    }
    private void detach() {
        if (service != null) service.observeBackground(null);
        service = null;
        if (bound) { bound = false; unbindService(connection); }
    }
    @Override public boolean onStopJob(JobParameters params) {
        record(this, "job_stopped", android.os.Build.VERSION.SDK_INT >= 31 ? params.getStopReason() : -1);
        running = null;
        detach();
        // A cancelled request did not finish. Let Android retry when constraints recover.
        getSharedPreferences("widget_state", MODE_PRIVATE).edit().remove("autoAttemptAt").apply();
        return true;
    }
    @Override public void onDestroy() {
        running = null;
        detach();
        super.onDestroy();
    }
}
