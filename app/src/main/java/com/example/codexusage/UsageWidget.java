package com.example.codexusage;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.RemoteViews;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class UsageWidget extends AppWidgetProvider {
    private static final String REFRESH_WIDGET = "com.example.codexusage.REFRESH_WIDGET";
    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        UsageRefreshJob.schedule(context);
        for (int id : ids) manager.updateAppWidget(id, views(context, compact(manager.getAppWidgetOptions(id))));
    }
    @Override public void onEnabled(Context context) { UsageRefreshJob.schedule(context); }
    @Override public void onDisabled(Context context) { UsageRefreshJob.cancel(context); }
    @Override public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int id, Bundle options) {
        manager.updateAppWidget(id, views(context, compact(options)));
    }
    private static boolean compact(Bundle options) {
        return options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 50) < 108
                || options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 180) < 220;
    }
    @Override public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (REFRESH_WIDGET.equals(intent.getAction())) {
            try {
                context.startForegroundService(new Intent(context, UsageService.class).setAction(UsageService.REFRESH));
            } catch (RuntimeException ignored) {
                context.getSharedPreferences("widget_state", Context.MODE_PRIVATE).edit()
                        .putBoolean("loading", false).putString("error", "Open the app to refresh.").apply();
                updateAll(context);
            }
        }
    }
    static void updateAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, UsageWidget.class));
        if (ids.length == 0) return;
        for (int id : ids) manager.updateAppWidget(id, views(context, compact(manager.getAppWidgetOptions(id))));
    }
    static RemoteViews views(Context context) {
        return views(context, false);
    }
    static RemoteViews views(Context context, boolean compact) {
        return views(context, compact, UsageSnapshot.load(context));
    }
    static RemoteViews views(Context context, boolean compact, UsageSnapshot snapshot) {
        boolean dual = snapshot != null && snapshot.windows.size() > 1;
        RemoteViews views = new RemoteViews(context.getPackageName(), dual
                ? (compact ? R.layout.usage_widget_dual_compact : R.layout.usage_widget_dual)
                : (compact ? R.layout.usage_widget_compact : R.layout.usage_widget));
        SharedPreferences state = context.getSharedPreferences("widget_state", Context.MODE_PRIVATE);
        boolean loading = state.getBoolean("loading", false);
        String error = state.getString("error", "");
        if (snapshot == null && (error.startsWith("Sign in to") || error.startsWith("Sign in with"))) error = "";
        UsageSnapshot.Window window = snapshot == null ? null : dual ? snapshot.windows.get(0) : snapshot.widgetWindow();
        views.setTextViewText(R.id.widget_percent, window == null ? "—" : window.percent() + " left");
        views.setContentDescription(R.id.widget_percent, window == null ? "Usage not available"
                : window.label() + ", " + window.percent() + " remaining");
        views.setProgressBar(R.id.widget_progress, 1000,
                window == null ? 0 : (int) Math.round(window.remaining() * 10), false);
        if (dual) {
            UsageSnapshot.Window second = snapshot.windows.get(1);
            views.setTextViewText(R.id.widget_limit_label, compact ? shortLabel(window) : window.label());
            views.setTextViewText(R.id.widget_second_label, compact ? shortLabel(second) : second.label());
            views.setTextViewText(R.id.widget_percent, window.percent() + (compact ? "" : " left"));
            views.setTextViewText(R.id.widget_second_percent, second.percent() + (compact ? "" : " left"));
            views.setContentDescription(R.id.widget_second_percent, second.label() + ", " + second.percent() + " remaining");
            views.setProgressBar(R.id.widget_second_progress, 1000, (int)Math.round(second.remaining() * 10), false);
        }
        String updated = snapshot == null ? "Open app to sign in" : "Updated "
                + new SimpleDateFormat(System.currentTimeMillis() - snapshot.updatedAt >= 86_400_000
                        ? "MMM d, HH:mm" : "HH:mm:ss", Locale.getDefault()).format(new Date(snapshot.updatedAt));
        views.setTextViewText(R.id.widget_updated, loading ? "Refreshing…"
                : !error.isEmpty() ? "Refresh failed · retry" : updated);
        views.setViewVisibility(R.id.widget_resets, snapshot != null && snapshot.availableResets != null
                ? View.VISIBLE : View.GONE);
        if (snapshot != null && snapshot.availableResets != null) {
            int count = snapshot.availableResets;
            views.setTextViewText(R.id.widget_resets, count + (count == 1 ? " reset" : " resets"));
            views.setContentDescription(R.id.widget_resets, count + " resets available");
        }
        views.setFloat(R.id.widget_refresh, "setAlpha", loading ? 0.3f : 0.65f);
        PendingIntent open = PendingIntent.getActivity(context, 0, new Intent(context, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_body, open);
        PendingIntent refresh = PendingIntent.getBroadcast(context, 1,
                new Intent(context, UsageWidget.class).setAction(REFRESH_WIDGET),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_refresh, refresh);
        views.setOnClickPendingIntent(R.id.widget_updated, error.isEmpty() ? open : refresh);
        return views;
    }
    private static String shortLabel(UsageSnapshot.Window window) {
        return window.minutes == 10080 ? "Week" : window.minutes == 300 ? "5h" : window.label();
    }
}
