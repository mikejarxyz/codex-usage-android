package com.example.codexusage;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/** One on-phone server shared by the app and user-initiated widget refreshes. */
public final class UsageService extends Service {
    static final String REFRESH = "com.example.codexusage.REFRESH";
    interface Observer { void changed(); }
    final class LocalBinder extends Binder { UsageService service() { return UsageService.this; } }
    private final LocalBinder binder = new LocalBinder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private AppServerClient client;
    private Observer observer;
    private Observer backgroundObserver;
    private long generation;
    UsageSnapshot snapshot;
    boolean loading;
    boolean canSignIn;
    String error = "";
    String verificationUrl = "";
    String userCode = "";
    private final Runnable timeout = () -> {
        ++generation;
        if (client != null) client.close();
        client = null;
        canSignIn = false;
        verificationUrl = "";
        userCode = "";
        finish("Refresh timed out. Try again.");
    };

    @Override public void onCreate() {
        super.onCreate();
        snapshot = UsageSnapshot.load(this);
        startClient();
    }

    private void promote() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("usage_sync", "Usage refresh",
                NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        startForeground(1, new Notification.Builder(this, "usage_sync")
                .setSmallIcon(R.drawable.ic_usage)
                .setContentTitle("Codex Usage")
                .setContentText("Updating usage or waiting for sign-in…")
                .setContentIntent(open).setOngoing(true).build());
    }

    private void startClient() {
        loading = true;
        error = "";
        long current = ++generation;
        client = new AppServerClient(this, new AppServerClient.Listener() {
            @Override public void update(String status, boolean ready, String url, String code) {
                main.post(() -> {
                    if (current != generation) return;
                    canSignIn = ready;
                    verificationUrl = url;
                    userCode = code;
                    if (client.isLoginPending()) armTimeout(16 * 60 * 1000L);
                    changed();
                });
            }
            @Override public void quotaReceived(UsageSnapshot fresh) {
                main.post(() -> {
                    if (current != generation) return;
                    snapshot = fresh;
                    try { snapshot.save(UsageService.this); }
                    catch (Exception ignored) { error = "Couldn’t save the latest usage."; }
                });
            }
            @Override public void finished(String message) {
                main.post(() -> { if (current == generation) finish(message); });
            }
            @Override public void signedOut() {
                main.post(() -> {
                    if (current != generation) return;
                    snapshot = null;
                    UsageSnapshot.clear(UsageService.this);
                    verificationUrl = "";
                    userCode = "";
                    canSignIn = true;
                    finish("");
                });
            }
        });
        armTimeout(60_000);
        client.start();
        changed();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        // onCreate already starts the first read; subsequent taps explicitly refresh.
        if (loading) promote();
        else if (intent != null && REFRESH.equals(intent.getAction())) refresh();
        return START_NOT_STICKY;
    }

    void refresh() {
        refresh(true);
    }

    void refreshBackground() { refresh(false); }

    private void refresh(boolean foreground) {
        if (loading) return;
        if (foreground) promote();
        loading = true;
        error = "";
        changed();
        if (client == null || !client.isReady()) {
            if (client != null) client.close();
            startClient();
        } else {
            armTimeout(60_000);
            client.readQuota();
        }
    }

    void signIn(boolean deviceCode) {
        if (client == null || !canSignIn || client.isLoginPending()) return;
        promote();
        loading = true;
        error = "";
        armTimeout(16 * 60 * 1000L);
        client.signIn(deviceCode);
        changed();
    }

    void cancelSignIn() { if (client != null) client.cancelSignIn(); }
    boolean canSignOut() { return !loading && client != null && client.isReady(); }
    void signOut() {
        if (!canSignOut()) return;
        promote();
        loading = true;
        error = "";
        armTimeout(60_000);
        client.signOut();
        changed();
    }
    boolean authenticated() { return client != null && client.isAuthenticated(); }
    boolean loginPending() { return client != null && client.isLoginPending(); }

    private void armTimeout(long millis) {
        main.removeCallbacks(timeout);
        main.postDelayed(timeout, millis);
    }

    private void finish(String message) {
        main.removeCallbacks(timeout);
        loading = false;
        error = message;
        if (message.startsWith("Sign in to") || message.startsWith("Sign in with")) {
            snapshot = null;
            UsageSnapshot.clear(this);
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        changed();
        if (observer == null) stopSelf();
    }

    private void changed() {
        getSharedPreferences("widget_state", MODE_PRIVATE).edit()
                .putBoolean("loading", loading).putString("error", error).apply();
        UsageWidget.updateAll(this);
        if (observer != null) observer.changed();
        if (backgroundObserver != null) backgroundObserver.changed();
    }

    void observe(Observer value) { observer = value; if (observer != null) observer.changed(); }
    void observeBackground(Observer value) {
        backgroundObserver = value;
        if (backgroundObserver != null) backgroundObserver.changed();
    }
    @Override public IBinder onBind(Intent intent) { return binder; }
    @Override public boolean onUnbind(Intent intent) {
        observer = null;
        backgroundObserver = null;
        if (!loading) stopSelf();
        return false;
    }
    @Override public void onDestroy() {
        ++generation;
        main.removeCallbacksAndMessages(null);
        if (client != null) client.close();
        loading = false;
        getSharedPreferences("widget_state", MODE_PRIVATE).edit().putBoolean("loading", false).apply();
        UsageWidget.updateAll(this);
        super.onDestroy();
    }
}
