package com.poppy.scoreboard;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;

/**
 * The glass's backdrop. TV runs Android 11, which has no live blur (RenderEffect is API 31), so the backdrop is a
 * generated picture and its frosted copy is made once: an eighth of the screen, stack-blurred. Each glass panel draws
 * its own slice of that small bitmap, scaled up, which costs no more than drawing an image.
 */
final class Glass {
    private Glass() {}

    /** A deep, softly lit mesh of colour, like a tvOS wallpaper: navy with violet, teal, rose and amber light. */
    static Bitmap backdrop(int w, int h) {
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(0xFF070B1E);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        // x, y, radius (fractions of the width) and colour of each light
        float[][] blobs = {
                {0.08f, 0.10f, 0.55f}, {0.92f, 0.05f, 0.45f}, {0.70f, 0.95f, 0.55f},
                {0.18f, 0.98f, 0.40f}, {0.50f, 0.45f, 0.30f},
        };
        int[] colours = {0xFF4B2BD6, 0xFF0E7C9E, 0xFFB0306E, 0xFFC9851E, 0xFF2A3FA8};
        for (int i = 0; i < blobs.length; i++) {
            float[] b = blobs[i];
            int col = colours[i];
            p.setShader(new RadialGradient(b[0] * w, b[1] * h, b[2] * w,
                    new int[]{Color.argb(230, Color.red(col), Color.green(col), Color.blue(col)),
                            Color.argb(90, Color.red(col), Color.green(col), Color.blue(col)), 0},
                    new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
        }
        // a few soft orbs give the glass something to bend
        float[][] orbs = {
                {0.30f, 0.30f, 0.07f}, {0.62f, 0.22f, 0.05f}, {0.83f, 0.58f, 0.09f},
                {0.44f, 0.78f, 0.06f}, {0.12f, 0.62f, 0.045f}, {0.95f, 0.85f, 0.05f},
        };
        for (float[] o : orbs) {
            p.setShader(new RadialGradient(o[0] * w, o[1] * h, o[2] * w,
                    new int[]{0x66FFFFFF, 0x22FFFFFF, 0}, new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
            c.drawCircle(o[0] * w, o[1] * h, o[2] * w, p);
        }
        // a gentle vignette keeps white text readable at the edges
        p.setShader(new RadialGradient(w / 2f, h / 2f, w * 0.75f, new int[]{0, 0x00000000, 0x99000000},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        return bmp;
    }

    /** An eighth-size, blurred copy of {@code src} for the glass. */
    static Bitmap frost(Bitmap src, int screenW, int screenH, int radius) {
        int w = Math.max(16, screenW / 8), h = Math.max(9, screenH / 8);
        Bitmap small = Bitmap.createScaledBitmap(src, w, h, true);
        Bitmap out = small.isMutable() && small != src ? small : small.copy(Bitmap.Config.ARGB_8888, true);
        blur(out, radius);
        return out;
    }

    // ---- copied from the launcher's GlassBlur ----------------------------------------------------------

    /** Mario Klingemann's stack blur, in place, on an ARGB_8888 bitmap. */
    static void blur(Bitmap bitmap, int radius) {
        if (radius < 1) return;
        int w = bitmap.getWidth(), h = bitmap.getHeight();
        int[] pix = new int[w * h];
        bitmap.getPixels(pix, 0, w, 0, 0, w, h);
        blur(pix, w, h, radius);
        bitmap.setPixels(pix, 0, w, 0, 0, w, h);
    }

    /** The same blur on raw ARGB pixels (alpha kept opaque); separate for unit tests. */
    static void blur(int[] pix, int w, int h, int radius) {
        int wm = w - 1, hm = h - 1, wh = w * h, div = radius + radius + 1;
        int[] r = new int[wh], g = new int[wh], b = new int[wh];
        int rsum, gsum, bsum, x, y, i, p, yp, yi, yw;
        int[] vmin = new int[Math.max(w, h)];
        int divsum = (div + 1) >> 1;
        divsum *= divsum;
        int[] dv = new int[256 * divsum];
        for (i = 0; i < 256 * divsum; i++) dv[i] = i / divsum;
        yw = yi = 0;
        int[][] stack = new int[div][3];
        int stackpointer, stackstart, rbs, r1 = radius + 1;
        int[] sir;
        int routsum, goutsum, boutsum, rinsum, ginsum, binsum;
        for (y = 0; y < h; y++) {
            rinsum = ginsum = binsum = routsum = goutsum = boutsum = rsum = gsum = bsum = 0;
            for (i = -radius; i <= radius; i++) {
                p = pix[yi + Math.min(wm, Math.max(i, 0))];
                sir = stack[i + radius];
                sir[0] = (p & 0xff0000) >> 16;
                sir[1] = (p & 0x00ff00) >> 8;
                sir[2] = (p & 0x0000ff);
                rbs = r1 - Math.abs(i);
                rsum += sir[0] * rbs;
                gsum += sir[1] * rbs;
                bsum += sir[2] * rbs;
                if (i > 0) {
                    rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2];
                } else {
                    routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2];
                }
            }
            stackpointer = radius;
            for (x = 0; x < w; x++) {
                r[yi] = dv[rsum]; g[yi] = dv[gsum]; b[yi] = dv[bsum];
                rsum -= routsum; gsum -= goutsum; bsum -= boutsum;
                stackstart = stackpointer - radius + div;
                sir = stack[stackstart % div];
                routsum -= sir[0]; goutsum -= sir[1]; boutsum -= sir[2];
                if (y == 0) vmin[x] = Math.min(x + radius + 1, wm);
                p = pix[yw + vmin[x]];
                sir[0] = (p & 0xff0000) >> 16;
                sir[1] = (p & 0x00ff00) >> 8;
                sir[2] = (p & 0x0000ff);
                rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2];
                rsum += rinsum; gsum += ginsum; bsum += binsum;
                stackpointer = (stackpointer + 1) % div;
                sir = stack[stackpointer % div];
                routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2];
                rinsum -= sir[0]; ginsum -= sir[1]; binsum -= sir[2];
                yi++;
            }
            yw += w;
        }
        for (x = 0; x < w; x++) {
            rinsum = ginsum = binsum = routsum = goutsum = boutsum = rsum = gsum = bsum = 0;
            yp = -radius * w;
            for (i = -radius; i <= radius; i++) {
                yi = Math.max(0, yp) + x;
                sir = stack[i + radius];
                sir[0] = r[yi]; sir[1] = g[yi]; sir[2] = b[yi];
                rbs = r1 - Math.abs(i);
                rsum += r[yi] * rbs; gsum += g[yi] * rbs; bsum += b[yi] * rbs;
                if (i > 0) {
                    rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2];
                } else {
                    routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2];
                }
                if (i < hm) yp += w;
            }
            yi = x;
            stackpointer = radius;
            for (y = 0; y < h; y++) {
                pix[yi] = 0xff000000 | (dv[rsum] << 16) | (dv[gsum] << 8) | dv[bsum];
                rsum -= routsum; gsum -= goutsum; bsum -= boutsum;
                stackstart = stackpointer - radius + div;
                sir = stack[stackstart % div];
                routsum -= sir[0]; goutsum -= sir[1]; boutsum -= sir[2];
                if (x == 0) vmin[y] = Math.min(y + r1, hm) * w;
                p = x + vmin[y];
                sir[0] = r[p]; sir[1] = g[p]; sir[2] = b[p];
                rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2];
                rsum += rinsum; gsum += ginsum; bsum += binsum;
                stackpointer = (stackpointer + 1) % div;
                sir = stack[stackpointer];
                routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2];
                rinsum -= sir[0]; ginsum -= sir[1]; binsum -= sir[2];
                yi += w;
            }
        }
    }
}
