package com.generalsx.generalszh;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;
import android.os.Process;
import android.util.Log;

import java.io.File;
import java.io.IOException;

/**
 * Debug mode's Java side (LaunchOptions): for the whole game session, from launch until the
 * game closes, this process's logcat (engine, DXVK, SDL, Android and GPU driver messages)
 * is written to files/logs/logcat-session.txt (the previous session is kept as
 * logcat-session-prev.txt), and every 10 s a "GeneralsXPerf" line records thermal state,
 * battery temperature/level and free memory, so throttling shows up next to the engine's
 * own [perf] frame-time samples. "Save logs" includes the session file.
 */
final class DebugSession {
    private static final String TAG = "GeneralsXPerf";
    private static final long SAMPLE_MS = 10_000;

    private final Context context;
    private java.lang.Process logcat;
    private Thread sampler;

    private DebugSession(Context context) {
        this.context = context.getApplicationContext();
    }

    static File sessionLog(Context context) {
        return new File(CrashLogs.logDir(context), "logcat-session.txt");
    }

    /** Starts recording if debug mode is on; returns null otherwise. */
    static DebugSession startIfEnabled(Context context) {
        if (!LaunchOptions.debug(context)) {
            return null;
        }
        DebugSession session = new DebugSession(context);
        session.start();
        return session;
    }

    private void start() {
        File dir = CrashLogs.logDir(context);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            Log.w(TAG, "cannot create " + dir);
            return;
        }
        File current = sessionLog(context);
        File previous = new File(dir, "logcat-session-prev.txt");
        if (current.isFile() && !current.renameTo(previous)) {
            Log.w(TAG, "cannot keep the previous session log");
        }
        try {
            logcat = new ProcessBuilder("logcat", "-v", "threadtime",
                    "--pid=" + Process.myPid(), "-f", current.getAbsolutePath()).start();
        } catch (IOException e) {
            Log.w(TAG, "cannot start logcat capture", e);
        }
        Log.i(TAG, "debug session: " + Build.MANUFACTURER + " " + Build.MODEL + ", Android "
                + Build.VERSION.RELEASE + ", app " + appVersion());
        sampler = new Thread(this::sampleLoop, "GeneralsXPerf");
        sampler.setDaemon(true);
        sampler.start();
    }

    private String appVersion() {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return "unknown";
        }
    }

    void stop() {
        if (sampler != null) {
            sampler.interrupt();
        }
        if (logcat != null) {
            logcat.destroy();
        }
    }

    private void sampleLoop() {
        PowerManager power = context.getSystemService(PowerManager.class);
        ActivityManager am = context.getSystemService(ActivityManager.class);
        ActivityManager.MemoryInfo mem = new ActivityManager.MemoryInfo();
        while (!Thread.currentThread().isInterrupted()) {
            Intent battery = context.registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            int tempTenths = battery != null
                    ? battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) : -1;
            int level = battery != null ? battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) : -1;
            am.getMemoryInfo(mem);
            Log.i(TAG, "thermal=" + thermalName(power.getCurrentThermalStatus())
                    + " battery=" + level + "% " + (tempTenths / 10.0f) + "C"
                    + " availMem=" + (mem.availMem >> 20) + "MB lowMemory=" + mem.lowMemory);
            try {
                Thread.sleep(SAMPLE_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private static String thermalName(int status) {
        switch (status) {
            case PowerManager.THERMAL_STATUS_NONE: return "none";
            case PowerManager.THERMAL_STATUS_LIGHT: return "light";
            case PowerManager.THERMAL_STATUS_MODERATE: return "moderate";
            case PowerManager.THERMAL_STATUS_SEVERE: return "severe";
            case PowerManager.THERMAL_STATUS_CRITICAL: return "critical";
            case PowerManager.THERMAL_STATUS_EMERGENCY: return "emergency";
            case PowerManager.THERMAL_STATUS_SHUTDOWN: return "shutdown";
            default: return "unknown(" + status + ")";
        }
    }
}
