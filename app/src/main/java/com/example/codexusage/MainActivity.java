package com.example.codexusage;

import android.app.Activity;
import android.app.AlertDialog;
import android.appwidget.AppWidgetManager;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

public final class MainActivity extends Activity {
    private static final int BG = Color.rgb(15,15,15), SURFACE = Color.rgb(23,23,23);
    private static final int TEXT = Color.rgb(241,241,241), MUTED = Color.rgb(166,166,166);
    private UsageService service;
    private boolean bound;
    private String openedUrl = "";
    private LinearLayout windows, resets, auth, updateRow;
    private TextView updated, error, code, resetCount;
    private ImageButton refresh;
    private Button signIn, deviceSignIn, openPage, copyCode, cancel;
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((UsageService.LocalBinder) binder).service();
            service.observe(() -> render());
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            service = null;
            refresh.setEnabled(true);
            error.setText("Connection stopped. Tap refresh to reconnect.");
            error.setVisibility(View.VISIBLE);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        UsageRefreshJob.schedule(this);
        if (state != null) openedUrl = state.getString("openedUrl", "");
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(0);
        LinearLayout root = column();
        root.setPadding(dp(24), dp(28), dp(24), dp(32));
        root.setBackgroundColor(BG);
        LinearLayout heading = row();
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_openai);
        logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        heading.addView(logo, new LinearLayout.LayoutParams(dp(28), dp(28)));
        TextView title = text("Codex usage", 26, TEXT, true);
        if (android.os.Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -2, 1);
        titleParams.leftMargin = dp(12);
        heading.addView(title, titleParams);
        Button menu = button("⋮", false);
        menu.setTextSize(24);
        menu.setPadding(0, 0, 0, 0);
        menu.setContentDescription("More options");
        heading.addView(menu, new LinearLayout.LayoutParams(dp(48), dp(48)));
        menu.setOnClickListener(this::showMenu);
        root.addView(heading);

        LinearLayout usageCard = card();
        windows = column();
        usageCard.addView(windows);
        add(root, usageCard, 24);
        updateRow = row();
        resetCount = text("", 12, MUTED, false);
        updateRow.addView(resetCount);
        updated = text("Not updated yet", 12, MUTED, false);
        updated.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams updatedParams = new LinearLayout.LayoutParams(0, -2, 1);
        updatedParams.leftMargin = dp(8);
        updateRow.addView(updated, updatedParams);
        refresh = new ImageButton(this);
        refresh.setImageResource(R.drawable.ic_refresh);
        refresh.setPadding(dp(14), dp(14), dp(14), dp(14));
        refresh.setImageAlpha(166);
        refresh.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.rgb(85,85,85)),
                shape(SURFACE, 24, false), null));
        refresh.setContentDescription("Refresh usage");
        LinearLayout.LayoutParams refreshParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        refreshParams.rightMargin = -dp(14);
        updateRow.addView(refresh, refreshParams);
        add(usageCard, updateRow, 8);
        error = text("", 14, MUTED, false);
        error.setVisibility(View.GONE);
        error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        add(root, error, 8);
        resets = card();
        add(root, resets, 16);
        auth = column();
        add(root, auth, 24);
        code = text("", 24, TEXT, true);
        code.setTextIsSelectable(true);
        auth.addView(code);
        signIn = button("Sign in with ChatGPT", true);
        add(auth, signIn, 8);
        openPage = button("Open sign-in page", true);
        add(auth, openPage, 8);
        copyCode = button("Copy code", false);
        add(auth, copyCode, 4);
        deviceSignIn = button("Use a device code instead", false);
        add(auth, deviceSignIn, 4);
        cancel = button("Cancel sign-in", false);
        add(auth, cancel, 4);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        scroll.addView(root);
        setContentView(scroll);
        if (android.os.Build.VERSION.SDK_INT >= 35) {
            scroll.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets safe = insets.getInsets(android.view.WindowInsets.Type.systemBars()
                        | android.view.WindowInsets.Type.displayCutout() | android.view.WindowInsets.Type.ime());
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
                return insets;
            });
            scroll.requestApplyInsets();
        }
        renderSnapshot(UsageSnapshot.load(this));
        auth.setVisibility(View.GONE);
        refresh.setOnClickListener(view -> { if (service != null) service.refresh(); else connect(); });
        signIn.setOnClickListener(view -> { if (service != null) service.signIn(false); });
        deviceSignIn.setOnClickListener(view -> { if (service != null) service.signIn(true); });
        cancel.setOnClickListener(view -> { if (service != null) service.cancelSignIn(); });
        openPage.setOnClickListener(view -> { if (service != null) openBrowser(service.verificationUrl); });
        copyCode.setOnClickListener(view -> {
            if (service == null) return;
            getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Sign-in code", service.userCode));
            Toast.makeText(this, "Code copied", Toast.LENGTH_SHORT).show();
        });
    }
    @Override protected void onStart() { super.onStart(); connect(); }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused && windows != null) render();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("openedUrl", openedUrl);
        super.onSaveInstanceState(state);
    }
    private void connect() {
        startForegroundService(new Intent(this, UsageService.class).setAction(UsageService.REFRESH));
        if (!bound) bound = bindService(new Intent(this, UsageService.class), connection, BIND_AUTO_CREATE);
    }
    // The service owns auth even if the Activity is destroyed during the browser round trip.
    @Override protected void onDestroy() {
        if (service != null) service.observe(null);
        if (bound) unbindService(connection);
        super.onDestroy();
    }
    private void render() {
        if (service == null || isDestroyed()) return;
        renderSnapshot(service.snapshot);
        if (service.loading) updated.setText("Refreshing…");
        refresh.setImageAlpha(service.loading ? 77 : 166);
        refresh.setEnabled(!service.loading);
        String message = service.error.startsWith("Sign in to") || service.error.startsWith("Sign in with") ? "" : service.error;
        if (service.loginPending()) message = service.userCode.isEmpty()
                ? "Finish sign-in in your browser." : "Enter this code in your browser.";
        error.setText(message);
        error.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
        boolean needsAuth = !service.authenticated() && (service.loginPending()
                || (service.snapshot == null && service.canSignIn && !service.loading));
        auth.setVisibility(needsAuth ? View.VISIBLE : View.GONE);
        signIn.setVisibility(service.loginPending() ? View.GONE : View.VISIBLE);
        signIn.setEnabled(service.canSignIn);
        deviceSignIn.setVisibility(service.loginPending() ? View.GONE : View.VISIBLE);
        deviceSignIn.setEnabled(service.canSignIn);
        code.setText(service.userCode);
        code.setVisibility(service.userCode.isEmpty() ? View.GONE : View.VISIBLE);
        copyCode.setVisibility(service.userCode.isEmpty() ? View.GONE : View.VISIBLE);
        openPage.setVisibility(service.verificationUrl.isEmpty() ? View.GONE : View.VISIBLE);
        cancel.setVisibility(service.verificationUrl.isEmpty() ? View.GONE : View.VISIBLE);
        if (!service.verificationUrl.isEmpty() && service.userCode.isEmpty()
                && !openedUrl.equals(service.verificationUrl) && hasWindowFocus()) {
            openedUrl = service.verificationUrl;
            openBrowser(openedUrl);
        }
    }
    void renderSnapshot(UsageSnapshot snapshot) {
        windows.removeAllViews(); resets.removeAllViews();
        resets.setVisibility(snapshot != null && snapshot.availableResets != null
                && snapshot.availableResets > 0 ? View.VISIBLE : View.GONE);
        updateRow.setVisibility(snapshot == null ? View.GONE : View.VISIBLE);
        resetCount.setVisibility(snapshot != null && snapshot.availableResets != null ? View.VISIBLE : View.GONE);
        if (snapshot != null && snapshot.availableResets != null) {
            int count = snapshot.availableResets;
            resetCount.setText(count + (count == 1 ? " reset" : " resets"));
            resetCount.setContentDescription(count + " resets available");
        }
        if (snapshot == null) {
            LinearLayout empty = column();
            empty.addView(text("Your Codex usage", 17, TEXT, true));
            add(empty, text("Sign in to see what’s remaining.", 14, MUTED, false), 16);
            windows.addView(empty);
            updated.setText("Not updated yet");
            return;
        }
        for (UsageSnapshot.Window window : snapshot.windows) {
            if (windows.getChildCount() > 0) {
                View divider = new View(this); divider.setBackgroundColor(Color.rgb(48,48,48));
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(1));
                params.topMargin = dp(20); windows.addView(divider, params);
            }
            LinearLayout section = column();
            section.addView(text(window.label(), 16, TEXT, true));
            LinearLayout number = row(); number.setBaselineAligned(true);
            TextView percent = text(window.percent(), 48, TEXT, true);
            percent.setLetterSpacing(-0.04f); number.addView(percent);
            TextView left = text("left", 18, MUTED, false);
            LinearLayout.LayoutParams leftParams = new LinearLayout.LayoutParams(-2, -2);
            leftParams.leftMargin = dp(10); number.addView(left, leftParams);
            add(section, number, 12);
            ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            bar.setProgressDrawable(getDrawable(R.drawable.quota_progress));
            bar.setMax(1000); bar.setProgress((int) Math.round(window.remaining() * 10));
            bar.setContentDescription(window.percent() + " remaining");
            LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(-1, dp(6));
            barParams.topMargin = dp(12); section.addView(bar, barParams);
            TextView reset = text(window.resetLabel(), 13, MUTED, false);
            add(section, reset, 16);
            reset.setOnClickListener(view -> Toast.makeText(this,
                    "Resets " + UsageSnapshot.expiry(window.resetsAt), Toast.LENGTH_LONG).show());
            add(windows, section, windows.getChildCount() == 0 ? 0 : 20);
        }
        updated.setText("Updated " + UsageSnapshot.time(snapshot.updatedAt));
        if (snapshot.availableResets == null || snapshot.availableResets == 0) return;
        LinearLayout heading = row();
        heading.addView(text("Usage resets", 20, TEXT, true), new LinearLayout.LayoutParams(0, -2, 1));
        heading.addView(text(snapshot.availableResets + " available", 13, MUTED, false));
        resets.addView(heading);
        if (snapshot.resets.isEmpty()) {
            add(resets, text("Expiry details aren’t available.", 13, MUTED, false), 16);
        } else for (UsageSnapshot.Reset reset : snapshot.resets) {
            View divider = new View(this); divider.setBackgroundColor(Color.rgb(43,43,43));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(1));
            params.topMargin = dp(18); resets.addView(divider, params);
            add(resets, text(reset.title, 15, TEXT, true), 16);
            add(resets, text(reset.expiresAt == null ? "Expiry not provided"
                    : "Expires " + UsageSnapshot.expiry(reset.expiresAt), 13, MUTED, false), 6);
        }
    }
    private void showMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor, Gravity.END);
        AppWidgetManager widgets = getSystemService(AppWidgetManager.class);
        if (widgets.isRequestPinAppWidgetSupported()) menu.getMenu().add(0, 1, 0, "Add widget");
        if ((service != null && service.authenticated()) || UsageSnapshot.load(this) != null)
            menu.getMenu().add(0, 2, 1, "Sign out").setEnabled(service != null && service.canSignOut());
        menu.getMenu().add(0, 3, 2, "Open-source licenses");
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                widgets.requestPinAppWidget(new ComponentName(this, UsageWidget.class), null, null);
            } else if (item.getItemId() == 2) {
                new AlertDialog.Builder(this).setTitle("Sign out?")
                        .setMessage("Your saved sign-in and usage will be removed from this device.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Sign out", (dialog, which) -> {
                            if (service != null) { openedUrl = ""; service.signOut(); }
                        }).show();
            } else if (item.getItemId() == 3) {
                startActivity(new Intent(this, LicensesActivity.class));
            }
            return true;
        });
        menu.show();
    }
    private void openBrowser(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (ActivityNotFoundException ignored) { Toast.makeText(this, "No browser is available", Toast.LENGTH_LONG).show(); }
    }
    private LinearLayout card() {
        LinearLayout view = column(); view.setPadding(dp(20), dp(20), dp(20), dp(20));
        view.setBackground(shape(SURFACE, 20, true)); return view;
    }
    private GradientDrawable shape(int color, int radius, boolean border) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(dp(radius));
        if (border) drawable.setStroke(dp(1), Color.rgb(48,48,48)); return drawable;
    }
    private Button button(String label, boolean filled) {
        Button button = new Button(this); button.setText(label); button.setAllCaps(false); button.setTextSize(14);
        button.setTextColor(filled ? BG : TEXT); button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setMinHeight(dp(48)); button.setMinimumHeight(dp(48)); button.setPadding(dp(18), 0, dp(18), 0);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.rgb(85,85,85)),
                shape(filled ? TEXT : BG, 24, false), null)); return button;
    }
    private TextView text(String value, int size, int color, boolean medium) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setTypeface(Typeface.create(medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        view.setIncludeFontPadding(false); return view;
    }
    private LinearLayout column() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private LinearLayout row() {
        LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.HORIZONTAL); view.setGravity(Gravity.CENTER_VERTICAL); return view;
    }
    private void add(LinearLayout parent, View child, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(top); parent.addView(child, params);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
