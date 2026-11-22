package com.poppy.scoreboard;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.widget.FrameLayout;

/**
 * A pane of "liquid glass": the frosted slice of whatever is behind it, a faint white tint, a sheen across the top,
 * and a bright rim that is lit from the top left, like light catching the edge of a curved pane. Focused panes are
 * brighter and take an accent glow on the rim.
 *
 * <p>The frost comes from a {@link Source}: one small blurred bitmap the size of the screen's eighth. Panes find
 * their slice from where they sit in the window, so a scrolled list must call {@link #invalidate()} as it scrolls.
 */
public class GlassView extends FrameLayout {

    /** The blurred picture of the screen the panes sit on. */
    static final class Source {
        final Bitmap frost;
        final int screenW, screenH;

        Source(Bitmap frost, int screenW, int screenH) {
            this.frost = frost;
            this.screenW = screenW;
            this.screenH = screenH;
        }
    }

    private Source source;
    private float radius;
    private int tint = 0x1FFFFFFF;
    private int focusedTint = 0x40FFFFFF;
    private int accent = 0;
    private boolean lit;
    private boolean solidFocus;

    private final Paint frostPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private final RectF rect = new RectF();
    private final int[] loc = new int[2];
    private BitmapShader shader;
    private int shaderW = -1, shaderH = -1;

    public GlassView(Context context) {
        super(context);
        setWillNotDraw(false);
        float d = getResources().getDisplayMetrics().density;
        radius = 22 * d;
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(1.4f * d);
        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeWidth(2.5f * d);
    }

    GlassView source(Source s) {
        source = s;
        shader = null;
        invalidate();
        return this;
    }

    GlassView radius(float px) {
        radius = px;
        invalidate();
        return this;
    }

    /** The white tint at rest and when focused, as 0xAARRGGBB. */
    GlassView tint(int rest, int focused) {
        tint = rest;
        focusedTint = focused;
        invalidate();
        return this;
    }

    /** A colour for the focus glow on the rim; 0 for plain white. */
    GlassView accent(int colour) {
        accent = colour;
        invalidate();
        return this;
    }

    /** Draw as focused even without focus (a selected segment). */
    void setLit(boolean on) {
        if (lit != on) {
            lit = on;
            invalidate();
        }
    }

    /** Focused, the pane turns almost solid white, like a tvOS button; its children should switch to dark text. */
    GlassView solidFocus(boolean on) {
        solidFocus = on;
        return this;
    }

    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        boolean on = lit || isFocused();
        rect.set(0, 0, w, h);
        float r = Math.min(radius, h / 2f);

        if (source != null && source.frost != null && !source.frost.isRecycled()) {
            if (shader == null) {
                shader = new BitmapShader(source.frost, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                frostPaint.setShader(shader);
            }
            getLocationInWindow(loc);
            matrix.setScale(source.screenW / (float) source.frost.getWidth(),
                    source.screenH / (float) source.frost.getHeight());
            matrix.postTranslate(-loc[0], -loc[1]);
            shader.setLocalMatrix(matrix);
            canvas.drawRoundRect(rect, r, r, frostPaint);
        }

        fill.setColor(on ? (solidFocus ? 0xF2FFFFFF : focusedTint) : tint);
        canvas.drawRoundRect(rect, r, r, fill);

        if (shaderW != w || shaderH != h) {
            shaderW = w;
            shaderH = h;
            sheen.setShader(new LinearGradient(0, 0, 0, h, new int[]{0x2EFFFFFF, 0x08FFFFFF, 0x00FFFFFF, 0x14000000},
                    new float[]{0f, 0.35f, 0.7f, 1f}, Shader.TileMode.CLAMP));
            rim.setShader(new LinearGradient(0, 0, w * 0.6f, h, new int[]{0xB3FFFFFF, 0x26FFFFFF, 0x0DFFFFFF,
                    0x4DFFFFFF}, new float[]{0f, 0.35f, 0.7f, 1f}, Shader.TileMode.CLAMP));
        }
        if (!(on && solidFocus)) canvas.drawRoundRect(rect, r, r, sheen);

        float inset = rim.getStrokeWidth() / 2f;
        rect.inset(inset, inset);
        canvas.drawRoundRect(rect, r - inset, r - inset, rim);

        if (on && !solidFocus) {
            glow.setColor(accent != 0 ? (accent & 0x00FFFFFF) | 0xCC000000 : 0x99FFFFFF);
            canvas.drawRoundRect(rect, r - inset, r - inset, glow);
        }
    }
}
