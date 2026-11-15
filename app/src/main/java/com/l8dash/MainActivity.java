package com.l8dash;

import android.app.Activity;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.Toast;

/** Full-screen preview of the dashboard. Tap to set it as the wallpaper. */
public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        View v = new Preview(this);
        v.setOnClickListener(x -> setWallpaper());
        setContentView(v);
    }

    private void setWallpaper() {
        try {
            startActivity(new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                    .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                            new ComponentName(this, DashWallpaper.class)));
        } catch (Exception e) {
            Toast.makeText(this, "This launcher has no live wallpaper picker", Toast.LENGTH_LONG).show();
        }
    }

    private static class Preview extends View {
        private final Dash dash;
        private final CarData data;
        private volatile CarData.Snapshot snap = new CarData.Snapshot(); // all "—" until first read
        private final Runnable refresh = this::invalidate;
        private Thread reader;
        private volatile boolean alive;

        Preview(Context c) {
            super(c);
            dash = new Dash(c);
            data = new CarData(c);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            alive = true;
            reader = new Thread(() -> {            // car reads use a socket: never on the UI thread
                while (alive) {
                    snap = data.read();
                    try { Thread.sleep(DashWallpaper.DATA_MS); } catch (InterruptedException e) { return; }
                }
            });
            reader.start();
        }

        @Override
        protected void onDraw(Canvas c) {
            dash.draw(c, snap, SystemClock.uptimeMillis() / 1000f);
            postDelayed(refresh, DashWallpaper.FRAME_MS);
        }

        @Override
        protected void onDetachedFromWindow() {
            alive = false;
            if (reader != null) reader.interrupt();
            removeCallbacks(refresh);
            super.onDetachedFromWindow();
        }
    }
}
