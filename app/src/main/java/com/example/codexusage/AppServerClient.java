package com.example.codexusage;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** A diagnostic stdio client. Raw server output and account payloads are never logged. */
final class AppServerClient {
    interface Listener {
        void update(String status, boolean canSignIn, String verificationUrl, String userCode);
        default void quotaReceived(UsageSnapshot snapshot) { }
        default void finished(String error) { }
        default void signedOut() { }
    }

    private final Context context;
    private final Listener listener;
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private final AtomicInteger nextId = new AtomicInteger();
    private final Map<Integer, String> requests = new ConcurrentHashMap<>();
    private final JSONObject diagnostic = new JSONObject();
    private volatile Process process;
    private BufferedWriter stdin;
    private volatile boolean closed;
    private volatile boolean initialized;
    private boolean signedOutObserved;
    private boolean chatgptAccount;
    private boolean quotaVerified;
    private volatile boolean loginPending;
    private boolean loggingOut;
    private String verificationUrl = "";
    private String userCode = "";
    private String loginId = "";

    AppServerClient(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    void start() {
        publish("Starting the on-phone app-server…");
        writer.execute(() -> {
            try {
                File binary = new File(context.getApplicationInfo().nativeLibraryDir,
                        "libcodex_app_server.so");
                if (!binary.isFile()) {
                    publish("The native app-server is not packaged in this build yet.");
                    return;
                }
                File home = new File(context.getFilesDir(), "codex");
                if (!home.isDirectory() && !home.mkdirs()) {
                    publish("Could not create private auth storage.");
                    return;
                }
                ProcessBuilder builder = new ProcessBuilder(binary.getAbsolutePath(),
                        "--listen", "stdio://", "-c", "cli_auth_credentials_store=\"file\"",
                        "-c", "analytics.enabled=false", "-c", "otel.exporter=\"none\"",
                        "-c", "otel.trace_exporter=\"none\"", "-c", "otel.metrics_exporter=\"none\"");
                builder.directory(context.getFilesDir());
                builder.environment().put("CODEX_HOME", home.getAbsolutePath());
                builder.environment().put("TMPDIR", context.getCacheDir().getAbsolutePath());
                builder.environment().put("SSL_CERT_DIR", "/system/etc/security/cacerts");
                builder.environment().put("RUST_LOG", "off");
                if (closed) return;
                process = builder.start();
                if (closed) {
                    process.destroy();
                    return;
                }
                stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(),
                        StandardCharsets.UTF_8));
                Thread errors = new Thread(() -> {
                    try {
                        byte[] buffer = new byte[4096];
                        while (process.getErrorStream().read(buffer) != -1) {
                            // Drain stderr without recording credentials or account data.
                        }
                    } catch (Exception ignored) { }
                }, "app-server-stderr");
                errors.setDaemon(true);
                errors.start();
                Thread reader = new Thread(this::readMessages, "app-server-stdout");
                reader.setDaemon(true);
                reader.start();
                JSONObject client = new JSONObject().put("name", "codex_usage_android")
                        .put("title", "Codex Usage Android").put("version", "0.1");
                sendNow("initialize", new JSONObject().put("clientInfo", client));
            } catch (Exception e) {
                publish("App-server startup failed (" + e.getClass().getSimpleName() + ").");
            }
        });
    }

    synchronized void signIn(boolean deviceCode) {
        if (!initialized || loginPending || closed) return;
        if (chatgptAccount) {
            readQuota();
            return;
        }
        loginPending = true;
        diagnostic.remove("loginFailure");
        publish(deviceCode ? "Requesting a device sign-in code…" : "Preparing browser sign-in…");
        try {
            request("account/login/start", new JSONObject().put("type",
                    deviceCode ? "chatgptDeviceCode" : "chatgpt"));
        } catch (Exception e) {
            loginPending = false;
            publish("Could not request sign-in.");
        }
    }

    void readQuota() {
        if (initialized && !closed) request("account/read", new JSONObject());
    }

    boolean isReady() { return initialized && !closed; }
    boolean isLoginPending() { return loginPending; }
    synchronized boolean isAuthenticated() { return chatgptAccount; }

    synchronized void signOut() {
        if (!isReady() || loggingOut) return;
        loggingOut = true;
        request("account/logout", null);
    }

    synchronized void cancelSignIn() {
        if (loginId.isEmpty()) return;
        try { request("account/login/cancel", new JSONObject().put("loginId", loginId)); }
        catch (Exception ignored) { }
    }

    private void request(String method, JSONObject params) {
        if (closed) return;
        writer.execute(() -> {
            if (closed || requests.containsValue(method)) return;
            try {
                sendNow(method, params);
            } catch (Exception e) {
                publish("Could not send " + method + " (" + e.getClass().getSimpleName() + ").");
                listener.finished("Couldn’t connect. Try refreshing again.");
            }
        });
    }

    private void sendNow(String method, JSONObject params) throws Exception {
        int id = nextId.getAndIncrement();
        requests.put(id, method);
        write(new JSONObject().put("id", id).put("method", method).put("params", params));
    }

    private void write(JSONObject message) throws Exception {
        stdin.write(message.toString());
        stdin.newLine();
        stdin.flush();
    }

    private void readMessages() {
        try (BufferedReader stdout = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (!closed && (line = stdout.readLine()) != null) {
                handle(new JSONObject(line));
            }
            if (!closed) {
                int exit = process.waitFor();
                initialized = false;
                publish("App-server stopped (exit " + exit + "). Reopen the app to retry.");
                listener.finished("Connection stopped. Try refreshing again.");
            }
        } catch (Exception e) {
            if (!closed) {
                initialized = false;
                process.destroy();
                publish("App-server communication failed ("
                        + e.getClass().getSimpleName() + "). Reopen the app to retry.");
                listener.finished("Connection stopped. Try refreshing again.");
            }
        }
    }

    private synchronized void handle(JSONObject message) throws Exception {
        if (!message.has("id")) {
            if (loggingOut) return;
            if ("account/login/completed".equals(message.optString("method"))) {
                JSONObject params = message.optJSONObject("params");
                if (params == null || !loginId.equals(params.optString("loginId"))) return;
                loginPending = false;
                loginId = "";
                verificationUrl = "";
                userCode = "";
                if (params.optBoolean("success")) {
                    publish("Sign-in completed. Verifying account and quota…");
                    request("account/read", new JSONObject());
                } else {
                    String reason = failureCategory(params.optString("error"));
                    diagnostic.put("loginFailure", reason);
                    publish("Sign-in failed: " + reason + ". You can try again.");
                    listener.finished("Sign-in failed: " + reason + ".");
                }
            } else if ("account/updated".equals(message.optString("method"))) {
                request("account/read", new JSONObject());
            } else if ("account/rateLimits/updated".equals(message.optString("method"))) {
                request("account/rateLimits/read", new JSONObject());
            }
            return;
        }
        String method = requests.remove(message.getInt("id"));
        if (method == null) return;
        if (loggingOut && !"account/logout".equals(method)) return;
        JSONObject error = message.optJSONObject("error");
        if (error != null) {
            if ("account/logout".equals(method)) {
                loggingOut = false;
                listener.finished("Couldn’t sign out. Try again.");
                return;
            }
            if ("account/login/start".equals(method)) loginPending = false;
            diagnostic.put("rpcErrorCode", error.optInt("code"));
            publish(method + " failed (code " + error.optInt("code") + "): "
                    + failureCategory(error.optString("message")) + ".");
            listener.finished("Couldn’t refresh: " + failureCategory(error.optString("message")) + ".");
            return;
        }
        diagnostic.remove("rpcErrorCode");
        JSONObject result = message.optJSONObject("result");
        if (result == null) return;
        switch (method) {
            case "account/logout":
                loggingOut = false;
                chatgptAccount = false;
                quotaVerified = false;
                signedOutObserved = true;
                loginPending = false;
                loginId = "";
                verificationUrl = "";
                userCode = "";
                diagnostic.remove("primary");
                diagnostic.remove("updatedAt");
                diagnostic.remove("availableResets");
                diagnostic.remove("resetDetailCount");
                publish("Signed out.");
                listener.signedOut();
                break;
            case "initialize":
                initialized = true;
                writer.execute(() -> {
                    try {
                        write(new JSONObject().put("method", "initialized")
                                .put("params", new JSONObject()));
                        sendNow("account/read", new JSONObject());
                    } catch (Exception e) {
                        publish("Could not complete initialization.");
                    }
                });
                publish("App-server initialized on this phone. Checking account…");
                break;
            case "account/read":
                JSONObject account = result.optJSONObject("account");
                chatgptAccount = account != null && "chatgpt".equals(account.optString("type"));
                if (account == null) {
                    signedOutObserved = true;
                    if (!loginPending && !diagnostic.has("loginFailure"))
                        publish("App-server is running on this phone. Signed out.");
                    if (!loginPending) listener.finished("Sign in to see your usage.");
                } else if (chatgptAccount) {
                    loginPending = false;
                    loginId = "";
                    verificationUrl = "";
                    userCode = "";
                    diagnostic.remove("loginFailure");
                    publish("ChatGPT account verified. Reading real quota…");
                    request("account/rateLimits/read", new JSONObject());
                } else {
                    publish("The account is not a managed ChatGPT account.");
                    listener.finished("Sign in with ChatGPT to see your usage.");
                }
                break;
            case "account/login/start":
                loginId = result.optString("loginId");
                verificationUrl = result.optString("authUrl", result.optString("verificationUrl"));
                userCode = result.optString("userCode");
                if (verificationUrl.isEmpty() || loginId.isEmpty()) {
                    loginPending = false;
                    publish("The server did not return sign-in instructions.");
                    listener.finished("Couldn’t start sign-in. Try again.");
                } else if (userCode.isEmpty()) {
                    publish("Complete sign-in in your browser, then return here. "
                            + "No device code is needed.");
                } else {
                    publish("Open the sign-in page, enter the code below, and approve. "
                            + "Then return to this app.");
                }
                break;
            case "account/rateLimits/read":
                try {
                    UsageSnapshot snapshot = UsageSnapshot.fromResponse(result, System.currentTimeMillis());
                    UsageSnapshot.Window primary = snapshot.windows.get(0);
                    double used = primary.used;
                    int minutes = primary.minutes;
                    long resets = primary.resetsAt;
                    quotaVerified = true;
                    diagnostic.put("primary", new JSONObject().put("usedPercent", used)
                            .put("windowDurationMins", minutes).put("resetsAt", resets));
                    diagnostic.put("updatedAt", snapshot.updatedAt)
                            .put("availableResets", snapshot.availableResets == null
                                    ? JSONObject.NULL : snapshot.availableResets)
                            .put("resetDetailCount", snapshot.resets.size());
                    listener.quotaReceived(snapshot);
                    publish("Real quota received: " + used + "% used in a " + minutes
                            + " minute window. Resets " + DateFormat.getDateTimeInstance()
                            .format(new Date(resets * 1000L)) + ".");
                    listener.finished("");
                } catch (Exception e) {
                    publish("No complete quota window was returned.");
                    listener.finished("Usage isn’t available yet. Try refreshing again.");
                }
                break;
            default:
                break;
        }
    }

    // Only fixed categories leave the RPC reader: never persist raw auth errors.
    private String failureCategory(String error) {
        String value = error.toLowerCase(Locale.ROOT);
        if (value.contains("timed out") || value.contains("timeout")) return "sign-in timed out";
        if (value.contains("not completed") || value.contains("cancel")) return "sign-in was cancelled";
        if (value.contains("certificate") || value.contains("ssl") || value.contains("tls"))
            return "secure connection failed";
        if (value.contains("exchange")) return "authorization code exchange failed";
        if (value.contains("permission") || value.contains("persist") || value.contains("storage"))
            return "auth storage or account permission failed";
        if (value.contains("device auth failed")) return "device authorization was rejected";
        if (value.contains("connect") || value.contains("request") || value.contains("network"))
            return "network request failed";
        if (value.contains("login server")) return "browser callback failed";
        return "server could not finish authentication";
    }

    private synchronized void publish(String status) {
        try {
            diagnostic.put("initialized", initialized).put("signedOutObserved", signedOutObserved)
                    .put("chatgptAccount", chatgptAccount).put("quotaVerified", quotaVerified)
                    .put("status", status);
            try (FileOutputStream output = context.openFileOutput("diagnostic.json", Context.MODE_PRIVATE)) {
                output.write(diagnostic.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) { }
        if (!closed) listener.update(status, initialized && !loginPending,
                verificationUrl, userCode);
    }

    void close() {
        closed = true;
        writer.shutdownNow();
        Process running = process;
        if (running != null) running.destroy();
    }
}
