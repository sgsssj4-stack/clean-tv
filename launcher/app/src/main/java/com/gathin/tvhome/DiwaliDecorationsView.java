package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import java.io.File;

/**
 * Diwali festive decorations:
 * 1. Flowing lush marigold garlands on the sides.
 * 2. Row of 14 realistic glowing terracotta diyas matching the top pill festive icon (DiyaDrawable).
 */
public final class DiwaliDecorationsView extends View {

    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF rect = new RectF();
    private Bitmap garland;
    private final DiyaDrawable[] diyas = new DiyaDrawable[14];

    public DiwaliDecorationsView(Context context) {
        this(context, null);
    }

    public DiwaliDecorationsView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setFocusable(false);
        setClickable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);

        float density = getResources().getDisplayMetrics().density;
        for (int i = 0; i < diyas.length; i++) {
            diyas[i] = new DiyaDrawable(density);
            diyas[i].start();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        // Lazy load side garland if needed
        if (garland == null) {
            try {
                File dir = getContext().getExternalFilesDir(null);
                if (dir != null) {
                    File f = new File(dir, "diwali_garland.png");
                    if (f.exists()) garland = BitmapFactory.decodeFile(f.getPath());
                }
            } catch (Exception ignored) {}
        }

        // 1. Draw flowing side garlands
        if (garland != null && !garland.isRecycled()) {
            rect.set(0, 0, w, h);
            canvas.drawBitmap(garland, null, rect, bitmapPaint);
        }

        // 2. Draw row of 14 DiyaDrawables below dock
        float density = getResources().getDisplayMetrics().density;
        float diyaY = h - 36f * density;
        int count = diyas.length;
        float spacing = 58f * density;
        float startX = (w - (count - 1) * spacing) * 0.5f;
        int size = Math.round(22f * density);

        for (int i = 0; i < count; i++) {
            float cx = startX + i * spacing;
            int left = Math.round(cx - size * 0.5f);
            int top = Math.round(diyaY - size * 0.5f);
            diyas[i].setBounds(left, top, left + size, top + size);
            diyas[i].draw(canvas);
        }

        postInvalidateDelayed(66);
    }
}
