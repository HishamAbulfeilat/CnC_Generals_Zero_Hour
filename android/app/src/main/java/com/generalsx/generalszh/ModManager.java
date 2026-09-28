package com.generalsx.generalszh;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Installed mods and which of them the game loads.
 *
 * Each mod lives in {@code files/Mods/<id>/} (its files under {@code files/}, settings in
 * {@code mod.json}); the game's own data is never modified, so turning every mod off gives
 * back the stock game. The engine accepts one {@code -mod <folder>} and loads every *.big
 * under it (recursively, sorted by path, later files overriding earlier ones), so enabled
 * mods are linked into {@code files/ModsActive/NN_<id>} in load order and that folder is
 * passed to the game. Loose game files in a custom mod are packed into a BIG first, since
 * -mod only loads archives.
 */
final class ModManager {
    private static final String TAG = "GeneralsX";

    /** Game data file types (the Mod Builder's "relevantGameDataFileTypes"). */
    private static final Set<String> GAME_FILE_TYPES = new HashSet<>(Arrays.asList(
            "ani", "bik", "bmp", "csf", "dds", "ini", "map", "mp3", "pso", "scb", "str", "tga",
            "wak", "w3d", "wav", "wnd", "vso"));
    /** Top-level folders of game data inside an archive (Data\INI\..., Art\Textures\...). */
    private static final Set<String> GAME_ROOT_DIRS = new HashSet<>(Arrays.asList(
            "data", "art", "window", "maps", "audio", "shaders", "english"));
    private static final String LOOSE_FILES_BIG = "zz_loose_files.big";

    static final class Mod {
        final String id;
        final String name;
        final String description;
        final String source;     // "custom", "superpatch", "cnconline"
        final String version;
        final boolean enabled;
        final long order;        // load order: higher loads later and wins conflicts

