package com.mdmuntakim.linux41;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

/** Renders the RFB framebuffer on a SurfaceView and turns touch into mouse input. */
public final class VncView extends SurfaceView implements SurfaceHolder.Callback {

    public interface KeySink { void key(int keysym, boolean down); }

    private final Object lock = new Object();
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect dst = new Rect();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Path cursorPath = new Path();
    private final Paint cursorFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cursorStroke = new Paint(Paint.ANTI_ALIAS_FLAG);

    private Bitmap bitmap;
    private int fbWidth;
    private int fbHeight;
    private RfbClient client;
    private KeySink keySink;

    private boolean trackpad;
    private float mouseSpeed = 1.0f;
    private float displayScale = 1.0f;
    private int cursorX;
    private int cursorY;
    private int pointerX;
    private int pointerY;

    private boolean leftDown;
    private boolean twoFinger;
    private boolean longFired;
    private float downX;
    private float downY;
    private float lastX;
    private float lastY;
    private int wheelAccum;

    private final Runnable longPress = new Runnable() {
        @Override public void run() {
            if (twoFinger || longFired || !leftDown) return;
            longFired = true;
            if (trackpad) {
                rightClick(cursorX, cursorY);
            } else {
                leftDown = false;
                client.pointer(0, pointerX, pointerY);
                rightClick(pointerX, pointerY);
            }
        }
    };

    public VncView(Context context) {
        this(context, null);
    }

    public VncView(Context context, AttributeSet attrs) {
        super(context, attrs);
        getHolder().addCallback(this);
        setFocusable(true);
        setFocusableInTouchMode(true);
        cursorFill.setColor(Color.WHITE);
        cursorStroke.setColor(Color.BLACK);
        cursorStroke.setStyle(Paint.Style.STROKE);
        cursorStroke.setStrokeWidth(2f);
    }

    public void setClient(RfbClient client) {
        this.client = client;
    }

    public void setKeySink(KeySink sink) { this.keySink = sink; }

    public void setTrackpad(boolean trackpad) { this.trackpad = trackpad; }

    public void setMouseSpeed(float speed) { this.mouseSpeed = speed; }

    public void setDisplayScale(float scale) {
        this.displayScale = scale <= 0 ? 1.0f : scale;
        render();
    }

    public void clickRight() { rightClick(pointerX, pointerY); }

    public int framebufferWidth() { return fbWidth; }
    public int framebufferHeight() { return fbHeight; }

    // ---- framebuffer ----

    public void onConnected(int width, int height) {
        synchronized (lock) {
            fbWidth = width;
            fbHeight = height;
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        }
        if (cursorX == 0 && cursorY == 0) {
            cursorX = width / 2;
            cursorY = height / 2;
            pointerX = cursorX;
            pointerY = cursorY;
        }
        render();
    }

    public void onFrame(int[] argb, int width, int height) {
        synchronized (lock) {
            if (bitmap == null || fbWidth != width || fbHeight != height) {
                fbWidth = width;
                fbHeight = height;
                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            }
            bitmap.setPixels(argb, 0, width, 0, 0, width, height);
        }
        render();
    }

