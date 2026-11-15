package com.l8dash;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES30;
import android.opengl.Matrix;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Offscreen GLES3 renderer for the low-poly car (assets/car_model.json). Renders a transparent PNG-like
 * bitmap each call, with the doors and sunroof posed to live values. Keeps the 2D dashboard intact —
 * Dash just draws the returned bitmap where the car goes. Create + use on ONE thread (EGL is per-thread).
 */
final class CarRenderer {
    private static final String TAG = "CarRenderer";
    private final int W, H;

    // camera matches the Blender hero cam
    private static final float[] EYE = {6.4f, -5.6f, 3.0f};
    private static final float[] CEN = {-0.1f, 0f, 1.15f};

    private EGLDisplay dpy; private EGLContext ctx; private EGLSurface surf;
    private int prog, uMVP, uModel;
    private final float[] proj = new float[16], view = new float[16], vp = new float[16],
            model = new float[16], mvp = new float[16], tmp = new float[16];

    private static final class Part {        // one draw unit
        FloatBuffer vb; int count; float[] pivot; int side;
        float[] center; float[] slide; float tilt;   // sunroof
    }
    private Part staticPart;
    private final Part[] doors = new Part[4];   // FL,FR,RL,RR
    private Part sunroof;

    private ByteBuffer pix;
    private Bitmap bmp;
    boolean ok;

    CarRenderer(Context c, int w, int h) {
        W = w; H = h;
        try {
            initEgl();
            compile();
            loadModel(c);
            pix = ByteBuffer.allocateDirect(W * H * 4).order(ByteOrder.nativeOrder());
            bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
            // projection: horizontal FOV from Blender lens 62mm on 36mm sensor, Y flipped so readback is top-down
            float hfov = (float) (2 * Math.atan(18.0 / 62.0));
            float vfov = (float) (2 * Math.atan(Math.tan(hfov / 2) / ((double) W / H)));
            Matrix.perspectiveM(proj, 0, (float) Math.toDegrees(vfov), (float) W / H, 0.5f, 60f);
            for (int i = 0; i < 4; i++) proj[1 + i * 4] = -proj[1 + i * 4]; // flip Y
            Matrix.setLookAtM(view, 0, EYE[0], EYE[1], EYE[2], CEN[0], CEN[1], CEN[2], 0, 0, 1);
            Matrix.multiplyMM(vp, 0, proj, 0, view, 0);
            ok = true;
        } catch (Throwable t) {
            Log.w(TAG, "init failed: " + t);
            release();
        }
    }

    /** doorDeg: FL,FR,RL,RR open angle (deg). sunPct: sunroof 0..1. Returns a bitmap (or null). */
    Bitmap render(float[] doorDeg, float sunPct) {
        if (!ok) return null;
        EGL14.eglMakeCurrent(dpy, surf, surf, ctx);
        GLES30.glViewport(0, 0, W, H);
        GLES30.glClearColor(0, 0, 0, 0);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT | GLES30.GL_DEPTH_BUFFER_BIT);
        GLES30.glEnable(GLES30.GL_DEPTH_TEST);
        GLES30.glDisable(GLES30.GL_CULL_FACE);
        GLES30.glUseProgram(prog);

        Matrix.setIdentityM(model, 0);
        drawPart(staticPart, model);

        for (int i = 0; i < 4; i++) {
            Part d = doors[i];
            if (d == null) continue;
            Matrix.setIdentityM(model, 0);
            Matrix.translateM(model, 0, d.pivot[0], d.pivot[1], d.pivot[2]);
            Matrix.rotateM(model, 0, doorDeg[i] * d.side, 0, 0, 1); // side = +1 (R) / -1 (L)
            drawPart(d, model);
        }
        if (sunroof != null) {
            Matrix.setIdentityM(model, 0);
            Matrix.translateM(model, 0, sunroof.center[0] + sunroof.slide[0] * sunPct,
                    sunroof.center[1] + sunroof.slide[1] * sunPct, sunroof.center[2] + sunroof.slide[2] * sunPct);
            Matrix.rotateM(model, 0, sunroof.tilt * sunPct, 0, 1, 0);
            drawPart(sunroof, model);
        }

