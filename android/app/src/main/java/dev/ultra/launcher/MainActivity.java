package dev.ultra.launcher;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The home screen activity. The UI is the ultra app in examples/launcher (LPython compiled
 * to wasm), packaged as assets and served to a transparent WebView from a virtual https
 * origin. This class answers the two dynamic URLs the app fetches:
 *
 *   apps.json       {"apps": [{"label", "component", "icon"}]}, every launchable activity
 *   icon?c=COMP     the activity's icon as a PNG
 *
 * and exposes window.Android (launch, appInfo, openSettings, pickWallpaper, is24Hour) for
 * the app's @jscall imports. Package changes, the home button, back and resume are passed
 * to the page as window.ultraHome.reload() / home() / back() / tick().
 */
public class MainActivity extends Activity {
    static final String HOST = "appassets.androidplatform.net";
    static final String ORIGIN = "https://" + HOST + "/";
    static final int ICON_DP = 56;

    private WebView web;
    private boolean ready;
    private int insetTop, insetBottom; // CSS px
    private LauncherApps launcher;
    private final UserHandle user = Process.myUserHandle();
    private final Map<String, byte[]> icons = new ConcurrentHashMap<>();
    private final Handler main = new Handler(Looper.getMainLooper());

    private final LauncherApps.Callback packages = new LauncherApps.Callback() {
        @Override public void onPackageRemoved(String pkg, UserHandle u) { reload(); }
        @Override public void onPackageAdded(String pkg, UserHandle u) { reload(); }
        @Override public void onPackageChanged(String pkg, UserHandle u) { reload(); }
        @Override public void onPackagesAvailable(String[] pkgs, UserHandle u, boolean replacing) { reload(); }
        @Override public void onPackagesUnavailable(String[] pkgs, UserHandle u, boolean replacing) { reload(); }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        launcher = getSystemService(LauncherApps.class);
        launcher.registerCallback(packages, main);

        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
        } else {
            w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        createWebView();
    }

