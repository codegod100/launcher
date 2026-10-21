package dev.ultra.launcher;

import android.app.Activity;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
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
import android.util.Base64;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.Window;
import android.view.WindowInsets;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The home screen activity. The UI is the ultra app in examples/launcher (LPython compiled
 * to wasm), packaged as assets and served to a transparent WebView from a virtual https
 * origin. This class answers the two dynamic URLs the app fetches:
 *
 *   apps.json       {"apps": [{"label", "component", "icon"}]}, every launchable activity
 *   icon?c=COMP     the activity's icon as a PNG
 *   widgets.json    {"widgets": [{"label", "provider", "icon", "h"}]}, every widget provider
 *   wicon?p=PROV    the provider's icon as a PNG
 *
 * and exposes window.Android (launch, appInfo, addWidget, removeWidget, placeWidgets,
 * openSettings, pickWallpaper, haptic, is24Hour) for the app's @jscall imports.
 *
 * Widgets are real AppWidgetHostViews in a layer above the WebView. The page reports where
 * its live widget slots are (placeWidgets), and this class moves the views there. Package
 * changes, the home button, back, resume, a new widget and a long press on a widget reach
 * the page as window.ultraHome.reload() / home() / back() / tick() / widgetAdded(id) /
 * widgetMenu(id).
 */
public class MainActivity extends Activity {
    static final String HOST = "appassets.androidplatform.net";
    static final String ORIGIN = "https://" + HOST + "/";
    static final int ICON_DP = 56;
    static final int HOST_ID = 0x0171;
    static final int REQ_BIND = 1, REQ_CONFIGURE = 2;

