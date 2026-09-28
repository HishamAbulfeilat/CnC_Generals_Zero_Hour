package com.generalsx.generalszh;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads C&C Online's "servserv" files, the same ones the game requests before going online
 * (MainMenuUtils.cpp / urlBuilder.cpp, redirected to C&C Online by OnlineServiceHosts.h):
 * the message of the day, the online config and the game and map-pack patch lists for the
 * retail 1.04 version numbers.
 *
 * Patch-list entries ("patch <mandatory> <url>") that point at a .zip or .big over HTTP(S)
 * can be installed as a mod; Windows installers (.exe) cannot run on Android and are only
 * listed.
 */
final class CncOnlineFiles {
    /**
     * The retail servserv URL, fetched through C&C Online's HTTP server as a proxy: the service
     * serves these files for the retail host name (what the PC launcher's DNS redirect sends),
     * and answers HTTP 404 when asked for them as http.server.cnc-online.net. Same routing as
     * the game (MainMenuUtils.cpp routeServservRequest).
     */
    private static final String RETAIL_BASE = "http://servserv.generals.ea.com/servserv/GeneralsZH/";
    private static final String SERVICE_HOST = "http.server.cnc-online.net";
    /** Fallback: the same tree addressed to the service directly. */
    private static final String DIRECT_BASE = "http://" + SERVICE_HOST + "/servserv/GeneralsZH/";

    static final class Entry {
        final String url;
        final boolean mandatory;

        Entry(String url, boolean mandatory) {
            this.url = url;
            this.mandatory = mandatory;
        }

        boolean installable() {
            String u = url.toLowerCase(Locale.ROOT);
            return (u.startsWith("http://") || u.startsWith("https://"))
                    && (u.endsWith(".zip") || u.endsWith(".big"));
        }

        String fileName() {
            int slash = url.lastIndexOf('/');
            return slash >= 0 ? url.substring(slash + 1) : url;
        }
    }

    static final class Result {
        String motd;
        String motdError;
        boolean configReachable;
        /** Number of patch lists (game, map pack) that were read; 0 means "unknown". */
        int patchListsRead;
        final List<Entry> entries = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
    }

    private CncOnlineFiles() {}

    static Result fetch() {
        Result r = new Result();
        try {
            r.motd = get("MOTD-english.txt").trim();
        } catch (IOException e) {
            r.motdError = e.getMessage();
        }
        try {
            get("config.txt");
            r.configReachable = true;
        } catch (IOException e) {
            r.errors.add("config.txt: " + e.getMessage());
        }
        // Retail 1.04 registry values (Version 0x00010004, MapPackVersion 0x00010000).
        readPatchList(r, "english-" + 0x00010004 + ".txt");
        readPatchList(r, "maps-" + 0x00010000 + ".txt");
        return r;
    }

    private static void readPatchList(Result r, String name) {
        String body;
        try {
            body = get(name);
        } catch (IOException e) {
            r.errors.add(name + ": " + e.getMessage());
            return;
        }
        r.patchListsRead++;
        for (String line : body.split("\r?\n")) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length >= 3 && parts[0].equals("patch")) {
                r.entries.add(new Entry(parts[2], !parts[1].equals("0")));
            }
        }
    }

    /** Fetches a servserv file the way the game does, then directly if that fails. */
    private static String get(String name) throws IOException {
        Proxy service = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(SERVICE_HOST, 80));
        try {
            return ModCatalog.read(ModCatalog.open(new URL(RETAIL_BASE + name), service));
        } catch (IOException viaRetailHost) {
            try {
                return ModCatalog.get(DIRECT_BASE + name);
            } catch (IOException direct) {
                throw new IOException(viaRetailHost.getMessage() + "; direct: " + direct.getMessage(), direct);
            }
        }
    }
}
