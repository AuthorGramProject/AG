package org.telegram.messenger.authorgram;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;

/**
 * A golden, animated-glow badge drawable for the AuthorGram author.
 * Uses the same 18dp size as the old PNG badge so title layout and the
 * call-button in the header stay in the correct positions.
 */
public class AuthorGramBadgeDrawable extends Drawable {

    private static final int BADGE_SIZE_DP = 18;

    private final Drawable baseDrawable;
    private final int sizePx;

    private Bitmap maskBitmap;
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint gradientPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix shaderMatrix = new Matrix();
    private LinearGradient gradient;

    private float phase;
    private long lastTime;

    private static final int[] GOLD_COLORS = {
            0xFFD4A017, // dark gold
            0xFFFFD700, // gold
            0xFFFFF8DC, // light shimmer
            0xFFFFD700, // gold
            0xFFD4A017, // dark gold
    };
    private static final float[] GOLD_STOPS = {0f, 0.35f, 0.5f, 0.65f, 1f};

    public AuthorGramBadgeDrawable() {
        sizePx = AndroidUtilities.dp(BADGE_SIZE_DP);
        baseDrawable = ContextCompat.getDrawable(
                ApplicationLoader.applicationContext, R.drawable.ic_author_badge).mutate();
        lastTime = System.currentTimeMillis();
    }

    /* ---- Intrinsic dimensions — CRITICAL for SimpleTextView layout ---- */

    @Override
    public int getIntrinsicWidth() {
        return sizePx;
    }

    @Override
    public int getIntrinsicHeight() {
        return sizePx;
    }

    /* ---- Drawing ---- */

    private void ensureMask(int w, int h) {
        if (maskBitmap != null && maskBitmap.getWidth() == w && maskBitmap.getHeight() == h) {
            return;
        }
        if (maskBitmap != null) {
            maskBitmap.recycle();
        }
        // Render the base icon (PNG crown) into an off-screen bitmap
        maskBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(maskBitmap);
        baseDrawable.setBounds(0, 0, w, h);
        baseDrawable.draw(c);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect bounds = getBounds();
        int w = bounds.width();
        int h = bounds.height();
        if (w <= 0 || h <= 0) return;

        ensureMask(w, h);

        // Advance animation phase
        long now = System.currentTimeMillis();
        long dt = Math.min(now - lastTime, 50);
        lastTime = now;
        phase += dt * 0.002f; // speed factor
        if (phase > 2f) phase -= 2f;

        // Create / update gradient that slides across the icon
        if (gradient == null) {
            gradient = new LinearGradient(0, 0, w * 2, 0,
                    GOLD_COLORS, GOLD_STOPS, Shader.TileMode.REPEAT);
            gradientPaint.setShader(gradient);
            gradientPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        }

        shaderMatrix.reset();
        shaderMatrix.postTranslate(-w * phase, 0);
        gradient.setLocalMatrix(shaderMatrix);

        // Save layer so SRC_IN compositing only affects what we draw here
        int sc = canvas.saveLayer(bounds.left, bounds.top, bounds.right, bounds.bottom, null);

        // 1. Draw mask (the crown shape — opaque pixels define where gradient appears)
        canvas.drawBitmap(maskBitmap, bounds.left, bounds.top, bitmapPaint);

        // 2. Draw the animated gradient, clipped to the mask via SRC_IN
        canvas.drawRect(bounds.left, bounds.top, bounds.right, bounds.bottom, gradientPaint);

        canvas.restoreToCount(sc);

        // Schedule next frame
        AndroidUtilities.runOnUIThread(this::invalidateSelf, 32);
    }

    @Override
    public void setAlpha(int alpha) {
        bitmapPaint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        // intentionally empty — we use our own gradient
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
