package dev.ultra.launcher;

import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.util.SizeF;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.qtproject.qt.android.bindings.QtActivity;

import java.io.ByteArrayOutputStream;
import java.text.Collator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The home screen activity. The UI is the QML app in qt/qml, run by Qt; this class is the
 * Android side of it. The C++ backend (qt/src/backend.cpp) calls the static methods below
 * from Qt's thread, and this class calls back through {@link Native} once Qt is ready.
 *
 * All geometry crossing between QML and Java is in physical screen pixels: QML maps its
 * items to global coordinates and the backend scales them by the device pixel ratio.
 *
 * Widgets are real AppWidgetHostViews in a layer above Qt's surface. QML reports where its
 * live widget slots are (placeWidgets) and this class moves the views there.
 */
public class HomeActivity extends QtActivity {
    /** The GitHub release the updater installs from. */
    static final String RELEASE = "launcher-qt-latest";
    // The WebView build's host id: its widgets are bound to it, so they carry over.
    static final int HOST_ID = 0x0171;
    static final int REQ_BIND = 1, REQ_CONFIGURE = 2;

    private static volatile HomeActivity self;
    private static volatile boolean qtReady;

    private final Handler main = new Handler(Looper.getMainLooper());
    private LauncherApps launcher;
    private final UserHandle user = Process.myUserHandle();
    private final Map<String, LauncherActivityInfo> activities = new ConcurrentHashMap<>();
    private AppWidgetManager widgets;
    private AppWidgetHost widgetHost;
    private CoverLayer widgetLayer;
    private final Map<Integer, AppWidgetHostView> widgetViews = new HashMap<>();
    private final Map<Integer, Long> widgetSizes = new HashMap<>();
    private int pendingWidget = -1;
    private volatile int editingWidget = -1;
    private int insetTop, insetBottom;
    private final int[] layerAt = new int[2];

    private final LauncherApps.Callback packages = new LauncherApps.Callback() {
        @Override public void onPackageRemoved(String pkg, UserHandle u) { appsChanged(); }
        @Override public void onPackageAdded(String pkg, UserHandle u) { appsChanged(); }
        @Override public void onPackageChanged(String pkg, UserHandle u) { appsChanged(); }
        @Override public void onPackagesAvailable(String[] pkgs, UserHandle u, boolean replacing) { appsChanged(); }
        @Override public void onPackagesUnavailable(String[] pkgs, UserHandle u, boolean replacing) { appsChanged(); }
    };

    @Override
    public void onCreate(Bundle state) {
        self = this;
        super.onCreate(state);
        launcher = getSystemService(LauncherApps.class);
        launcher.registerCallback(packages, main);
        widgets = AppWidgetManager.getInstance(this);
        widgetHost = new Host(this);
        seeThrough();
    }