        pix.rewind();
        GLES30.glReadPixels(0, 0, W, H, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pix);
        pix.rewind();
        bmp.copyPixelsFromBuffer(pix);
        return bmp;
    }

    private void drawPart(Part p, float[] modelM) {
        Matrix.multiplyMM(mvp, 0, vp, 0, modelM, 0);
        GLES30.glUniformMatrix4fv(uMVP, 1, false, mvp, 0);
        GLES30.glUniformMatrix4fv(uModel, 1, false, modelM, 0);
        p.vb.position(0);
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 40, p.vb); GLES30.glEnableVertexAttribArray(0);
        p.vb.position(3);
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, 40, p.vb); GLES30.glEnableVertexAttribArray(1);
        p.vb.position(6);
        GLES30.glVertexAttribPointer(2, 3, GLES30.GL_FLOAT, false, 40, p.vb); GLES30.glEnableVertexAttribArray(2);
        p.vb.position(9);
        GLES30.glVertexAttribPointer(3, 1, GLES30.GL_FLOAT, false, 40, p.vb); GLES30.glEnableVertexAttribArray(3);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, p.count);
    }

    // ---- setup ----
    private void initEgl() {
        dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] ver = new int[2];
        EGL14.eglInitialize(dpy, ver, 0, ver, 1);
        int[] cfgAttr = {EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_DEPTH_SIZE, 16, EGL14.EGL_NONE};
        EGLConfig[] cfgs = new EGLConfig[1]; int[] n = new int[1];
        EGL14.eglChooseConfig(dpy, cfgAttr, 0, cfgs, 0, 1, n, 0);
        ctx = EGL14.eglCreateContext(dpy, cfgs[0], EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE}, 0);
        surf = EGL14.eglCreatePbufferSurface(dpy, cfgs[0], new int[]{EGL14.EGL_WIDTH, W, EGL14.EGL_HEIGHT, H, EGL14.EGL_NONE}, 0);
        EGL14.eglMakeCurrent(dpy, surf, surf, ctx);
    }

    private void compile() {
        String vs = "#version 300 es\n" +
                "layout(location=0) in vec3 aPos; layout(location=1) in vec3 aNor;" +
                "layout(location=2) in vec3 aCol; layout(location=3) in float aEmis;" +
                "uniform mat4 uMVP; uniform mat4 uModel;" +
                "out vec3 vNor; out vec3 vCol; out float vEmis;" +
                "void main(){ gl_Position=uMVP*vec4(aPos,1.0); vNor=mat3(uModel)*aNor; vCol=aCol; vEmis=aEmis; }";
        String fs = "#version 300 es\nprecision mediump float;" +
                "in vec3 vNor; in vec3 vCol; in float vEmis; out vec4 frag;" +
                "const vec3 L=normalize(vec3(-0.55,0.5,-0.95));" +
                "void main(){ vec3 N=normalize(vNor); float d=max(0.0,dot(N,-L));" +
                " vec3 c=vCol*(0.4+0.72*d)+vCol*vEmis; frag=vec4(c,1.0); }";
        int v = sh(GLES30.GL_VERTEX_SHADER, vs), f = sh(GLES30.GL_FRAGMENT_SHADER, fs);
        prog = GLES30.glCreateProgram();
        GLES30.glAttachShader(prog, v); GLES30.glAttachShader(prog, f); GLES30.glLinkProgram(prog);
        int[] ls = new int[1]; GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, ls, 0);
        if (ls[0] == 0) throw new RuntimeException("link: " + GLES30.glGetProgramInfoLog(prog));
        uMVP = GLES30.glGetUniformLocation(prog, "uMVP");
        uModel = GLES30.glGetUniformLocation(prog, "uModel");
    }

    private int sh(int type, String src) {
        int s = GLES30.glCreateShader(type); GLES30.glShaderSource(s, src); GLES30.glCompileShader(s);
        int[] cs = new int[1]; GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, cs, 0);
        if (cs[0] == 0) throw new RuntimeException("shader: " + GLES30.glGetShaderInfoLog(s));
        return s;
    }

    private void loadModel(Context c) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getAssets().open("car_model.json")))) {
            for (String l; (l = r.readLine()) != null; ) sb.append(l);
        }
        JSONObject root = new JSONObject(sb.toString());
        staticPart = partFromTris(root.getJSONArray("static"));
        JSONObject dd = root.getJSONObject("doors");
        String[] tags = {"FL", "FR", "RL", "RR"};
        for (int i = 0; i < 4; i++) {
            if (!dd.has(tags[i])) continue;
            JSONObject o = dd.getJSONObject(tags[i]);
            Part p = partFromTris(o.getJSONArray("tris"));
            JSONArray pv = o.getJSONArray("pivot");
            p.pivot = new float[]{(float) pv.getDouble(0), (float) pv.getDouble(1), (float) pv.getDouble(2)};
            p.side = o.getString("side").equals("R") ? 1 : -1;
            doors[i] = p;
        }
        if (!root.isNull("sunroof")) {
            JSONObject o = root.getJSONObject("sunroof");
            Part p = partFromTris(o.getJSONArray("tris"));
            JSONArray ce = o.getJSONArray("center");
            p.center = new float[]{(float) ce.getDouble(0), (float) ce.getDouble(1), (float) ce.getDouble(2)};
            JSONArray sl = o.getJSONArray("slide");
            p.slide = new float[]{(float) sl.getDouble(0), (float) sl.getDouble(1), (float) sl.getDouble(2)};
            p.tilt = (float) o.getDouble("tilt_deg");
            sunroof = p;
        }
    }

    /** tris: array of [v0x,v0y,v0z, v1x,v1y,v1z, v2x,v2y,v2z, nx,ny,nz, r,g,b, emis] (16 floats). */
    private Part partFromTris(JSONArray tris) throws Exception {
        int n = tris.length();
        float[] buf = new float[n * 3 * 10]; // 3 verts * (pos3,nor3,col3,emis1)
        int k = 0;
        for (int i = 0; i < n; i++) {
            JSONArray t = tris.getJSONArray(i);
            float[] v = new float[16];
            for (int j = 0; j < 16; j++) v[j] = (float) t.getDouble(j);
            float[] nor = {v[9], v[10], v[11]}, col = {v[12], v[13], v[14]}; float em = v[15];
            for (int vi = 0; vi < 3; vi++) {
                buf[k++] = v[vi * 3]; buf[k++] = v[vi * 3 + 1]; buf[k++] = v[vi * 3 + 2];
                buf[k++] = nor[0]; buf[k++] = nor[1]; buf[k++] = nor[2];
                buf[k++] = col[0]; buf[k++] = col[1]; buf[k++] = col[2];
                buf[k++] = em;
            }
        }
        Part p = new Part();
        p.vb = ByteBuffer.allocateDirect(buf.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        p.vb.put(buf); p.vb.position(0);
        p.count = n * 3;
        return p;
    }

    void release() {
        ok = false;
        try {
            if (dpy != null) {
                EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                if (surf != null) EGL14.eglDestroySurface(dpy, surf);
                if (ctx != null) EGL14.eglDestroyContext(dpy, ctx);
                EGL14.eglTerminate(dpy);
            }
        } catch (Throwable ignored) { }
        dpy = null; ctx = null; surf = null;
    }
}
