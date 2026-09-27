package com.generalsx.generalszh;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.concurrent.CompletableFuture;

import in.dragonbra.javasteam.steam.authentication.IAuthenticator;

/**
 * Launcher. Starts the game straight away when the retail data is present; otherwise
 * offers the ways to get a legitimate copy onto the device:
 *  - download it from the user's own Steam account (SteamGameDownloader),
 *  - import a folder already on the device, SD card or USB stick (FolderImporter),
 *  - open the Steam store page to buy it.
 */
public class SetupActivity extends Activity {
    private static final String TAG = "GeneralsX";
    private static final int REQUEST_PICK_FOLDER = 1;
    private static final String STEAM_STORE_URL =
            "https://store.steampowered.com/app/" + GameDataPaths.STEAM_APP_ID + "/";

    private TextView status;
    private ProgressBar progress;
    private LinearLayout buttons;
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        File dataDir = GameDataPaths.findGameDataDir(this);
        if (dataDir != null) {
            launchGame(dataDir);
            return;
        }
        buildUi();
    }

    private void launchGame(File dataDir) {
        if (!dataDir.getAbsolutePath().equals(GameDataPaths.LEGACY_DIR)) {
            try {
                GameDataPaths.installBundledFonts(this, dataDir);
            } catch (Exception e) {
                // Not fatal: text renders only if fonts/ exists, so surface it in logcat.
                Log.w(TAG, "Could not install bundled fonts into " + dataDir, e);
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

        TextView intro = new TextView(this);
        intro.setText("This app is the game engine only. It needs the game files from your own "
                + "copy of Zero Hour (Steam, EA App or retail CD). Choose how to get them:");
        intro.setTextColor(Color.LTGRAY);
        intro.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        intro.setPadding(0, dp(8), 0, dp(16));
        root.addView(intro);

        buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(button("Download from Steam", v -> askSteamCredentials()));
        buttons.addView(button("Import from folder", v -> pickFolder()));
        buttons.addView(button("Buy on Steam", v ->
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(STEAM_STORE_URL)))));
        root.addView(buttons);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setVisibility(View.GONE);
        progress.setPadding(0, dp(16), 0, 0);
        root.addView(progress, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        status = new TextView(this);
        status.setTextColor(Color.LTGRAY);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        status.setPadding(0, dp(12), 0, 0);
        status.setText("Download from Steam: sign in with the Steam account that owns Zero Hour. "
                + "Your password goes only to Steam and is not saved.\n"
                + "Import from folder: copy your PC install folder to the device, SD card or "
                + "USB stick first, then pick it here.");
        root.addView(status);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(18, 20, 24));
        scroll.addView(root);
        setContentView(scroll);
    }

    private Button button(String label, View.OnClickListener onClick) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(0, 0, dp(12), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private void setBusy(boolean value) {
        busy = value;
        buttons.setVisibility(value ? View.GONE : View.VISIBLE);
        progress.setVisibility(value ? View.VISIBLE : View.GONE);
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
        if (!busy) {
            super.onBackPressed();
        }
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

    private void startSteamDownload(String username, String password) {
        File dest = GameDataPaths.appDataDir(this);
        if (!checkFreeSpace(dest)) {
            return;
        }
        setBusy(true);
        new SteamGameDownloader(username, password, new DialogAuthenticator(), dest,
                new SteamGameDownloader.Listener() {
                    @Override
                    public void onStatus(String message) {
                        showStatus(message);
                    }

                    @Override
                    public void onProgress(float fraction) {
                        showProgress(fraction);
                    }

                    @Override
                    public void onFinished(File installDir) {
                        onSetupFinished(installDir);
                    }

                    @Override
                    public void onFailed(String message) {
                        onSetupFailed(message);
                    }
                }).start();
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

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_FOLDER || resultCode != RESULT_OK || data == null
                || data.getData() == null) {
            return;
        }
        Uri tree = data.getData();
        File dest = GameDataPaths.appDataDir(this);
        setBusy(true);
        new Thread(() -> {
            try {
                new FolderImporter(getContentResolver(), tree, new FolderImporter.Listener() {
                    @Override
                    public void onStatus(String message) {
                        showStatus(message);
                    }

                    @Override
                    public void onProgress(float fraction) {
                        showProgress(fraction);
                    }
                }).importInto(dest);
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
}
