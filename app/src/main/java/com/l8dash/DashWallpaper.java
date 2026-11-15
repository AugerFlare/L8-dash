package com.l8dash;

import android.graphics.Canvas;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.service.wallpaper.WallpaperService;
import android.view.SurfaceHolder;

public class DashWallpaper extends WallpaperService {
    static final long DATA_MS = 5000;  // how often to read the car
    static final long FRAME_MS = 50;   // ~20 fps, enough for the subtle animations

    @Override
    public Engine onCreateEngine() { return new DashEngine(); }

    private class DashEngine extends Engine {
        private final HandlerThread thread = new HandlerThread("dash");
        private Dash dash;
        private CarData data;
        private CarData.Snapshot snap;
        private long lastRead;
        private Handler h;

        private final Runnable tick = new Runnable() {
            @Override public void run() {
                drawFrame();
                h.postDelayed(this, FRAME_MS);
            }
        };

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
            setTouchEventsEnabled(false);
            thread.start();
            h = new Handler(thread.getLooper());
            dash = new Dash(DashWallpaper.this);
            data = new CarData(DashWallpaper.this);
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            h.removeCallbacks(tick);
            if (visible) h.post(tick); // only animate and poll the car while on screen
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int w, int hgt) {
            h.post(this::drawFrame);
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            h.removeCallbacksAndMessages(null);
        }

        @Override
        public void onDestroy() {
            h.removeCallbacksAndMessages(null);
            thread.quitSafely();
        }

        private void drawFrame() {
            long now = SystemClock.uptimeMillis();
            if (snap == null || now - lastRead >= DATA_MS) {
                snap = data.read();
                lastRead = now;
            }
            SurfaceHolder sh = getSurfaceHolder();
            Canvas c = null;
            try {
                c = sh.lockCanvas();
                if (c != null) dash.draw(c, snap, now / 1000f);
            } catch (IllegalStateException ignored) { // surface went away mid-frame
            } finally {
                if (c != null) {
                    try { sh.unlockCanvasAndPost(c); } catch (IllegalStateException ignored) { }
                }
            }
        }
    }
}
