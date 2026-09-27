package com.generalsx.generalszh;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Remembers the Steam sign-in so the user does not have to log in (and pass Steam Guard)
 * again, and so an interrupted download can resume without any UI.
 *
 * Only the account name and Steam's refresh token are kept, never the password. The token
 * is encrypted with an AES-GCM key that lives in the Android Keystore (it cannot be read out
 * of the device), and the ciphertext is stored in the app's private preferences. Signing
 * out, or Steam rejecting the token, deletes it.
 */
final class SteamLoginStore {
    private static final String TAG = "GeneralsX";
    private static final String PREFS = "steam_login";
    private static final String KEY_ACCOUNT = "account";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_IV = "iv";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "generalszh_steam_login";
    private static final int GCM_TAG_BITS = 128;

    static final class Login {
        final String accountName;
        final String refreshToken;

        Login(String accountName, String refreshToken) {
            this.accountName = accountName;
            this.refreshToken = refreshToken;
        }
    }

    private SteamLoginStore() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static SecretKey key() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return ((KeyStore.SecretKeyEntry) keyStore.getEntry(KEY_ALIAS, null)).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }

    static void save(Context context, String accountName, String refreshToken) {
        try {
            // No provider named: SteamGameDownloader puts BouncyCastle first, but JCA's delayed
            // provider selection picks, at init(), the provider that accepts the Keystore key.
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] encrypted = cipher.doFinal(refreshToken.getBytes(StandardCharsets.UTF_8));
            prefs(context).edit()
                    .putString(KEY_ACCOUNT, accountName)
                    .putString(KEY_TOKEN, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                    .putString(KEY_IV, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                    .apply();
        } catch (Exception e) {
            // Not fatal: the user just has to sign in again next time.
            Log.w(TAG, "Could not save the Steam login", e);
        }
    }

    /** The saved login, or null when there is none (or it can no longer be decrypted). */
    static Login load(Context context) {
        SharedPreferences p = prefs(context);
        String account = p.getString(KEY_ACCOUNT, null);
        String token = p.getString(KEY_TOKEN, null);
        String iv = p.getString(KEY_IV, null);
        if (account == null || token == null || iv == null) {
            return null;
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(),
                    new GCMParameterSpec(GCM_TAG_BITS, Base64.decode(iv, Base64.NO_WRAP)));
            byte[] plain = cipher.doFinal(Base64.decode(token, Base64.NO_WRAP));
            return new Login(account, new String(plain, StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(TAG, "Saved Steam login is unreadable; discarding it", e);
            clear(context);
            return null;
        }
    }

    static String savedAccountName(Context context) {
        return prefs(context).getString(KEY_ACCOUNT, null);
    }

    static void clear(Context context) {
        prefs(context).edit().clear().apply();
    }
}
