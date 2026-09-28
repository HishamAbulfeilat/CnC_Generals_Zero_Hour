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
 *
 * GeneralsX @feature HishamAbulfeilat 28/09/2026 A second row of quick commands runs through
 * the engine (nativeQuickCommand, SDL3GameEngine.cpp) rather than hotkeys, whose letters
 * depend on the game's language: Army (select every combat unit), Army attack (select the
 * army, then attack-move: tap the target), Attack-move and Guard for the current selection
 * (tap where), Stop and Deselect (also clears a selected building). With the mobile touch
 * scheme this row stays visible, and "Box" switches one-finger drags from camera to
 * selection box.
 */
final class TouchKeyBar {
    private static final String PREFS = "touch_key_bar";
    private static final String KEY_EXPANDED = "expanded";
    private static final String KEY_HIDDEN = "hidden";
    private static final int IDLE_COLOR = 0x99202020;
    private static final int HELD_COLOR = 0xCCB06000;

    // Must match enum QuickCommand in SDL3GameEngine.cpp.
    private static final int QUICK_SELECT_ARMY = 1;
    private static final int QUICK_ATTACK_MOVE = 2;
    private static final int QUICK_GUARD = 3;
    private static final int QUICK_STOP = 4;
    private static final int QUICK_DESELECT = 5;
    private static final int QUICK_ARMY_ATTACK = 6;

    private static native void nativeQuickCommand(int command);
    private static native void nativeSetBoxSelect(boolean on);

    private final Activity activity;
    private final List<Modifier> modifiers = new ArrayList<>();
    /** Row that addTap/addModifier/addCommand append to. */
    private LinearLayout row;

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

    private TouchKeyBar(Activity activity) {
        this.activity = activity;
    }

    /** Whether the player turned the bar off (Settings, "On-screen keys"). */
    static boolean isHidden(android.content.Context context) {
        return context.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).getBoolean(KEY_HIDDEN, false);
    }

    static void setHidden(android.content.Context context, boolean hidden) {
        context.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).edit()
                .putBoolean(KEY_HIDDEN, hidden).apply();
    }

    /**
     * Adds the bar on top of the game surface in {@code layout} (SDLActivity's RelativeLayout).
     * {@code mobileScheme}: the command row stays visible and gets the Box toggle.
     */
    static TouchKeyBar attach(Activity activity, ViewGroup layout, boolean mobileScheme) {
        TouchKeyBar kb = new TouchKeyBar(activity);

        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.END);

        LinearLayout commands = kb.newRow();
        kb.addCommand("Army", QUICK_SELECT_ARMY);
        kb.addCommand("Army attack", QUICK_ARMY_ATTACK);
        kb.addCommand("Attack-move", QUICK_ATTACK_MOVE);
        kb.addCommand("Guard", QUICK_GUARD);
        kb.addCommand("Stop", QUICK_STOP);
        kb.addCommand("Deselect", QUICK_DESELECT);
        if (mobileScheme) {
            kb.addBoxToggle();
        }

        LinearLayout keys = kb.newRow();
        kb.addTap("Menu", KeyEvent.KEYCODE_ESCAPE);
        kb.addModifier("Shift", KeyEvent.KEYCODE_SHIFT_LEFT);
        kb.addModifier("Ctrl", KeyEvent.KEYCODE_CTRL_LEFT);
        kb.addModifier("Alt", KeyEvent.KEYCODE_ALT_LEFT);
        kb.addTap("Event", KeyEvent.KEYCODE_SPACE);
        for (int i = 1; i <= 5; i++) {
            kb.addTap(String.valueOf(i), KeyEvent.KEYCODE_0 + i);
        }
        TextView toggle = kb.button("Keys");
        keys.addView(toggle, 0);

        SharedPreferences prefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
        boolean expanded = prefs.getBoolean(KEY_EXPANDED, false);
        // Collapsed: only the "Keys" button (plus the command row in the mobile scheme).
        kb.showExpanded(keys, commands, toggle, expanded, mobileScheme);
        toggle.setOnClickListener(v -> {
            boolean expand = !prefs.getBoolean(KEY_EXPANDED, false);
            if (!expand) {
                kb.releaseModifiers();
            }
            kb.showExpanded(keys, commands, toggle, expand, mobileScheme);
            prefs.edit().putBoolean(KEY_EXPANDED, expand).apply();
        });

        column.addView(commands);
        column.addView(keys);

        RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        lp.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
        lp.setMargins(0, kb.dp(6), kb.dp(6), 0);
        layout.addView(column, lp);
        return kb;
    }

    private void showExpanded(LinearLayout keys, LinearLayout commands, TextView toggle,
                              boolean expanded, boolean mobileScheme) {
        for (int i = 0; i < keys.getChildCount(); i++) {
            View child = keys.getChildAt(i);
            child.setVisibility(expanded || child == toggle ? View.VISIBLE : View.GONE);
        }
        commands.setVisibility(expanded || mobileScheme ? View.VISIBLE : View.GONE);
    }

    private LinearLayout newRow() {
        row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END);
        row.setPadding(0, 0, 0, dp(4));
        return row;
    }

    private void addCommand(String label, int command) {
        TextView b = button(label);
        b.setOnClickListener(v -> nativeQuickCommand(command));
        row.addView(b);
    }

    private void addBoxToggle() {
        TextView b = button("Box");
        boolean[] on = { false };
        b.setOnClickListener(v -> {
            on[0] = !on[0];
            nativeSetBoxSelect(on[0]);
            b.setBackground(background(on[0] ? HELD_COLOR : IDLE_COLOR));
        });
        row.addView(b);
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
        row.addView(b);
    }

    private void addModifier(String label, int keyCode) {
        TextView b = button(label);
        Modifier m = new Modifier(keyCode, b);
        b.setOnClickListener(v -> m.set(!m.held));
        modifiers.add(m);
        row.addView(b);
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
