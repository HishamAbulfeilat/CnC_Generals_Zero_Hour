package com.generalsx.generalszh;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.util.concurrent.CompletableFuture;

import in.dragonbra.javasteam.steam.authentication.IAuthenticator;

/**
 * Runs the Steam download as a foreground service so it continues with the screen off, with
 * the app in the background, or after the user swipes the app away. Progress shows in a
 * notification with a Pause action.
 *
 * Resuming: while a download is unfinished a "pending" flag stays set. If Android kills the
 * process the service is restarted (START_STICKY) and resumes with the saved Steam login;
 * opening the app with the flag set resumes too. The depot downloader keeps what is already
 * on disk and fetches only the missing chunks.
 *
 * The setup screen observes {@link #state()} through {@link #setObserver}; a first sign-in
 * with a password is handed over with {@link #startWithCredentials} and Steam Guard prompts
 * go to the screen's authenticator ({@link #setUiAuthenticator}).
 */
public class DownloadService extends Service {
    private static final String TAG = "GeneralsX";
    private static final String CHANNEL_ID = "downloads";
    private static final int NOTIFICATION_ID = 1;
    private static final String ACTION_PAUSE = "com.generalsx.generalszh.PAUSE_DOWNLOAD";
    private static final String PREFS = "download";
    private static final String KEY_PENDING = "pending";
    private static final long NOTIFY_INTERVAL_MS = 1000;

    /** Snapshot of the download for the UI. */
    static final class State {
        final boolean running;
        final String status;
        final float progress;       // [0, 1], or negative when unknown
        final String error;         // last failure, null when none
        final File completedDir;    // set once the game data is complete

        State(boolean running, String status, float progress, String error, File completedDir) {
            this.running = running;
            this.status = status;
            this.progress = progress;
            this.error = error;
            this.completedDir = completedDir;
        }
    }

    interface Observer {
        void onDownloadState(State state);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile State state = new State(false, null, -1f, null, null);
    private static Observer observer;
    private static IAuthenticator uiAuthenticator;
    private static String pendingUser;
    private static String pendingPassword;

    private SteamGameDownloader downloader;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private long lastNotifyTime;

    // ---------------------------------------------------------------------------------
    // API for SetupActivity (main thread)

    static State state() {
        return state;
    }

    static void setObserver(Observer o) {
        observer = o;
        if (o != null) {
            o.onDownloadState(state);
        }
    }

    static void setUiAuthenticator(IAuthenticator authenticator) {
        uiAuthenticator = authenticator;
    }

    /** Unregisters only if still current, so a recreated screen keeps its own. */
    static void clearUiAuthenticator(IAuthenticator authenticator) {
        if (uiAuthenticator == authenticator) {
            uiAuthenticator = null;
        }
    }

    static boolean isPending(Context context) {
        return prefs(context).getBoolean(KEY_PENDING, false);
    }

    /** First sign-in: the password is handed over in memory only, never via the Intent. */
    static void startWithCredentials(Context context, String user, String password) {
        pendingUser = user;
        pendingPassword = password;
        context.startForegroundService(new Intent(context, DownloadService.class));
    }

    /** Start or resume with the saved Steam login. */
    static void startWithSavedLogin(Context context) {
        context.startForegroundService(new Intent(context, DownloadService.class));
    }

    static void pause(Context context) {
        context.startService(new Intent(context, DownloadService.class).setAction(ACTION_PAUSE));
    }

    // ---------------------------------------------------------------------------------
    // Service

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private void setPending(boolean pending) {
        prefs(this).edit().putBoolean(KEY_PENDING, pending).apply();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_PAUSE.equals(intent.getAction())) {
            if (downloader != null) {
                setPending(false); // paused by the user: don't auto-resume
                downloader.cancel();
            } else {
                stopSelf();
            }
            return START_NOT_STICKY;
        }

        startInForeground(notification("Preparing download…", -1f));
        if (downloader != null) {
            return START_STICKY; // already running
        }

        File dest = GameDataPaths.appDataDir(this);
        String user = pendingUser;
        String password = pendingPassword;
        pendingUser = null;
        pendingPassword = null;

        if (user != null && password != null) {
            downloader = SteamGameDownloader.withCredentials(user, password,
                    new ForwardingAuthenticator(), dest, new Listener(dest));
        } else {
            SteamLoginStore.Login login = SteamLoginStore.load(this);
            if (login == null) {
                // e.g. restarted by the system but the user signed out meanwhile
                setPending(false);
                publish(new State(false, null, -1f, "Sign in to Steam to download the game.", null));
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
                return START_NOT_STICKY;
            }
            downloader = SteamGameDownloader.withSavedLogin(login.accountName, login.refreshToken,
                    dest, new Listener(dest));
        }

        setPending(true);
        acquireLocks();
        publish(new State(true, "Connecting to Steam…", -1f, null, null));
        downloader.start();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        releaseLocks();
        super.onDestroy();
    }

    private void startInForeground(Notification n) {
        startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
    }

