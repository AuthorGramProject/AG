package toss.authorgram.features;

import android.text.Editable;
import android.text.TextUtils;

import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.ui.Components.ChatActivityEnterView;
import org.telegram.ui.Components.EditTextCaption;

import xyz.nextalone.nagram.NaConfig;

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
        
        // Suffix Mode should always happen on Send, to prevent typing interruption bugs
        if (NaConfig.INSTANCE.getVoiceTimingSuffixMode().Bool()) return;

        boolean insertOnType = NaConfig.INSTANCE.getVoiceTimingInsertOnType().Bool();
        if (!insertOnType) return;

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
            String separator = getSeparator();
            if (editable != null) {
                editable.insert(0, timing + separator);
                editField.setSelection(editable.length());
            } else {
                enterView.setFieldText(timing + separator + text);
            }
        } catch (Exception ignore) {
        } finally {
            isInserting = false;
        }
    }

    public static CharSequence onSendMessage(MessageObject replyMsg, CharSequence messageText) {
        if (replyMsg == null || messageText == null || messageText.toString().trim().isEmpty()) {
            return messageText;
        }
        
        boolean insertOnType = NaConfig.INSTANCE.getVoiceTimingInsertOnType().Bool();
        boolean suffixMode = NaConfig.INSTANCE.getVoiceTimingSuffixMode().Bool();
        
        if (insertOnType && !suffixMode) {
            return messageText; // Handled by typing
        }

        if (!isValidMediaType(replyMsg)) {
            return messageText;
        }

        String timing = buildTiming(replyMsg);
        if (TextUtils.isEmpty(timing) || messageText.toString().contains(timing)) {
            return messageText;
        }

        String separator = getSeparator();
        return suffixMode ? (messageText + separator + timing) : (timing + separator + messageText);
    }

    private static String getSeparator() {
        int val = NaConfig.INSTANCE.getVoiceTimingSeparator().Int();
        if (val == 1) return "\n";
        if (val == 2) return "";
        return " ";
    }

    private static boolean isValidMediaType(MessageObject replyMsg) {
        if (replyMsg.isVoice()) return NaConfig.INSTANCE.getVoiceTimingVoice().Bool();
        if (replyMsg.isRoundVideo()) return NaConfig.INSTANCE.getVoiceTimingRound().Bool();
        if (replyMsg.isMusic()) return NaConfig.INSTANCE.getVoiceTimingMusic().Bool();
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

        if (seconds < 0) seconds = 0;
        if (seconds == 0 && NaConfig.INSTANCE.getVoiceTimingIgnoreZeros().Bool()) return null;

        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;

        String timeStr;
        if (hours > 0) {
            timeStr = String.format("%02d:%02d:%02d", hours, minutes, secs);
        } else {
            timeStr = String.format("%02d:%02d", minutes, secs);
        }

        String format = NaConfig.INSTANCE.getVoiceTimingFormat().String();
        if (format == null || !format.contains("{time}")) {
            format = "[{time}]";
        }
        
        // Remove line breaks just in case they typed them before
        format = format.replace("\n", "").replace("\r", "");
        
        return format.replace("{time}", timeStr).trim();
    }
}
