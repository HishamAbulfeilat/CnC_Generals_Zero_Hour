package com.generalsx.generalszh;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Mods the app knows about.
 *
 * The Super Patch is downloaded directly: TheSuperHackers publish no builds, so
 * .github/workflows/build-super-patch.yml builds it and publishes it as a
 * "superpatch-<commit>" release of this repository.
 *
 * The large community mods below are data-only (.big files), which is what this engine port
 * can load, but they are distributed through their own sites with their own terms, so the
 * app links to them and the user imports the download with "Add mod".
 */
final class ModCatalog {
    static final String SUPER_PATCH_ID = "super-patch";
    private static final String RELEASES_API =
            "https://api.github.com/repos/" + AppUpdater.REPO + "/releases?per_page=30";
    private static final String SUPER_PATCH_TAG_PREFIX = "superpatch-";
    private static final String SUPER_PATCH_ASSET = "SuperPatch-FullEnglish.zip";

    static final class KnownMod {
        final String name;
        final String description;
        final String url;

        KnownMod(String name, String description, String url) {
            this.name = name;
            this.description = description;
            this.url = url;
        }
    }

    /** Community mods to download and import manually; none tested on Android yet. */
    static final KnownMod[] KNOWN_MODS = {
            new KnownMod("Contra",
                    "Large expansion: new units, generals and effects for Zero Hour.",
                    "https://www.moddb.com/mods/contra"),
            new KnownMod("ShockWave",
                    "Expansion with new generals, units and reworked factions.",
                    "https://www.moddb.com/mods/shockwave"),
            new KnownMod("Rise of the Reds",
                    "Total conversion adding the ECA and Russia factions.",
                    "https://www.moddb.com/mods/rise-of-the-reds"),
    };

    static final class Download {
        final String version;
        final String url;
        final long size;

        Download(String version, String url, long size) {
            this.version = version;
            this.url = url;
            this.size = size;
        }
    }

    private ModCatalog() {}

    /** The newest Super Patch build published for the app, or null if none exists yet. */
    static Download latestSuperPatch() throws IOException {
        JSONArray releases;
        try {
            releases = new JSONArray(get(RELEASES_API));
            for (int i = 0; i < releases.length(); i++) {
                JSONObject r = releases.getJSONObject(i);
                String tag = r.getString("tag_name");
                if (!tag.startsWith(SUPER_PATCH_TAG_PREFIX)) {
                    continue;
                }
                JSONArray assets = r.getJSONArray("assets");
                for (int j = 0; j < assets.length(); j++) {
                    JSONObject a = assets.getJSONObject(j);
                    if (SUPER_PATCH_ASSET.equals(a.getString("name"))) {
                        return new Download(tag.substring(SUPER_PATCH_TAG_PREFIX.length()),
                                a.getString("browser_download_url"), a.getLong("size"));
                    }
                }
            }
            return null;
        } catch (JSONException e) {
            throw new IOException("Unexpected answer from GitHub: " + e.getMessage(), e);
        }
    }

    static String get(String url) throws IOException {
        return read(open(url));
    }

    /** Reads a response body as UTF-8 and disconnects. */
    static String read(HttpURLConnection conn) throws IOException {
        try (InputStream in = conn.getInputStream()) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[16 * 1024];
            int n;
            while ((n = in.read(b)) > 0) {
                buf.write(b, 0, n);
            }
            return buf.toString(StandardCharsets.UTF_8.name());
        } finally {
            conn.disconnect();
        }
    }

    static HttpURLConnection open(String url) throws IOException {
        return open(new URL(url), Proxy.NO_PROXY);
    }

    static HttpURLConnection open(URL url, Proxy proxy) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) url.openConnection(proxy);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "GeneralsZH-Android");
        int code = conn.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            conn.disconnect();
            throw new IOException("HTTP " + code + " from " + url.getHost());
        }
        return conn;
    }
}
