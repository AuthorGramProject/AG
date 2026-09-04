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
import android.os.SystemClock;
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
    public int type = AuthorGramBadgeManager.TYPE_AUTHOR;
    
    // Animation properties
    private final Paint shimmerPaint;
    private final Matrix shimmerMatrix;
    private long lastUpdateTime;
    private float progress = 0f;
    private boolean animating = false;
        
    public AuthorGramBadgeDrawable(int type) {
        this.type = type;
        sizePx = AndroidUtilities.dp(16);
                
        int resId = R.drawable.ic_author_badge_a;
        if (type == AuthorGramBadgeManager.TYPE_LOVE) {
            resId = R.drawable.ic_author_badge_heart;
        } else if (type == AuthorGramBadgeManager.TYPE_SUPPORT_PRO) {
            resId = R.drawable.ic_author_badge_support_pro;
        } else if (type == AuthorGramBadgeManager.TYPE_SUPPORT) {
            resId = R.drawable.ic_author_badge_support;
        }
        
        baseDrawable = ContextCompat.getDrawable(ApplicationLoader.applicationContext, resId).mutate();
        
        shimmerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        shimmerMatrix = new Matrix();
        lastUpdateTime = SystemClock.elapsedRealtime();
        
        
    }
    
    public void setParentView(android.view.View view) {
        this.parentViewRef = new java.lang.ref.WeakReference<>(view);
    }
    
    public void startAnimation() {
        if (!animating) {
            animating = true;
            lastUpdateTime = SystemClock.elapsedRealtime();
            invalidateDrawable();
        }
    }

    public void stopAnimation() {
        if (animating) {
            animating = false;
        }
    }

    private void invalidateDrawable() {
        if (parentViewRef != null && parentViewRef.get() != null) {
            parentViewRef.get().invalidate();
        } else {
            invalidateSelf();
        }
    }

    public boolean checkClick(float x, float y) {
        Rect b = getBounds();
        return x >= b.left && x <= b.right && y >= b.top && y <= b.bottom;
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
        Rect innerBounds = bounds;
        baseDrawable.setBounds(innerBounds);
        
        // Setup shimmer gradient
        int color = 0x66FFFFFF; // 40% white
        LinearGradient gradient = new LinearGradient(
                0, 0, innerBounds.width() * 1.5f, 0,
                new int[]{0x00FFFFFF, color, 0x00FFFFFF},
                new float[]{0.3f, 0.5f, 0.7f},
                Shader.TileMode.CLAMP
        );
        shimmerPaint.setShader(gradient);
        shimmerPaint.setXfermode(new android.graphics.PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP));
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        // Apply theme color
        int themeColor = Theme.getColor(Theme.key_chats_verifiedBackground);
        
        if (type == AuthorGramBadgeManager.TYPE_LOVE) {
            themeColor = 0xFFE91E63;
        } else if (type == AuthorGramBadgeManager.TYPE_SUPPORT || type == AuthorGramBadgeManager.TYPE_SUPPORT_PRO) {
            themeColor = 0xFFFF9800;
        }

        if (type != AuthorGramBadgeManager.TYPE_SUPPORT_PRO) {
            if (cachedColorFilter == null || lastThemeColor != themeColor) {
                lastThemeColor = themeColor;
                cachedColorFilter = new PorterDuffColorFilter(themeColor, PorterDuff.Mode.SRC_IN);
            }
            baseDrawable.setColorFilter(cachedColorFilter);
        } else {
            baseDrawable.clearColorFilter();
        }
        
        Rect innerBounds = getBounds();
        
        // Handle animation logic first
        long newTime = SystemClock.elapsedRealtime();
        long dt = newTime - lastUpdateTime;
        lastUpdateTime = newTime;
        
        boolean needsInvalidate = false;
        boolean drawShimmer = false;
        
        if (animating) {
            progress += dt / 1500f;
            if (progress > 1.5f) {
                progress = -0.5f;
            }
            if (progress > -0.2f && progress < 1.2f) {
                needsInvalidate = true;
                drawShimmer = true;
            }
        }

        if (drawShimmer) {
            // Use saveLayer only when shimmer is active to optimize performance
            int saveCount = canvas.saveLayer(innerBounds.left, innerBounds.top, innerBounds.right, innerBounds.bottom, null);
            baseDrawable.draw(canvas);
            
            float translate = innerBounds.width() * 2f * progress - innerBounds.width();
            shimmerMatrix.reset();
            shimmerMatrix.postRotate(45, 0, 0);
            shimmerMatrix.postTranslate(innerBounds.left + translate, innerBounds.top);
            shimmerPaint.getShader().setLocalMatrix(shimmerMatrix);
            
            canvas.drawRect(innerBounds, shimmerPaint);
            canvas.restoreToCount(saveCount);
        } else {
            // Fast path: just draw the badge directly without offscreen buffer
            baseDrawable.draw(canvas);
        }
        
        
        
        if (needsInvalidate) {
            invalidateDrawable();
        }
    }

    @Override
    public void setAlpha(int alpha) {
        baseDrawable.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        if (baseDrawable != null && type != AuthorGramBadgeManager.TYPE_SUPPORT_PRO) {
            baseDrawable.setColorFilter(colorFilter);
        }
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
