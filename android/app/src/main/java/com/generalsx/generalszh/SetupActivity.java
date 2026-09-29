package com.generalsx.generalszh;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import in.dragonbra.javasteam.steam.authentication.IAuthenticator;

/**
 * Launcher. Starts the game straight away when the retail data is present; otherwise
 * offers the ways to get a legitimate copy onto the device:
 *  - download it from the user's own Steam account (DownloadService runs
 *    SteamGameDownloader in the background; the Steam login is remembered),
 *  - import a folder already on the device, SD card or USB stick (FolderImporter),
 *  - open the Steam store page to buy it.
 */
public class SetupActivity extends Activity {
    private static final String TAG = "GeneralsX";
    private static final int REQUEST_PICK_FOLDER = 1;
    private static final int REQUEST_PICK_MAPS = 2;
    private static final int REQUEST_SAVE_LOGS = 3;
    private static final int REQUEST_BACKUP = 4;
    private static final int REQUEST_RESTORE = 5;
    private static final int REQUEST_IMPORT_REPLAYS = 6;
    private static final int REQUEST_EXPORT_REPLAY = 7;
    private static final String PREFS = "launcher";
    private static final String KEY_AUTOSAVE_SEEN = "autosave_seen";
    /** Replay chosen in the Replays list, written by the next REQUEST_EXPORT_REPLAY result. */
    private File replayToExport;
    private static final String STEAM_STORE_URL =
            "https://store.steampowered.com/app/" + GameDataPaths.STEAM_APP_ID + "/";
    /**
     * C&C Online (the GameSpy replacement the online menus connect to) setup guide, which
     * leads to account sign-up and the "server login" the game uses. The old
     * /en/connect/register/ page now answers 404.
     */
    private static final String CNC_ONLINE_REGISTER_URL = "https://cnc-online.net/en/setup/";