    private void render() {
        SurfaceHolder holder = getHolder();
        if (!holder.getSurface().isValid()) return;
        Canvas canvas = null;
        try {
            canvas = holder.lockCanvas();
            if (canvas == null) return;
            synchronized (lock) {
                canvas.drawColor(Color.BLACK);
                if (bitmap != null) {
                    computeDst(canvas.getWidth(), canvas.getHeight());
                    canvas.drawBitmap(bitmap, null, dst, paint);
                    drawCursor(canvas);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (canvas != null) {
                try { holder.unlockCanvasAndPost(canvas); } catch (Throwable ignored) { }
            }
        }
    }

    private void drawCursor(Canvas canvas) {
        if (fbWidth <= 0 || dst.width() <= 0) return;
        float sx = dst.left + (cursorX + 0.5f) * dst.width() / fbWidth;
        float sy = dst.top + (cursorY + 0.5f) * dst.height() / fbHeight;
        float s = Math.max(1.0f, dst.width() / (float) fbWidth);
        cursorPath.reset();
        cursorPath.moveTo(sx, sy);
        cursorPath.lineTo(sx, sy + 14 * s);
        cursorPath.lineTo(sx + 4 * s, sy + 10 * s);
        cursorPath.lineTo(sx + 9 * s, sy + 15 * s);
        cursorPath.lineTo(sx + 11 * s, sy + 13 * s);
        cursorPath.lineTo(sx + 6 * s, sy + 8 * s);
        cursorPath.lineTo(sx + 10 * s, sy + 8 * s);
        cursorPath.close();
        canvas.drawPath(cursorPath, cursorFill);
        canvas.drawPath(cursorPath, cursorStroke);
    }

    private void computeDst(int viewW, int viewH) {
        if (fbWidth <= 0 || fbHeight <= 0 || viewW <= 0 || viewH <= 0) {
            dst.set(0, 0, viewW, viewH);
            return;
        }
        float scale = Math.min(viewW / (float) fbWidth, viewH / (float) fbHeight)
                * Math.max(0.3f, Math.min(1.0f, displayScale));
        int w = Math.round(fbWidth * scale);
        int h = Math.round(fbHeight * scale);
        int left = (viewW - w) / 2;
        int top = (viewH - h) / 2;
        dst.set(left, top, left + w, top + h);
    }

    private int mapX(float x) {
        computeDst(getWidth(), getHeight());
        if (dst.width() <= 0) return 0;
        int gx = Math.round((x - dst.left) * fbWidth / (float) dst.width());
        return Math.max(0, Math.min(fbWidth - 1, gx));
    }

    private int mapY(float y) {
        computeDst(getWidth(), getHeight());
        if (dst.height() <= 0) return 0;
        int gy = Math.round((y - dst.top) * fbHeight / (float) dst.height());
        return Math.max(0, Math.min(fbHeight - 1, gy));
    }

    // ---- input ----

    private void sendPointer(int mask, int x, int y) {
        pointerX = x;
        pointerY = y;
        cursorX = x;
        cursorY = y;
        if (client != null) client.pointer(mask, x, y);
    }

    private void rightClick(int x, int y) {
        if (client == null) return;
        client.pointer(4, x, y);
        client.pointer(0, x, y);
        render();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (client == null) return true;
        int action = e.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                lastX = e.getX();
                lastY = e.getY();
                leftDown = false;
                twoFinger = false;
                longFired = false;
                wheelAccum = 0;
                handler.postDelayed(longPress, 600);
                if (trackpad) {
                    leftDown = true; // a tap becomes a click on release
                } else {
                    leftDown = true;
                    sendPointer(1, mapX(e.getX()), mapY(e.getY()));
                }
                break;

            case MotionEvent.ACTION_POINTER_DOWN:
                if (e.getPointerCount() == 2) {
                    twoFinger = true;
                    handler.removeCallbacks(longPress);
                    if (leftDown && !trackpad) {
                        leftDown = false;
                        client.pointer(0, pointerX, pointerY);
                    }
                    rightClick(pointerX, pointerY);
                }
                break;

            case MotionEvent.ACTION_MOVE:
                if (twoFinger) {
                    if (e.getPointerCount() >= 2) {
                        float dy = e.getY(1) - lastY;
                        wheelAccum += (int) dy;
                        if (Math.abs(wheelAccum) >= 60) {
                            int button = wheelAccum > 0 ? 5 : 4;
                            client.pointer(button, pointerX, pointerY);
                            client.pointer(0, pointerX, pointerY);
                            wheelAccum = 0;
                        }
                    }
                } else if (trackpad) {
                    float dx = (e.getX() - lastX) * mouseSpeed;
                    float dy = (e.getY() - lastY) * mouseSpeed;
                    int nx = clamp((int) (cursorX + dx), 0, Math.max(0, fbWidth - 1));
                    int ny = clamp((int) (cursorY + dy), 0, Math.max(0, fbHeight - 1));
                    sendPointer(leftDown ? 1 : 0, nx, ny);
                } else if (leftDown) {
                    sendPointer(1, mapX(e.getX()), mapY(e.getY()));
                }
                if (Math.hypot(e.getX() - downX, e.getY() - downY) > 24) {
                    handler.removeCallbacks(longPress);
                }
                lastX = e.getX();
                lastY = e.getY();
                break;

            case MotionEvent.ACTION_UP:
                handler.removeCallbacks(longPress);
                if (trackpad && leftDown && !twoFinger && !longFired) {
                    client.pointer(1, cursorX, cursorY);
                    client.pointer(0, cursorX, cursorY);
                }
                if (!trackpad && leftDown) {
                    client.pointer(0, mapX(e.getX()), mapY(e.getY()));
                }
                leftDown = false;
                twoFinger = false;
                break;

            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(longPress);
                if (leftDown && !trackpad) client.pointer(0, pointerX, pointerY);
                leftDown = false;
                twoFinger = false;
                break;

            default:
                break;
        }
        return true;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    public void key(int keysym, boolean down) {
        if (keySink != null) keySink.key(keysym, down);
        else if (client != null) client.key(keysym, down);
    }

    @Override public void surfaceCreated(SurfaceHolder holder) { render(); }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { render(); }
    @Override public void surfaceDestroyed(SurfaceHolder holder) { }
}
