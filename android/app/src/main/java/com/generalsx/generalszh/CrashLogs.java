package com.generalsx.generalszh;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Collects what is needed to diagnose a game crash into one text report the player can
 * save and send: device and app details, why Android ended recent runs
 * (ApplicationExitInfo, e.g. a native crash or a low-memory kill), the engine's
 * ReleaseCrashInfo.txt, Options.ini, the native log of the last two runs (game.log and
 * game-prev.log, written by SDL3Main.cpp together with its crash backtrace) and this
 * app's logcat.
 */
final class CrashLogs {
    private static final String PREFS = "crash_logs";
    private static final String KEY_SEEN_EXIT = "seen_exit_timestamp";
    private static final int LOGCAT_LINES = 4000;

    private CrashLogs() {}

    static File logDir(Context context) {
        return new File(context.getFilesDir(), "logs");
    }

    /** The engine's user data folder (Options.ini, ReleaseCrashInfo.txt). */
    private static File userDataDir(Context context) {
        return new File(context.getFilesDir(), "GeneralsX/GeneralsZH");
    }

    /**
     * Describes the last run if it ended abnormally and the player has not been told about
     * it yet, else null. Marks it as seen.
     */
    static String takeUnseenCrash(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return null;
        }
        ApplicationExitInfo last = lastExit(context);
        if (last == null || !isAbnormal(last)) {
            return null;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (prefs.getLong(KEY_SEEN_EXIT, 0) >= last.getTimestamp()) {
            return null;
        }
        prefs.edit().putLong(KEY_SEEN_EXIT, last.getTimestamp()).apply();
        return explain(last);
    }

