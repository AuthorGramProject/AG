package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ChatMessageCell;

/**
 * Main-only visual representation of the selected Telegram message used by the
 * AuthorGram iOS-style message menu.
 *
 * The preview intentionally snapshots the already-bound native ChatMessageCell.
 * It never re-binds MessageObject data and therefore cannot trigger link, reply,
 * media, translation or settings-link code while the context menu is opening.
 * This keeps the menu isolated from Telegram's message-processing pipeline.
 */
public final class IOSMessageMenuPreview extends LinearLayout {
    private static final int MAX_WIDTH_DP = 320;
    private static final int MAX_BITMAP_HEIGHT_DP = 640;
    private static final int MIN_WIDTH_DP = 196;

    private Bitmap snapshot;

    private IOSMessageMenuPreview(
            Context context,
            int currentAccount,
            ChatMessageCell sourceCell,
            Theme.ResourcesProvider resourcesProvider
    ) {
        super(context);
        setOrientation(VERTICAL);
        setClipChildren(true);
        setClipToPadding(true);
        setPadding(
                AndroidUtilities.dp(6),
                AndroidUtilities.dp(6),
                AndroidUtilities.dp(6),
                AndroidUtilities.dp(6)
        );
        setMinimumWidth(AndroidUtilities.dp(MIN_WIDTH_DP));
        setBackground(Theme.createRoundRectDrawable(
                AndroidUtilities.dp(16),
                Theme.multAlpha(
                        Theme.getColor(
                                Theme.key_actionBarDefaultSubmenuBackground,
                                resourcesProvider
                        ),
                        0.97f
                )
        ));
        setTag("AUTHORGRAM_IOS_MESSAGE_MENU_V2");
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(
                        0,
                        0,
                        view.getWidth(),
                        view.getHeight(),
                        AndroidUtilities.dp(16)
                );
            }
        });
        setClipToOutline(true);

        if (sourceCell == null || sourceCell.getWidth() <= 0 || sourceCell.getHeight() <= 0) {
            setVisibility(GONE);
            return;
        }

        /*
         * Group cells already paint the sender identity. Private/outgoing cells
         * normally do not, so add a compact identity row only in that case.
         */
        if (sourceCell.getAvatarImage() == null) {
            addIdentityHeader(
                    context,
                    currentAccount,
                    sourceCell.getMessageObject(),
                    resourcesProvider
            );
        }

        SnapshotResult result = captureNativeCell(sourceCell);
        snapshot = result.bitmap;
        if (snapshot == null) {
            setVisibility(GONE);
            return;
        }

        ImageView image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.FIT_XY);
        image.setAdjustViewBounds(false);
        image.setImageBitmap(snapshot);
        image.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(image, new LinearLayout.LayoutParams(result.width, result.height));
    }

    public static IOSMessageMenuPreview create(
            Context context,
            int currentAccount,
            ChatMessageCell sourceCell,
            Theme.ResourcesProvider resourcesProvider
    ) {
        IOSMessageMenuPreview preview = new IOSMessageMenuPreview(
                context,
                currentAccount,
                sourceCell,
                resourcesProvider
        );
        return preview.isUsable() ? preview : null;
    }

    public boolean isUsable() {
        return snapshot != null && getVisibility() == VISIBLE;
    }

    private void addIdentityHeader(
            Context context,
            int currentAccount,
            MessageObject messageObject,
            Theme.ResourcesProvider resourcesProvider
    ) {
        if (messageObject == null) {
            return;
        }

        long fromId = messageObject.getFromChatId();
        if (fromId == 0 && messageObject.isOutOwner()) {
            fromId = UserConfig.getInstance(currentAccount).getClientUserId();
        }
        if (fromId == 0) {
            return;
        }

        MessagesController controller = MessagesController.getInstance(currentAccount);
        TLObject peer = null;
        String senderName = null;
        if (fromId > 0) {
            TLRPC.User user = controller.getUser(fromId);
            peer = user;
            senderName = user == null ? null : UserObject.getUserName(user);
        } else {
            TLRPC.Chat chat = controller.getChat(-fromId);
            peer = chat;
            senderName = chat == null ? null : chat.title;
        }

        if (TextUtils.isEmpty(senderName)) {
            return;
        }

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LayoutHelper.MATCH_PARENT,
                AndroidUtilities.dp(42)
        );
        rowParams.leftMargin = AndroidUtilities.dp(6);
        rowParams.rightMargin = AndroidUtilities.dp(6);
        rowParams.bottomMargin = AndroidUtilities.dp(4);
        addView(row, rowParams);

        BackupImageView avatar = new BackupImageView(context);
        avatar.setRoundRadius(AndroidUtilities.dp(18));
        AvatarDrawable avatarDrawable = new AvatarDrawable();
        if (peer != null) {
            avatarDrawable.setInfo(currentAccount, peer);
            avatar.setForUserOrChat(peer, avatarDrawable, messageObject);
        } else {
            avatarDrawable.setInfo(fromId, senderName, null);
            avatar.setImageDrawable(avatarDrawable);
        }
        row.addView(
                avatar,
                LayoutHelper.createLinear(36, 36, Gravity.CENTER_VERTICAL)
        );

        TextView name = new TextView(context);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setText(senderName);
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        name.setTypeface(AndroidUtilities.bold());
        name.setTextColor(Theme.getColor(
                Theme.key_actionBarDefaultSubmenuItem,
                resourcesProvider
        ));
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                0,
                LayoutHelper.WRAP_CONTENT,
                1.0f
        );
        nameParams.leftMargin = AndroidUtilities.dp(9);
        row.addView(name, nameParams);
    }

    private static SnapshotResult captureNativeCell(ChatMessageCell sourceCell) {
        final int sourceWidth = sourceCell.getWidth();
        final int sourceHeight = sourceCell.getHeight();
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            return SnapshotResult.EMPTY;
        }

        int left = Math.max(
                0,
                sourceCell.getBackgroundDrawableLeft()
                        - (sourceCell.getAvatarImage() == null
                        ? AndroidUtilities.dp(8)
                        : AndroidUtilities.dp(52))
        );
        int right = Math.min(
                sourceWidth,
                sourceCell.getBackgroundDrawableRight() + AndroidUtilities.dp(8)
        );
        int top = Math.max(
                0,
                sourceCell.getBackgroundDrawableTop() - AndroidUtilities.dp(6)
        );
        int bottom = Math.min(
                sourceHeight,
                sourceCell.getBackgroundDrawableBottom() + AndroidUtilities.dp(6)
        );

        if (right <= left || bottom <= top) {
            left = 0;
            right = sourceWidth;
            top = 0;
            bottom = sourceHeight;
        }

        final int cropWidth = Math.max(1, right - left);
        final int cropHeight = Math.max(1, bottom - top);
        float scale = Math.min(
                1.0f,
                AndroidUtilities.dp(MAX_WIDTH_DP) / (float) cropWidth
        );

        int targetWidth = Math.max(1, Math.round(cropWidth * scale));
        int targetHeight = Math.max(1, Math.round(cropHeight * scale));
        final int maxBitmapHeight = AndroidUtilities.dp(MAX_BITMAP_HEIGHT_DP);
        if (targetHeight > maxBitmapHeight) {
            float heightScale = maxBitmapHeight / (float) targetHeight;
            scale *= heightScale;
            targetWidth = Math.max(1, Math.round(cropWidth * scale));
            targetHeight = Math.max(1, Math.round(cropHeight * scale));
        }

        try {
            Bitmap bitmap = Bitmap.createBitmap(
                    targetWidth,
                    targetHeight,
                    Bitmap.Config.ARGB_8888
            );
            Canvas canvas = new Canvas(bitmap);
            canvas.scale(scale, scale);
            canvas.translate(-left, -top);
            sourceCell.draw(canvas);
            return new SnapshotResult(bitmap, targetWidth, targetHeight);
        } catch (RuntimeException ignored) {
            return SnapshotResult.EMPTY;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (snapshot != null && !snapshot.isRecycled()) {
            snapshot.recycle();
        }
        snapshot = null;
    }

    private static final class SnapshotResult {
        static final SnapshotResult EMPTY = new SnapshotResult(null, 0, 0);

        final Bitmap bitmap;
        final int width;
        final int height;

        SnapshotResult(Bitmap bitmap, int width, int height) {
            this.bitmap = bitmap;
            this.width = width;
            this.height = height;
        }
    }
}