    private void createWebView() {
        ready = false;
        web = new WebView(this);
        web.setBackgroundColor(Color.TRANSPARENT);
        web.setVerticalScrollBarEnabled(false);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true); // the dock is saved in localStorage
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new Client());
        web.setOnApplyWindowInsetsListener((v, insets) -> {
            int top, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                Insets ime = insets.getInsets(WindowInsets.Type.ime());
                top = bars.top;
                bottom = Math.max(bars.bottom, ime.bottom);
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            float d = getResources().getDisplayMetrics().density;
            insetTop = Math.round(top / d);
            insetBottom = Math.round(bottom / d);
            pushInsets();
            return insets;
        });
        setContentView(web);
        web.loadUrl(ORIGIN + "index.html");
    }

    @Override
    protected void onDestroy() {
        launcher.unregisterCallback(packages);
        web.destroy();
        super.onDestroy();
    }

    // The home button while we are already in front.
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        js("home()");
    }

    @Override
    protected void onResume() {
        super.onResume();
        js("tick()");
    }

    // A home screen has nowhere to go back to; let the page clear its search or edit mode.
    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        js("back()");
    }

    private void js(String call) {
        if (ready) web.evaluateJavascript("window.ultraHome && ultraHome." + call, null);
    }

    private void pushInsets() {
        if (!ready) return;
        web.evaluateJavascript("(s => { s.setProperty('--inset-top', '" + insetTop + "px');"
                + " s.setProperty('--inset-bottom', '" + insetBottom + "px'); })"
                + "(document.documentElement.style)", null);
    }

    private void reload() {
        icons.clear();
        js("reload()");
    }

    // ------------------------------------------------------------ apps

    private String appsJson() throws JSONException {
        List<LauncherActivityInfo> list = new ArrayList<>(launcher.getActivityList(null, user));
        Map<LauncherActivityInfo, String> labels = new HashMap<>();
        for (LauncherActivityInfo a : list) labels.put(a, String.valueOf(a.getLabel()));
        Collator by = Collator.getInstance();
        list.sort((a, b) -> by.compare(labels.get(a), labels.get(b)));
        JSONArray apps = new JSONArray();
        for (LauncherActivityInfo a : list) {
            ComponentName cn = a.getComponentName();
            if (cn.getPackageName().equals(getPackageName())) continue;
            String c = cn.flattenToShortString();
            apps.put(new JSONObject()
                    .put("label", labels.get(a))
                    .put("component", c)
                    .put("icon", "icon?c=" + Uri.encode(c)));
        }
        return new JSONObject().put("apps", apps).toString();
    }

    private byte[] iconPng(String c) {
        byte[] png = icons.get(c);
        if (png != null) return png;
        ComponentName cn = c == null ? null : ComponentName.unflattenFromString(c);
        if (cn == null) return null;
        LauncherActivityInfo info = launcher.resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(cn), user);
        if (info == null) return null;
        int dpi = getResources().getDisplayMetrics().densityDpi;
        Drawable d = info.getIcon(dpi);
        int size = Math.round(ICON_DP * dpi / 160f);
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, size, size);
        d.draw(new Canvas(bmp));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
        bmp.recycle();
        png = out.toByteArray();
        icons.put(c, png);
        return png;
    }

    private void start(String component) {
        ComponentName cn = ComponentName.unflattenFromString(component);
        try {
            launcher.startMainActivity(cn, user, null, null);
        } catch (ActivityNotFoundException | SecurityException | NullPointerException e) {
            Toast.makeText(this, "Can't open " + component, Toast.LENGTH_SHORT).show();
        }
    }

    private void startIntent(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Not available on this device", Toast.LENGTH_SHORT).show();
        }
    }

    /** window.Android: called from the page's @jscall imports on a binder thread. */
    final class Bridge {
        @JavascriptInterface public void launch(String component) {
            main.post(() -> start(component));
        }

        @JavascriptInterface public void appInfo(String component) {
            main.post(() -> {
                ComponentName cn = ComponentName.unflattenFromString(component);
                if (cn != null) launcher.startAppDetailsActivity(cn, user, null, null);
            });
        }

        @JavascriptInterface public void openSettings() {
            main.post(() -> startIntent(new Intent(Settings.ACTION_SETTINGS)));
        }

        @JavascriptInterface public void pickWallpaper() {
            main.post(() -> startIntent(Intent.createChooser(new Intent(Intent.ACTION_SET_WALLPAPER), "Wallpaper")));
        }

        @JavascriptInterface public boolean is24Hour() {
            return DateFormat.is24HourFormat(MainActivity.this);
        }
    }

    // ------------------------------------------------------------ serving

    final class Client extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
            Uri u = req.getUrl();
            if (!HOST.equals(u.getHost())) return notFound();
            String path = u.getPath() == null ? "/" : u.getPath();
            try {
                switch (path) {
                    case "/apps.json":
                        return respond("application/json",
                                new ByteArrayInputStream(appsJson().getBytes(StandardCharsets.UTF_8)));
                    case "/icon":
                        byte[] png = iconPng(u.getQueryParameter("c"));
                        return png == null ? notFound() : respond("image/png", new ByteArrayInputStream(png));
                    default:
                        String name = path.equals("/") ? "index.html" : path.substring(1);
                        return respond(mime(name), getAssets().open(name));
                }
            } catch (IOException | JSONException | RuntimeException e) {
                return notFound();
            }
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
            return true; // the home screen never navigates away
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            ready = true;
            pushInsets();
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            // The renderer was killed (e.g. under memory pressure): start over with a new WebView.
            web.destroy();
            createWebView();
            return true;
        }
    }

    private static WebResourceResponse respond(String mime, InputStream body) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Cache-Control", "no-cache");
        return new WebResourceResponse(mime, null, 200, "OK", headers, body);
    }

    private static WebResourceResponse notFound() {
        return new WebResourceResponse("text/plain", null, 404, "Not Found", new HashMap<>(),
                new ByteArrayInputStream(new byte[0]));
    }

    private static String mime(String name) {
        if (name.endsWith(".html")) return "text/html";
        if (name.endsWith(".js")) return "text/javascript";
        if (name.endsWith(".css")) return "text/css";
        if (name.endsWith(".wasm")) return "application/wasm";
        if (name.endsWith(".json")) return "application/json";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }
}