    static String buildReport(Context context) {
        StringBuilder r = new StringBuilder();
        r.append("GeneralsZH Android log report, ")
                .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).format(new Date()))
                .append('\n');
        appendDevice(context, r);
        appendExits(context, r);
        appendFile(r, "ReleaseCrashInfo.txt", new File(userDataDir(context), "ReleaseCrashInfo.txt"), 256 * 1024);
        appendFile(r, "ReleaseCrashInfoPrev.txt", new File(userDataDir(context), "ReleaseCrashInfoPrev.txt"), 64 * 1024);
        appendFile(r, "Options.ini", new File(userDataDir(context), "Options.ini"), 64 * 1024);
        appendFile(r, "game.log (last run)", new File(logDir(context), "game.log"), 1536 * 1024);
        appendFile(r, "game-prev.log (run before)", new File(logDir(context), "game-prev.log"), 512 * 1024);
        appendLogcat(r);
        return r.toString();
    }

    // ---------------------------------------------------------------------------------

    private static void appendDevice(Context context, StringBuilder r) {
        section(r, "Device");
        try {
            PackageInfo p = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            r.append("App: ").append(p.versionName).append(" (").append(p.getLongVersionCode()).append(")\n");
        } catch (PackageManager.NameNotFoundException e) {
            r.append("App: version unknown (").append(e.getMessage()).append(")\n");
        }
        r.append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" (").append(Build.DEVICE).append(")\n");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            r.append("SoC: ").append(Build.SOC_MANUFACTURER).append(' ').append(Build.SOC_MODEL).append('\n');
        }
        r.append("Hardware: ").append(Build.HARDWARE).append(", board ").append(Build.BOARD).append('\n');
        r.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT)
                .append("), build ").append(Build.DISPLAY).append('\n');
        ActivityManager am = context.getSystemService(ActivityManager.class);
        ActivityManager.MemoryInfo mem = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mem);
        r.append("RAM: ").append(mem.totalMem >> 20).append(" MB total, ")
                .append(mem.availMem >> 20).append(" MB available, low-memory threshold ")
                .append(mem.threshold >> 20).append(" MB\n");
        r.append("App memory class: ").append(am.getMemoryClass()).append(" MB (large ")
                .append(am.getLargeMemoryClass()).append(" MB)\n");
        File data = GameDataPaths.findGameDataDir(context);
        r.append("Game data: ").append(data != null ? data.getAbsolutePath() : "not set up").append('\n');
        StringBuilder mods = new StringBuilder();
        for (ModManager.Mod m : new ModManager(context).list()) {
            if (m.enabled) {
                mods.append(mods.length() > 0 ? ", " : "").append(m.name);
            }
        }
        r.append("Enabled mods: ").append(mods.length() > 0 ? mods : "none").append('\n');
    }

    private static void appendExits(Context context, StringBuilder r) {
        section(r, "How recent runs ended");
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            r.append("Not available before Android 11.\n");
            return;
        }
        ActivityManager am = context.getSystemService(ActivityManager.class);
        List<ApplicationExitInfo> exits = am.getHistoricalProcessExitReasons(null, 0, 8);
        if (exits.isEmpty()) {
            r.append("None recorded.\n");
        }
        SimpleDateFormat time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
        for (ApplicationExitInfo e : exits) {
            r.append(time.format(new Date(e.getTimestamp()))).append("  ").append(reasonName(e.getReason()))
                    .append(", status ").append(e.getStatus())
                    .append(", importance ").append(e.getImportance())
                    .append(", pss ").append(e.getPss() >> 10).append(" MB")
                    .append(", rss ").append(e.getRss() >> 10).append(" MB");
            if (e.getDescription() != null) {
                r.append(", ").append(e.getDescription());
            }
            r.append('\n');
            if (e.getReason() == ApplicationExitInfo.REASON_ANR) {
                appendAnrTrace(e, r);
            }
        }
    }

    private static void appendAnrTrace(ApplicationExitInfo e, StringBuilder r) {
        try (InputStream in = e.getTraceInputStream()) {
            if (in != null) {
                r.append("  ANR trace:\n").append(readLimited(in, 128 * 1024)).append('\n');
            }
        } catch (IOException ex) {
            r.append("  ANR trace unreadable: ").append(ex.getMessage()).append('\n');
        }
    }

    private static ApplicationExitInfo lastExit(Context context) {
        ActivityManager am = context.getSystemService(ActivityManager.class);
        List<ApplicationExitInfo> exits = am.getHistoricalProcessExitReasons(null, 0, 1);
        return exits.isEmpty() ? null : exits.get(0);
    }

    private static boolean isAbnormal(ApplicationExitInfo e) {
        switch (e.getReason()) {
            case ApplicationExitInfo.REASON_CRASH:
            case ApplicationExitInfo.REASON_CRASH_NATIVE:
            case ApplicationExitInfo.REASON_ANR:
            case ApplicationExitInfo.REASON_LOW_MEMORY:
            case ApplicationExitInfo.REASON_SIGNALED:
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE:
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE:
                return true;
            case ApplicationExitInfo.REASON_EXIT_SELF:
                // The engine's fatal-error path (ReleaseCrash) ends with _exit(1).
                return e.getStatus() != 0;
            default:
                return false;
        }
    }

    private static String explain(ApplicationExitInfo e) {
        switch (e.getReason()) {
            case ApplicationExitInfo.REASON_LOW_MEMORY:
                return "Android closed the game because the device ran out of memory. Closing other"
                        + " apps before playing can help.";
            case ApplicationExitInfo.REASON_ANR:
                return "The game stopped responding and Android closed it.";
            case ApplicationExitInfo.REASON_EXIT_SELF:
                return "The game stopped after a fatal error.";
            case ApplicationExitInfo.REASON_SIGNALED:
                return "Android stopped the game (signal " + e.getStatus() + ").";
            default:
                return "The game crashed (" + reasonName(e.getReason()) + ").";
        }
    }

    private static String reasonName(int reason) {
        switch (reason) {
            case ApplicationExitInfo.REASON_EXIT_SELF: return "exited";
            case ApplicationExitInfo.REASON_SIGNALED: return "killed by signal";
            case ApplicationExitInfo.REASON_LOW_MEMORY: return "low-memory kill";
            case ApplicationExitInfo.REASON_CRASH: return "Java crash";
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "native crash";
            case ApplicationExitInfo.REASON_ANR: return "not responding (ANR)";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: return "initialization failure";
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE: return "permission change";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return "excessive resource use";
            case ApplicationExitInfo.REASON_USER_REQUESTED: return "stopped by user";
            case ApplicationExitInfo.REASON_USER_STOPPED: return "force-stopped by user";
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED: return "dependency died";
            case ApplicationExitInfo.REASON_OTHER: return "other";
            default: return "reason " + reason;
        }
    }

    // ---------------------------------------------------------------------------------

    private static void section(StringBuilder r, String title) {
        r.append("\n===== ").append(title).append(" =====\n");
    }

    /**
     * Appends a file; one larger than {@code limit} keeps its first eighth and its end,
     * where a crash is.
     */
    private static void appendFile(StringBuilder r, String title, File f, int limit) {
        section(r, title);
        if (!f.isFile()) {
            r.append("(not present)\n");
            return;
        }
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            long size = raf.length();
            if (size <= limit) {
                r.append(readRange(raf, 0, (int) size));
            } else {
                int head = limit / 8;
                int tail = limit - head;
                r.append(readRange(raf, 0, head))
                        .append("\n[... ").append(size - head - tail).append(" bytes skipped ...]\n")
                        .append(readRange(raf, size - tail, tail));
            }
            r.append('\n');
        } catch (IOException e) {
            r.append("(unreadable: ").append(e.getMessage()).append(")\n");
        }
    }

    private static String readRange(RandomAccessFile raf, long offset, int length) throws IOException {
        byte[] buf = new byte[length];
        raf.seek(offset);
        raf.readFully(buf);
        return new String(buf, StandardCharsets.UTF_8);
    }

    /** This app's logcat (Android lets an app read its own entries, including past runs). */
    private static void appendLogcat(StringBuilder r) {
        section(r, "logcat (last " + LOGCAT_LINES + " lines)");
        Process p = null;
        try {
            p = new ProcessBuilder("logcat", "-d", "-v", "threadtime", "-b", "main,system,crash",
                    "-t", String.valueOf(LOGCAT_LINES)).redirectErrorStream(true).start();
            r.append(readLimited(p.getInputStream(), 2 * 1024 * 1024));
            p.waitFor(5, TimeUnit.SECONDS);
        } catch (IOException e) {
            r.append("(logcat unavailable: ").append(e.getMessage()).append(")\n");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            r.append("(interrupted)\n");
        } finally {
            if (p != null) {
                p.destroy();
            }
        }
    }

    private static String readLimited(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] b = new byte[16 * 1024];
        int n;
        while (buf.size() < limit && (n = in.read(b)) > 0) {
            buf.write(b, 0, Math.min(n, limit - buf.size()));
        }
        return buf.toString(StandardCharsets.UTF_8.name());
    }
}
