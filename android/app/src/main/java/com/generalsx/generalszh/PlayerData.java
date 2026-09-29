package com.generalsx.generalszh;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * The player's own files, all in the engine's user data folder (GameOptions.userDataDir):
 * saved games (Save/), replays (Replays/), maps added by the player (Maps/), Options.ini and
 * the other preference files. They live in app-private storage, so uninstalling the app or
 * clearing its data deletes them; backup() packs them into one zip the player keeps anywhere,
 * restore() puts them back. Also the replay list for sharing single replays.
 */
final class PlayerData {
    /** SDL3GameEngine.cpp autosaveOnceWhileBackgrounded writes this slot. */
    static final String AUTOSAVE_FILE = "AndroidAutosave.sav";

    private PlayerData() {}

    static File replaysDir(Context context) {
        return new File(GameOptions.userDataDir(context), "Replays");
    }

    static File autosave(Context context) {
        return new File(new File(GameOptions.userDataDir(context), "Save"), AUTOSAVE_FILE);
    }

    /** Replays, newest first. */
    static List<File> replays(Context context) {
        File[] files = replaysDir(context).listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".rep"));
        List<File> list = new ArrayList<>();
        if (files != null) {
            list.addAll(Arrays.asList(files));
        }
        list.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return list;
    }

    /** Zips the whole user data folder; returns the number of files written. */
    static int backup(Context context, OutputStream out) throws IOException {
        File root = GameOptions.userDataDir(context);
        int[] count = { 0 };
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            addTree(zip, root, "", count);
        }
        return count[0];
    }

    private static void addTree(ZipOutputStream zip, File dir, String prefix, int[] count)
            throws IOException {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        byte[] buffer = new byte[64 * 1024];
        for (File c : children) {
            String name = prefix + c.getName();
            if (c.isDirectory()) {
                addTree(zip, c, name + "/", count);
                continue;
            }
            zip.putNextEntry(new ZipEntry(name));
            try (InputStream in = new FileInputStream(c)) {
                int n;
                while ((n = in.read(buffer)) > 0) {
                    zip.write(buffer, 0, n);
                }
            }
            zip.closeEntry();
            count[0]++;
        }
    }

    /**
     * Unpacks a backup into the user data folder, replacing files with the same name and
     * keeping the rest; returns the number of files restored. Entries that would land outside
     * the folder are rejected.
     */
    static int restore(Context context, InputStream in) throws IOException {
        File root = GameOptions.userDataDir(context).getCanonicalFile();
        int count = 0;
        byte[] buffer = new byte[64 * 1024];
        try (ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                File dst = new File(root, e.getName()).getCanonicalFile();
                if (!dst.getPath().startsWith(root.getPath() + File.separator)) {
                    throw new IOException("Not a GeneralsZH backup (bad entry " + e.getName() + ")");
                }
                if (e.isDirectory()) {
                    continue;
                }
                File parent = dst.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("Cannot create " + parent);
                }
                try (OutputStream out = new FileOutputStream(dst)) {
                    int n;
                    while ((n = zip.read(buffer)) > 0) {
                        out.write(buffer, 0, n);
                    }
                }
                count++;
            }
        }
        if (count == 0) {
            throw new IOException("The file holds no game data");
        }
        return count;
    }

    /** Copies one replay file into the Replays folder under {@code name}. */
    static void importReplay(Context context, String name, InputStream in) throws IOException {
        File dir = replaysDir(context);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Cannot create " + dir);
        }
        String safe = new File(name).getName();
        if (!safe.toLowerCase(Locale.ROOT).endsWith(".rep")) {
            safe = safe + ".rep";
        }
        try (OutputStream out = new FileOutputStream(new File(dir, safe))) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
        }
    }
}
