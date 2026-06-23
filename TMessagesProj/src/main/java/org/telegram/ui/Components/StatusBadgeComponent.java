package org.telegram.ui.Components;

import android.graphics.drawable.Drawable;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.Premium.PremiumGradient;

import java.util.HashSet;
import java.util.Set;

public class StatusBadgeComponent {

    // AuthorGram: decorative developer badge (same icon as app icon).
    // Shown ONLY for the specified three IDs, does not affect real verified logic.
    private static final Set<Long> AUTHOR_BADGE_IDS = new HashSet<>();
    static {
        AUTHOR_BADGE_IDS.add(6316376597L);
        AUTHOR_BADGE_IDS.add(2021861896L);
        AUTHOR_BADGE_IDS.add(2815463434L);
    }

    private final AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable statusDrawable;
    private Drawable verifiedDrawable;
    private Drawable authorBadgeDrawable;

    public StatusBadgeComponent(View parentView) {
        this(parentView, 18);
    }

    public StatusBadgeComponent(View parentView, int sizeDp) {
        statusDrawable = new AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable(parentView, AndroidUtilities.dp(sizeDp));
    }

    public Drawable updateDrawable(TLObject object, int colorFilter, boolean animated) {
        if (object instanceof TLRPC.User) {
            return updateDrawable((TLRPC.User) object, null, colorFilter, animated);
        } else if (object instanceof TLRPC.Chat) {
            return updateDrawable(null, (TLRPC.Chat) object, colorFilter, animated);
        }
        return updateDrawable(null, null, colorFilter, animated);
    }

    public Drawable updateDrawable(TLRPC.User user, TLRPC.Chat chat, int colorFilter, boolean animated) {
        long objectId = 0;
        if (user != null) {
            objectId = user.id;
        } else if (chat != null) {
            objectId = chat.id;
        }
        if (AUTHOR_BADGE_IDS.contains(objectId)) {
            if (authorBadgeDrawable == null) {
                authorBadgeDrawable = org.telegram.messenger.ApplicationLoader.applicationContext.getResources().getDrawable(org.telegram.messenger.R.drawable.ic_author_badge).mutate();
            }
            statusDrawable.set(authorBadgeDrawable, animated);
            statusDrawable.setColor(null);
            return statusDrawable;
        }
        if (chat != null && chat.verified) {
            statusDrawable.set(verifiedDrawable = (verifiedDrawable == null ? new CombinedDrawable(Theme.dialogs_verifiedDrawable, Theme.dialogs_verifiedCheckDrawable) : verifiedDrawable), animated);
            statusDrawable.setColor(null);
        } else if (chat != null && DialogObject.getEmojiStatusDocumentId(chat.emoji_status) != 0) {
            statusDrawable.set(DialogObject.getEmojiStatusDocumentId(chat.emoji_status), animated);
            statusDrawable.setColor(colorFilter);
        } else if (user != null && user.verified) {
            statusDrawable.set(verifiedDrawable = (verifiedDrawable == null ? new CombinedDrawable(Theme.dialogs_verifiedDrawable, Theme.dialogs_verifiedCheckDrawable) : verifiedDrawable), animated);
            statusDrawable.setColor(null);
        } else if (user != null && DialogObject.getEmojiStatusDocumentId(user.emoji_status) != 0) {
            statusDrawable.set(DialogObject.getEmojiStatusDocumentId(user.emoji_status), animated);
            statusDrawable.setColor(colorFilter);
        } else if (user != null && user.premium) {
            statusDrawable.set(PremiumGradient.getInstance().premiumStarDrawableMini, animated);
            statusDrawable.setColor(colorFilter);
        } else {
            statusDrawable.set((Drawable) null, animated);
            statusDrawable.setColor(null);
        }
        return statusDrawable;
    }

    public Drawable getDrawable() {
        return statusDrawable;
    }

    public void onAttachedToWindow() {
        statusDrawable.attach();
    }

    public void onDetachedFromWindow() {
        statusDrawable.detach();
    }
}