    private WebView web;
    private FrameLayout widgetLayer;
    private AppWidgetManager widgets;
    private AppWidgetHost widgetHost;
    private final Map<Integer, AppWidgetHostView> widgetViews = new HashMap<>();
    private int pendingWidget = -1;
    private boolean ready;
    private int insetTop, insetBottom; // CSS px
    private LauncherApps launcher;
    private final UserHandle user = Process.myUserHandle();
    private final Map<String, byte[]> icons = new ConcurrentHashMap<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService iconThread = Executors.newSingleThreadExecutor();

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
        widgets = AppWidgetManager.getInstance(this);
        widgetHost = new Host(this);

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
        // Long presses are the page's (on_longpress); no native text selection.
        web.setOnLongClickListener(v -> true);
        web.setHapticFeedbackEnabled(false);
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
        widgetViews.clear();
        widgetLayer = new FrameLayout(this);
        FrameLayout root = new FrameLayout(this);
        root.addView(web);
        root.addView(widgetLayer);
        setContentView(root);
        web.loadUrl(ORIGIN + "index.html");
    }

    @Override
    protected void onStart() {
        super.onStart();
        widgetHost.startListening();
    }

    @Override
    protected void onStop() {
        super.onStop();
        widgetHost.stopListening();
    }

    @Override
    protected void onDestroy() {
        launcher.unregisterCallback(packages);
        iconThread.shutdownNow();
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
                + "(document.documentElement.style); window.ultraHome && ultraHome.place()", null);
    }

    private void reload() {
        icons.clear();
        js("resetIcons()");
        js("reload()");
    }

    /**
     * Draw the icons for these keys ("icon?c=COMP" or "wicon?p=PROV", as apps.json and
     * widgets.json give them) on the icon thread, and hand them to the page as data: URIs,
     * a few at a time, through ultraIcons({key: uri}). A key that can't be drawn gets "".
     */
    private void loadIcons(String json) {
        List<String> keys = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) keys.add(a.getString(i));
        } catch (JSONException e) {
            return;
        }
        iconThread.execute(() -> {
            JSONObject batch = new JSONObject();
            for (int i = 0; i < keys.size(); i++) {
                String k = keys.get(i);
                byte[] png = null;
                if (k.startsWith("icon?c=")) png = iconPng(Uri.decode(k.substring(7)));
                else if (k.startsWith("wicon?p=")) png = widgetIconPng(Uri.decode(k.substring(8)));
                try {
                    batch.put(k, png == null ? ""
                            : "data:image/png;base64," + Base64.encodeToString(png, Base64.NO_WRAP));
                } catch (JSONException e) {
                    continue;
                }
                if (batch.length() == 8 || i == keys.size() - 1) {
                    String call = "window.ultraIcons && ultraIcons(" + batch + ")";
                    main.post(() -> web.evaluateJavascript(call, null));
                    batch = new JSONObject();
                }
            }
        });
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

    // Icons are drawn one at a time: the WebView asks for a screenful at once on several
    // threads, and some icon drawables don't survive being loaded and drawn concurrently.
    private final Object iconLock = new Object();

    private byte[] iconPng(String c) {
        byte[] png = icons.get(c);
        if (png != null) return png;
        synchronized (iconLock) {
            png = icons.get(c);
            if (png != null) return png;
            try {
                png = drawIcon(c);
            } catch (RuntimeException | OutOfMemoryError e) {
                Log.w("ultra", "icon for " + c, e);
                return null;
            }
            if (png != null) icons.put(c, png);
            return png;
        }
    }

    private byte[] drawIcon(String c) {
        ComponentName cn = c == null ? null : ComponentName.unflattenFromString(c);
        if (cn == null) return null;
        LauncherActivityInfo info = launcher.resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(cn), user);
        if (info == null) return null;
        int dpi = getResources().getDisplayMetrics().densityDpi;
        Drawable d = info.getIcon(dpi);
        if (d == null) d = getPackageManager().getDefaultActivityIcon();
        return toPng(d, Math.round(ICON_DP * dpi / 160f));
    }

    private static byte[] toPng(Drawable d, int size) {
        d = d.mutate(); // setBounds on a drawable shared with other callers would move theirs
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, size, size);
        d.draw(new Canvas(bmp));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
        bmp.recycle();
        return out.toByteArray();
    }

    // ------------------------------------------------------------ widgets

    private String widgetsJson() throws JSONException {
        List<AppWidgetProviderInfo> list = new ArrayList<>(widgets.getInstalledProvidersForProfile(user));
        Map<AppWidgetProviderInfo, String> labels = new HashMap<>();
        for (AppWidgetProviderInfo w : list) labels.put(w, w.loadLabel(getPackageManager()));
        Collator by = Collator.getInstance();
        list.sort((a, b) -> by.compare(labels.get(a), labels.get(b)));
        float d = getResources().getDisplayMetrics().density;
        JSONArray out = new JSONArray();
        for (AppWidgetProviderInfo w : list) {
            String p = w.provider.flattenToShortString();
            int h = Math.round(w.minHeight / d);
            out.put(new JSONObject()
                    .put("label", labels.get(w))
                    .put("provider", p)
                    .put("icon", "wicon?p=" + Uri.encode(p))
                    .put("h", Math.max(80, Math.min(h + 16, 420))));
        }
        return new JSONObject().put("widgets", out).toString();
    }

    private AppWidgetProviderInfo provider(String p) {
        ComponentName cn = p == null ? null : ComponentName.unflattenFromString(p);
        if (cn == null) return null;
        for (AppWidgetProviderInfo w : widgets.getInstalledProvidersForProfile(user)) {
            if (w.provider.equals(cn)) return w;
        }
        return null;
    }

    private byte[] widgetIconPng(String p) {
        String k = "w:" + p;
        byte[] png = icons.get(k);
        if (png != null) return png;
        synchronized (iconLock) {
            AppWidgetProviderInfo w = provider(p);
            if (w == null) return null;
            int dpi = getResources().getDisplayMetrics().densityDpi;
            Drawable d = null;
            try {
                d = w.loadIcon(this, dpi);
            } catch (RuntimeException e) {
                Log.w("ultra", "widget icon for " + p, e);
            }
            if (d == null) {
                // Fall back to the app's own icon, then to the system default.
                try {
                    d = getPackageManager().getApplicationIcon(w.provider.getPackageName());
                } catch (android.content.pm.PackageManager.NameNotFoundException | RuntimeException e) {
                    d = getPackageManager().getDefaultActivityIcon();
                }
            }
            try {
                png = toPng(d, Math.round(ICON_DP * dpi / 160f));
            } catch (RuntimeException | OutOfMemoryError e) {
                Log.w("ultra", "drawing widget icon for " + p, e);
                return null;
            }
            icons.put(k, png);
            return png;
        }
    }

    /** Bind a widget for provider p, then configure it if it asks to be; see onActivityResult. */
    private void addWidget(String p) {
        AppWidgetProviderInfo w = provider(p);
        if (w == null) return;
        int id = widgetHost.allocateAppWidgetId();
        pendingWidget = id;
        if (widgets.bindAppWidgetIdIfAllowed(id, user, w.provider, null)) {
            configureWidget(id);
        } else {
            Intent i = new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, w.provider)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, user);
            try {
                startActivityForResult(i, REQ_BIND);
            } catch (ActivityNotFoundException e) {
                dropPending();
            }
        }
    }

    private void configureWidget(int id) {
        AppWidgetProviderInfo w = widgets.getAppWidgetInfo(id);
        if (w != null && w.configure != null) {
            try {
                widgetHost.startAppWidgetConfigureActivityForResult(this, id, 0, REQ_CONFIGURE, null);
                return;
            } catch (ActivityNotFoundException | SecurityException e) {
                // Fall through: add it unconfigured, as most launchers do.
            }
        }
        widgetReady(id);
    }

    private void widgetReady(int id) {
        pendingWidget = -1;
        js("widgetAdded(" + id + ")");
    }

    private void dropPending() {
        if (pendingWidget != -1) widgetHost.deleteAppWidgetId(pendingWidget);
        pendingWidget = -1;
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (pendingWidget == -1) return;
        if (result != RESULT_OK) {
            dropPending();
        } else if (req == REQ_BIND) {
            configureWidget(pendingWidget);
        } else if (req == REQ_CONFIGURE) {
            widgetReady(pendingWidget);
        }
    }

    private void removeWidget(int id) {
        AppWidgetHostView v = widgetViews.remove(id);
        if (v != null) widgetLayer.removeView(v);
        widgetHost.deleteAppWidgetId(id);
    }

    /** Lay widget views over the page's live slots: [[id, x, y, w, h], ...] in CSS px. */
    private void placeWidgets(String json) {
        float d = getResources().getDisplayMetrics().density;
        Map<Integer, int[]> want = new HashMap<>();
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                JSONArray r = a.getJSONArray(i);
                want.put(r.getInt(0), new int[] {
                        Math.round(r.getInt(1) * d), Math.round(r.getInt(2) * d),
                        Math.round(r.getInt(3) * d), Math.round(r.getInt(4) * d),
                        r.getInt(3), r.getInt(4)});
            }
        } catch (JSONException e) {
            return;
        }
        for (Map.Entry<Integer, AppWidgetHostView> e : widgetViews.entrySet()) {
            if (!want.containsKey(e.getKey())) e.getValue().setVisibility(View.GONE);
        }
        for (Map.Entry<Integer, int[]> e : want.entrySet()) {
            int id = e.getKey();
            int[] r = e.getValue();
            AppWidgetHostView v = widgetViews.get(id);
            if (v == null) {
                AppWidgetProviderInfo info = widgets.getAppWidgetInfo(id);
                if (info == null) continue; // the provider was uninstalled
                v = widgetHost.createView(this, id, info);
                widgetViews.put(id, v);
                widgetLayer.addView(v);
            }
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(r[2], r[3]);
            lp.leftMargin = r[0];
            lp.topMargin = r[1];
            v.setLayoutParams(lp);
            v.setVisibility(View.VISIBLE);
            v.updateAppWidgetSize(new Bundle(), r[4], r[5], r[4], r[5]);
        }
    }

    /** Widget views that report a long press to the page instead of to the widget. */
    static final class Host extends AppWidgetHost {
        Host(MainActivity a) { super(a, HOST_ID); }

        @Override
        protected AppWidgetHostView onCreateView(Context context, int id, AppWidgetProviderInfo info) {
            return new WidgetView((MainActivity) context, id);
        }
    }

    static final class WidgetView extends AppWidgetHostView {
        private final MainActivity home;
        private final int id;
        private float downX, downY;
        private boolean longPressed;
        private final Runnable fire = new Runnable() {
            @Override public void run() {
                longPressed = true;
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                home.js("widgetMenu(" + id + ")");
            }
        };

        WidgetView(MainActivity home, int id) {
            super(home);
            this.home = home;
            this.id = id;
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    longPressed = false;
                    downX = e.getX();
                    downY = e.getY();
                    postDelayed(fire, ViewConfiguration.getLongPressTimeout());
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (Math.hypot(e.getX() - downX, e.getY() - downY)
                            > ViewConfiguration.get(getContext()).getScaledTouchSlop()) {
                        removeCallbacks(fire);
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    removeCallbacks(fire);
                    break;
            }
            return longPressed; // once fired, take the gesture away from the widget
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            return longPressed || super.onTouchEvent(e);
        }
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

        @JavascriptInterface public void addWidget(String provider) {
            main.post(() -> MainActivity.this.addWidget(provider));
        }

        @JavascriptInterface public void removeWidget(int id) {
            main.post(() -> MainActivity.this.removeWidget(id));
        }

        @JavascriptInterface public void placeWidgets(String json) {
            main.post(() -> MainActivity.this.placeWidgets(json));
        }

        @JavascriptInterface public void loadIcons(String keys) {
            MainActivity.this.loadIcons(keys);
        }

        @JavascriptInterface public void haptic() {
            main.post(() -> web.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS,
                    HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING));
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
                    case "/widgets.json":
                        return respond("application/json",
                                new ByteArrayInputStream(widgetsJson().getBytes(StandardCharsets.UTF_8)));
                    case "/wicon":
                        byte[] wpng = widgetIconPng(u.getQueryParameter("p"));
                        return wpng == null ? notFound() : respond("image/png", new ByteArrayInputStream(wpng));
                    case "/icon":
                        byte[] png = iconPng(u.getQueryParameter("c"));
                        return png == null ? notFound() : respond("image/png", new ByteArrayInputStream(png));
                    default:
                        String name = path.equals("/") ? "index.html" : path.substring(1);
                        return respond(mime(name), getAssets().open(name));
                }
            } catch (IOException | JSONException | RuntimeException e) {
                Log.w("ultra", "serving " + path, e);
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
            for (AppWidgetHostView v : widgetViews.values()) widgetLayer.removeView(v);
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
