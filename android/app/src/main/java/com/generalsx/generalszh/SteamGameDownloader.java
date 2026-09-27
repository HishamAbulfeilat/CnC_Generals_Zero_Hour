package com.generalsx.generalszh;

import android.util.Log;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.security.Security;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import in.dragonbra.javasteam.depotdownloader.DepotDownloader;
import in.dragonbra.javasteam.depotdownloader.IDownloadListener;
import in.dragonbra.javasteam.depotdownloader.data.AppItem;
import in.dragonbra.javasteam.depotdownloader.data.DownloadItem;
import in.dragonbra.javasteam.enums.EResult;
import in.dragonbra.javasteam.networking.steam3.ProtocolTypes;
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
import in.dragonbra.javasteam.steam.steamclient.configuration.SteamConfiguration;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

/**
 * Downloads the user's own copy of Zero Hour from Steam with JavaSteam's depot downloader
 * (the same approach as scripts/get-assets.sh, which uses steamcmd on a PC).
 *
 * The user signs in to Steam directly; Steam only serves depots for games the account
 * owns. A credential sign-in asks Steam for a persistent session and hands the resulting
 * refresh token to {@link Listener#onSignedIn} (SteamLoginStore keeps it, encrypted); later
 * runs sign in with that token and need no password or Steam Guard. The password itself is
 * never stored.
 *
 * Re-running a download into the same directory resumes it: the depot downloader
 * validates files already on disk and fetches only missing or damaged chunks.
 *
 * Runs its own thread; all Listener callbacks arrive on that thread.
 */
final class SteamGameDownloader implements IDownloadListener {
    private static final String TAG = "GeneralsX";

    interface Listener {
        void onStatus(String message);

        /** fraction in [0, 1], or negative when unknown. */
        void onProgress(float fraction);

        /** A credential sign-in succeeded; persist this to skip signing in next time. */
        void onSignedIn(String accountName, String refreshToken);

        /** Steam no longer accepts the saved refresh token; forget it. */
        void onSavedLoginRejected();

        void onFinished(File installDir);

        /**
         * @param resumable true when running again later can succeed without user action
         *                  (connection lost, paused); false for sign-in or ownership problems.
         */
        void onFailed(String message, boolean resumable);
    }

    private final String username;
    private final String password;
    private final String savedRefreshToken;
    private final IAuthenticator authenticator;
    private final File installDir;
    private final Listener listener;

    private SteamClient steamClient;
    private SteamUser steamUser;
    private volatile boolean running;
    private volatile boolean finished;
    private volatile boolean cancelled;
    private volatile boolean everConnected;

    /** Sign in with a password (and Steam Guard through {@code authenticator}). */
    static SteamGameDownloader withCredentials(String username, String password,
            IAuthenticator authenticator, File installDir, Listener listener) {
        return new SteamGameDownloader(username, password, null, authenticator, installDir, listener);
    }

    /** Sign in with a refresh token saved from an earlier sign-in; needs no UI. */
    static SteamGameDownloader withSavedLogin(String accountName, String refreshToken,
            File installDir, Listener listener) {
        return new SteamGameDownloader(accountName, null, refreshToken, null, installDir, listener);
    }

    private SteamGameDownloader(String username, String password, String savedRefreshToken,
                                IAuthenticator authenticator, File installDir, Listener listener) {
        this.username = username;
        this.password = password;
        this.savedRefreshToken = savedRefreshToken;
        this.authenticator = authenticator;
        this.installDir = installDir;
        this.listener = listener;
    }

    void start() {
        Thread thread = new Thread(this::run, "SteamGameDownloader");
        thread.start();
    }

