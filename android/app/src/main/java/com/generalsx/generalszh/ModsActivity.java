package com.generalsx.generalszh;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;

/**
 * Mods screen: installed mods with on/off switches, the Super Patch download, custom mods
 * from a folder or .zip, links to known community mods, and C&C Online's current files.
 */
public class ModsActivity extends Activity {
    private static final int REQUEST_MOD_FOLDER = 1;
    private static final int REQUEST_MOD_ZIP = 2;
    private static final int BG = Color.rgb(18, 20, 24);

    private ModManager mods;
    private LinearLayout installedList;
    private LinearLayout actions;
    private ProgressBar progress;
    private TextView status;
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mods = new ModManager(this);
        buildUi();
        refresh();
    }

    // ---------------------------------------------------------------------------------
    // UI

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) {
            t.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return t;
    }

    private Button button(String label, View.OnClickListener onClick) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(onClick);
        return b;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(20), dp(24), dp(24));
        root.setBackgroundColor(BG);

        root.addView(text("Mods", 22, Color.WHITE, true));
        TextView warn = text("Mods change the game. Everyone in a multiplayer match needs the"
                + " same mods, so turn them off for regular C&C Online games. Your game files"
                + " are never modified: turning all mods off gives the original game back.",
                14, Color.rgb(255, 204, 128), false);
        warn.setPadding(0, dp(8), 0, dp(12));
        root.addView(warn);

        root.addView(text("Installed", 17, Color.WHITE, true));
        installedList = new LinearLayout(this);
        installedList.setOrientation(LinearLayout.VERTICAL);
        installedList.setPadding(0, dp(4), 0, dp(12));
        root.addView(installedList);

        actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.addView(button("Get or update Super Patch", v -> downloadSuperPatch()));
        actions.addView(button("Add mod from folder", v -> startActivityForResult(
                new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQUEST_MOD_FOLDER)));
        actions.addView(button("Add mod from .zip", v -> {
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            pick.addCategory(Intent.CATEGORY_OPENABLE);
            pick.setType("application/zip");
            startActivityForResult(pick, REQUEST_MOD_ZIP);
        }));
        actions.addView(button("C&C Online: latest files", v -> fetchCncOnlineFiles()));
        root.addView(actions);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setVisibility(View.GONE);
        root.addView(progress, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        status = text("", 14, Color.LTGRAY, false);
        status.setPadding(0, dp(8), 0, dp(16));
        root.addView(status);

        root.addView(text("More mods", 17, Color.WHITE, true));
        root.addView(text("Download these from their pages (on the phone or a PC), extract the"
                + " archive if needed, then use \"Add mod\". Not yet tested on Android.",
                13, Color.LTGRAY, false));
        for (ModCatalog.KnownMod k : ModCatalog.KNOWN_MODS) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(8), 0, 0);
            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            info.addView(text(k.name, 15, Color.WHITE, true));
            info.addView(text(k.description, 13, Color.LTGRAY, false));
            row.addView(info, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(button("Open page", v -> startActivity(
                    new Intent(Intent.ACTION_VIEW, Uri.parse(k.url)))));
            root.addView(row);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.addView(root);
        setContentView(scroll);
    }

    private void refresh() {
        installedList.removeAllViews();
        List<ModManager.Mod> list = mods.list();
        if (list.isEmpty()) {
            installedList.addView(text("No mods installed.", 14, Color.LTGRAY, false));
            return;
        }
        for (ModManager.Mod m : list) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, dp(6));

            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            info.addView(text(m.name + (m.version.isEmpty() ? "" : "  (" + m.version + ")"),
                    15, Color.WHITE, true));
            if (!m.description.isEmpty()) {
                info.addView(text(m.description, 13, Color.LTGRAY, false));
            }
            row.addView(info, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            Switch toggle = new Switch(this);
            toggle.setChecked(m.enabled);
            toggle.setOnCheckedChangeListener((b, on) -> run("Updating mods…", () -> {
                mods.setEnabled(m.id, on);
                return m.name + (on ? " is on." : " is off.");
            }));
            row.addView(toggle);

            row.addView(button("Remove", v -> new AlertDialog.Builder(this)
                    .setTitle("Remove " + m.name + "?")
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton("Remove", (d, w) -> run("Removing…", () -> {
                        mods.remove(m.id);
                        return m.name + " removed.";
                    }))
                    .show()));
            installedList.addView(row);
        }
    }

    private interface Job {
        String run() throws Exception;
    }

    /** Runs a job off the UI thread with the progress bar; shows its result message. */
    private void run(String what, Job job) {
        if (busy) {
            return;
        }
        busy = true;
        actions.setVisibility(View.GONE);
        progress.setVisibility(View.VISIBLE);
        progress.setIndeterminate(true);
        status.setText(what);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        new Thread(() -> {
            String message;
            try {
                message = job.run();
            } catch (Exception e) {
                message = "Failed: " + SteamGameDownloader.describe(e);
            }
            String result = message;
            runOnUiThread(() -> {
                busy = false;
                actions.setVisibility(View.VISIBLE);
                progress.setVisibility(View.GONE);
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                status.setText(result);
                refresh();
            });
        }, "Mods").start();
    }

    private void showProgress(float fraction) {
        runOnUiThread(() -> {
            progress.setIndeterminate(fraction < 0);
            if (fraction >= 0) {
                progress.setProgress((int) (Math.min(fraction, 1f) * 1000));
            }
        });
    }

    private void showStatus(String message) {
        runOnUiThread(() -> status.setText(message));
    }

    // ---------------------------------------------------------------------------------
    // Sources

    private void downloadSuperPatch() {
        run("Looking for the latest Super Patch…", () -> {
            ModCatalog.Download d = ModCatalog.latestSuperPatch();
            if (d == null) {
                return "No Super Patch build has been published yet. Try again later.";
            }
            ModManager.Mod current = mods.get(ModCatalog.SUPER_PATCH_ID);
            if (current != null && d.version.equals(current.version)) {
                return "Super Patch is up to date (" + d.version + ").";
            }
            showStatus("Downloading Super Patch " + d.version + " ("
                    + d.size / (1024 * 1024) + " MB)…");
            try (InputStream in = new ProgressInputStream(
                    ModCatalog.open(d.url).getInputStream(), d.size, this::showProgress)) {
                mods.installZip(ModCatalog.SUPER_PATCH_ID, "Super Patch",
                        "TheSuperHackers' community fixes and improvements (test version)."
                                + " EA has not endorsed and does not support this product.",
                        "superpatch", d.version, in);
            }
            return "Super Patch " + d.version + " installed and turned on.";
        });
    }

    private void fetchCncOnlineFiles() {
        run("Contacting C&C Online…", () -> {
            CncOnlineFiles.Result r = CncOnlineFiles.fetch();
            runOnUiThread(() -> showCncOnlineResult(r));
            if (r.motd == null && !r.configReachable) {
                return "Could not reach C&C Online's servers.";
            }
            return "C&C Online reached.";
        });
    }

    private void showCncOnlineResult(CncOnlineFiles.Result r) {
        StringBuilder sb = new StringBuilder();
        sb.append(r.motd != null && !r.motd.isEmpty() ? r.motd
                : "No message of the day" + (r.motdError != null ? " (" + r.motdError + ")" : "") + ".");
        sb.append("\n\nOnline config: ").append(r.configReachable ? "reachable" : "not reachable");
        List<CncOnlineFiles.Entry> installable = new ArrayList<>();
        if (r.entries.isEmpty()) {
            sb.append("\nNo patches or map packs offered for version 1.04: you are up to date.");
        } else {
            sb.append("\n\nOffered files:");
            for (CncOnlineFiles.Entry e : r.entries) {
                sb.append("\n• ").append(e.fileName());
                if (e.installable()) {
                    installable.add(e);
                } else {
                    sb.append(" (Windows installer; cannot be used on Android)");
                }
            }
        }
        for (String err : r.errors) {
            sb.append("\n(").append(err).append(')');
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("C&C Online")
                .setMessage(sb.toString())
                .setNegativeButton(android.R.string.ok, null);
        if (!installable.isEmpty()) {
            b.setPositiveButton("Install " + installable.size() + " as mods",
                    (d, w) -> installOnlineFiles(installable));
        }
        b.show();
    }

    private void installOnlineFiles(List<CncOnlineFiles.Entry> entries) {
        run("Downloading from C&C Online…", () -> {
            for (CncOnlineFiles.Entry e : entries) {
                String name = e.fileName();
                String id = "cnconline-" + ModManager.idFor(name);
                showStatus("Downloading " + name + "…");
                HttpURLConnection conn = ModCatalog.open(e.url);
                try (InputStream in = new ProgressInputStream(conn.getInputStream(),
                        conn.getContentLengthLong(), this::showProgress)) {
                    if (name.toLowerCase().endsWith(".zip")) {
                        mods.installZip(id, name, "From C&C Online", "cnconline", "", in);
                    } else {
                        File staging = mods.newStaging(id);
                        copy(in, new File(new File(staging, "files"), name));
                        mods.commit(staging, id, name, "From C&C Online", "cnconline", "");
                    }
                } finally {
                    conn.disconnect();
                }
            }
            return "Installed " + entries.size() + " file(s) from C&C Online.";
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        if (requestCode == REQUEST_MOD_FOLDER) {
            run("Copying the mod…", () -> {
                String tmpId = "import-" + System.currentTimeMillis();
                File staging = mods.newStaging(tmpId);
                String name = new FolderImporter(getContentResolver(), uri,
                        new FolderImporter.Listener() {
                            @Override
                            public void onStatus(String message) {
                                showStatus(message);
                            }

                            @Override
                            public void onProgress(float fraction) {
                                showProgress(fraction);
                            }
                        }).importTreeInto(new File(staging, "files"));
                ModManager.Mod m = mods.commit(staging, ModManager.idFor(name), name,
                        "Custom mod", "custom", "");
                return m.name + " added and turned on.";
            });
        } else if (requestCode == REQUEST_MOD_ZIP) {
            String fileName = displayName(uri);
            String name = fileName.replaceAll("(?i)\\.zip$", "");
            run("Extracting " + fileName + "…", () -> {
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in == null) {
                        throw new java.io.IOException("Cannot read " + fileName);
                    }
                    ModManager.Mod m = mods.installZip(ModManager.idFor(name), name,
                            "Custom mod", "custom", "", in);
                    return m.name + " added and turned on.";
                }
            });
        }
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri,
                new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) {
                return c.getString(0);
            }
        }
        return "Custom mod.zip";
    }

    private static void copy(InputStream in, File out) throws java.io.IOException {
        byte[] buf = new byte[256 * 1024];
        try (OutputStream o = new FileOutputStream(out)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                o.write(buf, 0, n);
            }
        }
    }

    /** Reports read progress of a download of known size. */
    private static final class ProgressInputStream extends java.io.FilterInputStream {
        interface Callback {
            void onProgress(float fraction);
        }

        private final long total;
        private final Callback callback;
        private long read;

        ProgressInputStream(InputStream in, long total, Callback callback) {
            super(in);
            this.total = total;
            this.callback = callback;
        }

        @Override
        public int read(byte[] b, int off, int len) throws java.io.IOException {
            int n = super.read(b, off, len);
            if (n > 0) {
                read += n;
                callback.onProgress(total > 0 ? (float) read / total : -1f);
            }
            return n;
        }
    }
}
