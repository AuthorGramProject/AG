package org.telegram.messenger.authorgram;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;
import org.telegram.ui.Components.Premium.StarParticlesView;

public class AuthorGramBadgeDrawable extends Drawable {
    private final Drawable baseDrawable;
    private final StarParticlesView.Drawable particles;
    private final int sizePx;
    private View parentView;

    public AuthorGramBadgeDrawable() {
        sizePx = AndroidUtilities.dp(18);
        baseDrawable = ContextCompat.getDrawable(ApplicationLoader.applicationContext, R.drawable.ic_author_badge_a).mutate();
        
        particles = new StarParticlesView.Drawable(15);
        particles.type = StarParticlesView.TYPE_APP_ICON_STAR_PREMIUM;
        particles.roundEffect = false;
        particles.isCircle = true;
        particles.useGradient = true;
        particles.useBlur = true;
        particles.checkBounds = true;
        particles.size1 = 9;
        particles.size2 = 7;
        particles.size3 = 5;
        particles.k1 = 0.8f;
        particles.k2 = 0.8f;
        particles.k3 = 0.9f;
        particles.speedScale = 0.4f;
        particles.minLifeTime = 1000;
        particles.randLifeTime = 1000;
        particles.init();
    }
    
    public void setParentView(View view) {
        this.parentView = view;
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
        
        // Expand the bounds slightly so sparks can fly outside
        particles.rect.set(bounds);
        particles.rect.inset(-AndroidUtilities.dp(4), -AndroidUtilities.dp(4));
        particles.rect2.set(bounds);
        particles.excludeRect.set(bounds);
        particles.excludeRadius = AndroidUtilities.dp(6);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        particles.onDraw(canvas);
        baseDrawable.draw(canvas);
        
        if (parentView != null) {
            parentView.invalidate();
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
