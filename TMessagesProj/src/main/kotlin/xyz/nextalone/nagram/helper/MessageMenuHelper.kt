package xyz.nextalone.nagram.helper

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.ActionBarMenuSubItem
import org.telegram.ui.ActionBar.ActionBarPopupWindow
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.LayoutHelper
import xyz.nextalone.nagram.NaConfig

/**
 * Lifts the most-used message actions (Reply, Copy, Forward, Edit, Delete)
 * into a compact horizontal toolbar above the long-press popup, hiding their
 * vertical counterparts so the menu stays visually balanced.
 */
object MessageMenuHelper {

    // Drawables that identify the primary actions to lift into the top bar.
    private val PRIMARY_TEXT_KEYS = arrayOf(
        R.string.Reply,
        R.string.Copy,
        R.string.Forward,
        R.string.Edit,
        R.string.Delete,
    )

    @JvmStatic
    fun applyTopActionBar(
        popupLayout: ActionBarPopupWindow.ActionBarPopupWindowLayout?,
        items: Array<ActionBarMenuSubItem?>?,
        themeDelegate: Theme.ResourcesProvider?
    ) {
        if (popupLayout == null || items == null) return
        if (!NaConfig.topMessageMenuEnabled.Bool()) return

        val ctx: Context = popupLayout.context ?: return
        val labels = PRIMARY_TEXT_KEYS.map { LocaleController.getString(it) }.toSet()

        val primary = mutableListOf<ActionBarMenuSubItem>()
        for (item in items) {
            if (item == null) continue
            val text = item.textView?.text?.toString() ?: continue
            if (text in labels) primary.add(item)
        }
        if (primary.isEmpty()) return

        val toolbar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(AndroidUtilities.dp(8f), AndroidUtilities.dp(6f), AndroidUtilities.dp(8f), AndroidUtilities.dp(6f))
        }

        val tint = Theme.getColor(Theme.key_actionBarDefaultSubmenuItemIcon, themeDelegate)
        val rippleColor = Theme.getColor(Theme.key_listSelector, themeDelegate)

        primary.forEach { src ->
            val iconRes = src.iconResId
            if (iconRes == 0) return@forEach

            val btn = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER
                setImageResource(iconRes)
                colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN)
                background = Theme.createSelectorDrawable(rippleColor, 1)
                contentDescription = src.textView?.text
                setOnClickListener {
                    src.callOnClick()
                }
            }
            toolbar.addView(
                btn,
                LayoutHelper.createLinear(0, 40, 1f, Gravity.CENTER, 2, 0, 2, 0)
            )

            // Hide the vertical counterpart so the action isn't duplicated.
            src.visibility = View.GONE
        }

        // Top toolbar + thin divider GapView.
        popupLayout.addView(
            toolbar,
            0,
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT)
        )
        popupLayout.addView(
            ActionBarPopupWindow.GapView(ctx, themeDelegate),
            1,
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 8)
        )
    }
}
