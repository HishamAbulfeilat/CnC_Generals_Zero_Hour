package com.generalsx.generalszh;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Game settings, edited before launch. Everything except the on-screen keys lives in the
 * game's own Options.ini (GameOptions), the same file the in-game Options menu saves, so
 * either place can change them. "Default" removes the key so the game picks its own value.
 */
public class SettingsActivity extends Activity {
    private static final int BG = Color.rgb(18, 20, 24);

    /** One choice of a setting: what the player sees and the Options.ini value (null = default). */
    private static final class Choice {
        final String label;
        final String value;

        Choice(String label, String value) {
            this.label = label;
            this.value = value;
        }
    }

    private GameOptions options;
    private LinearLayout rows;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        try {
            options = GameOptions.load(this);
        } catch (IOException e) {
            status.setText("Could not read the game settings: " + e.getMessage());
            return;
        }
        refresh();
    }

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

        root.addView(text("Game settings", 22, Color.WHITE, true));
        TextView note = text("Applied the next time the game starts. The in-game Options menu"
                + " changes the same settings.", 14, Color.LTGRAY, false);
        note.setPadding(0, dp(6), 0, dp(12));
        root.addView(note);

        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        root.addView(rows);

        root.addView(button("Reset all to defaults", v -> resetAll()));
        status = text("", 14, Color.LTGRAY, false);
        status.setPadding(0, dp(8), 0, 0);
        root.addView(status);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.addView(root);
        setContentView(scroll);
    }

    private void refresh() {
        rows.removeAllViews();
        addRow("Resolution",
                "Lower resolutions run faster. 16:9 and 4:3 show black bars at the sides.",
                "Resolution", resolutionChoices());
        addRow("Graphics detail", "Effects, shadows and particles.", "StaticGameLOD", new Choice[] {
                new Choice("Default (game picks)", null),
                new Choice("Low", "Low"),
                new Choice("Medium", "Medium"),
                new Choice("High", "High"),
                new Choice("Very high", "VeryHigh"),
        });
        addRow("Texture quality", null, "TextureReduction", new Choice[] {
                new Choice("Default", null),
                new Choice("Full", "0"),
                new Choice("Half (less memory)", "1"),
                new Choice("Quarter (least memory)", "2"),
        });
        addRow("Camera zoom-out",
                "How far the camera can pull back. Raise it if the battlefield feels zoomed in.",
                "MaxCameraHeight", new Choice[] {
                        new Choice("Default", null),
                        new Choice("Far (400)", "400"),
                        new Choice("Farther (500)", "500"),
                        new Choice("Very far (650)", "650"),
                        new Choice("Maximum (800)", "800"),
                });
        addRow("Scroll speed", null, "ScrollFactor", new Choice[] {
                new Choice("Default", null),
                new Choice("Slow", "25"),
                new Choice("Normal", "50"),
                new Choice("Fast", "75"),
                new Choice("Very fast", "100"),
        });
        addRow("Frame rate limit", "Limiting saves battery and heat.", "FPSLimit", new Choice[] {
                new Choice("Default (on)", null),
                new Choice("On", "yes"),
                new Choice("Off", "no"),
        });
        addKeyBarRow();
    }

    /** The panel's own shape at a few heights, plus 16:9 and 4:3 (see W3DDisplay.cpp). */
    private Choice[] resolutionChoices() {
        int w;
        int h;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Rect bounds = getWindowManager().getMaximumWindowMetrics().getBounds();
            w = bounds.width();
            h = bounds.height();
        } else {
            DisplayMetrics dm = new DisplayMetrics();
            getDisplay().getRealMetrics(dm);
            w = dm.widthPixels;
            h = dm.heightPixels;
        }
        int panelW = Math.max(w, h);
        int panelH = Math.min(w, h);
        List<Choice> list = new ArrayList<>();
        list.add(new Choice("Full screen, native (" + panelW + " x " + panelH + ")", null));
        int[] heights = { 1080, 900, 720 };
        for (int ht : heights) {
            if (ht < panelH) {
                int wd = (int) ((long) ht * panelW / panelH) & ~1;
                list.add(new Choice("Full screen, " + wd + " x " + ht, wd + " " + ht));
            }
        }
        for (int ht : new int[] { panelH, 1080, 720 }) {
            if (ht <= panelH) {
                int wd = ht * 16 / 9;
                String label = "16:9, " + wd + " x " + ht;
                if (!containsValue(list, wd + " " + ht)) {
                    list.add(new Choice(label, wd + " " + ht));
                }
            }
        }
        if (panelH >= 768) {
            list.add(new Choice("4:3 classic, 1024 x 768", "1024 768"));
        }
        return list.toArray(new Choice[0]);
    }

    private static boolean containsValue(List<Choice> list, String value) {
        for (Choice c : list) {
            if (value.equals(c.value)) {
                return true;
            }
        }
        return false;
    }

    private void addRow(String title, String help, String key, Choice[] choices) {
        String current = options.get(key);
        String shown = null;
        for (Choice c : choices) {
            if (c.value == null ? current == null : c.value.equalsIgnoreCase(current)) {
                shown = c.label;
            }
        }
        if (shown == null) {
            shown = current + " (set in game)";
        }
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));
        row.addView(text(title, 16, Color.WHITE, true));
        if (help != null) {
            row.addView(text(help, 13, Color.LTGRAY, false));
        }
        String[] labels = new String[choices.length];
        for (int i = 0; i < choices.length; i++) {
            labels[i] = choices[i].label;
        }
        row.addView(button(shown, v -> new AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(labels, (d, which) -> apply(key, choices[which].value))
                .show()));
        rows.addView(row);
    }

    private void addKeyBarRow() {
        boolean hidden = TouchKeyBar.isHidden(this);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));
        row.addView(text("On-screen keys", 16, Color.WHITE, true));
        row.addView(text("The \"Keys\" button in the game's top-right corner (Esc, Shift, Ctrl,"
                + " Alt, groups). Hide it when you play with a keyboard.", 13, Color.LTGRAY, false));
        row.addView(button(hidden ? "Hidden" : "Shown", v -> {
            TouchKeyBar.setHidden(this, !hidden);
            refresh();
        }));
        rows.addView(row);
    }

    private void apply(String key, String value) {
        options.set(key, value);
        save();
    }

    private void resetAll() {
        for (String key : new String[] { "Resolution", "StaticGameLOD", "TextureReduction",
                "MaxCameraHeight", "ScrollFactor", "FPSLimit" }) {
            options.set(key, null);
        }
        TouchKeyBar.setHidden(this, false);
        save();
    }

    private void save() {
        try {
            options.save();
            status.setText("Saved.");
        } catch (IOException e) {
            status.setText("Could not save: " + e.getMessage());
        }
        refresh();
    }
}
