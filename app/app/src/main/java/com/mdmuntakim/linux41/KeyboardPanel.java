package com.mdmuntakim.linux41;

import android.content.Context;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;

/** A compact on-screen keyboard; every key is sent as an X11 keysym over RFB. */
public final class KeyboardPanel extends LinearLayout {

    public interface Listener { void onKey(int keysym); }

    private static final String[] ROWS = {
            "1234567890",
            "qwertyuiop",
            "asdfghjkl",
            "zxcvbnm",
    };

    private final Listener listener;
    private boolean shift;

    public KeyboardPanel(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        setOrientation(VERTICAL);
        setBackgroundColor(0xcc101010);
        int pad = dp(2);
        setPadding(pad, pad, pad, pad);

        for (String row : ROWS) addRow(row);

        LinearLayout bottom = new LinearLayout(context);
        bottom.setOrientation(HORIZONTAL);
        addKey(bottom, "Shift", new Runnable() {
            @Override public void run() {
                shift = !shift;
                setAlphaForShift();
            }
        });
        addKey(bottom, "Space", new Runnable() {
            @Override public void run() { send(' '); }
        });
        addKey(bottom, "Bksp", new Runnable() {
            @Override public void run() { listener.onKey(Keys.BACKSPACE); }
        });
        addKey(bottom, "Tab", new Runnable() {
            @Override public void run() { listener.onKey(Keys.TAB); }
        });
        addKey(bottom, "Esc", new Runnable() {
            @Override public void run() { listener.onKey(Keys.ESCAPE); }
        });
        addKey(bottom, "Enter", new Runnable() {
            @Override public void run() { listener.onKey(Keys.ENTER); }
        });
        addView(bottom, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    private void addRow(String chars) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        for (int i = 0; i < chars.length(); i++) {
            final char c = chars.charAt(i);
            addKey(row, String.valueOf(c), new Runnable() {
                @Override public void run() { send(c); }
            });
        }
        addView(row, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    private void send(char c) {
        if (shift) {
            int sym = Character.isLetter(c) ? Character.toUpperCase(c) : shifted(c);
            listener.onKey(sym);
        } else {
            listener.onKey(c);
        }
    }

    private static int shifted(char c) {
        switch (c) {
            case '1': return '!';
            case '2': return '@';
            case '3': return '#';
            case '4': return '$';
            case '5': return '%';
            case '6': return '^';
            case '7': return '&';
            case '8': return '*';
            case '9': return '(';
            case '0': return ')';
            default: return c;
        }
    }

    private void setAlphaForShift() {
        for (int i = 0; i < getChildCount(); i++) {
            View row = getChildAt(i);
            if (!(row instanceof LinearLayout)) continue;
            LinearLayout l = (LinearLayout) row;
            for (int j = 0; j < l.getChildCount(); j++) {
                View b = l.getChildAt(j);
                if (b instanceof Button && "Shift".contentEquals(((Button) b).getText())) {
                    b.setAlpha(shift ? 1.0f : 0.55f);
                }
            }
        }
    }

    private void addKey(LinearLayout row, String label, final Runnable action) {
        Button b = new Button(getContext());
        b.setText(label);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(0x44ffffff);
        b.setPadding(0, 0, 0, 0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setGravity(Gravity.CENTER);
        b.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { action.run(); }
        });
        LayoutParams lp = new LayoutParams(0, dp(34), 1f);
        lp.setMargins(dp(1), dp(1), dp(1), dp(1));
        row.addView(b, lp);
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }
}
