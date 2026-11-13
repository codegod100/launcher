package dev.ultra.launcher.wasm;

import android.app.NativeActivity;
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
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
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
        seeThrough();
        getWindow().getDecorView().setOnApplyWindowInsetsListener((v, insets) -> {
            onInsets(insets);
            return v.onApplyWindowInsets(insets);
        });
        getWindow().getDecorView().requestApplyInsets();
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

    public void haptic() {
        main.post(() -> getWindow().getDecorView().performHapticFeedback(
                HapticFeedbackConstants.LONG_PRESS, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING));
    }

    public void setTextInput(boolean active) {
        main.post(() -> {
            InputMethodManager imm = getSystemService(InputMethodManager.class);
            if (imm == null) return;
            if (active) imm.showSoftInput(getWindow().getDecorView(), 0);
            else imm.hideSoftInputFromWindow(getWindow().getDecorView().getWindowToken(), 0);
        });
    }
}
