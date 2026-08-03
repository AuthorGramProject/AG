package org.telegram.messenger.authorgram;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.os.Build;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;

import java.security.GeneralSecurityException;
import java.util.Arrays;

/**
 * One-window AuthorGram encryption controller.
 *
 * The same menu action creates or rotates a word key, enables encryption, and
 * disables encryption. The passphrase is cleared immediately and never stored.
 */
public final class AuthorGramKeyDialog {
    private AuthorGramKeyDialog() {
    }

    public static void show(Activity activity, int account, long dialogId) {
        show(activity, account, dialogId, null);
    }

    public static void show(
            Activity activity,
            int account,
            long dialogId,
            Runnable onStateChanged
    ) {
        if (!isActivityUsable(activity)
                || AuthorGramPlayPolicy.isEncryptionForbidden(dialogId)) {
            return;
        }

        final boolean enabled = AuthorGramChatState.isEnabled(account, dialogId);
        final boolean hasCustomKey =
                AuthorGramChatKeyStore.hasCustomKey(account, dialogId);

        LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        int horizontalPadding = AndroidUtilities.dp(24);
        container.setPadding(
                horizontalPadding,
                AndroidUtilities.dp(4),
                horizontalPadding,
                0
        );

        int explanationId = enabled
                ? R.string.AuthorGramEncryptionEnabledInfo
                : hasCustomKey
                ? R.string.AuthorGramEncryptionReadyInfo
                : R.string.AuthorGramPassphraseInfo;
        TextView explanation = createText(
                activity,
                LocaleController.getString(explanationId),
                15,
                Theme.getColor(Theme.key_dialogTextBlack)
        );
        explanation.setLineSpacing(AndroidUtilities.dp(2), 1.0f);
        container.addView(explanation, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setTextSize(17);
        input.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        input.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        input.setHint(LocaleController.getString(
                enabled
                        ? R.string.AuthorGramNewPassphraseHint
                        : R.string.AuthorGramPassphraseHint
        ));
        input.setInputType(
                InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_VARIATION_PASSWORD
                        | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        );
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(512)});
        input.setFilterTouchesWhenObscured(true);
        input.setSelectAllOnFocus(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            input.setImportantForAutofill(
                    View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            );
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            input.setBackgroundTintList(ColorStateList.valueOf(
                    Theme.getColor(Theme.key_dialogInputFieldActivated)
            ));
        }
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                AndroidUtilities.dp(52)
        );
        inputParams.topMargin = AndroidUtilities.dp(16);
        container.addView(input, inputParams);

        CheckBox showPassphrase = new CheckBox(activity);
        showPassphrase.setText(LocaleController.getString(
                R.string.AuthorGramShowPassphrase
        ));
        showPassphrase.setTextSize(15);
        showPassphrase.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        showPassphrase.setGravity(Gravity.CENTER_VERTICAL);
        showPassphrase.setOnCheckedChangeListener((button, checked) -> {
            int selection = input.getSelectionStart();
            input.setInputType(
                    InputType.TYPE_CLASS_TEXT
                            | (checked
                            ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                            : InputType.TYPE_TEXT_VARIATION_PASSWORD)
                            | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            );
            input.setSelection(Math.max(0, Math.min(selection, input.length())));
        });
        container.addView(showPassphrase, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                AndroidUtilities.dp(48)
        ));

        int positiveLabel = enabled
                ? R.string.AuthorGramChangePassphrase
                : hasCustomKey
                ? R.string.AuthorGramEnableEncryption
                : R.string.AuthorGramSaveAndEnable;

        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(LocaleController.getString(
                        R.string.AuthorGramPassphraseSettings
                ))
                .setView(container)
                .setPositiveButton(
                        LocaleController.getString(positiveLabel),
                        null
                )
                .setNegativeButton(
                        LocaleController.getString(R.string.Cancel),
                        null
                );

        if (enabled) {
            builder.setNeutralButton(
                    LocaleController.getString(
                            R.string.AuthorGramDisableEncryption
                    ),
                    (ignored, which) -> {
                        AuthorGramChatState.setEnabled(account, dialogId, false);
                        toast(
                                activity,
                                R.string.AuthorGramEncryptionDisabled,
                                Toast.LENGTH_SHORT
                        );
                        runCallback(onStateChanged);
                    }
            );
        }

        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(ignored -> {
            Window window = dialog.getWindow();
            if (window != null) {
                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
                window.setSoftInputMode(
                        WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                );
            }

            dialog.getButton(DialogInterface.BUTTON_POSITIVE)
                    .setOnClickListener(view -> {
                        char[] passphrase =
                                input.getText().toString().toCharArray();
                        input.setText("");

                        if (passphrase.length == 0 && !enabled && hasCustomKey) {
                            AuthorGramChatState.setEnabled(
                                    account,
                                    dialogId,
                                    true
                            );
                            boolean activated =
                                    AuthorGramChatState.isEnabled(account, dialogId);
                            Arrays.fill(passphrase, '\0');
                            toast(
                                    activity,
                                    activated
                                            ? R.string.AuthorGramEncryptionEnabled
                                            : R.string.AuthorGramPassphraseOperationFailed,
                                    Toast.LENGTH_LONG
                            );
                            if (activated) {
                                runCallback(onStateChanged);
                                dialog.dismiss();
                            }
                            return;
                        }

                        if (passphrase.length == 0) {
                            Arrays.fill(passphrase, '\0');
                            toast(
                                    activity,
                                    R.string.AuthorGramPassphraseInvalid,
                                    Toast.LENGTH_LONG
                            );
                            input.requestFocus();
                            return;
                        }

                        int codePointCount = Character.codePointCount(
                                passphrase,
                                0,
                                passphrase.length
                        );
                        if (codePointCount
                                > AuthorGramChatKeyStore.getMaxPassphraseCodePoints()) {
                            Arrays.fill(passphrase, '\0');
                            toast(
                                    activity,
                                    R.string.AuthorGramPassphraseTooLong,
                                    Toast.LENGTH_LONG
                            );
                            input.requestFocus();
                            return;
                        }

                        dialog.dismiss();
                        showDerivationProgress(
                                activity,
                                account,
                                dialogId,
                                passphrase,
                                onStateChanged
                        );
                    });
        });
        dialog.setOnDismissListener(ignored -> input.setText(""));
        dialog.show();
        input.requestFocus();
    }

    private static void showDerivationProgress(
            Activity activity,
            int account,
            long dialogId,
            char[] passphrase,
            Runnable onStateChanged
    ) {
        if (!isActivityUsable(activity)) {
            Arrays.fill(passphrase, '\0');
            return;
        }

        AlertDialog progress = new AlertDialog.Builder(activity)
                .setTitle(LocaleController.getString(
                        R.string.AuthorGramPassphraseSettings
                ))
                .setMessage(LocaleController.getString(
                        R.string.AuthorGramDerivingPassphrase
                ))
                .create();
        progress.setCancelable(false);
        progress.setCanceledOnTouchOutside(false);
        progress.show();

        Utilities.globalQueue.postRunnable(() -> {
            boolean success = false;
            boolean invalid = false;
            try {
                AuthorGramChatKeyStore.deriveAndStore(
                        account,
                        dialogId,
                        passphrase
                );
                if (AuthorGramChatKeyStore.hasCustomKey(account, dialogId)) {
                    AuthorGramChatState.setEnabled(account, dialogId, true);
                    success = AuthorGramChatState.isEnabled(account, dialogId);
                }
            } catch (GeneralSecurityException | RuntimeException exception) {
                FileLog.e("AuthorGram: unable to create dialog key", exception);
                invalid = exception.getMessage() != null
                        && (exception.getMessage().startsWith("Passphrase")
                        || exception.getMessage().startsWith("Missing"));
            } finally {
                Arrays.fill(passphrase, '\0');
            }

            final boolean operationSucceeded = success;
            final boolean invalidPassphrase = invalid;
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    progress.dismiss();
                } catch (Exception ignored) {
                    // The activity may have been recreated during derivation.
                }
                if (!isActivityUsable(activity)) {
                    return;
                }
                toast(
                        activity,
                        operationSucceeded
                                ? R.string.AuthorGramPassphraseSavedAndEnabled
                                : invalidPassphrase
                                ? R.string.AuthorGramPassphraseInvalid
                                : R.string.AuthorGramPassphraseOperationFailed,
                        Toast.LENGTH_LONG
                );
                if (operationSucceeded) {
                    runCallback(onStateChanged);
                }
            });
        });
    }

    private static void runCallback(Runnable callback) {
        if (callback != null) {
            callback.run();
        }
    }

    private static TextView createText(
            Activity activity,
            String value,
            int size,
            int color
    ) {
        TextView textView = new TextView(activity);
        textView.setText(value);
        textView.setTextSize(size);
        textView.setTextColor(color);
        return textView;
    }

    private static boolean isActivityUsable(Activity activity) {
        return activity != null
                && !activity.isFinishing()
                && (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1
                || !activity.isDestroyed());
    }

    private static void toast(Activity activity, int stringId, int duration) {
        Toast.makeText(
                activity,
                LocaleController.getString(stringId),
                duration
        ).show();
    }
}
