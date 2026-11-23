package com.mdmuntakim.linux41;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;

/** Landscape, immersive PC-style front end for the Linux 4.1.52 guest. */
public final class MainActivity extends Activity implements VmController.Listener,
        RfbClient.Listener, KeyboardPanel.Listener {

    private FrameLayout root;
    private VncView view;
    private TextView splash;
    private TextView error;
    private Button retry;
    private KeyboardPanel keyboard;

    private VmController vm;
    private volatile RfbClient rfb;
    private boolean ctrl;
    private boolean alt;
    private boolean shift;

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("vm", MODE_PRIVATE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
        setImmersive();
        vm = new VmController(this);
        startVm();
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        view = new VncView(this);
        view.setTrackpad(prefs.getBoolean("trackpad", false));
        view.setMouseSpeed(prefs.getInt("mouseSpeed", 100) / 100f);
        view.setDisplayScale(prefs.getInt("displayScale", 100) / 100f);
        root.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        splash = new TextView(this);
        splash.setText("Linux 4.1.52\n\nstarting QEMU / TCG ...");
        splash.setTextColor(0xffe0e0e0);
        splash.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        splash.setGravity(Gravity.CENTER);
        splash.setBackgroundColor(0xff101010);
        root.addView(splash, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        error = new TextView(this);
        error.setTextColor(0xffff8888);
        error.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        error.setVisibility(View.GONE);
        error.setPadding(dp(16), dp(80), dp(16), dp(16));
        root.addView(error, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        retry = new Button(this);
        retry.setText("Retry");
        retry.setVisibility(View.GONE);
        FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        rlp.bottomMargin = dp(24);
        retry.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startVm(); }
        });
        root.addView(retry, rlp);

        root.addView(buildTopBar(), topParams());
        keyboard = new KeyboardPanel(this, this);
        keyboard.setVisibility(View.GONE);
        FrameLayout.LayoutParams klp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        klp.gravity = Gravity.BOTTOM;
        root.addView(keyboard, klp);

        setContentView(root);
        view.requestFocus();
    }

    private FrameLayout.LayoutParams topParams() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP;
        return lp;
    }

    private View buildTopBar() {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setBackgroundColor(0xaa000000);
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(4), dp(2), dp(4), dp(2));

        bar.addView(button("Settings", new Runnable() {
            @Override public void run() { showSettings(); }
        }));
        bar.addView(button("Keyboard", new Runnable() {
            @Override public void run() {
                keyboard.setVisibility(keyboard.getVisibility() == View.VISIBLE
                        ? View.GONE : View.VISIBLE);
            }
        }));
        bar.addView(button("R-Click", new Runnable() {
            @Override public void run() { view.clickRight(); }
        }));
        bar.addView(toggle("Ctrl", new Runnable() {
            @Override public void run() { ctrl = !ctrl; }
        }));
        bar.addView(toggle("Alt", new Runnable() {
            @Override public void run() { alt = !alt; }
        }));
        bar.addView(button("Esc", new Runnable() {
            @Override public void run() { tap(Keys.ESCAPE); }
        }));
        bar.addView(button("Tab", new Runnable() {
            @Override public void run() { tap(Keys.TAB); }
        }));
        bar.addView(button("Enter", new Runnable() {
            @Override public void run() { tap(Keys.ENTER); }
        }));
        bar.addView(button("←", new Runnable() {
            @Override public void run() { tap(Keys.LEFT); }
        }));
        bar.addView(button("↑", new Runnable() {
            @Override public void run() { tap(Keys.UP); }
        }));
        bar.addView(button("↓", new Runnable() {
            @Override public void run() { tap(Keys.DOWN); }
        }));
        bar.addView(button("→", new Runnable() {
            @Override public void run() { tap(Keys.RIGHT); }
        }));

        scroll.addView(bar, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private Button button(String label, final Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(0x33ffffff);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(8), 0, dp(8), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
        lp.setMargins(dp(1), 0, dp(1), 0);
        b.setLayoutParams(lp);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { action.run(); }
        });
        return b;
    }

    private Button toggle(final String label, final Runnable action) {
        final Button b = button(label, new Runnable() {
            @Override public void run() {
                action.run();
                boolean on = "Ctrl".equals(label) ? ctrl : alt;
                b.setBackgroundColor(on ? 0xff4caf50 : 0x33ffffff);
            }
        });
        return b;
    }

    private void startVm() {
        RfbClient old = rfb;
        rfb = null;
        if (old != null) old.close();
        if (vm != null) vm.stop();

        splash.setVisibility(View.VISIBLE);
        splash.setText("Linux 4.1.52\n\nstarting QEMU / TCG ...");
        error.setVisibility(View.GONE);
        retry.setVisibility(View.GONE);

        vm = new VmController(this);
        vm.start(this);
    }

    private void connectVnc() {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                final RfbClient c = new RfbClient("127.0.0.1", VmController.VNC_PORT,
                        MainActivity.this);
                try {
                    c.connect();
                    rfb = c;
                    view.setClient(c);
                    c.start();
                } catch (IOException e) {
                    c.close();
                    onError(e);
                }
            }
        }, "vnc-connect");
        t.setDaemon(true);
        t.start();
    }

    // ---- VM callbacks (background thread) ----

    @Override
    public void onReady() {
        runOnUiThread(new Runnable() {
            @Override public void run() { connectVnc(); }
        });
    }

    @Override
    public void onError(final String message) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                splash.setVisibility(View.GONE);
                error.setText("The VM did not start.\n\n" + message);
                error.setVisibility(View.VISIBLE);
                retry.setVisibility(View.VISIBLE);
            }
        });
    }

    // ---- RFB callbacks (reader thread) ----

    @Override
    public void onConnected(final int width, final int height) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                splash.setVisibility(View.GONE);
                error.setVisibility(View.GONE);
                retry.setVisibility(View.GONE);
                view.onConnected(width, height);
            }
        });
    }

    @Override
    public void onFrame(int[] argb, int width, int height) {
        view.onFrame(argb, width, height);
    }

    @Override
    public void onError(final Throwable t) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                error.setText("VNC connection lost: " + t.getMessage()
                        + "\n\nTap Retry to restart the VM.");
                error.setVisibility(View.VISIBLE);
                retry.setVisibility(View.VISIBLE);
            }
        });
    }

    // ---- input ----

    @Override
    public void onKey(int keysym) {
        tap(keysym);
    }

    private void tap(int keysym) {
        RfbClient c = rfb;
        if (c == null) return;
        if (ctrl) c.key(Keys.CONTROL, true);
        if (alt) c.key(Keys.ALT, true);
        if (shift) c.key(Keys.SHIFT, true);
        c.key(keysym, true);
        c.key(keysym, false);
        if (shift) c.key(Keys.SHIFT, false);
        if (alt) c.key(Keys.ALT, false);
        if (ctrl) c.key(Keys.CONTROL, false);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        RfbClient c = rfb;
        if (c != null && (event.getAction() == KeyEvent.ACTION_DOWN
                || event.getAction() == KeyEvent.ACTION_UP)) {
            int keyCode = event.getKeyCode();
            if (keyCode == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event);
            int keysym = Keys.forKeyCode(keyCode);
            int unicode = event.getUnicodeChar();
            if (event.isCtrlPressed() || event.isAltPressed()) {
                // keep the letter keysym; the modifier is sent by the OS key event
            } else if (unicode >= 0x20 && unicode < 0x7f) {
                keysym = unicode;
            }
            if (keysym != 0) {
                c.key(keysym, event.getAction() == KeyEvent.ACTION_DOWN);
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    // ---- settings ----

    private void showSettings() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        form.setPadding(p, p, p, p);

        EditText ram = field("RAM MiB (128-4096)", prefs.getInt("ramMb", 512));
        EditText cpus = field("vCPU count (1-8)", prefs.getInt("cpus", 2));
        EditText disk = field("Data disk MiB (256-8192, new disk only)",
                prefs.getInt("diskMb", 1024));
        EditText scale = field("Display scale % (50-100)",
                prefs.getInt("displayScale", 100));
        EditText speed = field("Mouse speed % (50-300)",
                prefs.getInt("mouseSpeed", 100));
        CheckBox trackpad = new CheckBox(this);
        trackpad.setText("Trackpad mode (relative mouse)");
        trackpad.setChecked(prefs.getBoolean("trackpad", false));

        form.addView(ram);
        form.addView(cpus);
        form.addView(disk);
        form.addView(scale);
        form.addView(speed);
        form.addView(trackpad);

        TextView note = new TextView(this);
        note.setText("\nChanges restart the VM. The guest disk keeps its data; "
                + "'Reset disk' deletes data.img and the guest formats it again on boot.");
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        note.setTextColor(0xff888888);
        form.addView(note);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);

        new AlertDialog.Builder(this)
                .setTitle("Linux4.1 settings")
                .setView(scroll)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        SharedPreferences.Editor e = prefs.edit();
                        e.putInt("ramMb", clamp(parse(ram, 512), 128, 4096));
                        e.putInt("cpus", clamp(parse(cpus, 2), 1, 8));
                        e.putInt("diskMb", clamp(parse(disk, 1024), 256, 8192));
                        e.putInt("displayScale", clamp(parse(scale, 100), 50, 100));
                        e.putInt("mouseSpeed", clamp(parse(speed, 100), 50, 300));
                        e.putBoolean("trackpad", trackpad.isChecked());
                        e.apply();
                        view.setTrackpad(trackpad.isChecked());
                        view.setMouseSpeed(prefs.getInt("mouseSpeed", 100) / 100f);
                        view.setDisplayScale(prefs.getInt("displayScale", 100) / 100f);
                        startVm();
                    }
                })
                .setNeutralButton("Reset disk", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        vm.resetDisk();
                        startVm();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private EditText field(String hint, int value) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setText(Integer.toString(value));
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        return e;
    }

    private static int parse(EditText e, int fallback) {
        try {
            return Integer.parseInt(e.getText().toString().trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    // ---- lifecycle ----

    private void setImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        setImmersive();
    }

    @Override
    protected void onDestroy() {
        RfbClient c = rfb;
        rfb = null;
        if (c != null) c.close();
        if (vm != null) vm.stop();
        super.onDestroy();
    }
}
