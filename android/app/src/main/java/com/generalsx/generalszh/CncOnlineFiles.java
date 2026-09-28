package com.generalsx.generalszh;

import java.io.IOException;
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
    private static final String BASE =
            "http://http.server.cnc-online.net/servserv/GeneralsZH/";

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
        final List<Entry> entries = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
    }

    private CncOnlineFiles() {}

    static Result fetch() {
        Result r = new Result();
        try {
            r.motd = ModCatalog.get(BASE + "MOTD-english.txt").trim();
        } catch (IOException e) {
            r.motdError = e.getMessage();
        }
        try {
            ModCatalog.get(BASE + "config.txt");
            r.configReachable = true;
        } catch (IOException e) {
            r.errors.add("config.txt: " + e.getMessage());
        }
        // Retail 1.04 registry values (Version 0x00010004, MapPackVersion 0x00010000).
        readPatchList(r, BASE + "english-" + 0x00010004 + ".txt");
        readPatchList(r, BASE + "maps-" + 0x00010000 + ".txt");
        return r;
    }

    private static void readPatchList(Result r, String url) {
        String body;
        try {
            body = ModCatalog.get(url);
        } catch (IOException e) {
            r.errors.add(url.substring(url.lastIndexOf('/') + 1) + ": " + e.getMessage());
            return;
        }
        for (String line : body.split("\r?\n")) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length >= 3 && parts[0].equals("patch")) {
                r.entries.add(new Entry(parts[2], !parts[1].equals("0")));
            }
        }
    }
}
