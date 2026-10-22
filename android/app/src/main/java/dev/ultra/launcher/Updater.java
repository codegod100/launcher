package dev.ultra.launcher;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Updates the launcher from the rolling "launcher-latest" GitHub prerelease. CI uploads
 * version.json ({"versionCode": N, "commit": SHA}) next to ultra-launcher.apk, with N the
 * workflow run number, which is also the APK's versionCode. When N is newer than the installed
 * build, the APK is streamed into a PackageInstaller session and committed. Android then asks
 * the user to confirm (or, on Android 12+ once this app installed itself, may not ask at all).
 *
 * The home screen checks on start at most every CHECK_EVERY; the home menu's
 * "Check for updates" checks now and reports what it found.
 */
final class Updater {
    static final String BASE = "https://github.com/codegod100/launcher/releases/download/launcher-latest/";
    static final long CHECK_EVERY = 6 * 60 * 60 * 1000L;
    static final String EXTRA_VERSION = "dev.ultra.launcher.VERSION";

    private static final AtomicBoolean busy = new AtomicBoolean();
    private static final Handler main = new Handler(Looper.getMainLooper());

    private Updater() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("updater", Context.MODE_PRIVATE);
    }

    /** Check in the background; manual checks always run and say what happened. */
    static void check(Context ctx, boolean manual) {
        Context c = ctx.getApplicationContext();
        long now = System.currentTimeMillis();
        if (!manual && now - prefs(c).getLong("lastCheck", 0) < CHECK_EVERY) return;
        if (!busy.compareAndSet(false, true)) {
            if (manual) toast(c, "Already checking for updates");
            return;
        }
        prefs(c).edit().putLong("lastCheck", now).apply();
        if (manual) toast(c, "Checking for updates…");
        new Thread(() -> {
            try {
                run(c, manual);
            } catch (Exception e) {
                Log.w("ultra", "update check", e);
                if (manual) toast(c, "Couldn't check for updates");
            } finally {
                busy.set(false);
            }
        }, "updater").start();
    }

    private static void run(Context c, boolean manual) throws Exception {
        JSONObject v = new JSONObject(new String(get(BASE + "version.json"), StandardCharsets.UTF_8));
        long latest = v.getLong("versionCode");
        long installed = installedVersion(c);
        if (latest <= installed) {
            if (manual) toast(c, "ultra Home is up to date (build " + installed + ")");
            return;
        }
        // An install the user cancelled isn't offered again until there is a newer one, or they ask.
        if (!manual && latest == prefs(c).getLong("declined", -1)) return;
        toast(c, "Downloading launcher update (build " + latest + ")…");
        install(c, latest);
    }

    @SuppressWarnings("deprecation")
    static long installedVersion(Context c) throws PackageManager.NameNotFoundException {
        PackageInfo p = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
        return Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode;
    }

    private static void install(Context c, long version) throws IOException {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(c.getPackageName());
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        int id = pi.createSession(params);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            HttpURLConnection conn = open(BASE + "ultra-launcher.apk");
            try (InputStream in = conn.getInputStream();
                 OutputStream out = s.openWrite("ultra-launcher.apk", 0, conn.getContentLengthLong())) {
                byte[] buf = new byte[64 * 1024];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                s.fsync(out);
            } finally {
                conn.disconnect();
            }
            Intent done = new Intent(c, InstallReceiver.class).putExtra(EXTRA_VERSION, version);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            s.commit(PendingIntent.getBroadcast(c, id, done, flags).getIntentSender());
        } catch (IOException | RuntimeException e) {
            pi.abandonSession(id);
            throw e;
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setUseCaches(false);
        conn.setInstanceFollowRedirects(true); // github.com redirects assets to its download host
        int code = conn.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            conn.disconnect();
            throw new IOException("HTTP " + code + " for " + url);
        }
        return conn;
    }

    private static byte[] get(String url) throws IOException {
        HttpURLConnection conn = open(url);
        try (InputStream in = conn.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            conn.disconnect();
        }
    }

    static void toast(Context c, String msg) {
        main.post(() -> Toast.makeText(c, msg, Toast.LENGTH_LONG).show());
    }

    /** Hears back from the install session: shows Android's confirm screen, or notes the outcome. */
    public static final class InstallReceiver extends BroadcastReceiver {
        @Override
        @SuppressWarnings("deprecation")
        public void onReceive(Context c, Intent intent) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            switch (status) {
                case PackageInstaller.STATUS_PENDING_USER_ACTION:
                    Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                    if (confirm != null) c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    break;
                case PackageInstaller.STATUS_SUCCESS:
                    break; // the new build replaces this process
                case PackageInstaller.STATUS_FAILURE_ABORTED:
                    prefs(c).edit().putLong("declined", intent.getLongExtra(EXTRA_VERSION, -1)).apply();
                    break;
                default:
                    String why = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                    Log.w("ultra", "update install failed: " + status + " " + why);
                    Toast.makeText(c, "Launcher update failed" + (why == null ? "" : ": " + why),
                            Toast.LENGTH_LONG).show();
            }
        }
    }
}
