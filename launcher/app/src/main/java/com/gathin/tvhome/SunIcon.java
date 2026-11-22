package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/** A small coloured sunrise (warm gold, arrow up) or sunset (orange-red, arrow down) icon: half a sun on the horizon. */
final class SunIcon extends Drawable {
    private final boolean rise;
    private final int size;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG);

    SunIcon(boolean rise, int sizePx) {
        this.rise = rise;
        this.size = sizePx;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        setBounds(0, 0, sizePx, sizePx);
    }

    @Override
    public void draw(Canvas c) {
        float s = getBounds().width(), x0 = getBounds().left, y0 = getBounds().top;
        float cx = x0 + s / 2f, horizon = y0 + s * 0.70f, r = s * 0.24f;
        int top = rise ? 0xFFFFE27A : 0xFFFFB36B, bottom = rise ? 0xFFFFA726 : 0xFFF0524F;
        fill.setShader(new LinearGradient(cx, horizon - r, cx, horizon, top, bottom, Shader.TileMode.CLAMP));
        c.drawArc(new RectF(cx - r, horizon - r, cx + r, horizon + r), 180, 180, true, fill);
        line.setColor(rise ? 0xFFFFC94D : 0xFFFF8A5C);
        line.setStrokeWidth(s * 0.075f);
        for (int k = 0; k < 5; k++) {
            double a = Math.PI + Math.PI * (k + 1) / 6.0;
            float r1 = r * 1.45f, r2 = r * 1.9f;
            if (k == 2) continue;   // the arrow goes straight up the middle
            c.drawLine(cx + (float) Math.cos(a) * r1, horizon + (float) Math.sin(a) * r1,
                    cx + (float) Math.cos(a) * r2, horizon + (float) Math.sin(a) * r2, line);
        }
        // arrow above the sun: up for sunrise, down for sunset
        float aTop = y0 + s * 0.06f, aBot = horizon - r * 1.35f, w = s * 0.11f;
        c.drawLine(cx, aTop, cx, aBot, line);
        Path p = new Path();
        if (rise) {
            p.moveTo(cx - w, aTop + w);
            p.lineTo(cx, aTop);
            p.lineTo(cx + w, aTop + w);
        } else {
            p.moveTo(cx - w, aBot - w);
            p.lineTo(cx, aBot);
            p.lineTo(cx + w, aBot - w);
        }
        c.drawPath(p, line);
        line.setColor(0xCCFFFFFF);
        c.drawLine(x0 + s * 0.08f, horizon, x0 + s * 0.92f, horizon, line);
    }

    @Override
    public int getIntrinsicWidth() {
        return size;
    }

    @Override
    public int getIntrinsicHeight() {
        return size;
    }

    @Override
    public void setAlpha(int alpha) {
        fill.setAlpha(alpha);
        line.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        fill.setColorFilter(cf);
        line.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
