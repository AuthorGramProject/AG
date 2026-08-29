package org.telegram.messenger.authorgram;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;

public class AuthorGramBadgeDrawable extends Drawable {
    
    private final Drawable baseDrawable;
    private Bitmap cachedBitmap;
    private final Paint gradientPaint;
    private final Matrix matrix;
    private float shift = 0f;
    private long lastUpdateTime;
    private boolean isGlowing = true;

    // Midnight Gold Colors for the Author badge
    private final int[] colors = new int[]{
            0xFFFFD700, // Gold
            0xFFFFA500, // Orange Gold
            0xFFFFF8DC, // Light Gold/White glow
            0xFFFFD700, // Gold
            0xFFFF8C00  // Dark Orange Gold
    };
    
    private final float[] positions = new float[]{0.0f, 0.3f, 0.5f, 0.7f, 1.0f};

    public AuthorGramBadgeDrawable() {
        this.baseDrawable = ContextCompat.getDrawable(ApplicationLoader.applicationContext, R.drawable.ic_author_badge_vector).mutate();
        gradientPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        matrix = new Matrix();
        lastUpdateTime = System.currentTimeMillis();
    }

    private void updateBitmap(int w, int h) {
        if (cachedBitmap != null && cachedBitmap.getWidth() == w && cachedBitmap.getHeight() == h) {
            return;
        }
        if (cachedBitmap != null) {
            cachedBitmap.recycle();
        }
        cachedBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(cachedBitmap);
        baseDrawable.setBounds(0, 0, w, h);
        baseDrawable.draw(canvas);

        LinearGradient shader = new LinearGradient(
                0, 0, w * 2f, h * 2f, 
                colors, positions, Shader.TileMode.CLAMP);
        gradientPaint.setShader(shader);
        gradientPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect bounds = getBounds();
        int width = bounds.width();
        int height = bounds.height();
        
        if (width <= 0 || height <= 0) return;
        
        updateBitmap(width, height);

        long newTime = System.currentTimeMillis();
        long dt = newTime - lastUpdateTime;
        if (dt > 100) dt = 16;
        lastUpdateTime = newTime;

        if (isGlowing) {
            shift += (dt / 1000f) * width * 1.5f; // speed
            if (shift > width * 3) {
                shift = -width * 2;
            }
        }
        
        matrix.reset();
        matrix.postTranslate(shift, 0);
        gradientPaint.getShader().setLocalMatrix(matrix);
        
        canvas.save();
        canvas.translate(bounds.left, bounds.top);
        
        // Draw the golden shape
        canvas.drawBitmap(cachedBitmap, 0, 0, null);
        
        // Draw the glowing gradient masked into the shape
        canvas.drawRect(0, 0, width, height, gradientPaint);
        
        canvas.restore();

        if (isGlowing) {
            AndroidUtilities.runOnUIThread(this::invalidateSelf, 16);
        }
    }

    @Override
    public void setAlpha(int alpha) {
        baseDrawable.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        baseDrawable.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
