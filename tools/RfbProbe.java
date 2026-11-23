package com.mdmuntakim.linux41;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

/**
 * CI probe: connects to the pinned QEMU with the same RFB client the app uses,
 * captures a real framebuffer screenshot, measures delivered frame rate and
 * keyboard-to-framebuffer latency.  Runs on a plain JVM, no Android needed.
 */
public final class RfbProbe {

    private static final Object LOCK = new Object();
    private static int[] latest;
    private static int width;
    private static int height;
    private static int frames;
    private static long lastFrameAt;
    private static final CountDownLatch FIRST = new CountDownLatch(1);

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: RfbProbe host port outPrefix [fpsSeconds] [latencySamples]");
            System.exit(2);
        }
        String host = args[0];
        int port = Integer.parseInt(args[1]);
        String prefix = args[2];
        int fpsSeconds = args.length > 3 ? Integer.parseInt(args[3]) : 0;
        int latencySamples = args.length > 4 ? Integer.parseInt(args[4]) : 0;

        RfbClient client = new RfbClient(host, port, new RfbClient.Listener() {
            @Override public void onConnected(int w, int h) {
                synchronized (LOCK) { width = w; height = h; }
            }

            @Override public void onFrame(int[] argb, int w, int h) {
                synchronized (LOCK) {
                    width = w;
                    height = h;
                    if (latest == null || latest.length != argb.length) latest = new int[argb.length];
                    System.arraycopy(argb, 0, latest, 0, argb.length);
                    frames++;
                    lastFrameAt = System.nanoTime();
                }
                FIRST.countDown();
            }

            @Override public void onError(Throwable t) {
                System.err.println("rfb error: " + t);
            }
        });

        client.connect();
        client.start();
        if (!FIRST.await(90, TimeUnit.SECONDS)) {
            throw new IllegalStateException("no framebuffer within 90 s");
        }
        Thread.sleep(500);
        shot(prefix + "-screenshot.png");

        double fps = 0;
        int fpsFrames = 0;
        if (fpsSeconds > 0) {
            int before;
            long t0;
            synchronized (LOCK) { before = frames; t0 = System.nanoTime(); }
            Thread.sleep(fpsSeconds * 1000L);
            synchronized (LOCK) { fpsFrames = frames - before; }
            double seconds = (System.nanoTime() - t0) / 1e9;
            fps = fpsFrames / seconds;
        }

        List<Double> latency = new ArrayList<>();
        if (latencySamples > 0) {
            for (int i = 0; i < latencySamples; i++) {
                int before;
                synchronized (LOCK) { before = frames; }
                long t0 = System.nanoTime();
                client.key(Keys.ENTER, true);
                client.key(Keys.ENTER, false);
                long deadline = System.nanoTime() + 3_000_000_000L;
                boolean changed = false;
                while (System.nanoTime() < deadline) {
                    synchronized (LOCK) { if (frames > before) { changed = true; break; } }
                    Thread.sleep(2);
                }
                if (changed) {
                    long at;
                    synchronized (LOCK) { at = lastFrameAt; }
                    latency.add((at - t0) / 1e6);
                }
                Thread.sleep(250);
            }
        }
        if (latencySamples > 0) shot(prefix + "-screenshot.png");

        double median = 0;
        if (!latency.isEmpty()) {
            List<Double> sorted = new ArrayList<>(latency);
            Collections.sort(sorted);
            median = sorted.get(sorted.size() / 2);
        }

        File json = new File(prefix + "-metrics.json");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(json), StandardCharsets.UTF_8)) {
            w.write(String.format(Locale.US,
                    "{\"width\":%d,\"height\":%d,\"fps\":%.2f,\"fps_frames\":%d,"
                            + "\"latency_median_ms\":%.1f,\"latency_samples_ms\":%s}\n",
                    width, height, fps, fpsFrames, median, latency));
        }
        System.out.println("probe: " + width + "x" + height + ", fps=" + String.format(Locale.US, "%.2f", fps)
                + ", input latency median=" + String.format(Locale.US, "%.1f", median) + " ms");
        client.close();
    }

    private static void shot(String path) throws Exception {
        int[] copy;
        int w;
        int h;
        synchronized (LOCK) {
            if (latest == null) throw new IllegalStateException("no frame");
            copy = latest.clone();
            w = width;
            h = height;
        }
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, w, h, copy, 0, w);
        File out = new File(path);
        File parent = out.getParentFile();
        if (parent != null && !parent.isDirectory()) parent.mkdirs();
        ImageIO.write(img, "png", out);
        System.out.println("probe: wrote " + path);
    }

    private RfbProbe() { }
}
