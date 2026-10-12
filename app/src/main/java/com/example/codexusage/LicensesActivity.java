package com.example.codexusage;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Local license text only; JavaScript, file/content access, and WebView networking are disabled. */
public final class LicensesActivity extends Activity {
    private WebView content;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        int background = Color.rgb(15,15,15);
        getWindow().setStatusBarColor(background);
        getWindow().setNavigationBarColor(background);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(background);
        Button back = new Button(this);
        back.setText("Back"); back.setAllCaps(false);
        back.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        back.setOnClickListener(view -> finish());
        root.addView(back, new LinearLayout.LayoutParams(-1, -2));
        content = new WebView(this);
        content.setBackgroundColor(background);
        content.getSettings().setJavaScriptEnabled(false);
        content.getSettings().setAllowFileAccess(false);
        content.getSettings().setAllowContentAccess(false);
        content.getSettings().setBlockNetworkLoads(true);
        content.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if ("https".equals(request.getUrl().getScheme()) || "mailto".equals(request.getUrl().getScheme())) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, request.getUrl())); }
                    catch (ActivityNotFoundException ignored) { }
                }
                return true;
            }
        });
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        if (android.os.Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets safe = insets.getInsets(android.view.WindowInsets.Type.systemBars()
                        | android.view.WindowInsets.Type.displayCutout());
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
                return insets;
            });
            root.requestApplyInsets();
        }
        boolean privacy = getIntent().getBooleanExtra("privacy", false);
        try (InputStream input = getAssets().open(privacy ? "privacy.html" : "third_party_licenses.html");
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            content.loadDataWithBaseURL(null, new String(output.toByteArray(), StandardCharsets.UTF_8),
                    "text/html", "UTF-8", null);
        } catch (Exception ignored) {
            TextView error = new TextView(this);
            error.setText(privacy ? "Privacy information could not be loaded." : "License notices could not be loaded.");
            root.addView(error);
        }
    }
    @Override protected void onDestroy() {
        if (content != null) content.destroy();
        super.onDestroy();
    }
}