    /** The wallpaper shows through the window and Qt's surface. */
    private void seeThrough() {
        Window w = getWindow();
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        w.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
        } else {
            w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    /** Called once QML is up: add the widget layer over Qt's views and start talking to it. */
    static void ready() {
        HomeActivity a = self;
        if (a == null) return;
        a.main.post(() -> {
            a.seeThrough();
            if (a.widgetLayer == null) {
                a.widgetLayer = new CoverLayer(a);
                ViewGroup content = a.findViewById(android.R.id.content);
                content.addView(a.widgetLayer, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                content.setOnApplyWindowInsetsListener((v, insets) -> {
                    a.onInsets(insets);
                    return v.onApplyWindowInsets(insets);
                });
                content.requestApplyInsets();
            }
            qtReady = true;
            Native.insets(a.insetTop, a.insetBottom);
            a.readLegacyHome();
        });
    }

    /**
     * The WebView build kept its home screen in its page's localStorage, which lives on in
     * this app's data dir. Read it once, through a throwaway WebView on the same origin,
     * and hand the ultra.home.* keys to QML, which merges them into the Qt home.
     */
    private void readLegacyHome() {
        final android.content.SharedPreferences prefs = getSharedPreferences("qt", MODE_PRIVATE);
        if (prefs.getBoolean("legacyRead", false)) return;
        final android.webkit.WebView w;
        try {
            w = new android.webkit.WebView(this);
        } catch (RuntimeException e) {
            Log.w("ultra", "no WebView to read the old home from", e);
            return;
        }
        w.getSettings().setJavaScriptEnabled(true);
        w.getSettings().setDomStorageEnabled(true);
        final boolean[] done = {false};
        w.setWebViewClient(new android.webkit.WebViewClient() {
            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(
                    android.webkit.WebView v, android.webkit.WebResourceRequest r) {
                return new android.webkit.WebResourceResponse("text/html", "utf-8",
                        new java.io.ByteArrayInputStream("<!doctype html><title>.</title>".getBytes()));
            }

            @Override
            public void onPageFinished(android.webkit.WebView v, String url) {
                if (done[0]) return;
                done[0] = true;
                v.evaluateJavascript(LEGACY_JS, value -> {
                    String json = null;
                    try {
                        json = new JSONArray("[" + value + "]").optString(0, null);
                    } catch (JSONException e) {
                        Log.w("ultra", "old home unreadable: " + value);
                    }
                    prefs.edit().putBoolean("legacyRead", true).apply();
                    if (json != null && json.startsWith("{")) Native.legacyHome(json);
                    v.destroy();
                });
            }
        });
        w.loadUrl("https://appassets.androidplatform.net/index.html");
    }

    private static final String LEGACY_JS =
            "(function(){var o={};for(var i=0;i<localStorage.length;i++){var k=localStorage.key(i);"
            + "if(k.indexOf('ultra.home.')===0)o[k]=localStorage.getItem(k);}return JSON.stringify(o);})()";

    private void onInsets(WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= 30) {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsets.Type.ime());
            insetTop = bars.top;
            insetBottom = Math.max(bars.bottom, ime.bottom);
        } else {
            insetTop = insets.getSystemWindowInsetTop();
            insetBottom = insets.getSystemWindowInsetBottom();
        }
        if (qtReady) Native.insets(insetTop, insetBottom);
    }

    @Override
    public void onStart() {
        super.onStart();
        widgetHost.startListening();
        Updater.check(this, false);
    }

    @Override
    public void onStop() {
        super.onStop();
        widgetHost.stopListening();
    }

    @Override
    public void onDestroy() {
        launcher.unregisterCallback(packages);
        if (self == this) self = null;
        super.onDestroy();
    }