        Mod(String id, String name, String description, String source, String version,
            boolean enabled, long order) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.source = source;
            this.version = version;
            this.enabled = enabled;
            this.order = order;
        }
    }

    private final Context context;

    ModManager(Context context) {
        this.context = context.getApplicationContext();
    }

    private File modsRoot() {
        return new File(context.getFilesDir(), "Mods");
    }

    /** The folder passed to the game with -mod. */
    File activeDir() {
        return new File(context.getFilesDir(), "ModsActive");
    }

    private File modDir(String id) {
        return new File(modsRoot(), id);
    }

    File filesDir(String id) {
        return new File(modDir(id), "files");
    }

    // ---------------------------------------------------------------------------------
    // Listing and settings

    List<Mod> list() {
        List<Mod> mods = new ArrayList<>();
        File[] dirs = modsRoot().listFiles(File::isDirectory);
        if (dirs != null) {
            for (File d : dirs) {
                Mod m = read(d.getName());
                if (m != null) {
                    mods.add(m);
                }
            }
        }
        Collections.sort(mods, (a, b) -> Long.compare(a.order, b.order));
        return mods;
    }

    Mod get(String id) {
        return read(id);
    }

    private Mod read(String id) {
        File json = new File(modDir(id), "mod.json");
        try {
            JSONObject o = new JSONObject(new String(Files.readAllBytes(json.toPath()),
                    StandardCharsets.UTF_8));
            return new Mod(id, o.optString("name", id), o.optString("description", ""),
                    o.optString("source", "custom"), o.optString("version", ""),
                    o.optBoolean("enabled", false), o.optLong("order", 0));
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    private void write(Mod m) throws IOException {
        try {
            JSONObject o = new JSONObject()
                    .put("name", m.name).put("description", m.description)
                    .put("source", m.source).put("version", m.version)
                    .put("enabled", m.enabled).put("order", m.order);
            Files.write(new File(modDir(m.id), "mod.json").toPath(),
                    o.toString(2).getBytes(StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    void setEnabled(String id, boolean enabled) throws IOException {
        Mod m = read(id);
        if (m == null) {
            return;
        }
        // Enabling moves a mod to the end of the load order, so it wins over earlier ones.
        long order = enabled && !m.enabled ? System.currentTimeMillis() : m.order;
        write(new Mod(m.id, m.name, m.description, m.source, m.version, enabled, order));
        rebuildActive();
    }

    void remove(String id) throws IOException {
        deleteTree(modDir(id));
        rebuildActive();
    }

    boolean hasEnabledMods() {
        for (Mod m : list()) {
            if (m.enabled) {
                return true;
            }
        }
        return false;
    }

    /** Arguments for the game: {@code -mod <activeDir>} when any mod is enabled. */
    String[] launchArguments() {
        rebuildActiveQuietly();
        String[] entries = activeDir().list();
        if (entries == null || entries.length == 0) {
            return new String[0];
        }
        return new String[] { "-mod", activeDir().getAbsolutePath() };
    }

    private void rebuildActiveQuietly() {
        try {
            rebuildActive();
        } catch (IOException e) {
            Log.w(TAG, "Could not prepare the active mods folder", e);
        }
    }

    /** Re-links enabled mods into the active folder in load order. */
    synchronized void rebuildActive() throws IOException {
        File active = activeDir();
        deleteTree(active);
        if (!active.mkdirs()) {
            throw new IOException("Cannot create " + active);
        }
        int n = 0;
        for (Mod m : list()) {
            if (!m.enabled) {
                continue;
            }
            File link = new File(active, String.format(Locale.ROOT, "%02d_%s", n++, m.id));
            try {
                Os.symlink(filesDir(m.id).getAbsolutePath(), link.getAbsolutePath());
            } catch (ErrnoException e) {
                throw new IOException("Cannot link mod " + m.name + ": " + e.getMessage(), e);
            }
        }
    }

    // ---------------------------------------------------------------------------------
    // Installing

    /** Creates (or replaces) a mod from a zip stream; returns the installed mod. */
    Mod installZip(String id, String name, String description, String source, String version,
                   InputStream zip) throws IOException {
        File staging = newStaging(id);
        File files = new File(staging, "files");
        String canonicalRoot = files.getCanonicalPath() + File.separator;
        byte[] buf = new byte[256 * 1024];
        try (ZipInputStream in = new ZipInputStream(zip)) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                File out = new File(files, e.getName());
                // Reject entries escaping the mod folder ("zip slip").
                if (!out.getCanonicalPath().startsWith(canonicalRoot)) {
                    throw new IOException("Unsafe path in archive: " + e.getName());
                }
                if (e.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("Cannot create " + parent);
                }
                try (OutputStream o = new FileOutputStream(out)) {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        o.write(buf, 0, n);
                    }
                }
            }
        }
        return commit(staging, id, name, description, source, version);
    }

    /** Creates (or replaces) a mod from files already copied into {@code staging/files}. */
    Mod commit(File staging, String id, String name, String description, String source,
               String version) throws IOException {
        int archives = prepare(new File(staging, "files"));
        if (archives == 0) {
            deleteTree(staging);
            throw new IOException("No game files found. A mod needs .big files or game data"
                    + " folders such as Data, Art or Window.");
        }
        Mod previous = read(id);
        File target = modDir(id);
        deleteTree(target);
        if (!staging.renameTo(target)) {
            throw new IOException("Cannot install mod " + name);
        }
        long order = previous != null ? previous.order : System.currentTimeMillis();
        boolean enabled = previous == null || previous.enabled;
        Mod mod = new Mod(id, name, description, source, version, enabled, order);
        write(mod);
        rebuildActive();
        return mod;
    }

    File newStaging(String id) throws IOException {
        File staging = new File(modsRoot(), "." + id + ".staging");
        deleteTree(staging);
        File files = new File(staging, "files");
        if (!files.mkdirs()) {
            throw new IOException("Cannot create " + files);
        }
        return staging;
    }

    /**
     * Makes a mod folder loadable: loose game files (relative to their game-data root, e.g.
     * the folder holding Data\ and Art\) are packed into one BIG and removed. Returns the
     * number of BIG archives the folder then holds.
     */
    private int prepare(File files) throws IOException {
        reorderForModFolder(files);
        List<File> loose = new ArrayList<>();
        collectLoose(files, loose);
        if (!loose.isEmpty()) {
            File root = gameDataRoot(files, loose.get(0));
            List<File> packable = new ArrayList<>();
            for (File f : loose) {
                if (f.getAbsolutePath().startsWith(root.getAbsolutePath() + File.separator)) {
                    packable.add(f);
                }
            }
            if (!packable.isEmpty()) {
                BigArchiveWriter.pack(root, packable, new File(files, LOOSE_FILES_BIG));
                for (File f : packable) {
                    Files.deleteIfExists(f.toPath());
                }
            }
        }
        return countBigs(files);
    }

    /**
     * Mods are made for the game folder, where BIG archives load in sorted path order and the
     * FIRST one to provide a file wins (e.g. the Super Patch names its optional art
     * 600_899_* so it beats its 600_900_* core files). The -mod folder loads in the same
     * sorted order but with overwrite, so the LAST one wins. Reverse the order: move every
     * archive to the mod root renamed "NNN_<name>" with descending numbers, so each mod keeps
     * the priority its authors intended. (Loose files, packed into zz_loose_files.big, stay
     * last and win, as loose files do in the game folder.)
     */
    private static void reorderForModFolder(File files) throws IOException {
        List<File> bigs = new ArrayList<>();
        collectBigs(files, bigs);
        List<String> paths = new ArrayList<>();
        for (File b : bigs) {
            paths.add(files.toURI().relativize(b.toURI()).getPath());
        }
        Collections.sort(paths);
        for (int i = 0; i < paths.size(); i++) {
            File src = new File(files, paths.get(i));
            File dst = new File(files, String.format(Locale.ROOT, "%03d_%s",
                    999 - i, src.getName()));
            if (!src.renameTo(dst)) {
                throw new IOException("Cannot arrange " + paths.get(i));
            }
        }
    }

    private static void collectBigs(File dir, List<File> out) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File c : children) {
            if (c.isDirectory()) {
                collectBigs(c, out);
            } else if (c.getName().toLowerCase(Locale.ROOT).endsWith(".big")) {
                out.add(c);
            }
        }
    }

    private static void collectLoose(File dir, List<File> out) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        Arrays.sort(children);
        for (File c : children) {
            if (c.isDirectory()) {
                collectLoose(c, out);
            } else {
                String name = c.getName().toLowerCase(Locale.ROOT);
                int dot = name.lastIndexOf('.');
                if (dot > 0 && GAME_FILE_TYPES.contains(name.substring(dot + 1))) {
                    out.add(c);
                }
            }
        }
    }

    /** The folder whose child is Data/Art/Window/... on the path to {@code sample}. */
    private static File gameDataRoot(File files, File sample) {
        File p = sample.getParentFile();
        while (p != null && !p.equals(files)) {
            if (GAME_ROOT_DIRS.contains(p.getName().toLowerCase(Locale.ROOT))) {
                return p.getParentFile();
            }
            p = p.getParentFile();
        }
        return files;
    }

    private static int countBigs(File dir) {
        int n = 0;
        File[] children = dir.listFiles();
        if (children == null) {
            return 0;
        }
        for (File c : children) {
            if (c.isDirectory()) {
                n += countBigs(c);
            } else if (c.getName().toLowerCase(Locale.ROOT).endsWith(".big")) {
                n++;
            }
        }
        return n;
    }

    /** Deletes a tree without following symbolic links (the active folder holds links). */
    static void deleteTree(File f) throws IOException {
        if (!f.exists() && !Files.isSymbolicLink(f.toPath())) {
            return;
        }
        if (f.isDirectory() && !Files.isSymbolicLink(f.toPath())) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) {
                    deleteTree(c);
                }
            }
        }
        Files.deleteIfExists(f.toPath());
    }

    /** A folder-safe id from a display name. */
    static String idFor(String name) {
        String id = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return id.isEmpty() ? "mod-" + System.currentTimeMillis() : id;
    }
}