    private TextView status;
    private ProgressBar progress;
    private TextView intro;
    private LinearLayout buttons;
    /** Buttons of the current screen; layoutButtons() arranges them into a grid. */
    private final List<Button> buttonList = new ArrayList<>();
    private Button pauseButton;
    private boolean busy;
    /** Download state already acted on (dialog shown / game launched); survives recreation. */
    private static DownloadService.State acknowledged;
    private final DialogAuthenticator authenticator = new DialogAuthenticator();
    /** Usable game data directory, or null while the game still has to be set up. */
    private File readyDataDir;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        readyDataDir = GameDataPaths.findGameDataDir(this);
        buildUi();
        if (readyDataDir != null) {
            showLauncher();
        } else {
            showSetup();
        }
        DownloadService.setUiAuthenticator(authenticator);
        if (savedInstanceState == null) {
            offerLogsAfterCrash();
            if (readyDataDir != null) {
                showAutosaveHint();
            }
        }
    }

    /**
     * The engine saves a single-player match when the app goes to the background
     * (SDL3GameEngine.cpp); if the game was closed since, say once where to find it.
     */
    private void showAutosaveHint() {
        File autosave = PlayerData.autosave(this);
        if (!autosave.isFile()) {
            return;
        }
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        long saved = autosave.lastModified();
        if (prefs.getLong(KEY_AUTOSAVE_SEEN, 0) >= saved) {
            return;
        }
        prefs.edit().putLong(KEY_AUTOSAVE_SEEN, saved).apply();
        status.setText("Your last match was saved automatically when you left the game ("
                + java.text.DateFormat.getDateTimeInstance().format(new Date(saved))
                + "). To continue it: Play, then Load Game and pick \"Android autosave\".");
    }

    @Override
    protected void onResume() {
        super.onResume();
        DownloadService.setObserver(this::onDownloadState);
        // A download interrupted by the app being closed or killed resumes on its own
        // with the saved Steam login.
        if (!DownloadService.state().running && DownloadService.isPending(this)
                && SteamLoginStore.savedAccountName(this) != null) {
            DownloadService.startWithSavedLogin(this);
        }
    }

    @Override
    protected void onPause() {
        DownloadService.setObserver(null);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        DownloadService.clearUiAuthenticator(authenticator);
        super.onDestroy();
    }

    /** This screen follows the device rotation (the game itself is landscape-only). */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        layoutButtons();
    }

    private void launchGame(File dataDir) {
        if (!dataDir.getAbsolutePath().equals(GameDataPaths.LEGACY_DIR)) {
            try {
                GameDataPaths.installBundledFonts(this, dataDir);
                GameDataPaths.installBundledGameFiles(this, dataDir);
            } catch (Exception e) {
                // Not fatal (text renders only if fonts/ exists; Extras needs its .wnd), so log it.
                Log.w(TAG, "Could not install bundled fonts/game files into " + dataDir, e);
            }
        }
        Intent intent = new Intent(this, GeneralsXZHActivity.class);
        intent.putExtra(GeneralsXZHActivity.EXTRA_GAME_DATA_DIR, dataDir.getAbsolutePath());
        startActivity(intent);
        finish();
    }

    // ---------------------------------------------------------------------------------
    // UI (plain framework views: the app has no AndroidX dependency)

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(32), dp(24), dp(32), dp(24));
        root.setBackgroundColor(Color.rgb(18, 20, 24));

        TextView title = new TextView(this);
        title.setText("Command & Conquer Generals: Zero Hour");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        intro = new TextView(this);
        intro.setTextColor(Color.LTGRAY);
        intro.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        intro.setPadding(0, dp(8), 0, dp(16));
        root.addView(intro);

        buttons = new LinearLayout(this);
        root.addView(buttons);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setVisibility(View.GONE);
        progress.setPadding(0, dp(16), 0, 0);
        root.addView(progress, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        pauseButton = new Button(this);
        pauseButton.setText("Pause download");
        pauseButton.setAllCaps(false);
        pauseButton.setVisibility(View.GONE);
        pauseButton.setOnClickListener(v -> DownloadService.pause(this));
        root.addView(pauseButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        status = new TextView(this);
        status.setTextColor(Color.LTGRAY);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        status.setPadding(0, dp(12), 0, 0);
        root.addView(status);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(18, 20, 24));
        scroll.addView(root);
        setContentView(scroll);
    }

    /** Game data is present: play, add maps, set up online, or replace the game files. */
    private void showLauncher() {
        intro.setText("Ready to play.");
        buttonList.clear();
        buttonList.add(button("Play", v -> launchGame(readyDataDir)));
        buttonList.add(button("Settings", v -> startActivity(new Intent(this, SettingsActivity.class))));
        buttonList.add(button("Mods", v -> startActivity(new Intent(this, ModsActivity.class))));
        buttonList.add(button("Add maps", v -> pickMapsFolder()));
        buttonList.add(button("Replays", v -> showReplays()));
        buttonList.add(button("Backup & restore", v -> showBackupMenu()));
        buttonList.add(button("Update game files", v -> onUpdateGameFiles()));
        buttonList.add(button("Clear caches", v -> onClearCaches()));
        buttonList.add(button("Check for app updates", v -> onCheckForAppUpdate()));
        buttonList.add(button("C&C Online account", v -> openUrl(CNC_ONLINE_REGISTER_URL)));
        buttonList.add(button("Save logs", v -> saveLogs()));
        buttonList.add(button(debugLabel(), this::toggleDebug));
        buttonList.add(button("Re-import game files", v -> showSetup()));
        layoutButtons();
        status.setText("Online play uses C&C Online: create a free account, then sign in with it"
                + " on the in-game Online login screen.\n"
                + "Add maps: pick a map folder (it contains a .map file) or a folder of map folders."
                + " Maps other players send you in a lobby are saved automatically.");
    }

    /** No game data yet (or the user asked to replace it): the ways to get a legal copy. */
    private void showSetup() {
        intro.setText("This app is the game engine only. It needs the game files from your own "
                + "copy of Zero Hour (Steam, EA App or retail CD). Choose how to get them:");
        buttonList.clear();
        String account = SteamLoginStore.savedAccountName(this);
        buttonList.add(button(account != null ? "Download from Steam (" + account + ")"
                : "Download from Steam", v -> onDownloadFromSteam()));
        buttonList.add(button("Import from folder", v -> pickFolder()));
        buttonList.add(button("Buy on Steam", v -> openUrl(STEAM_STORE_URL)));
        buttonList.add(button("Check for app updates", v -> onCheckForAppUpdate()));
        buttonList.add(button("Save logs", v -> saveLogs()));
        if (account != null) {
            buttonList.add(button("Sign out of Steam", v -> {
                SteamLoginStore.clear(this);
                showSetup();
            }));
        }
        layoutButtons();
        status.setText("Download from Steam: sign in once with the Steam account that owns Zero Hour."
                + " The login is remembered (encrypted on this device, the password is never saved)."
                + " The download continues in the background and resumes if interrupted.\n"
                + "Import from folder: copy your PC install folder to the device, SD card or "
                + "USB stick first, then pick it here.");
    }

    private void openUrl(String url) {
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    }

    private Button button(String label, View.OnClickListener onClick) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(onClick);
        return b;
    }

    /** A grid of buttons: one full-width column in portrait, three columns in landscape. */
    private void layoutButtons() {
        boolean portrait = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_PORTRAIT;
        int columns = portrait ? 1 : Math.min(3, Math.max(1, buttonList.size()));
        buttons.removeAllViews();
        buttons.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = null;
        for (int i = 0; i < buttonList.size(); i++) {
            if (i % columns == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                buttons.addView(row, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            }
            Button b = buttonList.get(i);
            if (b.getParent() != null) {
                ((ViewGroup) b.getParent()).removeView(b);
            }
            row.addView(b, cell(i % columns < columns - 1));
        }
        // Pad the last row so its buttons keep the same width as the rows above.
        int remainder = buttonList.size() % columns;
        for (int i = remainder; remainder > 0 && i < columns; i++) {
            row.addView(new View(this), cell(i < columns - 1));
        }
    }

    private LinearLayout.LayoutParams cell(boolean gapAfter) {
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(0, 0, gapAfter ? dp(12) : 0, dp(8));
        return lp;
    }

    private void setBusy(boolean value) {
        setBusy(value, false);
    }

    private void setBusy(boolean value, boolean pausable) {
        busy = value;
        buttons.setVisibility(value ? View.GONE : View.VISIBLE);
        progress.setVisibility(value ? View.VISIBLE : View.GONE);
        pauseButton.setVisibility(value && pausable ? View.VISIBLE : View.GONE);
        progress.setIndeterminate(true);
        if (value) {
            // A multi-GB transfer; don't let the screen sleep and pause the app.
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void showStatus(String message) {
        runOnUiThread(() -> status.setText(message));
    }

    private void showProgress(float fraction) {
        runOnUiThread(() -> {
            if (fraction < 0) {
                progress.setIndeterminate(true);
            } else {
                progress.setIndeterminate(false);
                progress.setProgress((int) (Math.min(fraction, 1f) * 1000));
            }
        });
    }

    private void onSetupFinished(File dataDir) {
        runOnUiThread(() -> {
            try {
                GameDataPaths.markComplete(dataDir);
            } catch (Exception e) {
                onSetupFailed(e.getMessage());
                return;
            }
            setBusy(false);
            launchGame(dataDir);
        });
    }

    private void onSetupFailed(String message) {
        runOnUiThread(() -> {
            setBusy(false);
            status.setText(message);
            new AlertDialog.Builder(this)
                    .setTitle("Setup did not finish")
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        });
    }

    private boolean checkFreeSpace(File dir) {
        if (GameDataPaths.freeBytes(dir) < GameDataPaths.REQUIRED_FREE_BYTES) {
            onSetupFailed("Not enough free storage. Zero Hour needs about 3 GB free.");
            return false;
        }
        return true;
    }

    @Override
    public void onBackPressed() {
        // A Steam download keeps running in the background service, so leaving is fine then.
        if (!busy || DownloadService.state().running) {
            super.onBackPressed();
        }
    }

    // ---------------------------------------------------------------------------------
    // Maintenance: app updates, game file updates, caches

    private void onCheckForAppUpdate() {
        setBusy(true);
        status.setText("Checking for updates…");
        new Thread(() -> {
            try {
                AppUpdater.Release release = AppUpdater.findNewerRelease(this);
                runOnUiThread(() -> {
                    setBusy(false);
                    if (release == null) {
                        status.setText("You have the latest version (r"
                                + AppUpdater.installedVersionCode(this) + ").");
                    } else {
                        offerUpdate(release);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setBusy(false);
                    status.setText("Could not check for updates: " + e.getMessage());
                });
            }
        }, "UpdateCheck").start();
    }

    private void offerUpdate(AppUpdater.Release release) {
        new AlertDialog.Builder(this)
                .setTitle("Update available")
                .setMessage("Version r" + release.versionCode + " is available (you have r"
                        + AppUpdater.installedVersionCode(this) + ", download "
                        + (release.apkSize / (1024 * 1024)) + " MB). Your game files, maps and"
                        + " Steam login are kept.")
                .setNegativeButton("Later", null)
                .setPositiveButton("Update", (d, w) -> installUpdate(release))
                .show();
    }

    private void installUpdate(AppUpdater.Release release) {
        if (!getPackageManager().canRequestPackageInstalls()) {
            // One-time Android permission to install updates from this app.
            status.setText("Allow \"Install unknown apps\" for Generals ZH, then tap"
                    + " \"Check for app updates\" again.");
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        setBusy(true);
        status.setText("Downloading update r" + release.versionCode + "…");
        new Thread(() -> {
            try {
                AppUpdater.install(this, release, this::showProgress);
                runOnUiThread(() -> {
                    setBusy(false);
                    status.setText("Confirm the update in the Android installer.");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setBusy(false);
                    status.setText("Update failed: " + e.getMessage());
                });
            }
        }, "UpdateInstall").start();
    }

    /** Re-runs the Steam download: validates every file, fetches what changed or is damaged. */
    private void onUpdateGameFiles() {
        new AlertDialog.Builder(this)
                .setTitle("Update game files")
                .setMessage("Checks your Zero Hour files against Steam and downloads anything"
                        + " that changed or is damaged. Maps and settings are kept.")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Check now", (d, w) -> onDownloadFromSteam())
                .show();
    }

    // ---------------------------------------------------------------------------------
    // Logs

    /** After the game crashed or was killed, says why and offers to save the logs. */
    private void offerLogsAfterCrash() {
        String crash = CrashLogs.takeUnseenCrash(this);
        if (crash == null) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("The game closed unexpectedly")
                .setMessage(crash + "\n\nSave the logs to a file you can send for a fix?")
                .setPositiveButton("Save logs", (d, w) -> saveLogs())
                .setNegativeButton("Not now", null)
                .show();
    }

    private String debugLabel() {
        return "Debug mode: " + (LaunchOptions.debug(this) ? "ON" : "off");
    }

    /**
     * Debug mode records the whole next game sessions (engine output unfiltered, frame-time
     * and memory samples, the app's logcat, thermal/battery state) for "Save logs".
     */
    private void toggleDebug(View v) {
        boolean on = !LaunchOptions.debug(this);
        LaunchOptions.setDebug(this, on);
        ((Button) v).setText(debugLabel());
        status.setText(on
                ? "Debug mode on: the next game sessions are recorded in full (it uses more storage"
                        + " and a little performance). Play, then tap \"Save logs\" and send the file."
                : "Debug mode off.");
    }

    /** Asks where to save the log report (CrashLogs); written in onActivityResult. */
    private void saveLogs() {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT)
                .format(new Date());
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TITLE, "GeneralsZH-logs-" + stamp + ".txt");
        startActivityForResult(intent, REQUEST_SAVE_LOGS);
    }

    private void writeLogs(Uri target) {
        status.setText("Collecting logs...");
        new Thread(() -> {
            String message;
            try (OutputStream out = getContentResolver().openOutputStream(target)) {
                if (out == null) {
                    throw new IOException("cannot open the chosen file");
                }
                byte[] report = CrashLogs.buildReport(this)
                        .getBytes(StandardCharsets.UTF_8);
                out.write(report);
                message = "Saved " + (report.length >> 10) + " KB of logs. Send that file along"
                        + " with what you were doing when the game crashed.";
            } catch (Exception e) {
                message = "Saving logs failed: " + e.getMessage();
            }
            String shown = message;
            runOnUiThread(() -> status.setText(shown));
        }, "SaveLogs").start();
    }

    private void onClearCaches() {
        File dataDir = readyDataDir;
        new Thread(() -> {
            int count = GameDataPaths.clearCaches(this, dataDir);
            runOnUiThread(() -> status.setText("Cleared " + count + " cache file"
                    + (count == 1 ? "" : "s") + ". Shader and map caches rebuild on the next start"
                    + " (the first load is slower)."));
        }, "ClearCaches").start();
    }

    // ---------------------------------------------------------------------------------
    // Steam

    private void askSteamCredentials() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(24), dp(8), dp(24), 0);
        EditText user = new EditText(this);
        user.setHint("Steam account name");
        user.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        EditText pass = new EditText(this);
        pass.setHint("Password");
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        form.addView(user);
        form.addView(pass);

        new AlertDialog.Builder(this)
                .setTitle("Sign in to Steam")
                .setMessage("Use the account that owns Command & Conquer Generals - Zero Hour.")
                .setView(form)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Sign in", (d, w) -> {
                    String u = user.getText().toString().trim();
                    String p = pass.getText().toString();
                    if (!u.isEmpty() && !p.isEmpty()) {
                        startSteamDownload(u, p);
                    }
                })
                .show();
    }

    private void onDownloadFromSteam() {
        if (!checkFreeSpace(GameDataPaths.appDataDir(this))) {
            return;
        }
        if (SteamLoginStore.savedAccountName(this) != null) {
            setBusy(true, true);
            DownloadService.startWithSavedLogin(this);
        } else {
            askSteamCredentials();
        }
    }

    private void startSteamDownload(String username, String password) {
        setBusy(true, true);
        DownloadService.startWithCredentials(this, username, password);
    }

    /** Mirrors the background download on this screen (main thread, while resumed). */
    private void onDownloadState(DownloadService.State s) {
        if (s.running) {
            setBusy(true, true);
            if (s.status != null) {
                status.setText(s.progress >= 0
                        ? Math.round(s.progress * 100) + "% · " + s.status : s.status);
            }
            showProgress(s.progress);
            return;
        }
        if (s == acknowledged) {
            return; // already handled before this screen was (re)opened
        }
        if (s.completedDir != null) {
            acknowledged = s;
            setBusy(false);
            launchGame(s.completedDir);
        } else if (s.error != null) {
            acknowledged = s;
            setBusy(false);
            showSetup();
            status.setText(s.error);
            if (!s.error.startsWith("Download paused")) {
                new AlertDialog.Builder(this)
                        .setTitle("Download did not finish")
                        .setMessage(s.error)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            }
        }
    }

    /** Steam Guard prompts, shown as dialogs; JavaSteam waits on the returned futures. */
    private final class DialogAuthenticator implements IAuthenticator {
        @Override
        public CompletableFuture<String> getDeviceCode(boolean previousCodeWasIncorrect) {
            return askCode((previousCodeWasIncorrect ? "That code was wrong. " : "")
                    + "Enter the code from the Steam Mobile app (Steam Guard).");
        }

        @Override
        public CompletableFuture<String> getEmailCode(String email, boolean previousCodeWasIncorrect) {
            return askCode((previousCodeWasIncorrect ? "That code was wrong. " : "")
                    + "Enter the Steam Guard code sent to " + (email != null ? email : "your email") + ".");
        }

        @Override
        public CompletableFuture<Boolean> acceptDeviceConfirmation() {
            CompletableFuture<Boolean> result = new CompletableFuture<>();
            runOnUiThread(() -> new AlertDialog.Builder(SetupActivity.this)
                    .setTitle("Steam Guard")
                    .setMessage("Approve this sign-in in the Steam Mobile app, then wait here.")
                    .setCancelable(false)
                    .setPositiveButton("I'll approve it", (d, w) -> result.complete(true))
                    .setNegativeButton("Enter a code instead", (d, w) -> result.complete(false))
                    .show());
            return result;
        }

        private CompletableFuture<String> askCode(String message) {
            CompletableFuture<String> result = new CompletableFuture<>();
            runOnUiThread(() -> {
                EditText code = new EditText(SetupActivity.this);
                code.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
                code.setGravity(Gravity.CENTER);
                new AlertDialog.Builder(SetupActivity.this)
                        .setTitle("Steam Guard")
                        .setMessage(message)
                        .setView(code)
                        .setCancelable(false)
                        .setNegativeButton(android.R.string.cancel,
                                (d, w) -> result.cancel(true))
                        .setPositiveButton(android.R.string.ok,
                                (d, w) -> result.complete(code.getText().toString().trim()))
                        .show();
            });
            return result;
        }
    }

    // ---------------------------------------------------------------------------------
    // Folder import

    private void pickFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        startActivityForResult(intent, REQUEST_PICK_FOLDER);
    }

    private void pickMapsFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        startActivityForResult(intent, REQUEST_PICK_MAPS);
    }

    private FolderImporter importer(Uri tree) {
        return new FolderImporter(getContentResolver(), tree, new FolderImporter.Listener() {
            @Override
            public void onStatus(String message) {
                showStatus(message);
            }

            @Override
            public void onProgress(float fraction) {
                showProgress(fraction);
            }
        });
    }

    // ---------------------------------------------------------------------------------
    // Saves, replays, settings (PlayerData)

    private void showBackupMenu() {
        new AlertDialog.Builder(this)
                .setTitle("Backup & restore")
                .setMessage("Saved games, replays, your added maps and game settings are stored inside"
                        + " the app and are deleted if it is uninstalled. Keep a backup file anywhere"
                        + " (Downloads, Google Drive, a PC) and restore it later.")
                .setPositiveButton("Back up", (d, w) -> {
                    String stamp = new SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(new Date());
                    startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
                            .addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("application/zip")
                            .putExtra(Intent.EXTRA_TITLE, "GeneralsZH-backup-" + stamp + ".zip"),
                            REQUEST_BACKUP);
                })
                .setNeutralButton("Restore", (d, w) -> startActivityForResult(
                        new Intent(Intent.ACTION_OPEN_DOCUMENT)
                                .addCategory(Intent.CATEGORY_OPENABLE)
                                .setType("application/zip"),
                        REQUEST_RESTORE))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showReplays() {
        List<File> replays = PlayerData.replays(this);
        String[] items = new String[replays.size() + 1];
        items[0] = "Import replay files…";
        DateFormat when = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
        for (int i = 0; i < replays.size(); i++) {
            File f = replays.get(i);
            items[i + 1] = f.getName() + "  (" + when.format(new Date(f.lastModified())) + ")";
        }
        new AlertDialog.Builder(this)
                .setTitle(replays.isEmpty() ? "Replays (none yet)" : "Replays: tap one to save a copy")
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                                .addCategory(Intent.CATEGORY_OPENABLE)
                                .setType("*/*")
                                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true),
                                REQUEST_IMPORT_REPLAYS);
                    } else {
                        replayToExport = replays.get(which - 1);
                        startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
                                .addCategory(Intent.CATEGORY_OPENABLE)
                                .setType("application/octet-stream")
                                .putExtra(Intent.EXTRA_TITLE, replayToExport.getName()),
                                REQUEST_EXPORT_REPLAY);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Runs {@code work} off the UI thread and shows its result (or error) in the status line. */
    private void runFileTask(String busyText, java.util.concurrent.Callable<String> work) {
        status.setText(busyText);
        new Thread(() -> {
            String message;
            try {
                message = work.call();
            } catch (Exception e) {
                message = "Failed: " + e.getMessage();
            }
            String shown = message;
            runOnUiThread(() -> status.setText(shown));
        }, "PlayerData").start();
    }

    private void importReplays(Intent data) {
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                uris.add(data.getClipData().getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        runFileTask("Importing replays…", () -> {
            for (Uri uri : uris) {
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in == null) {
                        throw new IOException("cannot open " + uri);
                    }
                    PlayerData.importReplay(this, displayName(uri), in);
                }
            }
            return "Imported " + uris.size() + (uris.size() == 1 ? " replay" : " replays")
                    + ". Watch them in the game: Load Replay.";
        });
    }

    private String displayName(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(uri,
                new String[] { android.provider.OpenableColumns.DISPLAY_NAME }, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) {
                return c.getString(0);
            }
        }
        return "Imported-" + System.currentTimeMillis() + ".rep";
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) {
            return;
        }
        if (requestCode == REQUEST_IMPORT_REPLAYS) {
            importReplays(data);
            return;
        }
        if (data.getData() == null) {
            return;
        }
        Uri tree = data.getData();
        if (requestCode == REQUEST_BACKUP) {
            runFileTask("Backing up…", () -> {
                try (OutputStream out = getContentResolver().openOutputStream(tree)) {
                    if (out == null) {
                        throw new IOException("cannot open the chosen file");
                    }
                    return "Backed up " + PlayerData.backup(this, out) + " files.";
                }
            });
        } else if (requestCode == REQUEST_RESTORE) {
            runFileTask("Restoring…", () -> {
                try (InputStream in = getContentResolver().openInputStream(tree)) {
                    if (in == null) {
                        throw new IOException("cannot open the chosen file");
                    }
                    return "Restored " + PlayerData.restore(this, in) + " files.";
                }
            });
        } else if (requestCode == REQUEST_EXPORT_REPLAY && replayToExport != null) {
            File replay = replayToExport;
            runFileTask("Saving replay…", () -> {
                try (OutputStream out = getContentResolver().openOutputStream(tree);
                     InputStream in = new java.io.FileInputStream(replay)) {
                    if (out == null) {
                        throw new IOException("cannot open the chosen file");
                    }
                    byte[] buffer = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buffer)) > 0) {
                        out.write(buffer, 0, n);
                    }
                }
                return "Saved " + replay.getName() + ".";
            });
        } else if (requestCode == REQUEST_SAVE_LOGS) {
            writeLogs(tree);
        } else if (requestCode == REQUEST_PICK_FOLDER) {
            importGame(tree);
        } else if (requestCode == REQUEST_PICK_MAPS) {
            importMaps(tree);
        }
    }

    private void importGame(Uri tree) {
        File dest = GameDataPaths.appDataDir(this);
        setBusy(true);
        new Thread(() -> {
            try {
                importer(tree).importInto(dest);
                if (!GameDataPaths.isGameDataDir(dest)) {
                    onSetupFailed("The copy finished but " + GameDataPaths.MARKER_FILE + " is missing.");
                    return;
                }
                onSetupFinished(dest);
            } catch (Exception e) {
                onSetupFailed("Import failed: " + e.getMessage());
            }
        }, "FolderImporter").start();
    }

    private void importMaps(Uri tree) {
        File mapsDir = GameDataPaths.userMapsDir(this);
        setBusy(true);
        new Thread(() -> {
            try {
                int count = importer(tree).importMapsInto(mapsDir);
                runOnUiThread(() -> {
                    setBusy(false);
                    status.setText("Added " + count + (count == 1 ? " map" : " maps")
                            + ". They appear in the Skirmish and multiplayer map lists.");
                });
            } catch (Exception e) {
                onSetupFailed("Adding maps failed: " + e.getMessage());
            }
        }, "MapImporter").start();
    }
}