    /** Stops the download; files already written stay and are reused by the next run. */
    void cancel() {
        cancelled = true;
        SteamClient client = steamClient;
        if (client != null) {
            client.disconnect();
        }
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
            // JavaSteam 1.8.0's WebSocket connection has a watchdog that drops the session
            // after 30 s without a binary frame (keep-alive pongs don't count). Approving a
            // sign-in in the Steam Guard app takes longer than that, and the next sign-in
            // request then fails with AsyncJobFailedException. The TCP connection has no such
            // watchdog, so prefer it; fall back to WebSocket (port 443) only when TCP cannot
            // connect at all, e.g. on networks that block Steam's TCP ports.
            runSession(ProtocolTypes.TCP);
            if (!everConnected && !cancelled && !finished) {
                Log.w(TAG, "Steam TCP connection failed; retrying over WebSocket");
                runSession(ProtocolTypes.WEB_SOCKET);
            }
            if (!finished) {
                fail(cancelled ? "Download paused." : everConnected
                        ? "Disconnected from Steam before the download finished."
                        : "Could not connect to Steam. Check the internet connection.", true);
            }
        } catch (Throwable t) {
            // Errors (e.g. a class failing to load) must still end the busy UI state.
            Log.e(TAG, "Steam download thread failed", t);
            fail("Steam download failed: " + describe(t), false);
        }
    }

    private void runSession(ProtocolTypes protocol) {
        // JavaSteam ships OkHttp as a runtime-only dependency, so the app cannot reference
        // OkHttp types to customise the HTTP client; only the CM protocol is set here.
        steamClient = new SteamClient(SteamConfiguration.create(
                builder -> builder.withProtocolTypes(EnumSet.of(protocol))));
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

        while (running && !cancelled) {
            manager.runWaitCallbacks(1000L);
        }

        for (Closeable subscription : subscriptions) {
            try {
                subscription.close();
            } catch (IOException e) {
                Log.w(TAG, "Closing a Steam callback subscription failed", e);
            }
        }
    }

    private void onConnected(ConnectedCallback callback) {
        everConnected = true;
        if (savedRefreshToken != null) {
            listener.onStatus("Signing in as " + username + " (saved login)…");
            logOn(username, savedRefreshToken);
            return;
        }

        listener.onStatus("Signing in as " + username + "…");
        AuthSessionDetails details = new AuthSessionDetails();
        details.username = username;
        details.password = password;
        details.deviceFriendlyName = "Generals ZH for Android";
        // Long-lived refresh token, saved so the user does not have to sign in again.
        details.persistentSession = true;
        details.authenticator = authenticator;

        try {
            CredentialsAuthSession session =
                    steamClient.getAuthentication().beginAuthSessionViaCredentials(details).get();
            AuthPollResult result = session.pollingWaitForResult().get();
            listener.onSignedIn(result.getAccountName(), result.getRefreshToken());
            logOn(result.getAccountName(), result.getRefreshToken());
        } catch (Exception | Error e) {
            if (!cancelled) {
                Log.e(TAG, "Steam sign-in failed", e);
                fail("Steam sign-in failed: " + describe(e), false);
            }
            steamClient.disconnect();
        }
    }

    private void logOn(String accountName, String refreshToken) {
        LogOnDetails logOn = new LogOnDetails();
        logOn.setUsername(accountName);
        logOn.setAccessToken(refreshToken);
        logOn.setShouldRememberPassword(true);
        // A random login ID keeps this session from kicking the user's desktop client.
        logOn.setLoginID(new Random().nextInt(Integer.MAX_VALUE));
        steamUser.logOn(logOn);
    }

    private void onDisconnected(DisconnectedCallback callback) {
        running = false;
    }

    private void onLoggedOn(LoggedOnCallback callback) {
        EResult result = callback.getResult();
        if (result != EResult.OK) {
            if (savedRefreshToken != null) {
                // Expired or revoked (e.g. password changed, "deauthorize all devices").
                listener.onSavedLoginRejected();
                fail("Your saved Steam login has expired (" + result + "). Sign in again.", false);
            } else {
                fail("Steam refused the sign-in: " + result, false);
            }
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
            fail("Could not read the account's game licenses: " + callback.getResult(), true);
            steamClient.disconnect();
            return;
        }
        download(callback.getLicenseList());
    }

    private void download(List<License> licenses) {
        if (!installDir.isDirectory() && !installDir.mkdirs()) {
            fail("Cannot create " + installDir, false);
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
        DepotDownloader downloader = new DepotDownloader(steamClient, licenses);
        try {
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
            // Poll instead of awaitCompletion(): closing the downloader on cancel does not
            // complete its future, so a plain join() would never return.
            while (!cancelled) {
                try {
                    downloader.getCompletion().get(1, TimeUnit.SECONDS);
                    break;
                } catch (TimeoutException stillRunning) {
                    // keep waiting
                }
            }
        } catch (Exception e) {
            failure.complete(e);
        } finally {
            downloader.close();
            steamUser.logOff();
        }

        if (cancelled) {
            return; // run() reports "paused"; the files on disk are reused on resume
        }
        Throwable error = failure.getNow(null);
        if (error != null) {
            fail("Download failed: " + describe(error)
                    + "\n\nMake sure this Steam account owns Command & Conquer Generals - Zero Hour.", true);
        } else if (!GameDataPaths.isGameDataDir(installDir)) {
            fail("The download finished but " + GameDataPaths.MARKER_FILE
                    + " is missing. Does this account own Zero Hour?", false);
        } else {
            finished = true;
            listener.onFinished(installDir);
        }
    }

    private void fail(String message, boolean resumable) {
        if (!finished) {
            finished = true;
            listener.onFailed(message, resumable);
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
