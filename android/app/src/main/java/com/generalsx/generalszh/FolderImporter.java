package com.generalsx.generalszh;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Copies a Zero Hour install the user picked with the system folder picker
 * (ACTION_OPEN_DOCUMENT_TREE) into the app's data directory. Works for a folder copied
 * from a PC over USB, an SD card, or a USB stick, without any storage permission -
 * file managers can no longer write into Android/data/ on Android 11+, so this is the
 * no-PC-tools way to get the files in place.
 *
 * Blocking; call from a background thread.
 */
final class FolderImporter {

    interface Listener {
        void onStatus(String message);

        void onProgress(float fraction);
    }

    /** Windows-only parts of an install the engine never reads. */
    private static final String[] SKIPPED_EXTENSIONS = { ".exe", ".dll", ".ico", ".lnk" };
    private static final String[] SKIPPED_DIRS = { "_CommonRedist", "RedistInstallers", "MSS", "Manuals", "steamapps" };

    private static final class Entry {
        final String documentId;
        final String name;
        final boolean directory;
        final long size;

        Entry(String documentId, String name, boolean directory, long size) {
            this.documentId = documentId;
            this.name = name;
            this.directory = directory;
            this.size = size;
        }
    }

    private final ContentResolver resolver;
    private final Uri treeUri;
    private final Listener listener;

    FolderImporter(ContentResolver resolver, Uri treeUri, Listener listener) {
        this.resolver = resolver;
        this.treeUri = treeUri;
        this.listener = listener;
    }

    /**
     * Copies the game into destDir. Accepts the install folder itself or a parent up to
     * two levels above it (e.g. the whole "common" Steam folder).
     */
    void importInto(File destDir) throws IOException {
        listener.onStatus("Looking for Zero Hour in the selected folder…");
        String rootId = findGameRoot(DocumentsContract.getTreeDocumentId(treeUri), 2);
        if (rootId == null) {
            throw new IOException("No " + GameDataPaths.MARKER_FILE + " found. Pick the Zero Hour"
                    + " install folder (the one containing the .big files).");
        }

        List<Entry> files = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        collect(rootId, "", files, paths);
        copyFiles(files, paths, destDir);
    }

    /**
     * Copies Zero Hour maps into the user map directory (the engine's
     * {@code <user data>/Maps/<name>/<name>.map} layout). Accepts a single map folder (it
     * holds a .map file) or a folder of map folders, e.g. a PC "Maps" folder.
     *
     * @return the number of maps imported
     */
    int importMapsInto(File mapsDir) throws IOException {
        listener.onStatus("Looking for maps in the selected folder…");
        String rootId = DocumentsContract.getTreeDocumentId(treeUri);
        List<Entry> children = list(rootId);

        List<Entry> files = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        int maps = 0;
        if (containsMapFile(children)) {
            collect(rootId, displayName(rootId) + "/", files, paths);
            maps = 1;
        } else {
            for (Entry e : children) {
                if (e.directory && containsMapFile(list(e.documentId))) {
                    collect(e.documentId, e.name + "/", files, paths);
                    maps++;
                }
            }
        }
        if (maps == 0) {
            throw new IOException("No maps found. Pick a map folder (it contains a .map file) or"
                    + " a folder of map folders. Extract .zip downloads first.");
        }
        copyFiles(files, paths, mapsDir);
        return maps;
    }

    private void copyFiles(List<Entry> files, List<String> paths, File destDir) throws IOException {
        long total = 0;
        for (Entry e : files) {
            total += Math.max(e.size, 0);
        }
        if (total > GameDataPaths.freeBytes(destDir)) {
            throw new IOException("Not enough free space: this needs "
                    + (total / (1024 * 1024)) + " MB.");
        }

        long copied = 0;
        byte[] buffer = new byte[256 * 1024];
        for (int i = 0; i < files.size(); i++) {
            Entry e = files.get(i);
            File dst = new File(destDir, paths.get(i));
            File parent = dst.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("Cannot create " + parent);
            }
            listener.onStatus("Copying " + paths.get(i));
            if (dst.length() == e.size && e.size > 0) {
                copied += e.size; // already there from an earlier, interrupted import
                continue;
            }
            Uri src = DocumentsContract.buildDocumentUriUsingTree(treeUri, e.documentId);
            try (InputStream in = resolver.openInputStream(src);
                 OutputStream out = new FileOutputStream(dst)) {
                if (in == null) {
                    throw new IOException("Cannot read " + paths.get(i));
                }
                int n;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                    copied += n;
                    if (total > 0) {
                        listener.onProgress((float) copied / total);
                    }
                }
            }
        }
    }

    private static boolean containsMapFile(List<Entry> entries) {
        for (Entry e : entries) {
            if (!e.directory && e.name.toLowerCase(Locale.ROOT).endsWith(".map")) {
                return true;
            }
        }
        return false;
    }

    private String displayName(String documentId) {
        Uri uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId);
        String[] projection = { DocumentsContract.Document.COLUMN_DISPLAY_NAME };
        try (Cursor c = resolver.query(uri, projection, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) {
                return c.getString(0);
            }
        }
        return "ImportedMap";
    }

    private String findGameRoot(String documentId, int depth) {
        List<Entry> children = list(documentId);
        for (Entry e : children) {
            if (!e.directory && e.name.equalsIgnoreCase(GameDataPaths.MARKER_FILE)) {
                return documentId;
            }
        }
        if (depth == 0) {
            return null;
        }
        for (Entry e : children) {
            if (e.directory && !isSkippedDir(e.name)) {
                String found = findGameRoot(e.documentId, depth - 1);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private void collect(String documentId, String prefix, List<Entry> files, List<String> paths) {
        for (Entry e : list(documentId)) {
            if (e.directory) {
                if (!isSkippedDir(e.name)) {
                    collect(e.documentId, prefix + e.name + "/", files, paths);
                }
            } else if (!isSkippedFile(e.name)) {
                files.add(e);
                paths.add(prefix + e.name);
            }
        }
    }

    private List<Entry> list(String documentId) {
        List<Entry> result = new ArrayList<>();
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId);
        String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
        };
        try (Cursor c = resolver.query(children, projection, null, null, null)) {
            while (c != null && c.moveToNext()) {
                String name = c.getString(1);
                if (name == null || name.startsWith(".")) {
                    continue;
                }
                boolean dir = DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2));
                long size = c.isNull(3) ? -1 : c.getLong(3);
                result.add(new Entry(c.getString(0), name, dir, size));
            }
        }
        return result;
    }

    private static boolean isSkippedDir(String name) {
        for (String skipped : SKIPPED_DIRS) {
            if (skipped.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSkippedFile(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : SKIPPED_EXTENSIONS) {
            if (lower.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }
}
