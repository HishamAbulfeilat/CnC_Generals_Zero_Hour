package com.generalsx.generalszh;

import android.content.Context;
import android.content.SharedPreferences;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

/**
 * Launcher switches that are not game settings (those live in Options.ini, GameOptions):
 * debug mode, the FPS overlay and the touch control scheme. GeneralsXZHActivity exports them
 * as environment variables before the engine starts:
 *  - debug mode: GENERALSX_DEBUG (gesture decisions, 5 s frame-time/memory samples,
 *    SDL3GameEngine.cpp), GENERALSX_VERBOSE (unfiltered engine output), DXVK_LOG_LEVEL=info
 *    and a larger game.log (SDL3Main.cpp); DebugSession adds the session's logcat and
 *    thermal/battery samples.
 *  - FPS overlay: DXVK_HUD, DXVK's on-screen frame rate and frame-time graph.
 *  - touch scheme: GX_TOUCH_SCHEME=mobile (one finger drags the camera).
 *  - frame-rate cap: GX_RENDER_FPS (render only; the simulation's rate is fixed).
 * Overheat protection and vibration are applied by GeneralsXZHActivity itself.
 */
final class LaunchOptions {
    private static final String PREFS = "launch_options";
    private static final String KEY_DEBUG = "debug";
    private static final String KEY_FPS_OVERLAY = "fps_overlay";
    private static final String KEY_MOBILE_TOUCH = "mobile_touch";
    private static final String KEY_RENDER_FPS = "render_fps";
    private static final String KEY_THERMAL_GUARD = "thermal_guard";
    private static final String KEY_HAPTICS = "haptics";

    private LaunchOptions() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean debug(Context context) {
        return prefs(context).getBoolean(KEY_DEBUG, false);
    }

    static void setDebug(Context context, boolean on) {
        prefs(context).edit().putBoolean(KEY_DEBUG, on).apply();
    }

    static boolean fpsOverlay(Context context) {
        return prefs(context).getBoolean(KEY_FPS_OVERLAY, false);
    }

    static void setFpsOverlay(Context context, boolean on) {
        prefs(context).edit().putBoolean(KEY_FPS_OVERLAY, on).apply();
    }

    static boolean mobileTouch(Context context) {
        return prefs(context).getBoolean(KEY_MOBILE_TOUCH, false);
    }

    static void setMobileTouch(Context context, boolean on) {
        prefs(context).edit().putBoolean(KEY_MOBILE_TOUCH, on).apply();
    }

    /** Render frame-rate cap; 0 = the game's own setting. */
    static int renderFps(Context context) {
        return prefs(context).getInt(KEY_RENDER_FPS, 0);
    }

    static void setRenderFps(Context context, int fps) {
        prefs(context).edit().putInt(KEY_RENDER_FPS, fps).apply();
    }

    /** Lower the frame rate while the phone is overheating (on by default). */
    static boolean thermalGuard(Context context) {
        return prefs(context).getBoolean(KEY_THERMAL_GUARD, true);
    }

    static void setThermalGuard(Context context, boolean on) {
        prefs(context).edit().putBoolean(KEY_THERMAL_GUARD, on).apply();
    }

    /** Vibrate on on-screen keys and gesture commands (on by default). */
    static boolean haptics(Context context) {
        return prefs(context).getBoolean(KEY_HAPTICS, true);
    }

    static void setHaptics(Context context, boolean on) {
        prefs(context).edit().putBoolean(KEY_HAPTICS, on).apply();
    }

    /** Sets the environment for the engine; call before the native library starts. */
    static void exportToEnvironment(Context context) {
        if (debug(context)) {
            setenv("GENERALSX_DEBUG", "1");
            setenv("GENERALSX_VERBOSE", "1");
            setenv("DXVK_LOG_LEVEL", "info");
        }
        if (fpsOverlay(context)) {
            setenv("DXVK_HUD", "fps,frametimes");
        }
        setenv("GX_TOUCH_SCHEME", mobileTouch(context) ? "mobile" : "classic");
        setenv("GX_RENDER_FPS", String.valueOf(renderFps(context)));
    }

    private static void setenv(String name, String value) {
        try {
            Os.setenv(name, value, true);
        } catch (ErrnoException e) {
            Log.w("GeneralsX", "setenv " + name + " failed", e);
        }
    }
}
