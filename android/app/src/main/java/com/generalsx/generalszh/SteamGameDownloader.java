package com.generalsx.generalszh;

import android.util.Log;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.security.Security;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import in.dragonbra.javasteam.depotdownloader.DepotDownloader;
import in.dragonbra.javasteam.depotdownloader.IDownloadListener;
import in.dragonbra.javasteam.depotdownloader.data.AppItem;
import in.dragonbra.javasteam.depotdownloader.data.DownloadItem;
import in.dragonbra.javasteam.enums.EResult;
import in.dragonbra.javasteam.steam.authentication.AuthPollResult;
import in.dragonbra.javasteam.steam.authentication.AuthSessionDetails;
import in.dragonbra.javasteam.steam.authentication.CredentialsAuthSession;
import in.dragonbra.javasteam.steam.authentication.IAuthenticator;
import in.dragonbra.javasteam.steam.handlers.steamapps.License;
import in.dragonbra.javasteam.steam.handlers.steamapps.callback.LicenseListCallback;
import in.dragonbra.javasteam.steam.handlers.steamuser.LogOnDetails;
import in.dragonbra.javasteam.steam.handlers.steamuser.SteamUser;
import in.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOffCallback;
import in.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOnCallback;
import in.dragonbra.javasteam.steam.steamclient.SteamClient;
import in.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackManager;
import in.dragonbra.javasteam.steam.steamclient.callbacks.ConnectedCallback;
import in.dragonbra.javasteam.steam.steamclient.callbacks.DisconnectedCallback;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

/**
 * Downloads the user's own copy of Zero Hour from Steam with JavaSteam's depot downloader
 * (the same approach as scripts/get-assets.sh, which uses steamcmd on a PC).
 *
 * The user signs in to Steam directly; Steam only serves depots for games the account
 * owns. Nothing is persisted: the password and the session's refresh token live in
 * memory for the duration of the download only.
 *
 * Runs its own thread; all Listener callbacks arrive on that thread.
 */
final class SteamGameDownloader implements IDownloadListener {

    interface Listener {
        void onStatus(String message);

        /** fraction in [0, 1], or negative when unknown. */
        void onProgress(float fraction);

        void onFinished(File installDir);

        void onFailed(String message);
    }

    private final String username;
    private final String password;
    private final IAuthenticator authenticator;
    private final File installDir;
    private final Listener listener;

    private SteamClient steamClient;
    private SteamUser steamUser;
    private volatile boolean running;
    private volatile boolean finished;

    SteamGameDownloader(String username, String password, IAuthenticator authenticator,
                        File installDir, Listener listener) {
        this.username = username;
        this.password = password;
        this.authenticator = authenticator;
        this.installDir = installDir;
        this.listener = listener;
    }

    void start() {
        Thread thread = new Thread(this::run, "SteamGameDownloader");
        thread.start();
    }

    /**
     * JavaSteam's CryptoHelper needs a full BouncyCastle provider registered as "BC" (it does
     * not bundle one; without it the class fails to initialise and sign-in dies with just
     * "in.dragonbra.javasteam.util.crypto.CryptoHelper"). Android already registers its own
     * stripped platform copy under the name "BC", which lacks algorithms JavaSteam asks for
     * and would block ours from being added, so replace it before JavaSteam loads.
     */
    private static synchronized void installBouncyCastle() {
        if (Security.getProvider("BC") instanceof BouncyCastleProvider) {
            return;
        }
        Security.removeProvider("BC");
        Security.insertProviderAt(new BouncyCastleProvider(), 1);
    }

    /** Readable failure text: the root cause's type and message, not a bare class name. */
    static String describe(Throwable error) {
        Throwable t = error;
        while ((t instanceof ExecutionException || t instanceof CompletionException
                || t instanceof ExceptionInInitializerError) && t.getCause() != null) {
            t = t.getCause();
        }
        String message = t.getMessage();
        return t.getClass().getSimpleName() + (message != null ? ": " + message : "");
    }

    private void run() {
        try {
            installBouncyCastle();
            runSession();
        } catch (Throwable t) {
            // Errors (e.g. a class failing to load) must still end the busy UI state.
            Log.e("GeneralsX", "Steam download thread failed", t);
            fail("Steam download failed: " + describe(t));
        }
    }

