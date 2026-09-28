package com.generalsx.generalszh;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The game's own settings file, Options.ini in the engine's user data folder
 * ($XDG_DATA_HOME/GeneralsX/GeneralsZH/, XDG_DATA_HOME being the app's files dir; see
 * GlobalData::getPath_UserData). Same "Key = Value" format as UserPreferences::write, and
 * every key the game wrote is kept, so settings changed here and in the in-game Options
 * menu stay in one place.
 */
final class GameOptions {
    private final File file;
    private final Map<String, String> values = new LinkedHashMap<>();

    private GameOptions(File file) {
        this.file = file;
    }

    static File userDataDir(Context context) {
        return new File(context.getFilesDir(), "GeneralsX/GeneralsZH");
    }

    static GameOptions load(Context context) throws IOException {
        GameOptions o = new GameOptions(new File(userDataDir(context), "Options.ini"));
        if (o.file.isFile()) {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    new FileInputStream(o.file), StandardCharsets.ISO_8859_1))) {
                String line;
                while ((line = r.readLine()) != null) {
                    int eq = line.indexOf('=');
                    if (eq > 0) {
                        o.values.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                    }
                }
            }
        }
        return o;
    }

    String get(String key) {
        return values.get(key);
    }

    /** Sets a value; null removes the key so the game uses its default. */
    void set(String key, String value) {
        if (value == null) {
            values.remove(key);
        } else {
            values.put(key, value);
        }
    }

    void save() throws IOException {
        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Cannot create " + dir);
        }
        File tmp = new File(file.getPath() + ".tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.ISO_8859_1)) {
            for (Map.Entry<String, String> e : values.entrySet()) {
                w.write(e.getKey() + " = " + e.getValue() + "\n");
            }
        }
        if (!tmp.renameTo(file)) {
            throw new IOException("Cannot write " + file);
        }
    }
}
