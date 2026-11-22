package com.poppy.scoreboard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

/** The two small drawings on the screen: a week of bars and a capsule meter. */
final class Charts {
    private Charts() {}

    /** Seven capsule bars, one per day, today's lit; labels underneath. */
    static final class Week extends View {
        private long[] values = new long[7];
        private String[] labels = new String[7];
        private int accent = 0xFF7DD3FC;
        private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final float d;

        Week(Context context) {
            super(context);
            d = getResources().getDisplayMetrics().density;
            track.setColor(0x1AFFFFFF);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTextSize(10 * d);
            text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }

        void set(long[] values, String[] labels, int accent) {
            this.values = values;
            this.labels = labels;
            this.accent = accent;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            int n = values.length;
            if (n == 0) return;
            float labelH = 16 * d;
            float h = getHeight() - labelH, w = getWidth();
            float slot = w / n, bw = Math.min(14 * d, slot * 0.45f);
            long max = 1;
            for (long v : values) max = Math.max(max, v);
            for (int i = 0; i < n; i++) {
                float cx = slot * i + slot / 2f;
                rect.set(cx - bw / 2, 0, cx + bw / 2, h);
                c.drawRoundRect(rect, bw / 2, bw / 2, track);
                float bh = values[i] <= 0 ? 0 : Math.max(bw, h * values[i] / (float) max);
                boolean today = i == n - 1;
                if (bh > 0) {
                    rect.set(cx - bw / 2, h - bh, cx + bw / 2, h);
                    int top = today ? 0xFFFFFFFF : (accent & 0x00FFFFFF) | 0xE6000000;
                    int bottom = (accent & 0x00FFFFFF) | (today ? 0xFF000000 : 0x8C000000);
                    bar.setShader(new LinearGradient(0, h - bh, 0, h, top, bottom, Shader.TileMode.CLAMP));
                    c.drawRoundRect(rect, bw / 2, bw / 2, bar);
                }
                text.setColor(today ? 0xFFFFFFFF : 0x99FFFFFF);
                if (labels[i] != null) c.drawText(labels[i], cx, getHeight() - 3 * d, text);
            }
        }
    }

    /** A thin capsule filled to a fraction, in the app's own colour. */
    static final class Meter extends View {
        private float fraction;
        private int colour = 0xFF8AB4FF;
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private float shown;

        Meter(Context context) {
            super(context);
            track.setColor(0x1FFFFFFF);
        }

        void set(float fraction, int colour) {
            this.fraction = Math.max(0f, Math.min(1f, fraction));
            this.colour = colour;
            invalidate();
        }

        /** 0..1 of the fill drawn, for the grow-in animation. */
        void setShown(float shown) {
            this.shown = shown;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            rect.set(0, 0, w, h);
            c.drawRoundRect(rect, h / 2, h / 2, track);
            float fw = Math.max(h, w * fraction * shown);
            if (fraction <= 0 || shown <= 0) return;
            rect.set(0, 0, fw, h);
            fill.setShader(new LinearGradient(0, 0, fw, 0, (colour & 0x00FFFFFF) | 0x99000000, colour,
                    Shader.TileMode.CLAMP));
            c.drawRoundRect(rect, h / 2, h / 2, fill);
        }
    }
}
