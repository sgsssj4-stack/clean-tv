package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A segmented control for the settings screens: a dark track, equal-width segments, the chosen one a white pill.
 * Focus never scales a segment: it is drawn inside the segment's own bounds, so nothing is cropped by the track.
 */
final class Segments {
    interface Pick {
        void at(int index);
    }

    private Segments() {}

    /** The track with one segment per option; the segments are put in {@code into}. {@code after} runs after each pick. */
    static LinearLayout build(Context ctx, float dp, Typeface face, String[] options, TextView[] into, Pick pick,
                              Runnable after) {
        return build(ctx, dp, face, options, into, pick, after, false);
    }

    /** {@code stretch}: the segments share the track's whole width (the track is then laid out MATCH_PARENT). */
    static LinearLayout build(Context ctx, float dp, Typeface face, String[] options, TextView[] into, Pick pick,
                              Runnable after, boolean stretch) {
        LinearLayout track = new LinearLayout(ctx);
        track.setOrientation(LinearLayout.HORIZONTAL);
        int pad = Math.round(4 * dp);
        track.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(22 * dp);
        bg.setColor(0x26000000);
        bg.setStroke(Math.max(1, Math.round(dp)), 0x1FFFFFFF);
        track.setBackground(bg);
        // every segment as wide as the widest label, so the pill moves in even steps
        Paint measure = new Paint();
        measure.setTypeface(face);
        measure.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14, ctx.getResources().getDisplayMetrics()));
        float widest = 0;
        for (String o : options) widest = Math.max(widest, measure.measureText(o));
        int width = Math.max(Math.round(44 * dp), Math.round(widest + 24 * dp));
        for (int i = 0; i < options.length; i++) {
            TextView seg = new TextView(ctx);
            seg.setText(options[i]);
            seg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            seg.setTypeface(face);
            seg.setGravity(Gravity.CENTER);
            seg.setSingleLine(true);
            seg.setIncludeFontPadding(false);
            seg.setFocusable(true);
            seg.setClickable(true);
            seg.setContentDescription(options[i]);
            int index = i;
            seg.setOnClickListener(v -> {
                pick.at(index);
                if (after != null) after.run();
            });
            seg.setOnFocusChangeListener((v, f) -> {
                paint((TextView) v, dp);
                bg.setStroke(Math.max(1, Math.round(dp)), track.hasFocus() || f ? 0x40FFFFFF : 0x1FFFFFFF);
            });
            LinearLayout.LayoutParams lp = stretch ? new LinearLayout.LayoutParams(0, Math.round(32 * dp), 1f)
                    : new LinearLayout.LayoutParams(width, Math.round(32 * dp));
            if (i > 0) lp.leftMargin = Math.round(2 * dp);
            track.addView(seg, lp);
            if (into != null && i < into.length) into[i] = seg;
            paint(seg, dp);
        }
        return track;
    }

    /** Marks one segment chosen or not. */
    static void mark(TextView seg, boolean on, float dp) {
        if (seg == null) return;
        seg.setSelected(on);
        paint(seg, dp);
    }

    static void paint(TextView seg, float dp) {
        boolean on = seg.isSelected(), f = seg.hasFocus();
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(16 * dp);
        int ring = Math.max(2, Math.round(2 * dp));
        if (on) {
            g.setColor(0xFFFFFFFF);
            if (f) g.setStroke(ring, 0xFF0A84FF);   // chosen and focused: a blue ring inside the white pill
        } else if (f) {
            g.setColor(0x33FFFFFF);
            g.setStroke(ring, 0xE6FFFFFF);
        } else {
            g.setColor(Color.TRANSPARENT);
        }
        seg.setBackground(g);
        seg.setTextColor(on ? 0xFF0B1220 : f ? Color.WHITE : 0xB3FFFFFF);
    }
}
