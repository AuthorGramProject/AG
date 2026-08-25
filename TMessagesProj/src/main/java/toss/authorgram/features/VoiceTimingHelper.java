package toss.authorgram.features;

import android.text.Editable;
import android.text.TextUtils;

import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.ui.Components.ChatActivityEnterView;
import org.telegram.ui.Components.EditTextCaption;

public class VoiceTimingHelper {

    private static boolean isInserting = false;
    private static int lastReplyDialogId = 0;
    private static int lastReplyMessageId = 0;
    private static boolean fieldWasEmpty = true;

    public static void onReplyChanged(ChatActivityEnterView enterView, MessageObject replyMsg) {
        if (replyMsg != null) {
            lastReplyDialogId = (int) replyMsg.getDialogId();
            lastReplyMessageId = replyMsg.getId();
        } else {
            lastReplyDialogId = 0;
            lastReplyMessageId = 0;
        }

        if (enterView != null && enterView.getEditField() != null) {
            CharSequence text = enterView.getEditField().getText();
            fieldWasEmpty = text == null || text.toString().trim().isEmpty();
        }
        isInserting = false;
    }

    public static void onFieldTextChanged(ChatActivityEnterView enterView) {
        if (isInserting || enterView == null) return;
        
        EditTextCaption editField = enterView.getEditField();
        if (editField == null) return;

        CharSequence textSeq = editField.getText();
        String text = textSeq != null ? textSeq.toString() : "";

        if (text.trim().isEmpty()) {
            fieldWasEmpty = true;
            return;
        }

        boolean wasEmpty = fieldWasEmpty;
        fieldWasEmpty = false;

        MessageObject replyMsg = enterView.getReplyingMessageObject();
        if (replyMsg == null) {
            lastReplyDialogId = 0;
            lastReplyMessageId = 0;
            return;
        }

        int currentDialogId = (int) replyMsg.getDialogId();
        int currentMessageId = replyMsg.getId();

        if (currentDialogId != lastReplyDialogId || currentMessageId != lastReplyMessageId) {
            lastReplyDialogId = currentDialogId;
            lastReplyMessageId = currentMessageId;
        } else if (!wasEmpty) {
            return; // Already inserted or text was modified
        }

        if (!isValidMediaType(replyMsg)) return;

        String timing = buildTiming(replyMsg);
        if (TextUtils.isEmpty(timing) || text.contains(timing)) return;

        isInserting = true;
        
        try {
            Editable editable = editField.getText();
            if (editable != null) {
                editable.insert(0, timing + " ");
                editField.setSelection(editable.length());
            } else {
                enterView.setFieldText(timing + " " + text);
            }
        } catch (Exception ignore) {
        } finally {
            isInserting = false;
        }
    }

    private static boolean isValidMediaType(MessageObject replyMsg) {
        if (replyMsg.isVoice()) return true;
        if (replyMsg.isRoundVideo()) return true;
        if (replyMsg.isMusic()) return true;
        return false;
    }

    private static String buildTiming(MessageObject replyMsg) {
        long seconds = 0;
        MediaController controller = MediaController.getInstance();
        if (controller.isPlayingMessage(replyMsg)) {
            long progressMs = controller.getProgressMs(replyMsg);
            if (progressMs > 0) {
                seconds = progressMs / 1000;
            } else {
                seconds = (long) replyMsg.audioProgressSec;
            }
        } else {
            return null; // Only format time if it's actually playing
        }

        if (seconds <= 0) return null; // Ignore 00:00

        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;

        String timeStr;
        if (hours > 0) {
            timeStr = String.format("%02d:%02d:%02d", hours, minutes, secs);
        } else {
            timeStr = String.format("%02d:%02d", minutes, secs);
        }

        return "[" + timeStr + "]";
    }
}