    private void acquireLocks() {
        // A 2 GB download outlasts the screen timeout; keep the CPU and Wi-Fi awake until done.
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GeneralsZH:download");
        wakeLock.acquire();
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wm != null) {
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "GeneralsZH:download");
            wifiLock.acquire();
        }
    }

    private void releaseLocks() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        if (wifiLock != null && wifiLock.isHeld()) {
            wifiLock.release();
        }
        wakeLock = null;
        wifiLock = null;
    }

    private void finish() {
        downloader = null;
        releaseLocks();
        stopSelf();
    }

    private static void publish(State s) {
        state = s;
        MAIN.post(() -> {
            Observer o = observer;
            if (o != null) {
                o.onDownloadState(state);
            }
        });
    }

    // ---------------------------------------------------------------------------------
    // Notification

    private Notification notification(String text, float progress) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Game download",
                    NotificationManager.IMPORTANCE_LOW));
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, SetupActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent pause = PendingIntent.getService(this, 1,
                new Intent(this, DownloadService.class).setAction(ACTION_PAUSE),
                PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Downloading Zero Hour")
                .setContentText(text)
                .setContentIntent(open)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Pause", pause).build());
        if (progress < 0) {
            b.setProgress(0, 0, true);
        } else {
            b.setProgress(1000, (int) (Math.min(progress, 1f) * 1000), false);
        }
        return b.build();
    }

    private void showResult(String title, String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, SetupActivity.class), PendingIntent.FLAG_IMMUTABLE);
        nm.notify(NOTIFICATION_ID + 1, new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build());
    }

    // ---------------------------------------------------------------------------------

    /** Steam Guard prompts go to whichever setup screen is showing when they are asked. */
    private static final class ForwardingAuthenticator implements IAuthenticator {
        private IAuthenticator ui() {
            return uiAuthenticator;
        }

        private static <T> CompletableFuture<T> noScreen() {
            CompletableFuture<T> f = new CompletableFuture<>();
            f.completeExceptionally(new IllegalStateException(
                    "Open the app to finish signing in to Steam"));
            return f;
        }

        @Override
        public CompletableFuture<String> getDeviceCode(boolean previousCodeWasIncorrect) {
            IAuthenticator ui = ui();
            return ui != null ? ui.getDeviceCode(previousCodeWasIncorrect) : noScreen();
        }

        @Override
        public CompletableFuture<String> getEmailCode(String email, boolean previousCodeWasIncorrect) {
            IAuthenticator ui = ui();
            return ui != null ? ui.getEmailCode(email, previousCodeWasIncorrect) : noScreen();
        }

        @Override
        public CompletableFuture<Boolean> acceptDeviceConfirmation() {
            IAuthenticator ui = ui();
            return ui != null ? ui.acceptDeviceConfirmation() : noScreen();
        }
    }

    private final class Listener implements SteamGameDownloader.Listener {
        private final File dest;
        private String lastStatus = "Connecting to Steam…";
        private float lastProgress = -1f;

        Listener(File dest) {
            this.dest = dest;
        }

        private void update() {
            publish(new State(true, lastStatus, lastProgress, null, null));
            long now = SystemClock.elapsedRealtime();
            if (now - lastNotifyTime >= NOTIFY_INTERVAL_MS) {
                lastNotifyTime = now;
                String text = lastProgress >= 0
                        ? Math.round(lastProgress * 100) + "% · " + lastStatus : lastStatus;
                getSystemService(NotificationManager.class).notify(NOTIFICATION_ID,
                        notification(text, lastProgress));
            }
        }

        @Override
        public void onStatus(String message) {
            lastStatus = message;
            update();
        }

        @Override
        public void onProgress(float fraction) {
            lastProgress = fraction;
            update();
        }

        @Override
        public void onSignedIn(String accountName, String refreshToken) {
            SteamLoginStore.save(DownloadService.this, accountName, refreshToken);
        }

        @Override
        public void onSavedLoginRejected() {
            SteamLoginStore.clear(DownloadService.this);
        }

        @Override
        public void onFinished(File installDir) {
            File done = installDir;
            try {
                GameDataPaths.markComplete(installDir);
                GameDataPaths.installBundledFonts(DownloadService.this, installDir);
            } catch (Exception e) {
                Log.w(TAG, "Finishing the game data setup failed", e);
                done = null;
            }
            setPending(false);
            MAIN.post(() -> {
                stopForeground(STOP_FOREGROUND_REMOVE);
                showResult("Zero Hour is ready", "The download finished. Tap to play.");
                finish();
            });
            publish(new State(false, "Download complete.", 1f,
                    done == null ? "Could not finish setting up the game files." : null, done));
        }

        @Override
        public void onFailed(String message, boolean resumable) {
            // Keep the pending flag for connection problems so the next app start resumes;
            // clear it for problems that need the user (sign-in, ownership) or a user pause.
            if (!resumable) {
                setPending(false);
            }
            MAIN.post(() -> {
                stopForeground(STOP_FOREGROUND_REMOVE);
                if (isPending(DownloadService.this)) {
                    showResult("Zero Hour download interrupted",
                            message + " It resumes when you open the app.");
                }
                finish();
            });
            publish(new State(false, null, lastProgress, message, null));
        }
    }
}
