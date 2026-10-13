package com.example.codexusage;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Only displayable quota values are cached; auth/account payloads never enter this model. */
final class UsageSnapshot {
    static final class Window {
        final double used;
        final int minutes;
        final long resetsAt;
        Window(double used, int minutes, long resetsAt) {
            this.used = used; this.minutes = minutes; this.resetsAt = resetsAt;
        }
        double remaining() { return Math.max(0, Math.min(100, 100 - used)); }
        String percent() {
            return String.format(Locale.getDefault(), remaining() == Math.rint(remaining())
                    ? "%.0f%%" : "%.1f%%", remaining());
        }
        String label() {
            if (minutes == 10080) return "Weekly limit";
            if (minutes % 1440 == 0) return (minutes / 1440) + "-day limit";
            if (minutes % 60 == 0) return (minutes / 60) + "-hour limit";
            return minutes + "-minute limit";
        }
        String resetLabel() {
            long seconds = resetsAt - System.currentTimeMillis() / 1000;
            if (seconds <= 0) return "Reset due · refresh to check";
            long hours = seconds / 3600;
            if (hours >= 24) return "Resets in " + (hours / 24) + "d " + (hours % 24) + "h";
            if (seconds >= 3600) return "Resets in " + (seconds / 3600) + "h " + ((seconds % 3600) / 60) + "m";
            return "Resets in " + Math.max(1, (seconds + 59) / 60) + "m";
        }
    }
    static final class Reset {
        final String title;
        final Long expiresAt;
        Reset(String title, Long expiresAt) { this.title = title; this.expiresAt = expiresAt; }
    }
    final List<Window> windows = new ArrayList<>();
    final List<Reset> resets = new ArrayList<>();
    long updatedAt;
    Integer availableResets;
    boolean resetDetailsKnown;

    static UsageSnapshot fromResponse(JSONObject response, long now) throws Exception {
        UsageSnapshot snapshot = new UsageSnapshot();
        JSONObject buckets = response.optJSONObject("rateLimitsByLimitId");
        JSONObject bucket = buckets == null ? null : buckets.optJSONObject("codex");
        if (bucket == null) bucket = response.optJSONObject("rateLimits");
        if (bucket != null) {
            snapshot.addWindow(bucket.optJSONObject("primary"));
            snapshot.addWindow(bucket.optJSONObject("secondary"));
        }
        if (snapshot.windows.isEmpty()) throw new IllegalArgumentException("Missing quota windows");
        snapshot.windows.sort(Comparator.comparingInt(window -> window.minutes));
        snapshot.updatedAt = now;
        JSONObject resetCredits = response.optJSONObject("rateLimitResetCredits");
        if (resetCredits != null && !resetCredits.isNull("availableCount")
                && resetCredits.has("availableCount")) {
            int count = resetCredits.getInt("availableCount");
            if (count >= 0) snapshot.availableResets = count;
            JSONArray rows = resetCredits.optJSONArray("credits");
            snapshot.resetDetailsKnown = rows != null;
            if (rows != null) for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null || !"available".equals(row.optString("status"))) continue;
                Long expiry = row.has("expiresAt") && !row.isNull("expiresAt")
                        ? row.getLong("expiresAt") : null;
                if (expiry != null && expiry <= now / 1000) continue;
                String title = row.optString("title", "");
                if (title.isEmpty() || "null".equals(title)) title = "Available reset";
                snapshot.resets.add(new Reset(title.substring(0, Math.min(120, title.length())), expiry));
            }
        }
        snapshot.resets.sort(Comparator.comparingLong(reset -> reset.expiresAt == null
                ? Long.MAX_VALUE : reset.expiresAt));
        return snapshot;
    }

    private void addWindow(JSONObject value) throws Exception {
        if (value == null || !value.has("usedPercent") || value.isNull("usedPercent")
                || !value.has("windowDurationMins") || value.isNull("windowDurationMins")
                || !value.has("resetsAt") || value.isNull("resetsAt")) return;
        double used = value.getDouble("usedPercent");
        int minutes = value.getInt("windowDurationMins");
        long reset = value.getLong("resetsAt");
        if (!Double.isFinite(used) || minutes <= 0 || reset <= 0) return;
        for (Window existing : windows) if (existing.minutes == minutes && existing.resetsAt == reset) return;
        windows.add(new Window(used, minutes, reset));
    }

    Window widgetWindow() {
        // Show the most depleted window, so a generous weekly limit cannot hide a short limit.
        return windows.stream().min(Comparator.comparingDouble(Window::remaining)).orElse(null);
    }

    static String time(long millis) {
        return new SimpleDateFormat("h:mm a", Locale.US).format(new Date(millis));
    }
    static String expiry(long seconds) {
        return new java.text.SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
                .format(new Date(seconds * 1000));
    }

    private JSONObject toJson() throws Exception {
        JSONObject value = new JSONObject().put("updatedAt", updatedAt)
                .put("availableResets", availableResets == null ? JSONObject.NULL : availableResets)
                .put("resetDetailsKnown", resetDetailsKnown);
        JSONArray windowRows = new JSONArray();
        for (Window window : windows) windowRows.put(new JSONObject().put("usedPercent", window.used)
                .put("windowDurationMins", window.minutes).put("resetsAt", window.resetsAt));
        JSONArray resetRows = new JSONArray();
        for (Reset reset : resets) resetRows.put(new JSONObject().put("title", reset.title)
                .put("expiresAt", reset.expiresAt == null ? JSONObject.NULL : reset.expiresAt));
        return value.put("windows", windowRows).put("resets", resetRows);
    }

    void save(Context context) throws Exception {
        AtomicFile file = cacheFile(context);
        FileOutputStream output = null;
        try {
            byte[] encoded = toJson().toString().getBytes(StandardCharsets.UTF_8);
            output = file.startWrite();
            output.write(encoded);
            file.finishWrite(output);
            output = null;
            // AtomicFile reports some sync/rename failures only through logging.
            if (!java.util.Arrays.equals(encoded, file.readFully()))
                throw new java.io.IOException("Display cache was not committed");
        } catch (Exception e) {
            if (output != null) file.failWrite(output);
            throw e;
        }
    }
    static UsageSnapshot load(Context context) {
        try {
            JSONObject value = new JSONObject(new String(cacheFile(context).readFully(), StandardCharsets.UTF_8));
            UsageSnapshot snapshot = new UsageSnapshot();
            snapshot.updatedAt = value.getLong("updatedAt");
            JSONArray windows = value.getJSONArray("windows");
            for (int i = 0; i < windows.length(); i++) snapshot.addWindow(windows.getJSONObject(i));
            if (snapshot.windows.isEmpty()) return null;
            if (!value.isNull("availableResets")) snapshot.availableResets = value.getInt("availableResets");
            snapshot.resetDetailsKnown = value.optBoolean("resetDetailsKnown");
            JSONArray resets = value.optJSONArray("resets");
            if (resets != null) for (int i = 0; i < resets.length(); i++) {
                JSONObject row = resets.getJSONObject(i);
                snapshot.resets.add(new Reset(row.getString("title"), row.isNull("expiresAt")
                        ? null : row.getLong("expiresAt")));
            }
            return snapshot;
        } catch (Exception ignored) { return null; }
    }
    static void clear(Context context) { cacheFile(context).delete(); }
    private static AtomicFile cacheFile(Context context) {
        return new AtomicFile(new File(context.getFilesDir(), "usage.json"));
    }
}