    // The home button while we are already in front.
    @Override
    public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (qtReady) Native.homePressed();
    }

    // A home screen has nowhere to go back to: back clears the drawer, a sheet or edit mode.
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled() && qtReady) Native.backPressed();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (qtReady) Native.backPressed();
    }

    private void appsChanged() {
        if (qtReady) Native.appsChanged();
    }

    // ------------------------------------------------------------ called from Qt

    /** {"apps": [{"label", "component", "system"}]}, every launchable activity, by label. */
    static String apps() {
        HomeActivity a = self;
        if (a == null) return "{\"apps\":[]}";
        try {
            List<LauncherActivityInfo> list = new ArrayList<>(a.launcher.getActivityList(null, a.user));
            Map<LauncherActivityInfo, String> labels = new HashMap<>();
            for (LauncherActivityInfo i : list) labels.put(i, String.valueOf(i.getLabel()));
            Collator by = Collator.getInstance();
            list.sort((x, y) -> by.compare(labels.get(x), labels.get(y)));
            JSONArray out = new JSONArray();
            for (LauncherActivityInfo i : list) {
                ComponentName cn = i.getComponentName();
                if (cn.getPackageName().equals(a.getPackageName())) continue;
                String c = cn.flattenToShortString();
                a.activities.put(c, i);
                JSONObject o = new JSONObject().put("label", labels.get(i)).put("component", c);
                // System apps can't be uninstalled, so the drawer offers no Uninstall for them.
                if ((i.getApplicationInfo().flags & ApplicationInfo.FLAG_SYSTEM) != 0) o.put("system", true);
                out.put(o);
            }
            return new JSONObject().put("apps", out).toString();
        } catch (JSONException | RuntimeException e) {
            Log.w("ultra", "listing apps", e);
            return "{\"apps\":[]}";
        }
    }

    /** {"widgets": [{"label", "app", "provider", "h"}]}, every widget provider; h in dp. */
    static String widgetProviders() {
        HomeActivity a = self;
        if (a == null) return "{\"widgets\":[]}";
        try {
            List<AppWidgetProviderInfo> list = new ArrayList<>(a.widgets.getInstalledProvidersForProfile(a.user));
            Map<AppWidgetProviderInfo, String> labels = new HashMap<>();
            for (AppWidgetProviderInfo w : list) labels.put(w, w.loadLabel(a.getPackageManager()));
            Collator by = Collator.getInstance();
            list.sort((x, y) -> by.compare(labels.get(x), labels.get(y)));
            float d = a.getResources().getDisplayMetrics().density;
            JSONArray out = new JSONArray();
            for (AppWidgetProviderInfo w : list) {
                int h = Math.round(w.minHeight / d);
                out.put(new JSONObject()
                        .put("label", labels.get(w))
                        .put("app", a.appLabel(w.provider.getPackageName()))
                        .put("provider", w.provider.flattenToShortString())
                        .put("h", Math.max(80, Math.min(h + 16, 420))));
            }
            return new JSONObject().put("widgets", out).toString();
        } catch (JSONException | RuntimeException e) {
            Log.w("ultra", "listing widgets", e);
            return "{\"widgets\":[]}";
        }
    }

    private String appLabel(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    // Some icon drawables don't survive being loaded and drawn concurrently.
    private static final Object iconLock = new Object();

    /** An icon as a PNG, size px square: key "a:COMPONENT" (an app) or "w:PROVIDER" (a widget). */
    static byte[] icon(String key, int size) {
        HomeActivity a = self;
        if (a == null || key == null || key.length() < 3) return null;
        synchronized (iconLock) {
            try {
                Drawable d = key.startsWith("w:") ? a.widgetIcon(key.substring(2)) : a.appIcon(key.substring(2));
                return d == null ? null : toPng(d, Math.max(16, Math.min(size, 512)));
            } catch (RuntimeException | OutOfMemoryError e) {
                Log.w("ultra", "icon for " + key, e);
                return null;
            }
        }
    }

    private Drawable appIcon(String c) {
        ComponentName cn = ComponentName.unflattenFromString(c);
        if (cn == null) return null;
        LauncherActivityInfo info = activities.get(c);
        if (info == null) {
            info = launcher.resolveActivity(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(cn), user);
        }
        if (info == null) return null;
        Drawable d = info.getIcon(getResources().getDisplayMetrics().densityDpi);
        return d != null ? d : getPackageManager().getDefaultActivityIcon();
    }

    private Drawable widgetIcon(String p) {
        AppWidgetProviderInfo w = provider(p);
        if (w == null) return null;
        Drawable d = null;
        try {
            d = w.loadIcon(this, getResources().getDisplayMetrics().densityDpi);
        } catch (RuntimeException e) {
            Log.w("ultra", "widget icon for " + p, e);
        }
        if (d == null) {
            try {
                d = getPackageManager().getApplicationIcon(w.provider.getPackageName());
            } catch (PackageManager.NameNotFoundException | RuntimeException e) {
                d = getPackageManager().getDefaultActivityIcon();
            }
        }
        return d;
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

    private AppWidgetProviderInfo provider(String p) {
        ComponentName cn = p == null ? null : ComponentName.unflattenFromString(p);
        if (cn == null) return null;
        for (AppWidgetProviderInfo w : widgets.getInstalledProvidersForProfile(user)) {
            if (w.provider.equals(cn)) return w;
        }
        return null;
    }

    private static void onMain(java.util.function.Consumer<HomeActivity> f) {
        HomeActivity a = self;
        if (a != null) a.main.post(() -> f.accept(a));
    }

    static void launch(String component) {
        onMain(a -> {
            ComponentName cn = ComponentName.unflattenFromString(component);
            try {
                a.launcher.startMainActivity(cn, a.user, null, null);
            } catch (ActivityNotFoundException | SecurityException | NullPointerException e) {
                Toast.makeText(a, "Can't open " + component, Toast.LENGTH_SHORT).show();
            }
        });
    }

    static void appInfo(String component) {
        onMain(a -> {
            ComponentName cn = ComponentName.unflattenFromString(component);
            if (cn != null) a.launcher.startAppDetailsActivity(cn, a.user, null, null);
        });
    }

    /** The system asks the user to confirm; the app list reloads when the package goes. */
    static void uninstall(String component) {
        onMain(a -> {
            ComponentName cn = ComponentName.unflattenFromString(component);
            if (cn != null) a.startIntent(new Intent(Intent.ACTION_DELETE,
                    Uri.fromParts("package", cn.getPackageName(), null)));
        });
    }

    static void openSettings() { onMain(a -> a.startIntent(new Intent(Settings.ACTION_SETTINGS))); }

    static void pickWallpaper() {
        onMain(a -> a.startIntent(Intent.createChooser(new Intent(Intent.ACTION_SET_WALLPAPER), "Wallpaper")));
    }

    static void checkUpdates() {
        HomeActivity a = self;
        if (a != null) Updater.check(a, true);
    }

    static void haptic() {
        onMain(a -> a.getWindow().getDecorView().performHapticFeedback(HapticFeedbackConstants.LONG_PRESS,
                HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING));
    }

    /**
     * Show Recents through RecentsService. Until the user has turned the service on, take
     * them to Accessibility settings, with our service highlighted where Settings supports it.
     */
    static void openRecents() {
        onMain(a -> {
            if (RecentsService.showRecents()) return;
            Toast.makeText(a, "Turn on \"ultra Home window list\" to show open windows", Toast.LENGTH_LONG).show();
            String me = new ComponentName(a, RecentsService.class).flattenToString();
            Bundle args = new Bundle();
            args.putString(":settings:fragment_args_key", me);
            a.startIntent(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .putExtra(":settings:fragment_args_key", me)
                    .putExtra(":settings:show_fragment_args", args));
        });
    }

    private void startIntent(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Not available on this device", Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------ widgets

    /** Bind a widget for provider p, then configure it if it asks to be; see onActivityResult. */
    static void addWidget(String p) {
        onMain(a -> a.bindWidget(p));
    }

    private void bindWidget(String p) {
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
        if (qtReady) Native.widgetAdded(id);
    }

    private void dropPending() {
        if (pendingWidget != -1) widgetHost.deleteAppWidgetId(pendingWidget);
        pendingWidget = -1;
    }

    @Override
    public void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (pendingWidget == -1 || (req != REQ_BIND && req != REQ_CONFIGURE)) return;
        if (result != RESULT_OK) {
            dropPending();
        } else if (req == REQ_BIND) {
            configureWidget(pendingWidget);
        } else {
            widgetReady(pendingWidget);
        }
    }

    static void removeWidget(int id) {
        onMain(a -> {
            AppWidgetHostView v = a.widgetViews.remove(id);
            if (v != null && a.widgetLayer != null) a.widgetLayer.removeView(v);
            a.widgetSizes.remove(id);
            a.widgetHost.deleteAppWidgetId(id);
        });
    }

    /**
     * A placed widget's natural width and how far it may be resized, in physical px:
     * {"w", "minW", "minH", "maxW", "maxH" (0 = no limit), "h", "v" (whether it resizes
     * horizontally / vertically)}.
     */
    static String widgetLimits(int id) {
        HomeActivity a = self;
        JSONObject o = new JSONObject();
        if (a == null) return o.toString();
        try {
            AppWidgetProviderInfo w = a.widgets.getAppWidgetInfo(id);
            if (w == null) return o.toString();
            // A minResize larger than the min size (or unset) means the min size applies.
            int minW = w.minResizeWidth > 0 && w.minResizeWidth < w.minWidth ? w.minResizeWidth : w.minWidth;
            int minH = w.minResizeHeight > 0 && w.minResizeHeight < w.minHeight ? w.minResizeHeight : w.minHeight;
            int maxW = 0, maxH = 0;
            if (Build.VERSION.SDK_INT >= 31) {
                maxW = w.maxResizeWidth;
                maxH = w.maxResizeHeight;
            }
            o.put("w", w.minWidth).put("minW", minW).put("minH", minH).put("maxW", maxW).put("maxH", maxH)
                    .put("h", (w.resizeMode & AppWidgetProviderInfo.RESIZE_HORIZONTAL) != 0)
                    .put("v", (w.resizeMode & AppWidgetProviderInfo.RESIZE_VERTICAL) != 0);
        } catch (JSONException | RuntimeException e) {
            Log.w("ultra", "widget limits", e);
        }
        return o.toString();
    }

    /** Keep the system's edge gestures off these rects ([[x, y, w, h], ...] screen px). */
    static void excludeGestures(String json) {
        if (Build.VERSION.SDK_INT < 29) return;
        onMain(a -> {
            View root = a.getWindow().getDecorView();
            int[] at = new int[2];
            root.getLocationOnScreen(at);
            List<Rect> rects = new ArrayList<>();
            try {
                JSONArray arr = new JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    JSONArray r = arr.getJSONArray(i);
                    int x = r.getInt(0) - at[0], y = r.getInt(1) - at[1];
                    rects.add(new Rect(x, y, x + r.getInt(2), y + r.getInt(3)));
                }
            } catch (JSONException e) {
                return;
            }
            root.setSystemGestureExclusionRects(rects);
        });
    }

    /** The drawer covers the screen down to y (screen px); -1 is all of it, 0 none. */
    static void coverWidgets(int y) {
        onMain(a -> {
            if (a.widgetLayer == null) return;
            if (y <= 0) { a.widgetLayer.cover(y); return; }
            a.widgetLayer.getLocationOnScreen(a.layerAt);
            a.widgetLayer.cover(Math.max(1, y - a.layerAt[1]));
        });
    }

    /** A sheet covers the screen from y (screen px) down, and dims the rest; y 0: no sheet. */
    static void sheetWidgets(int y, boolean dim) {
        onMain(a -> {
            if (a.widgetLayer == null) return;
            if (y <= 0) { a.widgetLayer.sheet(0, dim); return; }
            a.widgetLayer.getLocationOnScreen(a.layerAt);
            a.widgetLayer.sheet(Math.max(1, y - a.layerAt[1]), dim);
        });
    }

    /**
     * Lay widget views over QML's live slots: [[id, x, y, w, h, editing], ...] in screen
     * px, where editing is 1 for the one showing resize handles.
     */
    static void placeWidgets(String json) {
        onMain(a -> a.place(json));
    }

    private void place(String json) {
        if (widgetLayer == null) return;
        float d = getResources().getDisplayMetrics().density;
        widgetLayer.getLocationOnScreen(layerAt);
        Map<Integer, int[]> want = new HashMap<>();
        int editing = -1;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONArray r = arr.getJSONArray(i);
                if (r.optInt(5) == 1) editing = r.getInt(0);
                want.put(r.getInt(0), new int[] {r.getInt(1) - layerAt[0], r.getInt(2) - layerAt[1],
                        r.getInt(3), r.getInt(4)});
            }
        } catch (JSONException e) {
            return;
        }
        editingWidget = editing;
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
            // Scrolling moves a widget without resizing it; only tell it about new sizes (dp).
            int wdp = Math.round(r[2] / d), hdp = Math.round(r[3] / d);
            long size = ((long) wdp << 32) | hdp;
            Long was = widgetSizes.put(id, size);
            if (was == null || was != size) {
                if (Build.VERSION.SDK_INT >= 31) {
                    List<SizeF> sizes = new ArrayList<>();
                    sizes.add(new SizeF(wdp, hdp));
                    v.updateAppWidgetSize(new Bundle(), sizes);
                } else {
                    v.updateAppWidgetSize(new Bundle(), wdp, hdp, wdp, hdp);
                }
            }
        }
    }

    /**
     * Widget views that report a long press to QML instead of to the widget. The press
     * lifts the widget's slot; the moves and the release that follow go to QML as
     * widgetDrag(x, y) in screen px and widgetDrop(moved), so it can be dragged to a new
     * place. A widget showing resize handles is picked up by any press on it (widgetGrab).
     */
    static final class Host extends AppWidgetHost {
        Host(HomeActivity a) { super(a, HOST_ID); }

        @Override
        protected AppWidgetHostView onCreateView(Context context, int id, AppWidgetProviderInfo info) {
            return new WidgetView(context, id);
        }
    }

    /**
     * Holds the widget views. Qt can't draw over them, so the layer clips them itself:
     * the app drawer slides down over the home screen, so cover(top) hides them above
     * its edge (-1 all of them, 0 none); a sheet rises from the bottom, so sheet(bottom,
     * dim) hides them below its top edge (0: no sheet) and dims the rest like the
     * sheet's backdrop, which then takes their touches (a tap closes the sheet). Touches
     * that land on no widget fall through to Qt underneath.
     */
    static final class CoverLayer extends FrameLayout {
        private int top, bottom;
        private boolean dim;

        CoverLayer(Context c) { super(c); }

        void cover(int y) {
            if (y == top) return;
            top = y;
            apply();
        }

        void sheet(int y, boolean dimmed) {
            if (y == bottom && dimmed == dim) return;
            bottom = y;
            dim = dimmed;
            apply();
        }

        private void apply() {
            setVisibility(top < 0 ? View.INVISIBLE : View.VISIBLE);
            setAlpha(dim ? .55f : 1f);
            if (top <= 0 && bottom <= 0) {
                setClipBounds(null);
            } else {
                int t = Math.max(top, 0), b = bottom > 0 ? bottom : Math.max(getHeight(), t + 1);
                setClipBounds(new Rect(0, t, Math.max(getWidth(), 1), Math.max(b, t + 1)));
            }
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent ev) {
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN
                    && (dim || top < 0 || (top > 0 && ev.getY() < top) || (bottom > 0 && ev.getY() >= bottom))) {
                return false;
            }
            return super.dispatchTouchEvent(ev);
        }
    }

    static final class WidgetView extends AppWidgetHostView {
        private final int id;
        private float downX, downY;
        private boolean longPressed, moved;
        private final Runnable fire = new Runnable() {
            @Override public void run() {
                longPressed = true;
                moved = false;
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                setAlpha(.85f);
                if (qtReady) Native.widgetMenu(id);
            }
        };

        WidgetView(Context c, int id) {
            super(c);
            this.id = id;
        }

        /**
         * No inset: AppWidgetHostView pads older widgets by getDefaultPaddingForWidget
         * (about 8dp a side) whenever it binds one, which left a margin inside the slot.
         */
        @Override
        public void setPadding(int left, int top, int right, int bottom) {
            super.setPadding(0, 0, 0, 0);
        }

        private int slop() { return ViewConfiguration.get(getContext()).getScaledTouchSlop(); }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    longPressed = false;
                    downX = e.getX();
                    downY = e.getY();
                    HomeActivity a = self;
                    if (a != null && a.editingWidget == id) {
                        // Being resized: the press moves it, without a long press first.
                        longPressed = true;
                        moved = false;
                        setAlpha(.85f);
                        if (qtReady) Native.widgetGrab(id);
                        return true;
                    }
                    postDelayed(fire, ViewConfiguration.getLongPressTimeout());
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (Math.hypot(e.getX() - downX, e.getY() - downY) > slop()) removeCallbacks(fire);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    removeCallbacks(fire);
                    if (longPressed) {
                        drop(); // released before any move reached onTouchEvent
                        return true;
                    }
                    break;
            }
            return longPressed; // once fired, take the gesture away from the widget
        }

        private void drop() {
            longPressed = false;
            setAlpha(1f);
            if (qtReady) Native.widgetDrop(moved);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (!longPressed) {
                // Where no child took the press, its moves come here instead of through
                // onInterceptTouchEvent; keep the gesture so they do.
                int act = e.getActionMasked();
                if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL
                        || (act == MotionEvent.ACTION_MOVE && Math.hypot(e.getX() - downX, e.getY() - downY) > slop())) {
                    removeCallbacks(fire);
                }
                return super.onTouchEvent(e) || act == MotionEvent.ACTION_DOWN;
            }
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    if (!moved && Math.hypot(e.getX() - downX, e.getY() - downY) > slop()) moved = true;
                    // Raw screen coordinates: this view moves as QML reorders slots.
                    if (moved && qtReady) Native.widgetDrag(Math.round(e.getRawX()), Math.round(e.getRawY()));
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    drop();
                    break;
            }
            return true;
        }
    }
}
