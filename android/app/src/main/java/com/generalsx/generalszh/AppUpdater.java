package com.generalsx.generalszh;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Check for updates": compares this build with the newest GitHub release of the project
 * (release-android.yml publishes "android-r<N>", N = versionCode) and installs a newer APK
 * in place through PackageInstaller. Android asks the user to confirm, and the first time to
 * allow installing apps from this app.
 *
 * An in-place update only works when the new APK is signed with the same key as the
 * installed one (see "Release signing" in docs/BUILD/ANDROID.md); otherwise Android reports
 * a conflict and the user has to uninstall first.
 *
 * Blocking network calls; use from a background thread.
 */
final class AppUpdater {
    private static final String TAG = "GeneralsX";
    static final String REPO = "HishamAbulfeilat/CnC_Generals_Zero_Hour";
    private static final String LATEST_RELEASE_API =
            "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final Pattern RELEASE_NUMBER = Pattern.compile("android-r(\\d+)");

    static final class Release {
        final String tag;
        final long versionCode;
        final String apkUrl;
        final long apkSize;

        Release(String tag, long versionCode, String apkUrl, long apkSize) {
            this.tag = tag;
            this.versionCode = versionCode;
            this.apkUrl = apkUrl;
            this.apkSize = apkSize;
        }
    }

    interface Progress {
        void onProgress(float fraction);
    }

    private AppUpdater() {}

    static long installedVersionCode(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0)
                    .getLongVersionCode();
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    /** The newest published release with an APK, or null when it is not newer than this build. */
    static Release findNewerRelease(Context context) throws IOException {
        HttpURLConnection conn = open(LATEST_RELEASE_API);
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        String body;
        try (InputStream in = conn.getInputStream()) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[16 * 1024];
            int n;
            while ((n = in.read(b)) > 0) {
                buf.write(b, 0, n);
            }
            body = buf.toString(StandardCharsets.UTF_8.name());
        } finally {
            conn.disconnect();
        }
        try {
            JSONObject release = new JSONObject(body);
            String tag = release.getString("tag_name");
            Matcher m = RELEASE_NUMBER.matcher(tag);
            if (!m.find()) {
                return null; // e.g. a hand-made android-v* tag: no comparable build number
            }
            long code = Long.parseLong(m.group(1));
            if (code <= installedVersionCode(context)) {
                return null;
            }
            JSONArray assets = release.getJSONArray("assets");
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.getJSONObject(i);
                if (asset.getString("name").endsWith(".apk")) {
                    return new Release(tag, code, asset.getString("browser_download_url"),
                            asset.getLong("size"));
                }
            }
            return null;
        } catch (org.json.JSONException e) {
            throw new IOException("Unexpected answer from GitHub: " + e.getMessage(), e);
        }
    }

    /** Streams the APK into a PackageInstaller session and commits it (Android confirms). */
    static void install(Context context, Release release, Progress progress) throws IOException {
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName());
        params.setSize(release.apkSize);
        int sessionId = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(sessionId)) {
            HttpURLConnection conn = open(release.apkUrl);
            try (InputStream in = conn.getInputStream();
                 OutputStream out = session.openWrite("base.apk", 0, release.apkSize)) {
                byte[] buf = new byte[256 * 1024];
                long done = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    done += n;
                    if (release.apkSize > 0) {
                        progress.onProgress((float) done / release.apkSize);
                    }
                }
                session.fsync(out);
            } finally {
                conn.disconnect();
            }
            Intent status = new Intent(context, InstallStatusReceiver.class);
            // Mutable: the installer adds the status extras to this intent.
            PendingIntent pending = PendingIntent.getBroadcast(context, sessionId, status,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            session.commit(pending.getIntentSender());
        } catch (IOException | RuntimeException e) {
            installer.abandonSession(sessionId);
            throw e;
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        conn.setInstanceFollowRedirects(true); // release assets redirect to GitHub's CDN
        conn.setRequestProperty("User-Agent", "GeneralsZH-Android-Updater");
        int code = conn.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            conn.disconnect();
            throw new IOException("HTTP " + code + " from " + new URL(url).getHost());
        }
        return conn;
    }

    @SuppressWarnings("deprecation") // the typed overload only exists from API 33
    private static Intent confirmationIntent(Intent intent) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class)
                : intent.getParcelableExtra(Intent.EXTRA_INTENT);
    }

    /** Receives the install result; shows the system confirmation when Android asks for it. */
    public static final class InstallStatusReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                    PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = confirmationIntent(intent);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(confirm);
                }
                return;
            }
            if (status == PackageInstaller.STATUS_SUCCESS) {
                return; // the app restarts as the new version
            }
            String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            Log.w(TAG, "Update install failed: " + status + " " + message);
            String text = status == PackageInstaller.STATUS_FAILURE_CONFLICT
                    || status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE
                    ? "The update is signed with a different key than this build. "
                        + "Uninstall the app, then install the new version."
                    : status == PackageInstaller.STATUS_FAILURE_ABORTED
                    ? "Update cancelled."
                    : "Update failed" + (message != null ? ": " + message : ".");
            Toast.makeText(context, text, Toast.LENGTH_LONG).show();
        }
    }
}
