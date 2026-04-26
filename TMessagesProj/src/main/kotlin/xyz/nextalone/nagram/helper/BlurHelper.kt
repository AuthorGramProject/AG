package xyz.nextalone.nagram.helper

import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.annotation.RequiresApi
import org.telegram.messenger.AndroidUtilities
import org.telegram.ui.ActionBar.Theme
import xyz.nextalone.nagram.NaConfig

/**
 * Centralized blur helper for Nagram Extera. Wraps Android 12+ RenderEffect
 * blur and gracefully falls back to a translucent overlay on older devices.
 *
 * Liquid Glass exclusivity: when the user opts into Liquid Glass styling,
 * individual blur toggles are intentionally ignored so the two systems don't
 * fight each other.
 */
object BlurHelper {

    @JvmStatic
    fun isLiquidGlassActive(): Boolean = NaConfig.liquidGlass.Bool()

    /** Map the user-facing 0..100 intensity slider to a usable blur radius. */
    @JvmStatic
    fun blurRadiusPx(): Float {
        val intensity = NaConfig.blurIntensity.Int().coerceIn(0, 100)
        // 0..100 → 0..32dp; clamp at >=4 to avoid blank passes when enabled
        val dp = (intensity * 32f / 100f)
        return AndroidUtilities.dp(dp.coerceAtLeast(4f)).toFloat()
    }

    /** Map intensity to the alpha used by the translucent fallback (0..1). */
    @JvmStatic
    fun fallbackAlpha(): Float =
        (NaConfig.blurIntensity.Int().coerceIn(0, 100) / 200f) + 0.30f

    /**
     * Apply (or remove) a soft blur to [view]. Safe to call repeatedly; calling
     * with [enabled] = false (or while Liquid Glass is on) clears any prior
     * effect and overlay color.
     */
    @JvmStatic
    fun applyBlur(view: View?, enabled: Boolean) {
        if (view == null) return
        val effective = enabled && !isLiquidGlassActive()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            applyRenderEffect(view, effective)
        }
        applyTranslucentOverlay(view, effective)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun applyRenderEffect(view: View, enabled: Boolean) {
        if (enabled) {
            val r = blurRadiusPx()
            view.setRenderEffect(RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP))
        } else {
            view.setRenderEffect(null)
        }
    }

    private fun applyTranslucentOverlay(view: View, enabled: Boolean) {
        if (enabled) {
            val base = Theme.getColor(Theme.key_windowBackgroundWhite)
            val a = (fallbackAlpha() * 255f).toInt().coerceIn(0, 255)
            view.setBackgroundColor(
                Color.argb(
                    a,
                    Color.red(base),
                    Color.green(base),
                    Color.blue(base)
                )
            )
        }
    }

    /**
     * Toggle the system "Recent Apps" preview blur for [window]. Uses
     * Window#setRecentsScreenshotEnabled when available, otherwise falls back
     * to FLAG_SECURE which also masks the recents screenshot.
     */
    @JvmStatic
    fun applyRecentAppsBlur(window: Window?) {
        if (window == null) return
        val enabled = NaConfig.recentAppsBlur.Bool() && !isLiquidGlassActive()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                window.setRecentsScreenshotEnabled(!enabled)
                return
            } catch (_: Throwable) { /* fall through */ }
        }
        val flags = window.attributes.flags
        val hasSecure = (flags and WindowManager.LayoutParams.FLAG_SECURE) != 0
        if (enabled && !hasSecure) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else if (!enabled && hasSecure) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    /**
     * Apply a translucent navigation bar tint when NavBar Blur is enabled.
     * The compositor blurs the wallpaper / window content behind the
     * translucent bar; on older devices we just dim it.
     */
    @JvmStatic
    fun applyNavBarBlur(window: Window?) {
        if (window == null) return
        val enabled = NaConfig.navBarBlur.Bool() && !isLiquidGlassActive()
        try {
            if (enabled) {
                val base = Theme.getColor(Theme.key_windowBackgroundWhite)
                val a = (fallbackAlpha() * 255f).toInt().coerceIn(0, 255)
                window.navigationBarColor = Color.argb(
                    a,
                    Color.red(base),
                    Color.green(base),
                    Color.blue(base)
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    window.attributes = window.attributes.apply {
                        blurBehindRadius = blurRadiusPx().toInt()
                    }
                    window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                }
            } else {
                window.navigationBarColor = Theme.getColor(Theme.key_windowBackgroundWhite)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    window.attributes = window.attributes.apply {
                        blurBehindRadius = 0
                    }
                    window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                }
            }
        } catch (_: Throwable) { /* ignore — best-effort decoration */ }
    }
}
