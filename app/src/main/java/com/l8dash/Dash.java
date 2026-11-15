package com.l8dash;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Draws the dashboard on pure black. All sizes are in units of u = column width / 100. t = seconds, for animation. */
final class Dash {
    private static final int BG = 0xFF000000, CARD = 0xFF0C0C0C, LINE = 0xFF1E1E1E,
            DIM = 0xFF8A8A8A, TXT = 0xFFEDEDED, SAND = 0xFFD8CBB4,
            GREEN = 0xFF3CF0A0, CYAN = 0xFF38C8FF, AMBER = 0xFFFFB020;
    private static final String[] TYRES = {"FL", "FR", "RL", "RR"};

    // Visible-side door outlines on car_sketch.png (pixel coords of the 1102x627 image). FR/RR are hidden.
    // Door outline overlays were mapped to the old sketch; the 3/4 render uses a different geometry,
    // so outlines are off until the roofless top-down morph lands. Status still shows "DOOR OPEN".
    private static final float IMG_W = 1384, IMG_H = 921;
    private static final float[][] DOORS = {null, null, null, null};

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF r = new RectF();
    private final Path path = new Path();
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.US);

    // Live 3D car (offscreen GL). Created lazily on the drawing thread (EGL is per-thread).
    private final Context ctx;
    private CarRenderer car3d;
    private boolean car3dTried;
    private final float[] doorAnim = new float[4]; // smoothed open angle per door
    private float sunAnim;                          // smoothed sunroof 0..1
    private float lastCarT = -1;

    Dash(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    // Section heights, in units of that section's u (every section is 100u wide).
    private static final float HEADER_H = 11, TYRES_H = 24, CAR_H = 52, GAUGE_H = 46, RANGES_H = 22, FOOTER_H = 18;

    /**
     * Wide screens (the Leopard 8 centre screen) get two columns filling the screen: car + tyres left, numbers right.
     * Tall screens get one stacked column.
     */
    // DiLink keeps a status bar (top) and an icon dock (bottom) over everything, incl. wallpaper and
    // full-screen apps. Reserve those zones so nothing important is hidden under the car's own icons.
    private static final float INSET_TOP = 0.055f, INSET_BOTTOM = 0.075f; // fraction of screen height

    void draw(Canvas c, CarData.Snapshot s, float t) {
        int W = c.getWidth(), H = c.getHeight();
        c.drawColor(BG);
        float safeTop = H * INSET_TOP, safeH = H - safeTop - H * INSET_BOTTOM;
        if (W > H * 1.2f) {
            float cw = Math.min(W, safeH * 1.6f), U = cw / 160f;  // content box is 160U x 100U, fits in safeH
            float x0 = (W - cw) / 2f, y0 = safeTop + (safeH - 100 * U) / 2f;
            header(c, s, x0, y0, cw, U * 1.1f);
            float top = y0 + HEADER_H * U, room = 100 * U - HEADER_H * U;

            float uL = 1.02f * U, hL = CAR_H * uL, y = top + (room - hL) / 2;
            drawCar(c, s, t, x0, y, uL);

            float uR = 0.70f * U, xR = x0 + 90 * U, hR = (GAUGE_H + RANGES_H + FOOTER_H) * uR;
            y = top + (room - hR) / 2;
            status(c, s, t, xR + 4 * uR, y - 2 * uR, 3f * uR, Paint.Align.LEFT); // right side: clear of launcher clock
            gauge(c, s, xR, y, uR);
            ranges(c, s, xR, y += GAUGE_H * uR, uR);
            footer(c, s, xR, y + RANGES_H * uR, uR);
        } else {
            float cw = Math.min(W, H * 0.55f), u = cw / 100f;
            float x0 = (W - cw) / 2f;
            float y = Math.max(safeTop + 4 * u, safeTop + (safeH - 175 * u) / 2f);
            header(c, s, x0, y, cw, u);
            status(c, s, t, x0 + 4 * u, y + 7 * u, 3f * u, Paint.Align.LEFT);
            drawCar(c, s, t, x0, y += HEADER_H * u, u);
            gauge(c, s, x0, y += CAR_H * u, u);
            ranges(c, s, x0, y += GAUGE_H * u, u);
            footer(c, s, x0, y + RANGES_H * u, u);
        }
    }

    private void header(Canvas c, CarData.Snapshot s, float x, float y, float w, float u) {
        text(c, fmt(s.outside, "%.0f°C"), x + w - 4 * u, y + 5 * u, 3.8f * u, TXT, true, Paint.Align.RIGHT);
    }

    private void tyres(Canvas c, CarData.Snapshot s, float x, float y, float u) {
        float tw = (100 * u - 8 * u - 6 * u) / 4f;
        for (int i = 0; i < 4; i++) {
            float tx = x + 4 * u + i * (tw + 2 * u);
            card(c, tx, y, tx + tw, y + 18 * u, 2 * u);
            text(c, TYRES[i], tx + 2 * u, y + 4.5f * u, 2.8f * u, CYAN, true, Paint.Align.LEFT);
            String psi = fmt(s.tyrePsi[i], "%.1f");
            float end = text(c, psi, tx + 2 * u, y + 11 * u, 5f * u, TXT, true, Paint.Align.LEFT);
            if (s.tyrePsi[i] != null) text(c, " psi", end, y + 11 * u, 2.6f * u, DIM, false, Paint.Align.LEFT);
            text(c, fmt(s.tyreTemp[i], "%.0f°C"), tx + 2 * u, y + 15.5f * u, 2.8f * u, DIM, false, Paint.Align.LEFT);
        }
    }

    /** Battery ring (outer) + fuel ring (inner), with fuel / speed / odometer beside it. */
    private void gauge(Canvas c, CarData.Snapshot s, float x, float y, float u) {
        float gx = x + 30 * u, gy = y + 21 * u;
        ring(c, gx, gy, 18 * u, 3 * u, s.battery, GREEN);
        ring(c, gx, gy, 13 * u, 1.8f * u, s.fuel, CYAN);
        text(c, fmt(s.battery, "%.0f%%"), gx, gy + 2.5f * u, 8 * u, TXT, true, Paint.Align.CENTER);
        text(c, "BATTERY", gx, gy + 6.5f * u, 2.4f * u, DIM, false, Paint.Align.CENTER);
        float sx = x + 58 * u;
        stat(c, sx, y + 8 * u, u, "FUEL LEVEL", fmt(s.fuel, "%.0f%%"), CYAN);
        stat(c, sx, y + 22 * u, u, "SPEED", fmt(s.speed, "%.0f km/h"), TXT);
        stat(c, sx, y + 36 * u, u, "ODOMETER", fmt(s.odometer, "%,.0f km"), TXT);
    }

    private void ranges(Canvas c, CarData.Snapshot s, float x, float y, float u) {
        p.setColor(LINE);
        p.setStrokeWidth(0.3f * u);
        c.drawLine(x + 4 * u, y, x + 96 * u, y, p);
        float col = 92 * u / 3f;
        range(c, x + 4 * u, y, u, "ELECTRIC RANGE", s.evRange, GREEN);
        range(c, x + 4 * u + col, y, u, "FUEL RANGE", s.fuelRange, CYAN);
        range(c, x + 4 * u + 2 * col, y, u, "TOTAL RANGE", s.totalRange, AMBER);
    }

    private void footer(Canvas c, CarData.Snapshot s, float x, float y, float u) {
        float col = 92 * u / 3f;
        card(c, x + 4 * u, y, x + 96 * u, y + 18 * u, 3 * u);
        stat(c, x + 8 * u, y + 5 * u, u, "12V SYSTEM", fmt(s.volt12, "%.1f V"), AMBER);
        stat(c, x + 8 * u + col, y + 5 * u, u, "OUTSIDE", fmt(s.outside, "%.0f°C"), TXT);
        stat(c, x + 8 * u + 2 * col, y + 5 * u, u, "UPDATED", clock.format(new Date()), DIM);
    }

    private static final float CAR_ASPECT = 840f / 600f; // GL render size

    private void drawCar(Canvas c, CarData.Snapshot s, float t, float x0, float y, float u) {
        float cw = 100 * u;
        boolean charging = s.charging;
        float iw = 92 * u, ih = iw / CAR_ASPECT;
        float cx = x0 + cw / 2, ix = cx - iw / 2, top = y + 2 * u;
        float gy = top + ih - 6 * u;

        // Underglow: faint sand "breathing" when parked, green pulse when charging
        int rgb = charging ? GREEN & 0xFFFFFF : SAND & 0xFFFFFF;
        float a = charging ? 0.30f + 0.15f * (float) Math.sin(t * 2.5) : 0.10f + 0.04f * (float) Math.sin(t * 1.2);
        p.setShader(new RadialGradient(cx, gy, 40 * u, ((int) (a * 255) << 24) | rgb, rgb, Shader.TileMode.CLAMP));
        c.save();
        c.scale(1, 0.13f, cx, gy);
        c.drawCircle(cx, gy, 40 * u, p);
        c.restore();
        p.setShader(null);

        // Ease door/sunroof animation toward the live targets
        float dt = lastCarT < 0 ? 0 : Math.max(0, Math.min(0.1f, t - lastCarT));
        lastCarT = t;
        for (int i = 0; i < 4; i++) {
            float target = s.doorOpen[i] ? 72f : 0f;
            doorAnim[i] += (target - doorAnim[i]) * Math.min(1f, dt * 6f);
        }
        sunAnim += ((float) s.sunroofPct - sunAnim) * Math.min(1f, dt * 4f);

        // Live 3D car -> bitmap (created lazily on this thread)
        if (!car3dTried) { car3dTried = true; try { car3d = new CarRenderer(ctx, 840, 600); if (!car3d.ok) car3d = null; } catch (Throwable ignore) { car3d = null; } }
        if (car3d != null) {
            Bitmap b = car3d.render(doorAnim, sunAnim);
            if (b != null) {
                r.set(ix, top, ix + iw, top + ih);
                p.setColor(0xFFFFFFFF);
                c.drawBitmap(b, null, r, p);
            }
        } else {
            text(c, "3D unavailable", cx, top + ih / 2, 3 * u, DIM, false, Paint.Align.CENTER);
        }
    }

    /** Drives the state label (PARKED / DRIVING / CHARGING / DOOR OPEN) — placed clear of launcher chrome. */
    private void status(Canvas c, CarData.Snapshot s, float t, float x, float y, float size, Paint.Align a) {
        List<String> open = new ArrayList<>();
        for (int i = 0; i < 4; i++) if (s.doorOpen[i]) open.add(TYRES[i]);
        String label;
        int color;
        if (!open.isEmpty()) { label = "DOOR OPEN · " + String.join(", ", open); color = AMBER; }
        else if (s.charging) { label = "⚡ CHARGING"; color = withAlpha(GREEN, 0.75f + 0.25f * (float) Math.sin(t * 2.5)); }
        else if (s.driving()) { label = "DRIVING"; color = TXT; }
        else { label = "PARKED"; color = DIM; }
        text(c, label, x, y, size, color, true, a);
    }

    private void range(Canvas c, float x, float y, float u, String label, Double km, int color) {
        text(c, label, x, y + 6 * u, 2.6f * u, DIM, false, Paint.Align.LEFT);
        text(c, fmt(km, "%.0f km"), x, y + 15 * u, 6.5f * u, color, true, Paint.Align.LEFT);
    }

    private void stat(Canvas c, float x, float y, float u, String label, String value, int color) {
        text(c, label, x, y, 2.6f * u, DIM, false, Paint.Align.LEFT);
        text(c, value, x, y + 6.5f * u, 5f * u, color, true, Paint.Align.LEFT);
    }

    private void ring(Canvas c, float cx, float cy, float rad, float sw, Double pct, int color) {
        r.set(cx - rad, cy - rad, cx + rad, cy + rad);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(sw);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(LINE);
        c.drawArc(r, 0, 360, false, p);
        if (pct != null) {
            p.setColor(color);
            c.drawArc(r, -90, 3.6f * (float) Math.max(0, Math.min(100, pct)), false, p);
        }
        p.setStyle(Paint.Style.FILL);
    }

    private void card(Canvas c, float l, float t, float rt, float b, float rad) {
        r.set(l, t, rt, b);
        p.setStyle(Paint.Style.FILL);
        p.setColor(CARD);
        c.drawRoundRect(r, rad, rad, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1, rad / 10));
        p.setColor(LINE);
        c.drawRoundRect(r, rad, rad, p);
        p.setStyle(Paint.Style.FILL);
    }

    /** Draws text, returns the x where it ends (for left-aligned text). */
    private float text(Canvas c, String s, float x, float y, float size, int color, boolean bold, Paint.Align a) {
        p.setStyle(Paint.Style.FILL);
        p.setTextSize(size);
        p.setColor(color);
        p.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        p.setTextAlign(a);
        c.drawText(s, x, y, p);
        return x + p.measureText(s);
    }

    private static int withAlpha(int color, float a) {
        return ((int) (Math.max(0, Math.min(1, a)) * 255) << 24) | (color & 0xFFFFFF);
    }

    private static String fmt(Double v, String f) {
        return v == null ? "—" : String.format(Locale.US, f, v);
    }
}
