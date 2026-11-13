package dev.ultra.launcher.wasm;

import android.app.NativeActivity;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.PixelFormat;
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
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.util.SizeF;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.text.Collator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class LauncherActivity extends NativeActivity {
    private static final String TAG = "ultra-wasm";

    static {
        System.loadLibrary("ultra_launcher_wasm");
    }

    private static native void nativeOnEvent(String event);
    private static native void nativeOnInsets(int top, int bottom);

    private final Handler main = new Handler(Looper.getMainLooper());
    private LauncherApps launcher;
    private final UserHandle user = Process.myUserHandle();
    private final Map<String, LauncherActivityInfo> activities = new ConcurrentHashMap<>();
    private final Object iconLock = new Object();

    private static final int WIDGET_HOST_ID = 369;
    private static final int REQ_BIND_WIDGET = 101;
    private static final int REQ_CONFIGURE_WIDGET = 102;
    private AppWidgetManager widgets;
    private AppWidgetHost widgetHost;
    private FrameLayout widgetLayer;
    private final Map<Integer, AppWidgetHostView> widgetViews = new HashMap<>();
    private final Map<Integer, Long> widgetSizes = new HashMap<>();
    private final int[] layerAt = new int[2];
    private int pendingWidget = -1;
    private String pendingProvider = "";
    private EditText searchInput;
    private boolean suppressSearchWatch = false;

    private final LauncherApps.Callback packages = new LauncherApps.Callback() {
        @Override public void onPackageRemoved(String pkg, UserHandle u) { appsChanged(pkg); }
        @Override public void onPackageAdded(String pkg, UserHandle u) { appsChanged(pkg); }
        @Override public void onPackageChanged(String pkg, UserHandle u) { appsChanged(pkg); }
        @Override public void onPackagesAvailable(String[] pkgs, UserHandle u, boolean replacing) {
            appsChanged(String.join(",", pkgs));
        }
        @Override public void onPackagesUnavailable(String[] pkgs, UserHandle u, boolean replacing) {
            appsChanged(String.join(",", pkgs));
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        getWindow().setFormat(PixelFormat.TRANSLUCENT);
        super.onCreate(savedInstanceState);
        launcher = getSystemService(LauncherApps.class);
        launcher.registerCallback(packages, main);
        widgets = AppWidgetManager.getInstance(this);
        widgetHost = new AppWidgetHost(this, WIDGET_HOST_ID) {
            @Override
            protected AppWidgetHostView onCreateView(android.content.Context context, int appWidgetId,
                                                     AppWidgetProviderInfo appWidget) {
                return new DraggableWidgetView(appWidgetId);
            }
        };
        widgetLayer = new FrameLayout(this);
        searchInput = new EditText(this);
        searchInput.setAlpha(0f);
        searchInput.setBackgroundColor(Color.TRANSPARENT);
        searchInput.setTextColor(Color.TRANSPARENT);
        searchInput.setCursorVisible(false);
        searchInput.setSingleLine(true);
        searchInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        searchInput.setImeOptions(EditorInfo.IME_ACTION_GO);
        searchInput.setFocusable(false);
        searchInput.setFocusableInTouchMode(false);
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) {
                if (!suppressSearchWatch) {
                    nativeOnEvent("search:" + s.toString());
                }
            }
        });
        searchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN)) {
                nativeOnEvent("search-submit");
                return true;
            }
            return false;
        });
        getWindow().addContentView(searchInput, new ViewGroup.LayoutParams(1, 1));
        seeThrough();
        getWindow().getDecorView().setOnApplyWindowInsetsListener((v, insets) -> {
            onInsets(insets);
            return v.onApplyWindowInsets(insets);
        });
        getWindow().getDecorView().requestApplyInsets();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (widgetHost != null) widgetHost.startListening();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (widgetHost != null) {
            try { widgetHost.stopListening(); } catch (RuntimeException ignored) { }
        }
    }

    private void seeThrough() {
        Window w = getWindow();
        w.setFormat(PixelFormat.TRANSLUCENT);
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        w.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
        }
    }

    private void onInsets(WindowInsets insets) {
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
        nativeOnInsets(top, bottom);
        nativeOnEvent("insets");
    }

    @Override
    protected void onDestroy() {
        if (launcher != null) launcher.unregisterCallback(packages);
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        nativeOnEvent("home");
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled()) {
                nativeOnEvent("back");
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private void appsChanged(String pkgs) {
        if (pkgs != null && !pkgs.isEmpty()) {
            for (String pkg : pkgs.split(",")) {
                if (pkg.isEmpty()) continue;
                String prefix = pkg + "/";
                activities.keySet().removeIf(k -> k.startsWith(prefix));
            }
        }
        nativeOnEvent("apps-changed:" + (pkgs == null ? "" : pkgs));
    }

    // ---- Called from Rust via JNI ----

    public String apps() {
        try {
            List<LauncherActivityInfo> list = new ArrayList<>(launcher.getActivityList(null, user));
            Map<LauncherActivityInfo, String> labels = new HashMap<>();
            for (LauncherActivityInfo i : list) labels.put(i, String.valueOf(i.getLabel()));
            Collator by = Collator.getInstance();
            list.sort((x, y) -> by.compare(labels.get(x), labels.get(y)));
            JSONArray out = new JSONArray();
            Set<String> seen = new HashSet<>();
            for (LauncherActivityInfo i : list) {
                ComponentName cn = i.getComponentName();
                if (cn.getPackageName().equals(getPackageName())) continue;
                String c = cn.flattenToShortString();
                seen.add(c);
                activities.put(c, i);
                JSONObject o = new JSONObject().put("label", labels.get(i)).put("component", c);
                if ((i.getApplicationInfo().flags & ApplicationInfo.FLAG_SYSTEM) != 0) o.put("system", true);
                out.put(o);
            }
            activities.keySet().retainAll(seen);
            return new JSONObject().put("apps", out).toString();
        } catch (JSONException | RuntimeException e) {
            Log.w(TAG, "listing apps", e);
            return "{\"apps\":[]}";
        }
    }

    public byte[] icon(String key, int size) {
        if (key == null || key.length() < 3) return null;
        size = Math.max(16, Math.min(size, 512));
        File f = iconFile(key, size);
        if (f != null && f.length() == (long) size * size * 4) {
            try {
                return Files.readAllBytes(f.toPath());
            } catch (java.io.IOException e) {
                f.delete();
            }
        }
        byte[] px;
        synchronized (iconLock) {
            try {
                Drawable d = appIcon(key.substring(2));
                px = d == null ? null : toPixels(d, size);
            } catch (RuntimeException | OutOfMemoryError e) {
                Log.w(TAG, "icon for " + key, e);
                return null;
            }
        }
        if (px != null && f != null) {
            File tmp = new File(f.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(px);
            } catch (java.io.IOException e) {
                tmp.delete();
                tmp = null;
            }
            if (tmp != null && !tmp.renameTo(f)) tmp.delete();
        }
        return px;
    }

    private File iconFile(String key, int size) {
        try {
            String pkg = key.substring(2).split("/", 2)[0];
            long stamp = getPackageManager().getPackageInfo(pkg, 0).lastUpdateTime;
            String id = "v2|" + key + "|" + size + "|" + stamp + "|" + getResources().getDisplayMetrics().densityDpi;
            byte[] h = MessageDigest.getInstance("SHA-1").digest(id.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder name = new StringBuilder();
            for (byte b : h) name.append(String.format("%02x", b));
            File dir = new File(getCacheDir(), "icons");
            if (!dir.isDirectory() && !dir.mkdirs()) return null;
            return new File(dir, name.toString());
        } catch (Exception e) {
            return null;
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

    private static byte[] toPixels(Drawable d, int size) {
        d = d.mutate();
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, size, size);
        d.draw(new Canvas(bmp));
        ByteBuffer buf = ByteBuffer.allocate(size * size * 4);
        bmp.copyPixelsToBuffer(buf);
        bmp.recycle();
        return buf.array();
    }

    public void launch(String component) {
        main.post(() -> {
            ComponentName cn = ComponentName.unflattenFromString(component);
            try {
                launcher.startMainActivity(cn, user, null, null);
            } catch (ActivityNotFoundException | SecurityException | NullPointerException e) {
                Toast.makeText(this, "Can't open " + component, Toast.LENGTH_SHORT).show();
            }
        });
    }

    public void appInfo(String component) {
        main.post(() -> {
            ComponentName cn = ComponentName.unflattenFromString(component);
            if (cn != null) launcher.startAppDetailsActivity(cn, user, null, null);
        });
    }

    public void uninstall(String component) {
        main.post(() -> {
            ComponentName cn = ComponentName.unflattenFromString(component);
            if (cn != null) {
                try {
                    startActivity(new Intent(Intent.ACTION_DELETE,
                            Uri.fromParts("package", cn.getPackageName(), null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(this, "Not available", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    public void openSettings() {
        main.post(() -> startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
    }

    public void pickWallpaper() {
        main.post(() -> startActivity(Intent.createChooser(
                new Intent(Intent.ACTION_SET_WALLPAPER), "Wallpaper").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
    }

    public void openRecents() {
        main.post(() -> {
            if (RecentsService.showRecents()) return;
            Toast.makeText(this, "Turn on \"ultra Wasm window list\" to show open windows", Toast.LENGTH_LONG).show();
            String me = new ComponentName(this, RecentsService.class).flattenToString();
            Bundle args = new Bundle();
            args.putString(":settings:fragment_args_key", me);
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .putExtra(":settings:fragment_args_key", me)
                        .putExtra(":settings:show_fragment_args", args)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException e) {
                Toast.makeText(this, "Not available on this device", Toast.LENGTH_SHORT).show();
            }
        });
    }

    public void haptic() {
        main.post(() -> getWindow().getDecorView().performHapticFeedback(
                HapticFeedbackConstants.LONG_PRESS, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING));
    }

    public void setTextInput(boolean active) {
        main.post(() -> {
            InputMethodManager imm = getSystemService(InputMethodManager.class);
            if (imm == null || searchInput == null) return;
            if (active) {
                searchInput.setFocusable(true);
                searchInput.setFocusableInTouchMode(true);
                searchInput.requestFocus();
                imm.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT);
            } else {
                imm.hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
                suppressSearchWatch = true;
                searchInput.setText("");
                suppressSearchWatch = false;
                searchInput.clearFocus();
                searchInput.setFocusableInTouchMode(false);
                searchInput.setFocusable(false);
            }
        });
    }

    public String widgetProviders() {
        JSONArray out = new JSONArray();
        PackageManager pm = getPackageManager();
        for (AppWidgetProviderInfo info : widgets.getInstalledProvidersForProfile(user)) {
            if (info.provider == null) continue;
            JSONObject o = new JSONObject();
            try {
                o.put("provider", info.provider.flattenToString());
                o.put("label", info.loadLabel(pm));
                o.put("w", Math.max(1, Math.min(5, (info.minWidth + 69) / 70)));
                o.put("h", Math.max(1, Math.min(5, (info.minHeight + 69) / 70)));
                out.put(o);
            } catch (JSONException ignored) {
            }
        }
        return out.toString();
    }

    public void addWidget(String provider) {
        main.post(() -> {
            ComponentName cn = ComponentName.unflattenFromString(provider);
            if (cn == null) return;
            AppWidgetProviderInfo target = null;
            for (AppWidgetProviderInfo info : widgets.getInstalledProvidersForProfile(user)) {
                if (cn.equals(info.provider)) { target = info; break; }
            }
            if (target == null) return;
            int id = widgetHost.allocateAppWidgetId();
            pendingWidget = id;
            pendingProvider = provider;
            if (widgets.bindAppWidgetIdIfAllowed(id, user, target.provider, null)) {
                configureWidget(id);
            } else {
                Intent i = new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, target.provider)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, user);
                try {
                    startActivityForResult(i, REQ_BIND_WIDGET);
                } catch (ActivityNotFoundException e) {
                    dropPendingWidget();
                }
            }
        });
    }

    private void configureWidget(int id) {
        AppWidgetProviderInfo w = widgets.getAppWidgetInfo(id);
        if (w != null && w.configure != null) {
            try {
                widgetHost.startAppWidgetConfigureActivityForResult(this, id, 0, REQ_CONFIGURE_WIDGET, null);
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) {
            }
        }
        finishPendingWidget(id);
    }

    private void finishPendingWidget(int id) {
        String prov = pendingProvider;
        pendingWidget = -1;
        pendingProvider = "";
        nativeOnEvent("widget-added:" + id + ":" + prov);
    }

    private void dropPendingWidget() {
        if (pendingWidget != -1) widgetHost.deleteAppWidgetId(pendingWidget);
        pendingWidget = -1;
        pendingProvider = "";
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (pendingWidget == -1 || (req != REQ_BIND_WIDGET && req != REQ_CONFIGURE_WIDGET)) return;
        if (result != RESULT_OK) {
            dropPendingWidget();
        } else if (req == REQ_BIND_WIDGET) {
            configureWidget(pendingWidget);
        } else {
            finishPendingWidget(pendingWidget);
        }
    }

    private boolean widgetsVisible = true;
    private String lastPlacedWidgetsJson = "[]";

    public void removeWidget(int id) {
        main.post(() -> {
            AppWidgetHostView v = widgetViews.remove(id);
            if (v != null && v.isAttachedToWindow()) {
                try { getWindowManager().removeViewImmediate(v); } catch (RuntimeException ignored) { }
            }
            widgetSizes.remove(id);
            widgetHost.deleteAppWidgetId(id);
        });
    }

    public void setWidgetsVisible(boolean visible) {
        main.post(() -> {
            widgetsVisible = visible;
            for (AppWidgetHostView v : widgetViews.values()) {
                v.setVisibility(visible ? View.VISIBLE : View.GONE);
            }
        });
    }

    private final class DraggableWidgetView extends AppWidgetHostView {
        private final int widgetId;
        private final int touchSlop;
        private boolean lifted = false;
        private float downRawX, downRawY, lastRawX, lastRawY;
        private int startWinX, startWinY;
        private final Runnable longPress;

        DraggableWidgetView(int widgetId) {
            super(LauncherActivity.this);
            this.widgetId = widgetId;
            this.touchSlop = ViewConfiguration.get(LauncherActivity.this).getScaledTouchSlop();
            this.longPress = () -> {
                lifted = true;
                requestDisallowInterceptTouchEvent(true);
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS,
                        HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING);
                setAlpha(0.88f);
                setScaleX(1.04f);
                setScaleY(1.04f);
                WindowManager.LayoutParams lp = (WindowManager.LayoutParams) getLayoutParams();
                startWinX = lp.x;
                startWinY = lp.y;
                nativeOnEvent("widget-lift:" + this.widgetId + ":" + startWinX + ":" + startWinY);
            };
        }

        boolean isLifted() { return lifted; }

        private void handleMove(MotionEvent ev) {
            lastRawX = ev.getRawX();
            lastRawY = ev.getRawY();
            if (lifted) {
                WindowManager.LayoutParams lp = (WindowManager.LayoutParams) getLayoutParams();
                int nx = Math.round(startWinX + (lastRawX - downRawX));
                int ny = Math.round(startWinY + (lastRawY - downRawY));
                if (nx != lp.x || ny != lp.y) {
                    lp.x = nx;
                    lp.y = ny;
                    getWindowManager().updateViewLayout(this, lp);
                    nativeOnEvent("widget-drag:" + nx + ":" + ny);
                }
            } else if (Math.hypot(lastRawX - downRawX, lastRawY - downRawY) > touchSlop) {
                main.removeCallbacks(longPress);
            }
        }

        private void handleEnd(MotionEvent ev) {
            main.removeCallbacks(longPress);
            if (lifted) {
                lifted = false;
                setAlpha(1.0f);
                setScaleX(1.0f);
                setScaleY(1.0f);
                int finalX = Math.round(startWinX + (ev.getRawX() - downRawX));
                int finalY = Math.round(startWinY + (ev.getRawY() - downRawY));
                float dist = (float) Math.hypot(ev.getRawX() - downRawX, ev.getRawY() - downRawY);
                nativeOnEvent("widget-drop:" + widgetId + ":" + finalX + ":" + finalY + ":" + dist);
            }
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lifted = false;
                    downRawX = lastRawX = ev.getRawX();
                    downRawY = lastRawY = ev.getRawY();
                    main.removeCallbacks(longPress);
                    main.postDelayed(longPress, 450);
                    super.dispatchTouchEvent(ev);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    handleMove(ev);
                    if (lifted) return true;
                    return super.dispatchTouchEvent(ev);
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    boolean wasLifted = lifted;
                    handleEnd(ev);
                    if (wasLifted) return true;
                    return super.dispatchTouchEvent(ev);
            }
            return super.dispatchTouchEvent(ev);
        }
    }

    public void placeWidgets(String json) {
        main.post(() -> {
            lastPlacedWidgetsJson = json;
            if (getWindow().getDecorView().getWindowToken() == null) {
                getWindow().getDecorView().post(() -> placeWidgets(lastPlacedWidgetsJson));
                return;
            }
            float d = getResources().getDisplayMetrics().density;
            Map<Integer, int[]> want = new HashMap<>();
            try {
                JSONArray arr = new JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    JSONArray r = arr.getJSONArray(i);
                    want.put(r.getInt(0), new int[] {
                            r.getInt(1),
                            r.getInt(2),
                            r.getInt(3),
                            r.getInt(4)
                    });
                }
            } catch (JSONException e) {
                return;
            }
            WindowManager wm = getWindowManager();
            for (Map.Entry<Integer, AppWidgetHostView> e : widgetViews.entrySet()) {
                if (!want.containsKey(e.getKey())) e.getValue().setVisibility(View.GONE);
            }
            for (Map.Entry<Integer, int[]> e : want.entrySet()) {
                int id = e.getKey();
                int[] r = e.getValue();
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                        r[2],
                        r[3],
                        WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT);
                lp.gravity = Gravity.TOP | Gravity.LEFT;
                lp.x = r[0];
                lp.y = r[1];
                lp.token = getWindow().getDecorView().getWindowToken();

                AppWidgetHostView v = widgetViews.get(id);
                if (v instanceof DraggableWidgetView && ((DraggableWidgetView) v).isLifted()) {
                    continue;
                }
                if (v == null) {
                    AppWidgetProviderInfo info = widgets.getAppWidgetInfo(id);
                    if (info == null) continue;
                    v = widgetHost.createView(this, id, info);
                    widgetViews.put(id, v);
                    v.setVisibility(widgetsVisible ? View.VISIBLE : View.GONE);
                    wm.addView(v, lp);
                } else {
                    v.setVisibility(widgetsVisible ? View.VISIBLE : View.GONE);
                    wm.updateViewLayout(v, lp);
                }
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
        });
    }
}
