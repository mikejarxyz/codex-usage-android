package com.example.codexusage;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.util.SizeF;
import android.view.View;
import android.widget.RemoteViews;
import java.util.LinkedHashMap;
import java.util.Map;

public final class UsageWidget extends AppWidgetProvider {
    private static final String REFRESH_WIDGET = "com.example.codexusage.REFRESH_WIDGET";
    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        UsageRefreshJob.schedule(context);
        for (int id : ids) manager.updateAppWidget(id, responsiveViews(context, manager.getAppWidgetOptions(id)));
    }
    @Override public void onEnabled(Context context) { UsageRefreshJob.schedule(context); }
    @Override public void onDisabled(Context context) { UsageRefreshJob.cancel(context); }
    @Override public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int id, Bundle options) {
        manager.updateAppWidget(id, responsiveViews(context, options));
    }
    static float stackedHeight(Context context, boolean narrow) {
        return (narrow ? 180 : 168) + Math.max(0, context.getResources().getConfiguration().fontScale - 1) * 140;
    }
    static RemoteViews responsiveViews(Context context, Bundle options) {
        return responsiveViews(context, options, UsageSnapshot.load(context));
    }
    static RemoteViews responsiveViews(Context context, Bundle options, UsageSnapshot snapshot) {
        if (Build.VERSION.SDK_INT >= 31) {
            Map<SizeF, RemoteViews> layouts = new LinkedHashMap<>();
            layouts.put(new SizeF(180, 50), views(context, true, snapshot));
            layouts.put(new SizeF(180, stackedHeight(context, true)), views(context, false, snapshot, true));
            layouts.put(new SizeF(240, stackedHeight(context, false)), views(context, false, snapshot));
            return new RemoteViews(layouts);
        }
        // The legacy ranges describe portrait width/landscape height and vice versa.
        // Give each orientation its own layout instead of testing both minima at once.
        return new RemoteViews(legacyViews(context, options, snapshot, true),
                legacyViews(context, options, snapshot, false));
    }
    static RemoteViews legacyViews(Context context, Bundle options, UsageSnapshot snapshot, boolean landscape) {
        int width = options.getInt(landscape ? AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH
                : AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 240);
        int height = options.getInt(landscape ? AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT
                : AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 180);
        boolean narrow = width < 240;
        return views(context, height < stackedHeight(context, narrow), snapshot, narrow);
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
        for (int id : ids) manager.updateAppWidget(id, responsiveViews(context, manager.getAppWidgetOptions(id)));
    }
    static RemoteViews views(Context context) {
        return views(context, false);
    }
    static RemoteViews views(Context context, boolean compact) {
        return views(context, compact, UsageSnapshot.load(context));
    }
    static RemoteViews views(Context context, boolean compact, UsageSnapshot snapshot) {
        return views(context, compact, snapshot, false);
    }
    private static RemoteViews views(Context context, boolean compact, UsageSnapshot snapshot, boolean narrow) {
        boolean dual = snapshot != null && snapshot.windows.size() > 1;
        boolean condensed = narrow || context.getResources().getConfiguration().fontScale > 1.1f;
        // Width variants need distinct IDs so the host reinflates rather than
        // reusing a TextView layout measured at the previous width.
        RemoteViews views = new RemoteViews(context.getPackageName(), dual
                ? (compact ? R.layout.usage_widget_dual_compact
                        : narrow ? R.layout.usage_widget_dual_narrow : R.layout.usage_widget_dual)
                : (compact ? R.layout.usage_widget_compact
                        : narrow ? R.layout.usage_widget_narrow : R.layout.usage_widget));
        SharedPreferences state = context.getSharedPreferences("widget_state", Context.MODE_PRIVATE);
        boolean loading = state.getBoolean("loading", false);
        String error = state.getString("error", "");
        if (snapshot == null && (error.startsWith("Sign in to") || error.startsWith("Sign in with"))) error = "";
        UsageSnapshot.Window window = snapshot == null ? null : dual ? snapshot.windows.get(0) : snapshot.widgetWindow();
        views.setTextViewText(R.id.widget_percent, window == null ? "—" : window.percent() + (condensed ? "" : " left"));
        views.setContentDescription(R.id.widget_percent, window == null ? "Usage not available"
                : window.label() + ", " + window.percent() + " remaining");
        views.setProgressBar(R.id.widget_progress, 1000,
                window == null ? 0 : (int) Math.round(window.remaining() * 10), false);
        if (!compact) {
            views.setInt(R.id.widget_footer, "setMinimumHeight", Math.round(28
                    * context.getResources().getConfiguration().fontScale * context.getResources().getDisplayMetrics().density));
            views.setTextViewText(R.id.widget_limit_label, window == null ? "" : condensed ? shortLabel(window) : window.label());
            views.setTextViewText(R.id.widget_cycle_reset, window == null ? "" : window.widgetResetLabel());
            views.setViewVisibility(R.id.widget_cycle_reset, window == null ? View.GONE : View.VISIBLE);
        }
        if (dual) {
            UsageSnapshot.Window second = snapshot.windows.get(1);
            views.setTextViewText(R.id.widget_limit_label, compact || condensed ? shortLabel(window) : window.label());
            views.setTextViewText(R.id.widget_second_label, compact || condensed ? shortLabel(second) : second.label());
            views.setTextViewText(R.id.widget_percent, window.percent() + (compact || condensed ? "" : " left"));
            views.setTextViewText(R.id.widget_second_percent, second.percent() + (compact || condensed ? "" : " left"));
            views.setContentDescription(R.id.widget_second_percent, second.label() + ", " + second.percent() + " remaining");
            views.setProgressBar(R.id.widget_second_progress, 1000, (int)Math.round(second.remaining() * 10), false);
            if (!compact) views.setTextViewText(R.id.widget_second_cycle_reset, second.widgetResetLabel());
        }
        String updated = snapshot == null ? "Open app to sign in" : (condensed ? "" : "Updated ")
                + UsageSnapshot.time(snapshot.updatedAt);
        String status = loading ? "Refreshing…" : !error.isEmpty()
                ? (condensed ? "Retry refresh" : "Refresh failed · retry") : updated;
        views.setTextViewText(R.id.widget_updated, status);
        views.setViewVisibility(R.id.widget_resets, snapshot != null && snapshot.availableResets != null
                && (compact || (!loading && error.isEmpty()))
                ? View.VISIBLE : View.GONE);
        if (snapshot != null && snapshot.availableResets != null) {
            int count = snapshot.availableResets;
            views.setTextViewText(R.id.widget_resets, count + (compact || condensed
                    ? (count == 1 ? " credit" : " credits") : (count == 1 ? " reset credit" : " reset credits")));
            views.setContentDescription(R.id.widget_resets, count + " reset credits available");
        }
        views.setContentDescription(R.id.widget_updated, snapshot != null && !loading && error.isEmpty()
                ? "Updated " + UsageSnapshot.expiry(snapshot.updatedAt / 1000) : status);
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
