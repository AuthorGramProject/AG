package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Outline;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.authorgram.AuthorGramPlayPolicy;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;

/**
 * Compact iOS-inspired header for the long-press message menu.
 *
 * The selected message is represented independently from the action rows: avatar,
 * sender and a bounded text/caption preview. A live blurred layer is sampled from
 * the chat content when a source view is available; older/unsupported layouts keep
 * a translucent themed fallback without changing menu behaviour.
 */
public final class IOSMessageMenuPreview extends FrameLayout {
    private static final int CORNER_RADIUS_DP = 15;

    public IOSMessageMenuPreview(
            Context context,
            View blurSource,
            int currentAccount,
            MessageObject messageObject,
            Theme.ResourcesProvider resourcesProvider
    ) {
        super(context);

        // Defense in depth: this component is inert in the Play package even if a
        // future caller accidentally bypasses the guarded creation site.
        if (!AuthorGramPlayPolicy.canUseIosUi()) {
            setVisibility(GONE);
            return;
        }

        setClipChildren(true);
        setClipToPadding(true);
        setPadding(
                AndroidUtilities.dp(12),
                AndroidUtilities.dp(10),
                AndroidUtilities.dp(12),
                AndroidUtilities.dp(10)
        );

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            setClipToOutline(true);
            setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(
                            0,
                            0,
                            view.getWidth(),
                            view.getHeight(),
                            AndroidUtilities.dp(CORNER_RADIUS_DP)
                    );
                }
            });
        }

        if (blurSource != null && blurSource != this) {
            BluredView blurredView = new BluredView(context, blurSource, resourcesProvider);
            addView(blurredView, LayoutHelper.createFrame(
                    LayoutHelper.MATCH_PARENT,
                    LayoutHelper.MATCH_PARENT
            ));
        }

        View tint = new View(context);
        tint.setBackground(Theme.createRoundRectDrawable(
                AndroidUtilities.dp(CORNER_RADIUS_DP),
                Theme.multAlpha(
                        Theme.getColor(
                                Theme.key_actionBarDefaultSubmenuBackground,
                                resourcesProvider
                        ),
                        0.78f
                )
        ));
        addView(tint, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT,
                LayoutHelper.MATCH_PARENT
        ));

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        addView(row, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT
        ));

        BackupImageView avatar = new BackupImageView(context);
        avatar.setRoundRadius(AndroidUtilities.dp(20));
        row.addView(avatar, LayoutHelper.createLinear(40, 40, Gravity.TOP));

        LinearLayout textColumn = new LinearLayout(context);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textColumnParams = new LinearLayout.LayoutParams(
                0,
                LayoutHelper.WRAP_CONTENT,
                1.0f
        );
        textColumnParams.leftMargin = AndroidUtilities.dp(10);
        row.addView(textColumn, textColumnParams);

        int primaryTextColor = Theme.getColor(
                Theme.key_actionBarDefaultSubmenuItem,
                resourcesProvider
        );

        TextView sender = new TextView(context);
        sender.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        sender.setTypeface(AndroidUtilities.bold());
        sender.setSingleLine(true);
        sender.setEllipsize(TextUtils.TruncateAt.END);
        sender.setTextColor(primaryTextColor);
        textColumn.addView(sender, new LinearLayout.LayoutParams(
                LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT
        ));

        TextView body = new TextView(context);
        body.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        body.setMaxLines(4);
        body.setEllipsize(TextUtils.TruncateAt.END);
        body.setLineSpacing(0, 1.04f);
        body.setTextColor(Theme.multAlpha(primaryTextColor, 0.72f));
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(
                LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT
        );
        bodyParams.topMargin = AndroidUtilities.dp(2);
        textColumn.addView(body, bodyParams);

        bind(currentAccount, messageObject, avatar, sender, body);
    }

    private static void bind(
            int currentAccount,
            MessageObject messageObject,
            BackupImageView avatar,
            TextView sender,
            TextView body
    ) {
        if (messageObject == null) {
            sender.setText("");
            body.setText("");
            return;
        }

        long fromId = messageObject.getFromChatId();
        if (fromId == 0) {
            fromId = messageObject.getDialogId();
        }

        TLObject peer = null;
        String senderName = null;
        MessagesController controller = MessagesController.getInstance(currentAccount);

        if (fromId > 0) {
            TLRPC.User user = controller.getUser(fromId);
            peer = user;
            senderName = user == null ? null : UserObject.getUserName(user);
        } else if (fromId < 0) {
            TLRPC.Chat chat = controller.getChat(-fromId);
            peer = chat;
            senderName = chat == null ? null : chat.title;
        }

        if (TextUtils.isEmpty(senderName)) {
            senderName = "Telegram";
        }
        sender.setText(senderName);

        if (peer != null) {
            AvatarDrawable avatarDrawable = new AvatarDrawable();
            avatarDrawable.setInfo(currentAccount, peer);
            avatar.setForUserOrChat(peer, avatarDrawable, messageObject);
        } else {
            AvatarDrawable avatarDrawable = new AvatarDrawable();
            avatarDrawable.setInfo(fromId, senderName, null);
            avatar.setImageDrawable(avatarDrawable);
        }

        CharSequence preview = messageObject.caption;
        if (TextUtils.isEmpty(preview)) {
            preview = messageObject.messageText;
        }
        if (TextUtils.isEmpty(preview) && messageObject.messageOwner != null) {
            preview = messageObject.messageOwner.message;
        }
        body.setText(TextUtils.isEmpty(preview) ? "…" : preview);
    }
}
