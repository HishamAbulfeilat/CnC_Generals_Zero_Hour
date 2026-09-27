package com.generalsx.generalszh;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.StatFs;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Where the retail Zero Hour data lives on the device, and how to tell it is usable.
 *
 * Two locations are supported, checked in this order:
 *  1. {@code /sdcard/GeneralsZH} - the legacy location filled by
 *     scripts/build/android/push-assets-android.sh (needs the storage permission).
 *  2. {@code <external app files>/GeneralsZH} - filled by the in-app Steam download or
 *     folder import. App-specific storage needs no permission and is readable by the
 *     native engine directly.
 *
 * The engine resolves all game data relative to its working directory, so the chosen
 * directory is handed to SDL3Main.cpp through the GX_GAME_DATA_DIR environment variable.
 */
final class GameDataPaths {
    static final String ENV_GAME_DATA_DIR = "GX_GAME_DATA_DIR";
    static final String LEGACY_DIR = "/sdcard/GeneralsZH";

    /** A file only Zero Hour ships; its presence means the install is the expansion. */
    static final String MARKER_FILE = "INIZH.big";

    /**
     * Written after a download/import completes. The marker file alone can appear
     * before the rest of the data when a download or copy is interrupted.
     */
    static final String COMPLETE_FLAG = ".gx_setup_complete";

    /** Zero Hour on Steam, all editions. */
    static final int STEAM_APP_ID = 2732960;

    /** Rough install size plus headroom; the Steam depot is about 2 GB. */
    static final long REQUIRED_FREE_BYTES = 3L * 1024 * 1024 * 1024;

    private GameDataPaths() {}

    /** Directory the in-app download/import writes to. */
    static File appDataDir(Context context) {
        File base = context.getExternalFilesDir(null);
        if (base == null) {
            base = context.getFilesDir();
        }
        return new File(base, "GeneralsZH");
    }

    /**
     * The engine's user map directory: SDL3Main.cpp sets XDG_DATA_HOME to the internal files
     * dir, GlobalData builds the user data path as $XDG_DATA_HOME/GeneralsX/GeneralsZH/ and
     * MapCache appends "Maps". Maps received from other players in a lobby land here too.
     */
    static File userMapsDir(Context context) {
        return new File(context.getFilesDir(), "GeneralsX/GeneralsZH/Maps");
    }

    /**
     * Deletes caches the game rebuilds on its own: DXVK pipeline caches (*.dxvk-cache, written
     * next to the game data and in the app cache dir), Mesa Turnip's shader cache
     * ($HOME/.cache, HOME being the internal files dir) and the user map list cache
     * (Maps/MapCache.ini, rescanned on the next start). Fixes stale shaders after a driver
     * update or maps not showing up. Returns the number of files deleted.
     */
    static int clearCaches(Context context, File dataDir) {
        int deleted = 0;
        File[] roots = { dataDir, context.getCacheDir() };
        for (File root : roots) {
            File[] files = root != null ? root.listFiles() : null;
            if (files == null) {
                continue;
            }
            for (File f : files) {
                if (f.isFile() && f.getName().endsWith(".dxvk-cache") && f.delete()) {
                    deleted++;
                }
            }
        }
        deleted += deleteTree(new File(context.getFilesDir(), ".cache"));
        File mapCache = new File(userMapsDir(context), "MapCache.ini");
        if (mapCache.isFile() && mapCache.delete()) {
            deleted++;
        }
        return deleted;
    }

    private static int deleteTree(File f) {
        int deleted = 0;
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleted += deleteTree(c);
            }
        }
        if (f.exists() && f.delete() && children == null) {
            deleted++;
        }
        return deleted;
    }

    static boolean isGameDataDir(File dir) {
        return dir != null && new File(dir, MARKER_FILE).canRead();
    }

    /** Returns the first usable data directory, or null when the game has not been set up. */
    static File findGameDataDir(Context context) {
        File legacy = new File(LEGACY_DIR);
        if (isGameDataDir(legacy)) {
            return legacy;
        }
        File app = appDataDir(context);
        if (isGameDataDir(app) && new File(app, COMPLETE_FLAG).exists()) {
            return app;
        }
        return null;
    }

    static void markComplete(File dataDir) throws IOException {
        File flag = new File(dataDir, COMPLETE_FLAG);
        if (!flag.exists() && !flag.createNewFile()) {
            throw new IOException("Cannot write " + flag);
        }
    }

    static long freeBytes(File dir) {
        File probe = dir;
        while (probe != null && !probe.exists()) {
            probe = probe.getParentFile();
        }
        if (probe == null) {
            return Long.MAX_VALUE;
        }
        return new StatFs(probe.getAbsolutePath()).getAvailableBytes();
    }

    /**
     * The engine looks up TrueType fonts under {@code fonts/} in the data directory
     * (render2dsentence.cpp). The APK bundles metric-compatible Liberation fonts
     * (staged by scripts/build/ios/stage-fonts.sh); copy any that are missing.
     */
    static void installBundledFonts(Context context, File dataDir) throws IOException {
        AssetManager assets = context.getAssets();
        String[] fonts = assets.list("fonts");
        if (fonts == null || fonts.length == 0) {
            return;
        }
        File fontsDir = new File(dataDir, "fonts");
        if (!fontsDir.isDirectory() && !fontsDir.mkdirs()) {
            throw new IOException("Cannot create " + fontsDir);
        }
        byte[] buffer = new byte[64 * 1024];
        for (String name : fonts) {
            File dst = new File(fontsDir, name);
            if (dst.exists()) {
                continue;
            }
            try (InputStream in = assets.open("fonts/" + name);
                 OutputStream out = new FileOutputStream(dst)) {
                int n;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                }
            }
        }
    }
}
