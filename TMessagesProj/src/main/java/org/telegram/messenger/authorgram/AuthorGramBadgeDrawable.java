package org.telegram.messenger.authorgram;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;

public class AuthorGramBadgeDrawable extends Drawable {
    private int lastThemeColor = 0;
    private PorterDuffColorFilter cachedColorFilter;
    private final Drawable baseDrawable;
    private final int sizePx;
    private java.lang.ref.WeakReference<android.view.View> parentViewRef;
    
    // Animation properties
    private final Paint shimmerPaint;
    private final Matrix shimmerMatrix;
    private long lastUpdateTime;
    private float progress = 0f;
    private boolean animating = true;

    public AuthorGramBadgeDrawable() {
        sizePx = AndroidUtilities.dp(16);
        baseDrawable = ContextCompat.getDrawable(ApplicationLoader.applicationContext, R.drawable.ic_author_badge_a).mutate();
        
        shimmerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        shimmerMatrix = new Matrix();
        lastUpdateTime = System.currentTimeMillis();
    }
    
    public void setParentView(android.view.View view) {
        this.parentViewRef = new java.lang.ref.WeakReference<>(view);
    }

    @Override
    public int getIntrinsicWidth() {
        return sizePx;
    }

    @Override
    public int getIntrinsicHeight() {
        return sizePx;
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        baseDrawable.setBounds(bounds);
        
        // Setup shimmer gradient (transparent -> white -> transparent)
        int color = 0x66FFFFFF; // 40% white
        LinearGradient gradient = new LinearGradient(
                0, 0, bounds.width() * 1.5f, 0,
                new int[]{0x00FFFFFF, color, 0x00FFFFFF},
                new float[]{0.3f, 0.5f, 0.7f},
                Shader.TileMode.CLAMP
        );
        shimmerPaint.setShader(gradient);
        
        // Use SRC_ATOP so shimmer only draws where the badge is drawn
        shimmerPaint.setXfermode(new android.graphics.PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP));
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        // Apply theme color
        int themeColor = Theme.getColor(Theme.key_chats_verifiedBackground);
        if (cachedColorFilter == null || lastThemeColor != themeColor) {
            lastThemeColor = themeColor;
            cachedColorFilter = new PorterDuffColorFilter(themeColor, PorterDuff.Mode.SRC_IN);
            baseDrawable.setColorFilter(cachedColorFilter);
        }
        
        // Draw the badge inside a layer to allow SRC_ATOP blending
        Rect bounds = getBounds();
        int saveCount = canvas.saveLayer(bounds.left, bounds.top, bounds.right, bounds.bottom, null, 31);
        
        baseDrawable.draw(canvas);
        
        // Handle animation
        long newTime = System.currentTimeMillis();
        long dt = newTime - lastUpdateTime;
        lastUpdateTime = newTime;
        
        if (animating) {
            progress += dt / 1500f; // 1.5 seconds per sweep
            if (progress > 1.5f) { // Pause for a bit
                progress = -0.5f;
            }
        }
        
        // Draw shimmer
        if (progress > -0.2f && progress < 1.2f) {
            float translate = bounds.width() * 2f * progress - bounds.width();
            shimmerMatrix.reset();
            shimmerMatrix.postRotate(45, 0, 0);
            shimmerMatrix.postTranslate(bounds.left + translate, bounds.top);
            shimmerPaint.getShader().setLocalMatrix(shimmerMatrix);
            
            canvas.drawRect(bounds, shimmerPaint);
        }
        
        canvas.restoreToCount(saveCount);
        
        if (parentViewRef != null && parentViewRef.get() != null) {
            parentViewRef.get().invalidate();
        } else {
            invalidateSelf();
        }
    }

    @Override
    public void setAlpha(int alpha) {
        baseDrawable.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
