package com.example.codexusage;

import android.app.Activity;
import android.app.Instrumentation;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** Device checks for quota semantics and the real RemoteViews layout, without auth payloads. */
public final class UsageChecks extends Instrumentation {
    private AppWidgetHost widgetHost;
    private boolean signOutCheck;
    private boolean compatibilityCheck;
    private boolean licensesCheck;
    private boolean hardeningCheck;
    private boolean refreshLayoutCheck;
    private boolean schedulingPolicyCheck;
    private boolean backgroundStatusCheck;
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        signOutCheck = arguments != null && "true".equals(arguments.getString("signOutCheck"));
        compatibilityCheck = arguments != null && "true".equals(arguments.getString("compatibilityCheck"));
        licensesCheck = arguments != null && "true".equals(arguments.getString("licensesCheck"));
        hardeningCheck = arguments != null && "true".equals(arguments.getString("hardeningCheck"));
        refreshLayoutCheck = arguments != null && "true".equals(arguments.getString("refreshLayoutCheck"));
        schedulingPolicyCheck = arguments != null && "true".equals(arguments.getString("schedulingPolicyCheck"));
        backgroundStatusCheck = arguments != null && "true".equals(arguments.getString("backgroundStatusCheck"));
        start();
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        int resultCode = Activity.RESULT_OK;
        try {
            if (backgroundStatusCheck) {
                UsageSnapshot saved = UsageSnapshot.load(getTargetContext());
                android.content.SharedPreferences state = getTargetContext().getSharedPreferences("widget_state", 0);
                JSONObject status = new JSONObject().put("now", System.currentTimeMillis())
                        .put("updatedAt", saved == null ? JSONObject.NULL : saved.updatedAt)
                        .put("loading", state.getBoolean("loading", false))
                        .put("autoAttemptAt", state.getLong("autoAttemptAt", 0))
                        .put("history", new JSONArray(getTargetContext().getSharedPreferences(
                                "widget_refresh_diagnostics", 0).getString("history", "[]")));
                android.app.usage.StorageStats storage = getTargetContext().getSystemService(
                        android.app.usage.StorageStatsManager.class).queryStatsForUid(
                                android.os.storage.StorageManager.UUID_DEFAULT, android.os.Process.myUid());
                status.put("appBytes", storage.getAppBytes()).put("dataBytes", storage.getDataBytes())
                        .put("cacheBytes", storage.getCacheBytes());
                JSONArray largestFiles = new JSONArray();
                java.nio.file.Path data = getTargetContext().getDataDir().toPath();
                try (java.util.stream.Stream<java.nio.file.Path> paths = java.nio.file.Files.walk(data)) {
                    paths.filter(path -> java.nio.file.Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                            .sorted(java.util.Comparator.comparingLong((java.nio.file.Path path) -> path.toFile().length()).reversed())
                            .limit(8).forEach(path -> {
                                try { largestFiles.put(new JSONObject().put("path", data.relativize(path).toString())
                                        .put("bytes", path.toFile().length())); }
                                catch (org.json.JSONException e) { throw new AssertionError(e); }
                            });
                }
                status.put("largestPrivateFiles", largestFiles);
                result.putString("stream", status.toString() + "\n");
                return;
            }
            checkSchedulingPolicy();
            if (schedulingPolicyCheck) {
                checkUnusedPluginCacheCleanup();
                result.putString("stream", "Scheduling jitter, cooldown, cache recovery eligibility, clock rollback, and plugin-cache cleanup safety checks passed.\n");
                return;
            }
            if (refreshLayoutCheck) {
                checkRefreshLayout();
                File preview = new File(getTargetContext().getCacheDir(), "refresh-pressed-preview.png");
                result.putString("pressedPreview", android.util.Base64.encodeToString(
                        java.nio.file.Files.readAllBytes(preview.toPath()), android.util.Base64.NO_WRAP));
                result.putString("checkMessage", "Refresh bounds, compact footer, pressed state, stable loading geometry, and expanded touch target passed.");
                return;
            }
            if (hardeningCheck) {
                checkRecovery();
                result.putString("stream", "Cache failure, backend timeout, local logout recovery, and dual widget checks passed.\n");
                return;
            }
            if (licensesCheck) {
                checkLicenses();
                result.putString("stream", "Bundled license texts and restricted local license viewer passed.\n");
                return;
            }
            if (compatibilityCheck) {
                checkAndroid36Layout();
                result.putString("stream", "API 36 insets/layout and ARM64 backend initialization via translation passed on the 16 KB x86 emulator.\n");
                return;
            }
            if (signOutCheck) {
                checkSignOut();
                result.putString("stream", "Isolated official logout, cache clearing, and signed-out widget checks passed.\n");
                return;
            }
            checkUnusedPluginCacheCleanup();
            long now = 1_800_000_000_000L;
            JSONObject response = response(now);
            UsageSnapshot quota = UsageSnapshot.fromResponse(response, now);
            check(quota.windows.size() == 2, "Both quota windows must be shown");
            check(quota.widgetWindow().minutes == 300, "Widget must show most depleted window");
            check(quota.availableResets == null, "Missing reset data must not mean zero");
            check(quota.updatedAt == now, "Successful fetch time must be recorded");

            JSONObject credits = new JSONObject().put("availableCount", 3).put("credits", JSONObject.NULL);
            response.put("rateLimitResetCredits", credits);
            quota = UsageSnapshot.fromResponse(response, now);
            check(quota.availableResets == 3 && !quota.resetDetailsKnown, "Count-only resets");
            JSONArray rows = new JSONArray()
                    .put(new JSONObject().put("status", "available").put("title", "Full reset").put("expiresAt", now/1000 + 3600))
                    .put(new JSONObject().put("status", "available").put("expiresAt", JSONObject.NULL))
                    .put(new JSONObject().put("status", "available").put("expiresAt", now/1000 - 1))
                    .put(new JSONObject().put("status", "redeemed").put("expiresAt", now/1000 + 3600));
            credits.put("credits", rows);
            quota = UsageSnapshot.fromResponse(response, now);
            check(quota.availableResets == 3 && quota.resets.size() == 2,
                    "Authoritative count must survive capped/expired detail rows");
            check(quota.resets.get(1).expiresAt == null, "Unknown expiry must stay unknown");
            credits.put("availableCount", 0).put("credits", new JSONArray());
            quota = UsageSnapshot.fromResponse(response, now);
            check(quota.availableResets == 0 && quota.resetDetailsKnown, "Explicit zero reset state");
            JSONObject byId = new JSONObject().put("codex", response.getJSONObject("rateLimits"))
                    .put("unrelated", new JSONObject().put("primary", window(99, 60, now)));
            response.put("rateLimitsByLimitId", byId);
            check(UsageSnapshot.fromResponse(response, now).windows.size() == 2,
                    "Unrelated metered buckets must not leak into Codex display");
            response.getJSONObject("rateLimits").put("primary", window(150, 300, now));
            check(UsageSnapshot.fromResponse(response, now).widgetWindow().remaining() == 0,
                    "Over-limit progress must be clamped");
            boolean rejected = false;
            try { UsageSnapshot.fromResponse(new JSONObject(), now); }
            catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "Incomplete quota must not be presented as a successful refresh");

            Activity activity = startActivitySync(new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            long deadline = System.currentTimeMillis() + 25_000;
            UsageSnapshot actual;
            do {
                Thread.sleep(250);
                actual = UsageSnapshot.load(getTargetContext());
            } while (actual == null && System.currentTimeMillis() < deadline);
            check(actual != null, "Real quota snapshot must be available after launch");
            UsageSnapshot finalActual = actual;
            runOnMainSync(() -> {
                FrameLayout host = new FrameLayout(getTargetContext());
                View widget = UsageWidget.views(getTargetContext()).apply(getTargetContext(), host);
                host.addView(widget);
                check(((TextView) widget.findViewById(R.id.widget_percent)).getText().toString()
                        .equals((finalActual.windows.size() > 1 ? finalActual.windows.get(0) : finalActual.widgetWindow()).percent() + " left"),
                        "Widget must render real remaining quota");
                renderImage(host, 280, finalActual.windows.size() > 1 ? 124 : 84, "widget-preview.png");
                FrameLayout compactHost = new FrameLayout(getTargetContext());
                View compactWidget = UsageWidget.views(getTargetContext(), true).apply(getTargetContext(), compactHost);
                compactHost.addView(compactWidget);
                renderImage(compactHost, 180, 50, "widget-compact-preview.png");
                View refresh = widget.findViewById(R.id.widget_refresh);
                int[] bottom = new int[2];
                refresh.getLocationInWindow(bottom);
                check(bottom[1] + refresh.getHeight() <= host.getHeight(), "Compact widget refresh must fit");
                View compactUpdated = compactWidget.findViewById(R.id.widget_updated);
                int beforeY = compactUpdated.getTop();
                int beforeHeight = compactWidget.getMeasuredHeight();
                getTargetContext().getSharedPreferences("widget_state", 0).edit().putBoolean("loading", true).commit();
                UsageWidget.views(getTargetContext(), true).reapply(getTargetContext(), compactWidget);
                check(((TextView)compactUpdated).getText().toString().equals("Refreshing…"), "Refresh status must replace the timestamp");
                check(compactUpdated.getTop() == beforeY && compactWidget.getMeasuredHeight() == beforeHeight,
                        "Refreshing must not change geometry");
                getTargetContext().getSharedPreferences("widget_state", 0).edit().putBoolean("loading", false).commit();
                UsageWidget.views(getTargetContext(), true).reapply(getTargetContext(), compactWidget);
                renderImage(activity.getWindow().getDecorView(), 411, 836, "app-preview.png");
                try {
                    ((MainActivity)activity).renderSnapshot(UsageSnapshot.fromResponse(response(System.currentTimeMillis()), System.currentTimeMillis()));
                    renderImage(activity.getWindow().getDecorView(), 411, 836, "app-two-limits-preview.png");
                    ((MainActivity)activity).renderSnapshot(finalActual);
                } catch (Exception e) { throw new AssertionError("Could not render both limits", e); }
            });
            // A temporary widget host verifies provider registration and the actual PendingIntent.
            getUiAutomation().adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET");
            widgetHost = new AppWidgetHost(getTargetContext(), 918);
            int id = widgetHost.allocateAppWidgetId();
            AppWidgetManager manager = AppWidgetManager.getInstance(getTargetContext());
            check(manager.bindAppWidgetIdIfAllowed(id, new ComponentName(getTargetContext(), UsageWidget.class)),
                    "Widget provider must bind to a host");
            getUiAutomation().dropShellPermissionIdentity();
            long beforeRefresh = UsageSnapshot.load(getTargetContext()).updatedAt;
            runOnMainSync(() -> {
                widgetHost.startListening();
                AppWidgetHostView hostView = widgetHost.createView(getTargetContext(), id, manager.getAppWidgetInfo(id));
                hostView.updateAppWidget(UsageWidget.views(getTargetContext()));
                activity.moveTaskToBack(true);
                check(hostView.findViewById(R.id.widget_refresh).performClick(), "Widget refresh must be clickable");
            });
            deadline = System.currentTimeMillis() + 25_000;
            do { Thread.sleep(250); actual = UsageSnapshot.load(getTargetContext()); }
            while ((actual == null || actual.updatedAt <= beforeRefresh) && System.currentTimeMillis() < deadline);
            check(actual != null && actual.updatedAt > beforeRefresh, "Widget refresh must fetch a new real quota response");
            checkAutomaticRefresh(activity);
            result.putString("stream", "Quota/layout checks, manual refresh, background job, missing-cache recovery, diagnostics, and cooldown passed.\n");
        } catch (Throwable failure) {
            result.putString("stream", "Usage checks failed: " + failure.getClass().getSimpleName()
                    + ": " + failure.getMessage() + "\n");
            resultCode = Activity.RESULT_CANCELED;
        } finally {
            getUiAutomation().dropShellPermissionIdentity();
            if (widgetHost != null) { widgetHost.stopListening(); widgetHost.deleteHost(); }
            finish(resultCode, result);
        }
    }
    private void checkSchedulingPolicy() throws Exception {
        long now = 1_800_000_000_000L;
        UsageSnapshot quota = UsageSnapshot.fromResponse(response(now), now);
        check(!UsageRefreshJob.due(quota, true, 0, now), "Fresh data must skip automatic refresh");
        // The prior request took five seconds; Android invokes the next job 15 minutes after its start.
        check(UsageRefreshJob.due(quota, true, now - 5_000, now + UsageRefreshJob.INTERVAL - 5_000),
                "A response completing after job start must not cause the next period to be skipped");
        check(UsageRefreshJob.due(quota, true, now, now + UsageRefreshJob.MIN_REFRESH_GAP),
                "Refresh must be allowed at the cooldown boundary");
        check(!UsageRefreshJob.due(quota, true, 0, now + UsageRefreshJob.MIN_REFRESH_GAP - 1),
                "Recent manual refresh must avoid duplicate reads");
        check(!UsageRefreshJob.due(null, true, now - 60_000, now),
                "A failed request without cached data must still respect cooldown");
        check(UsageRefreshJob.due(null, true, 0, now), "Saved sign-in must recover a missing display cache");
        check(!UsageRefreshJob.due(null, false, 0, now), "Signed-out widgets must not poll");
        check(UsageRefreshJob.due(quota, true, now, now - 60_000),
                "Clock rollback must not freeze automatic updates");
    }
    private void checkUnusedPluginCacheCleanup() throws Exception {
        Path base = Files.createTempDirectory(getTargetContext().getCacheDir().toPath(), "plugin-cache-check");
        try {
            Path home = Files.createDirectories(base.resolve("home"));
            Path plugins = Files.createDirectories(home.resolve("plugins/cache/nested"));
            Path catalog = Files.createDirectories(home.resolve("cache/remote_plugin_catalog"));
            Path outside = Files.createDirectories(base.resolve("outside/cache"));
            Path sentinel = Files.write(outside.resolve("keep"), new byte[]{1});
            Path auth = Files.write(home.resolve("auth.json"), new byte[]{2});
            Path quota = Files.write(base.resolve("usage.json"), new byte[]{3});
            Files.write(plugins.resolve("download"), new byte[]{4});
            Files.write(catalog.resolve("catalog.json"), new byte[]{5});
            Files.createSymbolicLink(plugins.resolve("outside-link"), outside);
            AppServerClient.clearUnusedPluginCaches(home.toFile());
            check(!Files.exists(home.resolve("plugins/cache")) && !Files.exists(catalog),
                    "Unused plugin downloads and catalog must be removed");
            check(Files.readAllBytes(auth)[0] == 2 && Files.readAllBytes(quota)[0] == 3
                            && Files.readAllBytes(sentinel)[0] == 1,
                    "Cleanup must preserve sign-in, quota, and symlink targets");
            Files.delete(home.resolve("plugins"));
            Files.createSymbolicLink(home.resolve("plugins"), outside.getParent());
            boolean refused = false;
            try { AppServerClient.clearUnusedPluginCaches(home.toFile()); }
            catch (java.io.IOException expected) { refused = true; }
            check(refused && Files.exists(sentinel), "Cleanup must reject a parent symlink escaping private home");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(base)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList()))
                    Files.delete(path);
            }
        }
    }
    private void checkRefreshLayout() throws Exception {
        MainActivity activity = (MainActivity)startActivitySync(new Intent(getTargetContext(), MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        java.lang.reflect.Field serviceField = MainActivity.class.getDeclaredField("service");
        java.lang.reflect.Field refreshField = MainActivity.class.getDeclaredField("refresh");
        java.lang.reflect.Field footerField = MainActivity.class.getDeclaredField("updateRow");
        java.lang.reflect.Field updatedField = MainActivity.class.getDeclaredField("updated");
        for (java.lang.reflect.Field field : new java.lang.reflect.Field[]{serviceField, refreshField, footerField, updatedField}) field.setAccessible(true);
        waitFor(() -> {
            try { UsageService service = (UsageService)serviceField.get(activity); return service != null && !service.loading && service.snapshot != null; }
            catch (Exception e) { throw new AssertionError(e); }
        });
        float density = getTargetContext().getResources().getDisplayMetrics().density;
        java.util.concurrent.atomic.AtomicReference<UsageService> state = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicInteger height = new java.util.concurrent.atomic.AtomicInteger();
        long before = UsageSnapshot.load(getTargetContext()).updatedAt;
        runOnMainSync(() -> {
            try {
                android.widget.ImageButton button = (android.widget.ImageButton)refreshField.get(activity);
                View footer = (View)footerField.get(activity);
                android.view.ViewGroup card = (android.view.ViewGroup)footer.getParent();
                check(button.getLeft() >= 0 && button.getRight() <= footer.getWidth(), "Refresh visual/ripple must fit inside footer bounds");
                check(footer.getHeight() <= Math.round(32 * density) + 1, "Refresh must not inflate footer beyond 32 dp");
                height.set(card.getHeight());
                button.setPressed(true);
                button.getBackground().jumpToCurrentState();
                renderImage(activity.getWindow().getDecorView(), 411, 836, "refresh-pressed-preview.png");
                button.setPressed(false);
                android.graphics.Rect bounds = new android.graphics.Rect();
                button.getDrawingRect(bounds);
                card.offsetDescendantRectToMyCoords(button, bounds);
                // Tap outside the 32 dp visual button, inside its expanded 48 dp touch target.
                float x = bounds.right + 4 * density, y = bounds.exactCenterY();
                long time = android.os.SystemClock.uptimeMillis();
                android.view.MotionEvent down = android.view.MotionEvent.obtain(time, time, android.view.MotionEvent.ACTION_DOWN, x, y, 0);
                android.view.MotionEvent up = android.view.MotionEvent.obtain(time, time + 50, android.view.MotionEvent.ACTION_UP, x, y, 0);
                try { check(card.dispatchTouchEvent(down) && card.dispatchTouchEvent(up), "Expanded refresh target must accept the tap"); }
                finally { down.recycle(); up.recycle(); }
                UsageService service = (UsageService)serviceField.get(activity);
                state.set(service);
            } catch (Exception e) { throw new AssertionError(e); }
        });
        waitFor(() -> state.get().loading);
        runOnMainSync(() -> {
            try {
                check(((TextView)updatedField.get(activity)).getText().toString().equals("Refreshing…"), "Refreshing status must replace updated text");
            } catch (Exception e) { throw new AssertionError(e); }
        });
        waitFor(() -> !state.get().loading);
        check(UsageSnapshot.load(getTargetContext()).updatedAt > before, "Refresh must save a new successful quota response");
        runOnMainSync(() -> {
            try {
                View footer = (View)footerField.get(activity);
                check(((View)footer.getParent()).getHeight() == height.get(), "Refresh completion must not shift card height");
            } catch (Exception e) { throw new AssertionError(e); }
        });
    }
    private void checkLicenses() throws Exception {
        try (java.io.InputStream input = getTargetContext().getAssets().open("third_party_licenses.html");
                java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            String notice = new String(output.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
            check(notice.contains("OpenSSL-Apache-2.0.txt") && notice.contains("OpenAI-Codex-NOTICE.txt")
                    && notice.contains("Ratatui-MIT.txt") && notice.contains("MPL-2.0"), "APK must contain complete core/dependency notices");
        }
        Activity activity = startActivitySync(new Intent(getTargetContext(), LicensesActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            waitFor(() -> {
                android.webkit.WebView view = findLicenseView(activity.getWindow().getDecorView());
                return view != null && view.getProgress() == 100;
            });
            runOnMainSync(() -> {
                android.webkit.WebView view = findLicenseView(activity.getWindow().getDecorView());
                check(!view.getSettings().getJavaScriptEnabled() && !view.getSettings().getAllowFileAccess()
                        && !view.getSettings().getAllowContentAccess() && view.getSettings().getBlockNetworkLoads(),
                        "Local license viewer must not execute scripts or access files/network");
                renderImage(activity.getWindow().getDecorView(), 411, 836, "licenses-preview.png");
            });
        } finally { runOnMainSync(activity::finish); }
        Activity privacy = startActivitySync(new Intent(getTargetContext(), LicensesActivity.class)
                .putExtra("privacy", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            waitFor(() -> {
                android.webkit.WebView view = findLicenseView(privacy.getWindow().getDecorView());
                return view != null && view.getProgress() == 100;
            });
            runOnMainSync(() -> {
                android.webkit.WebView view = findLicenseView(privacy.getWindow().getDecorView());
                check(!view.getSettings().getJavaScriptEnabled() && view.getSettings().getBlockNetworkLoads(),
                        "Privacy policy must load offline without scripts");
                renderImage(privacy.getWindow().getDecorView(), 411, 836, "privacy-preview.png");
            });
        } finally { runOnMainSync(privacy::finish); }
    }
    private android.webkit.WebView findLicenseView(View view) {
        if (view instanceof android.webkit.WebView) return (android.webkit.WebView)view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup)view;
            for (int i = 0; i < group.getChildCount(); i++) {
                android.webkit.WebView found = findLicenseView(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }
    @android.annotation.TargetApi(36)
    private void checkAndroid36Layout() throws Exception {
        check(android.os.Build.VERSION.SDK_INT >= 36 && android.os.Build.SUPPORTED_ABIS[0].equals("x86_64"),
                "Layout-only check must run on the API 36 x86 emulator");
        check(android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE) == 16384, "Emulator must use 16 KB pages");
        UsageSnapshot fixture = UsageSnapshot.fromResponse(response(System.currentTimeMillis()), System.currentTimeMillis());
        fixture.save(getTargetContext());
        Activity activity = startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        waitFor(() -> activity.getWindow().getDecorView().getRootWindowInsets() != null);
        runOnMainSync(() -> {
            ((MainActivity)activity).renderSnapshot(fixture);
            View decor = activity.getWindow().getDecorView();
            android.graphics.Insets safe = decor.getRootWindowInsets().getInsets(android.view.WindowInsets.Type.systemBars()
                    | android.view.WindowInsets.Type.displayCutout() | android.view.WindowInsets.Type.ime());
            android.widget.ScrollView scroll = findScroll(decor);
            check(scroll != null && scroll.getPaddingTop() == safe.top && scroll.getPaddingBottom() == safe.bottom,
                    "Usage content must respect Android 16 system bars");
            renderImage(decor, 411, 836, "app-api36-preview.png");
        });
        // This image runs the actual ARM64 executable through Android's native bridge.
        // Check a real initialized account/read response, not just the Java layout.
        waitFor(() -> {
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                    getTargetContext().openFileInput("diagnostic.json")))) {
                JSONObject diagnostic = new JSONObject(reader.readLine());
                return diagnostic.optBoolean("initialized") && diagnostic.optBoolean("signedOutObserved");
            } catch (Exception ignored) { return false; }
        });
        runOnMainSync(activity::finish);
        UsageSnapshot.clear(getTargetContext());
    }
    private android.widget.ScrollView findScroll(View view) {
        if (view instanceof android.widget.ScrollView) return (android.widget.ScrollView)view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup)view;
            for (int i = 0; i < group.getChildCount(); i++) {
                android.widget.ScrollView found = findScroll(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }
    private void checkAutomaticRefresh(Activity activity) throws Exception {
        android.app.job.JobScheduler scheduler = getTargetContext().getSystemService(android.app.job.JobScheduler.class);
        android.app.job.JobInfo job = scheduler.getPendingJob(UsageRefreshJob.JOB_ID);
        check(job != null && job.isPersisted() && job.getIntervalMillis() == UsageRefreshJob.INTERVAL
                        && job.getFlexMillis() == UsageRefreshJob.FLEX,
                "Installed widgets must have a persistent 15-minute job with a five-minute scheduling window");
        runOnMainSync(activity::finish);
        Thread.sleep(1000);
        UsageSnapshot original = UsageSnapshot.load(getTargetContext());
        long before = original.updatedAt;
        original.updatedAt = System.currentTimeMillis() - UsageRefreshJob.INTERVAL - 1000;
        long aged = original.updatedAt;
        original.save(getTargetContext());
        getTargetContext().getSharedPreferences("widget_state", 0).edit().remove("autoAttemptAt").commit();
        try {
            String command = "cmd jobscheduler run -f " + getTargetContext().getPackageName() + " " + UsageRefreshJob.JOB_ID;
            check(shell(command).contains("Running job"), "Android must execute the scheduled refresh job");
            waitFor(() -> {
                UsageSnapshot fresh = UsageSnapshot.load(getTargetContext());
                return fresh != null && fresh.updatedAt > before && "refresh_succeeded".equals(lastBackgroundOutcome());
            });
            long attempt = getTargetContext().getSharedPreferences("widget_state", 0).getLong("autoAttemptAt", 0);
            check(attempt > 0, "Background job must record an automatic attempt");
            shell(command);
            Thread.sleep(1000);
            check(getTargetContext().getSharedPreferences("widget_state", 0).getLong("autoAttemptAt", 0) == attempt,
                    "Repeated jobs must skip recent data instead of fetching again");
            check("skip_fresh".equals(lastBackgroundOutcome()), "Skipped jobs must explain why no refresh happened");
            UsageSnapshot latest = UsageSnapshot.load(getTargetContext());
            UsageSnapshot.clear(getTargetContext());
            getTargetContext().getSharedPreferences("widget_state", 0).edit().remove("autoAttemptAt").commit();
            check(shell(command).contains("Running job"), "Android must run cache recovery");
            waitFor(() -> {
                UsageSnapshot recovered = UsageSnapshot.load(getTargetContext());
                return recovered != null && recovered.updatedAt > latest.updatedAt
                        && "refresh_succeeded".equals(lastBackgroundOutcome());
            });
        } finally {
            UsageSnapshot remaining = UsageSnapshot.load(getTargetContext());
            if (remaining == null || remaining.updatedAt == aged) { original.updatedAt = before; original.save(getTargetContext()); }
            // Forced runs advance the periodic window; restore the normal cadence after testing.
            UsageRefreshJob.cancel(getTargetContext());
            UsageRefreshJob.schedule(getTargetContext());
        }
    }
    private String lastBackgroundOutcome() {
        try {
            JSONArray events = new JSONArray(getTargetContext().getSharedPreferences(
                    "widget_refresh_diagnostics", 0).getString("history", "[]"));
            check(events.length() <= 32, "Background history must remain bounded");
            return events.length() == 0 ? "" : events.getJSONObject(events.length() - 1).getString("outcome");
        } catch (Exception e) { throw new AssertionError("Could not read background diagnostics", e); }
    }
    private String shell(String command) throws Exception {
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                new android.os.ParcelFileDescriptor.AutoCloseInputStream(getUiAutomation().executeShellCommand(command))))) {
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) output.append(line);
            return output.toString();
        }
    }
    private void checkRecovery() throws Exception {
        check(getTargetContext().getPackageName().endsWith(".checks"), "Recovery checks require an isolated installation");
        java.util.concurrent.atomic.AtomicReference<UsageService> service = new java.util.concurrent.atomic.AtomicReference<>();
        android.content.ServiceConnection connection = new android.content.ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, android.os.IBinder binder) {
                service.set(((UsageService.LocalBinder)binder).service());
                service.get().observe(() -> {});
            }
            @Override public void onServiceDisconnected(ComponentName name) { service.set(null); }
        };
        runOnMainSync(() -> getTargetContext().bindService(new Intent(getTargetContext(), UsageService.class),
                connection, android.content.Context.BIND_AUTO_CREATE));
        File cache = new File(getTargetContext().getFilesDir(), "usage.json");
        File blocker = new File(cache, "blocker");
        try {
            waitFor(() -> service.get() != null && !service.get().loading && service.get().canSignIn);
            UsageSnapshot fixture = UsageSnapshot.fromResponse(response(System.currentTimeMillis()), System.currentTimeMillis());
            UsageSnapshot.clear(getTargetContext());
            check(cache.mkdir(), "Must create simulated cache obstruction");
            check(blocker.createNewFile(), "Must obstruct cache replacement");
            runOnMainSync(() -> {
                service.get().acceptQuota(fixture);
                service.get().finish("");
                check(service.get().error.contains("save") && service.get().snapshot == fixture,
                        "Cache failure must survive request completion while keeping fresh data visible");
            });
            check(blocker.delete() && cache.delete(), "Must remove simulated obstruction");
            fixture.save(getTargetContext());
            java.lang.reflect.Field field = UsageService.class.getDeclaredField("timeout");
            field.setAccessible(true);
            Runnable timeout = (Runnable)field.get(service.get());
            runOnMainSync(() -> {
                timeout.run();
                check(service.get().canSignOut() && service.get().snapshot == fixture,
                        "A stopped backend must retain quota and permit local sign-out");
            });
            File auth = new File(getTargetContext().getFilesDir(), "codex/auth.json");
            check(!auth.exists(), "Isolated test must not contain an account session");
            try (FileOutputStream output = new FileOutputStream(auth)) { output.write("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
            runOnMainSync(() -> service.get().signOut());
            waitFor(() -> !service.get().loading && service.get().canSignIn);
            check(!auth.exists() && UsageSnapshot.load(getTargetContext()) == null,
                    "Local sign-out must remove the test session and display cache");
            runOnMainSync(() -> {
                getTargetContext().getSharedPreferences("widget_state", 0).edit().putString("error", "").commit();
                for (boolean compact : new boolean[]{false, true}) {
                    FrameLayout host = new FrameLayout(getTargetContext());
                    View widget = UsageWidget.views(getTargetContext(), compact, fixture).apply(getTargetContext(), host);
                    host.addView(widget);
                    renderImage(host, compact ? 180 : 280, compact ? 50 : 124,
                            compact ? "widget-dual-compact-preview.png" : "widget-dual-preview.png");
                    check(((TextView)widget.findViewById(R.id.widget_percent)).getText().toString().startsWith("20%"), "Five-hour percentage must render");
                    check(((TextView)widget.findViewById(R.id.widget_second_percent)).getText().toString().startsWith("60%"), "Weekly percentage must render");
                    View refresh = widget.findViewById(R.id.widget_refresh);
                    int[] position = new int[2];
                    refresh.getLocationInWindow(position);
                    check(position[1] + refresh.getHeight() <= host.getHeight(), "Dual widget footer must fit");
                    View updated = widget.findViewById(R.id.widget_updated);
                    int top = updated.getTop();
                    getTargetContext().getSharedPreferences("widget_state", 0).edit().putBoolean("loading", true).commit();
                    UsageWidget.views(getTargetContext(), compact, fixture).reapply(getTargetContext(), widget);
                    check(((TextView)updated).getText().toString().equals("Refreshing…") && updated.getTop() == top,
                            "Dual refresh status must stay in the timestamp slot");
                    getTargetContext().getSharedPreferences("widget_state", 0).edit().putBoolean("loading", false).commit();
                }
            });
        } finally {
            blocker.delete();
            if (cache.isDirectory()) cache.delete();
            runOnMainSync(() -> { if (service.get() != null) service.get().observe(null); getTargetContext().unbindService(connection); });
        }
    }
    private void checkSignOut() throws Exception {
        check(getTargetContext().getPackageName().endsWith(".checks"), "Logout test must use an isolated installation");
        java.util.concurrent.atomic.AtomicReference<UsageService> service = new java.util.concurrent.atomic.AtomicReference<>();
        android.content.ServiceConnection connection = new android.content.ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, android.os.IBinder binder) {
                service.set(((UsageService.LocalBinder)binder).service());
            }
            @Override public void onServiceDisconnected(ComponentName name) { service.set(null); }
        };
        Intent intent = new Intent(getTargetContext(), UsageService.class);
        runOnMainSync(() -> {
            getTargetContext().startForegroundService(intent);
            getTargetContext().bindService(intent, connection, android.content.Context.BIND_AUTO_CREATE);
        });
        try {
            waitFor(() -> service.get() != null && !service.get().loading && service.get().canSignIn);
            UsageSnapshot fixture = UsageSnapshot.fromResponse(response(System.currentTimeMillis()), System.currentTimeMillis());
            fixture.save(getTargetContext());
            check(UsageSnapshot.load(getTargetContext()) != null, "Fixture cache must exist before logout");
            runOnMainSync(() -> { service.get().snapshot = fixture; service.get().signOut(); });
            waitFor(() -> !service.get().loading);
            runOnMainSync(() -> {
                check(service.get().snapshot == null && !service.get().authenticated() && service.get().canSignIn,
                        "Successful logout must clear quota and allow another sign-in");
                check(UsageSnapshot.load(getTargetContext()) == null, "Logout must delete the display cache");
                FrameLayout host = new FrameLayout(getTargetContext());
                View widget = UsageWidget.views(getTargetContext()).apply(getTargetContext(), host);
                check(((TextView)widget.findViewById(R.id.widget_percent)).getText().toString().equals("—"), "Widget must clear its old percentage");
                check(widget.findViewById(R.id.widget_resets).getVisibility() == View.GONE, "Widget must clear its old resets");
            });
        } finally { runOnMainSync(() -> getTargetContext().unbindService(connection)); }
    }
    private void waitFor(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        java.util.concurrent.atomic.AtomicBoolean ready = new java.util.concurrent.atomic.AtomicBoolean();
        do {
            runOnMainSync(() -> ready.set(condition.getAsBoolean()));
            if (ready.get()) return;
            Thread.sleep(250);
        } while (System.currentTimeMillis() < deadline);
        throw new AssertionError("Service operation timed out");
    }
    private JSONObject response(long now) throws Exception {
        return new JSONObject().put("rateLimits", new JSONObject()
                .put("primary", window(80, 300, now)).put("secondary", window(40, 10080, now)));
    }
    private JSONObject window(double used, int minutes, long now) throws Exception {
        return new JSONObject().put("usedPercent", used).put("windowDurationMins", minutes)
                .put("resetsAt", now/1000 + 7200);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private void renderImage(View view, int widthDp, int heightDp, String filename) {
        float density = getTargetContext().getResources().getDisplayMetrics().density;
        int width = Math.round(widthDp * density), height = Math.round(heightDp * density);
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap));
        try (FileOutputStream output = new FileOutputStream(new File(getTargetContext().getCacheDir(), filename))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        } catch (Exception e) { throw new AssertionError("Could not save UI preview"); }
        bitmap.recycle();
    }
}
