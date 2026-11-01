package com.cleantv.launcher;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;

/** Placeholder tile with Platypus surface color and teal sweep effect. */
final class ShimmerDrawable extends Drawable {

    private static final float BAND = 0.6f;

    private final Paint plate = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private final RectF rect = new RectF();
    private final float radius;
    private float progress;

    ShimmerDrawable(float radius) {
        this.radius = radius;
        plate.setColor(0xFF121A20); // Platypus Surface
    }

    /** 0..1: the band enters on the left at 0 and leaves on the right at 1. */
    void setProgress(float p) {
        progress = p;
        Shader s = band.getShader();
        if (s == null) return;
        float w = rect.width(), bw = w * BAND;
        matrix.setTranslate(-bw + p * (w + bw), 0);
        s.setLocalMatrix(matrix);
        invalidateSelf();
    }

    @Override
    protected void onBoundsChange(Rect b) {
        rect.set(b);
        band.setShader(new LinearGradient(0, 0, b.width() * BAND, 0,
                new int[]{0x0000B7C3, 0x2200B7C3, 0x0000B7C3}, null, Shader.TileMode.CLAMP));
        setProgress(progress);
    }

    @Override
    public void draw(@NonNull Canvas c) {
        c.drawRoundRect(rect, radius, radius, plate);
        c.drawRoundRect(rect, radius, radius, band);
    }

    @Override
    public void getOutline(@NonNull Outline o) {
        o.setRoundRect(getBounds(), radius);
    }

    @Override
    public void setAlpha(int a) {
    }

    @Override
    public void setColorFilter(ColorFilter f) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
