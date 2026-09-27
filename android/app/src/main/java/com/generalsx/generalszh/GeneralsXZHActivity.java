package com.generalsx.generalszh;

import android.os.Bundle;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import java.io.File;

import org.libsdl.app.SDLActivity;

/**
 * GeneralsX @feature FadiLabib 06/07/2026 Thin SDLActivity shell.
 * getArguments() forwards an intent string extra "args" as engine argv,
 * enabling headless runs: adb shell am start -n <pkg>/.GeneralsXZHActivity
 *   --es args "-headless -replay 00000000.rep"
 *
 * The game data directory comes from SetupActivity (EXTRA_GAME_DATA_DIR) or, when the
 * activity is started directly over adb, from GameDataPaths.findGameDataDir(). It is
 * exported to SDL3Main.cpp as GX_GAME_DATA_DIR before the native library starts.
 */
public class GeneralsXZHActivity extends SDLActivity {
    static final String EXTRA_GAME_DATA_DIR = "gamedata";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        String dataDir = getIntent() != null ? getIntent().getStringExtra(EXTRA_GAME_DATA_DIR) : null;
        if (dataDir == null) {
            File found = GameDataPaths.findGameDataDir(this);
            if (found != null) {
                dataDir = found.getAbsolutePath();
            }
        }
        if (dataDir != null) {
            try {
                Os.setenv(GameDataPaths.ENV_GAME_DATA_DIR, dataDir, true);
            } catch (ErrnoException e) {
                Log.w("GeneralsX", "setenv " + GameDataPaths.ENV_GAME_DATA_DIR + " failed", e);
            }
        }
        super.onCreate(savedInstanceState);
    }

    @Override
    protected String[] getLibraries() {
        return new String[] { "SDL3", "main" };
    }

    @Override
    protected String[] getArguments() {
        String args = getIntent() != null ? getIntent().getStringExtra("args") : null;
        if (args == null || args.trim().isEmpty()) {
            return new String[0];
        }
        return args.trim().split("\\s+");
    }
}
