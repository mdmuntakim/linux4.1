package com.mdmuntakim.linux41;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/** Extracts the pinned guest assets and runs QEMU as an app-owned process. */
public final class VmController {

    public interface Listener {
        void onReady();
        void onError(String message);
    }

    private static final String TAG = "Linux41";
    public static final int VNC_PORT = 5900;

    private final Context context;
    private final SharedPreferences prefs;
    private Process process;
    private Thread thread;
    private volatile boolean stopping;

    public VmController(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = context.getSharedPreferences("vm", Context.MODE_PRIVATE);
    }

    public boolean isRunning() {
        return process != null && process.isAlive();
    }

    public void start(final Listener listener) {
        stop();
        stopping = false;
        thread = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    prepareAssets();
                    launch();
                    waitForVnc();
                    if (!stopping) listener.onReady();
                } catch (Throwable t) {
                    Log.e(TAG, "VM start failed", t);
                    if (!stopping) listener.onError(t.getMessage() == null ? t.toString() : t.getMessage());
                }
            }
        }, "vm-start");
        thread.setDaemon(true);
        thread.start();
    }

    private void prepareAssets() throws IOException {
        File files = context.getFilesDir();
        AssetManager assets = context.getAssets();
        String version = readAsset(assets, "version.txt").trim();
        File stamp = new File(files, "assets.version");
        boolean refresh = !stamp.exists() || !version.equals(readText(stamp).trim());

        File kernel = new File(files, "kernel/bzImage");
        File rootfs = new File(files, "rootfs.img");
        File bios = new File(files, "pc-bios");
        if (refresh || !kernel.exists()) copyAsset(assets, "kernel/bzImage", kernel);
        if (refresh || !rootfs.exists()) copyAsset(assets, "rootfs/rootfs.img", rootfs);
        if (refresh || !bios.isDirectory()) copyAssetDir(assets, "qemu/pc-bios", bios);
        writeText(stamp, version);

        File data = new File(files, "data.img");
        if (!data.exists()) {
            int mb = clamp(prefs.getInt("diskMb", 1024), 256, 8192);
            RandomAccessFile raf = new RandomAccessFile(data, "rw");
            try {
                raf.setLength((long) mb * 1024L * 1024L);
            } finally {
                raf.close();
            }
            Log.i(TAG, "created sparse data disk of " + mb + " MiB");
        }
    }

    private void launch() throws IOException {
        File files = context.getFilesDir();
        File qemu = new File(context.getApplicationInfo().nativeLibraryDir,
                "libqemu-system-x86_64.so");
        if (!qemu.isFile()) {
            throw new IOException("QEMU binary not packaged for this ABI: " + qemu);
        }

        int ram = clamp(prefs.getInt("ramMb", 512), 128, 4096);
        int cpus = clamp(prefs.getInt("cpus", 2), 1, 8);
        int disk = clamp(prefs.getInt("diskMb", 1024), 256, 8192);

        File log = new File(files, "vm.log");
        File serial = new File(files, "serial.log");
        if (serial.length() > 256 * 1024) serial.delete();

        List<String> cmd = new ArrayList<>();
        cmd.add(qemu.getAbsolutePath());
        cmd.add("-L"); cmd.add(new File(files, "pc-bios").getAbsolutePath());
        cmd.add("-name"); cmd.add("Linux4.1");
        cmd.add("-m"); cmd.add(Integer.toString(ram));
        cmd.add("-smp"); cmd.add(Integer.toString(cpus));
        cmd.add("-accel"); cmd.add("tcg,thread=multi,tb-size=64");
        cmd.add("-kernel"); cmd.add(new File(files, "kernel/bzImage").getAbsolutePath());
        cmd.add("-append");
        cmd.add("console=tty0 root=/dev/vda rw rootfstype=ext4 loglevel=4 panic=5");
        cmd.add("-drive");
        cmd.add("file=" + new File(files, "rootfs.img").getAbsolutePath()
                + ",if=virtio,format=raw,cache=writeback");
        cmd.add("-drive");
        cmd.add("file=" + new File(files, "data.img").getAbsolutePath()
                + ",if=virtio,format=raw,cache=writeback");
        cmd.add("-netdev"); cmd.add("user,id=n0");
        cmd.add("-device"); cmd.add("virtio-net-pci,netdev=n0");
        cmd.add("-vga"); cmd.add("std");
        cmd.add("-display"); cmd.add("none");
        cmd.add("-vnc"); cmd.add("127.0.0.1:0");
        cmd.add("-serial"); cmd.add("file:" + serial.getAbsolutePath());
        cmd.add("-monitor"); cmd.add("none");
        cmd.add("-rtc"); cmd.add("base=utc");
        cmd.add("-no-reboot");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(files);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log));
        pb.environment().put("TMPDIR", context.getCacheDir().getAbsolutePath());
        Log.i(TAG, "starting QEMU with " + ram + " MiB RAM, " + cpus + " vCPU, " + disk + " MiB disk");
        process = pb.start();
    }

    private void waitForVnc() throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + 60000;
        while (System.currentTimeMillis() < deadline) {
            if (stopping) return;
            if (!process.isAlive()) {
                throw new IOException("QEMU exited with code " + process.exitValue()
                        + "\n" + tail(new File(context.getFilesDir(), "vm.log")));
            }
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress("127.0.0.1", VNC_PORT), 300);
                return;
            } catch (IOException ignored) {
                // not listening yet
            } finally {
                try { socket.close(); } catch (IOException ignored) { }
            }
            Thread.sleep(200);
        }
        throw new IOException("VNC did not become ready within 60 s");
    }

    public void stop() {
        stopping = true;
        Process p = process;
        process = null;
        if (p != null) {
            p.destroy();
            try {
                if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                p.destroyForcibly();
            }
        }
        Thread t = thread;
        thread = null;
        if (t != null && t != Thread.currentThread()) {
            try { t.join(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    public void resetDisk() {
        stop();
        File data = new File(context.getFilesDir(), "data.img");
        if (data.exists() && !data.delete()) Log.w(TAG, "could not delete " + data);
    }

    // ---- asset helpers ----

    private static String readAsset(AssetManager assets, String name) throws IOException {
        InputStream in = assets.open(name);
        try {
            return readAll(in);
        } finally {
            in.close();
        }
    }

    private static void copyAsset(AssetManager assets, String name, File dest) throws IOException {
        File parent = dest.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("cannot create " + parent);
        }
        InputStream in = assets.open(name);
        FileOutputStream out = new FileOutputStream(dest);
        try {
            byte[] buf = new byte[1 << 20];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.getFD().sync();
        } finally {
            try { in.close(); } catch (IOException ignored) { }
            out.close();
        }
    }

    private void copyAssetDir(AssetManager assets, String name, File dest) throws IOException {
        String[] children = assets.list(name);
        if (children == null || children.length == 0) {
            throw new IOException("asset directory is empty: " + name);
        }
        if (!dest.isDirectory() && !dest.mkdirs()) throw new IOException("cannot create " + dest);
        for (String child : children) {
            copyAsset(assets, name + "/" + child, new File(dest, child));
        }
    }

    private static String readAll(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        char[] buf = new char[4096];
        int n;
        while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
        return sb.toString();
    }

    private static String readText(File f) {
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            try { return readAll(in); } finally { in.close(); }
        } catch (IOException e) {
            return "";
        }
    }

    private static void writeText(File f, String text) throws IOException {
        FileOutputStream out = new FileOutputStream(f);
        try { out.write(text.getBytes("UTF-8")); } finally { out.close(); }
    }

    private static String tail(File f) {
        if (!f.isFile()) return "";
        try {
            RandomAccessFile raf = new RandomAccessFile(f, "r");
            try {
                long len = raf.length();
                long from = Math.max(0, len - 4000);
                raf.seek(from);
                byte[] buf = new byte[(int) (len - from)];
                raf.readFully(buf);
                return new String(buf, "UTF-8");
            } finally {
                raf.close();
            }
        } catch (IOException e) {
            return "";
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
