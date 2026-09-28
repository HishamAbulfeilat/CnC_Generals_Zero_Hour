package com.generalsx.generalszh;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import org.libsdl.app.SDLActivity;

/**
 * On-screen keys for what touch gestures cannot express (see docs/port/TOUCH_CONTROLS.md,
 * "no keyboard-dependent controls"): the in-game menu (Esc), the Shift/Ctrl/Alt modifiers
 * (add to selection, force fire / assign control group, waypoints), select all units (Q),
 * jump to the last event (Space) and control groups 1-5.
 *
 * Modifiers latch: tap once to hold, again to release, so they combine with a tap on the
 * map or on a group number (Ctrl + 1 assigns group 1). Keys go through SDL's own Android
 * key path (SDLActivity.onNativeKeyDown/Up), exactly like a hardware keyboard.
 *
 * The bar sits in the top-right corner, collapsed to a single "Keys" button by default;
 * whether it is open is remembered.
 */
final class TouchKeyBar {
    private static final String PREFS = "touch_key_bar";
    private static final String KEY_EXPANDED = "expanded";
    private static final String KEY_HIDDEN = "hidden";
    private static final int IDLE_COLOR = 0x99202020;
    private static final int HELD_COLOR = 0xCCB06000;

    private final Activity activity;
    private final LinearLayout keys;
    private final List<Modifier> modifiers = new ArrayList<>();

    private final class Modifier {
        final int keyCode;
        final TextView view;
        boolean held;

        Modifier(int keyCode, TextView view) {
            this.keyCode = keyCode;
            this.view = view;
        }

        void set(boolean hold) {
            if (held == hold) {
                return;
            }
            held = hold;
            if (hold) {
                SDLActivity.onNativeKeyDown(keyCode);
            } else {
                SDLActivity.onNativeKeyUp(keyCode);
            }
            view.setBackground(background(hold ? HELD_COLOR : IDLE_COLOR));
        }
    }

    private TouchKeyBar(Activity activity, LinearLayout keys) {
        this.activity = activity;
        this.keys = keys;
    }

    /** Whether the player turned the bar off (Settings, "On-screen keys"). */
    static boolean isHidden(android.content.Context context) {
        return context.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).getBoolean(KEY_HIDDEN, false);
    }

    static void setHidden(android.content.Context context, boolean hidden) {
        context.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).edit()
                .putBoolean(KEY_HIDDEN, hidden).apply();
    }

    /** Adds the bar on top of the game surface in {@code layout} (SDLActivity's RelativeLayout). */
    static TouchKeyBar attach(Activity activity, ViewGroup layout) {
        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout keys = new LinearLayout(activity);
        keys.setOrientation(LinearLayout.HORIZONTAL);
        TouchKeyBar kb = new TouchKeyBar(activity, keys);

        kb.addTap("Menu", KeyEvent.KEYCODE_ESCAPE);
        kb.addModifier("Shift", KeyEvent.KEYCODE_SHIFT_LEFT);
        kb.addModifier("Ctrl", KeyEvent.KEYCODE_CTRL_LEFT);
        kb.addModifier("Alt", KeyEvent.KEYCODE_ALT_LEFT);
        kb.addTap("All units", KeyEvent.KEYCODE_Q);
        kb.addTap("Event", KeyEvent.KEYCODE_SPACE);
        for (int i = 1; i <= 5; i++) {
            kb.addTap(String.valueOf(i), KeyEvent.KEYCODE_0 + i);
        }

        SharedPreferences prefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
        keys.setVisibility(prefs.getBoolean(KEY_EXPANDED, false) ? View.VISIBLE : View.GONE);
        TextView toggle = kb.button("Keys");
        toggle.setOnClickListener(v -> {
            boolean expand = keys.getVisibility() != View.VISIBLE;
            if (!expand) {
                kb.releaseModifiers();
            }
            keys.setVisibility(expand ? View.VISIBLE : View.GONE);
            prefs.edit().putBoolean(KEY_EXPANDED, expand).apply();
        });

        bar.addView(keys);
        bar.addView(toggle);

        RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        lp.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
        lp.setMargins(0, kb.dp(6), kb.dp(6), 0);
        layout.addView(bar, lp);
        return kb;
    }

    /** Releases held modifiers, e.g. when the game goes to the background. */
    void releaseModifiers() {
        for (Modifier m : modifiers) {
            m.set(false);
        }
    }

    private void addTap(String label, int keyCode) {
        TextView b = button(label);
        b.setOnClickListener(v -> {
            SDLActivity.onNativeKeyDown(keyCode);
            SDLActivity.onNativeKeyUp(keyCode);
        });
        keys.addView(b);
    }

    private void addModifier(String label, int keyCode) {
        TextView b = button(label);
        Modifier m = new Modifier(keyCode, b);
        b.setOnClickListener(v -> m.set(!m.held));
        modifiers.add(m);
        keys.addView(b);
    }

    private TextView button(String label) {
        TextView b = new TextView(activity);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setGravity(Gravity.CENTER);
        b.setMinWidth(dp(40));
        b.setMinHeight(dp(34));
        b.setPadding(dp(8), dp(4), dp(8), dp(4));
        b.setBackground(background(IDLE_COLOR));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(2), 0, dp(2), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private GradientDrawable background(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(6));
        d.setStroke(dp(1), 0x66FFFFFF);
        return d;
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