    private void runSession() {
        // Default configuration: JavaSteam ships OkHttp as a runtime-only dependency, so the
        // app cannot reference OkHttp types to customise the HTTP client.
        steamClient = new SteamClient();
        CallbackManager manager = new CallbackManager(steamClient);
        steamUser = steamClient.getHandler(SteamUser.class);

        List<Closeable> subscriptions = new ArrayList<>();
        subscriptions.add(manager.subscribe(ConnectedCallback.class, this::onConnected));
        subscriptions.add(manager.subscribe(DisconnectedCallback.class, this::onDisconnected));
        subscriptions.add(manager.subscribe(LoggedOnCallback.class, this::onLoggedOn));
        subscriptions.add(manager.subscribe(LoggedOffCallback.class, this::onLoggedOff));
        subscriptions.add(manager.subscribe(LicenseListCallback.class, this::onLicenseList));

        running = true;
        listener.onStatus("Connecting to Steam…");
        steamClient.connect();

        while (running) {
            manager.runWaitCallbacks(1000L);
        }

        for (Closeable subscription : subscriptions) {
            try {
                subscription.close();
            } catch (IOException e) {
                Log.w("GeneralsX", "Closing a Steam callback subscription failed", e);
            }
        }
        if (!finished) {
            fail("Disconnected from Steam before the download finished.");
        }
    }

    private void onConnected(ConnectedCallback callback) {
        listener.onStatus("Signing in as " + username + "…");

        AuthSessionDetails details = new AuthSessionDetails();
        details.username = username;
        details.password = password;
        details.deviceFriendlyName = "Generals ZH for Android";
        details.persistentSession = false;
        details.authenticator = authenticator;

        try {
            CredentialsAuthSession session =
                    steamClient.getAuthentication().beginAuthSessionViaCredentials(details).get();
            AuthPollResult result = session.pollingWaitForResult().get();

            LogOnDetails logOn = new LogOnDetails();
            logOn.setUsername(result.getAccountName());
            logOn.setAccessToken(result.getRefreshToken());
            logOn.setShouldRememberPassword(false);
            // A random login ID keeps this session from kicking the user's desktop client.
            logOn.setLoginID(new Random().nextInt(Integer.MAX_VALUE));
            steamUser.logOn(logOn);
        } catch (Exception | Error e) {
            Log.e("GeneralsX", "Steam sign-in failed", e);
            fail("Steam sign-in failed: " + describe(e));
            steamClient.disconnect();
        }
    }

    private void onDisconnected(DisconnectedCallback callback) {
        running = false;
    }

    private void onLoggedOn(LoggedOnCallback callback) {
        if (callback.getResult() != EResult.OK) {
            fail("Steam refused the sign-in: " + callback.getResult());
            steamClient.disconnect();
            return;
        }
        listener.onStatus("Signed in. Checking that this account owns Zero Hour…");
    }

    private void onLoggedOff(LoggedOffCallback callback) {
        running = false;
    }

    private void onLicenseList(LicenseListCallback callback) {
        if (callback.getResult() != EResult.OK) {
            fail("Could not read the account's game licenses: " + callback.getResult());
            steamClient.disconnect();
            return;
        }
        download(callback.getLicenseList());
    }

    private void download(List<License> licenses) {
        if (!installDir.isDirectory() && !installDir.mkdirs()) {
            fail("Cannot create " + installDir);
            steamUser.logOff();
            return;
        }

        // Game data is platform independent; the Windows depot is the one Steam ships.
        AppItem app = new AppItem(
                GameDataPaths.STEAM_APP_ID,
                false,                          // installToGameNameDirectory
                installDir.getAbsolutePath(),   // installDirectory
                "public",                       // branch
                null,                           // branchPassword
                false,                          // downloadAllPlatforms
                "windows",                      // os
                false,                          // downloadAllArchs
                null,                           // osArch
                false,                          // downloadAllLanguages
                "english");                     // language

        CompletableFuture<Throwable> failure = new CompletableFuture<>();
        try (DepotDownloader downloader = new DepotDownloader(steamClient, licenses)) {
            downloader.addListener(this);
            downloader.addListener(new IDownloadListener() {
                @Override
                public void onDownloadFailed(DownloadItem item, Throwable error) {
                    failure.complete(error);
                }
            });
            listener.onStatus("Downloading Zero Hour from Steam (about 2 GB)…");
            downloader.add(app);
            downloader.finishAdding();
            downloader.awaitCompletion();
        } catch (Exception e) {
            failure.complete(e);
        } finally {
            steamUser.logOff();
        }

        Throwable error = failure.getNow(null);
        if (error != null) {
            fail("Download failed: " + describe(error)
                    + "\n\nMake sure this Steam account owns Command & Conquer Generals - Zero Hour.");
        } else if (!GameDataPaths.isGameDataDir(installDir)) {
            fail("The download finished but " + GameDataPaths.MARKER_FILE
                    + " is missing. Does this account own Zero Hour?");
        } else {
            finished = true;
            listener.onFinished(installDir);
        }
    }

    private void fail(String message) {
        if (!finished) {
            finished = true;
            listener.onFailed(message);
        }
    }

    // IDownloadListener

    @Override
    public void onStatusUpdate(String message) {
        listener.onStatus(message);
    }

    @Override
    public void onChunkCompleted(int depotId, float depotPercentComplete,
                                 long compressedBytes, long uncompressedBytes) {
        listener.onProgress(depotPercentComplete);
    }
}
