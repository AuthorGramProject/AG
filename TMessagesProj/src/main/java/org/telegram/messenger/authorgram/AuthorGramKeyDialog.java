package org.telegram.messenger.authorgram;

import android.app.Activity;
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
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;

import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;

/** Telegram-style dialog management for a dialog-specific AuthorGram passphrase. */
public final class AuthorGramKeyDialog {
    private AuthorGramKeyDialog() {
    }

    public static void show(Activity activity, int account, long dialogId) {
        if (!isActivityUsable(activity)) {
            return;
        }
        if (AuthorGramChatKeyStore.isSystemKeyLocked(dialogId)) {
            new AlertDialog.Builder(activity)
                    .setTitle(LocaleController.getString(R.string.AuthorGramPassphraseSettings))
                    .setMessage(LocaleController.getString(R.string.AuthorGramPassphraseSystemLocked))
                    .setPositiveButton(LocaleController.getString(R.string.OK), null)
                    .show();
            return;
        }

        boolean hasPassphrase = AuthorGramChatKeyStore.hasCustomKey(account, dialogId);
        ArrayList<CharSequence> labels = new ArrayList<>();
        ArrayList<Integer> actions = new ArrayList<>();

        labels.add(LocaleController.getString(
                hasPassphrase
                        ? R.string.AuthorGramChangePassphrase
                        : R.string.AuthorGramSetPassphrase
        ));
        actions.add(0);
        if (hasPassphrase) {
            labels.add(LocaleController.getString(R.string.AuthorGramUseSystemKey));
            actions.add(1);
        }

        new AlertDialog.Builder(activity)
                .setTitle(LocaleController.getString(R.string.AuthorGramPassphraseSettings))
                .setMessage(LocaleController.getString(
                        hasPassphrase
                                ? R.string.AuthorGramPassphraseCustomActive
                                : R.string.AuthorGramPassphraseSystemActive
                ))
                .setItems(labels.toArray(new CharSequence[0]), (dialog, which) -> {
                    if (actions.get(which) == 0) {
                        showPassphraseEditor(activity, account, dialogId, hasPassphrase);
                    } else {
                        confirmSystemKey(activity, account, dialogId);
                    }
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .show();
    }

    private static void showPassphraseEditor(
            Activity activity,
            int account,
            long dialogId,
            boolean replacing
    ) {
        if (!isActivityUsable(activity)) {
            return;
        }

        LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        int horizontalPadding = AndroidUtilities.dp(24);
        container.setPadding(horizontalPadding, AndroidUtilities.dp(4), horizontalPadding, 0);

        TextView explanation = createText(
                activity,
                LocaleController.getString(R.string.AuthorGramPassphraseInfo),
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
        input.setHint(LocaleController.getString(R.string.AuthorGramPassphraseHint));
        input.setInputType(
                InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_VARIATION_PASSWORD
                        | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        );
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(512)});
        input.setFilterTouchesWhenObscured(true);
        input.setSelectAllOnFocus(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
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
        showPassphrase.setText(LocaleController.getString(R.string.AuthorGramShowPassphrase));
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

        TextView notice = createText(
                activity,
                LocaleController.getString(R.string.AuthorGramPassphraseCaseNotice),
                13,
                Theme.getColor(Theme.key_dialogTextGray2)
        );
        notice.setLineSpacing(AndroidUtilities.dp(1), 1.0f);
        container.addView(notice, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(LocaleController.getString(
                        replacing
                                ? R.string.AuthorGramChangePassphrase
                                : R.string.AuthorGramSetPassphrase
                ))
                .setView(container)
                .setPositiveButton(LocaleController.getString(R.string.Save), (ignored, which) -> {
                    char[] passphrase = input.getText().toString().toCharArray();
                    input.setText("");
                    showDerivationProgress(activity, account, dialogId, passphrase);
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), (ignored, which) -> {
                    input.setText("");
                })
                .create();

        dialog.setOnShowListener(ignored -> {
            input.requestFocus();
            Window window = dialog.getWindow();
            if (window != null) {
                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
            }
        });
        dialog.setOnDismissListener(ignored -> input.setText(""));
        dialog.show();
    }

    private static void showDerivationProgress(
            Activity activity,
            int account,
            long dialogId,
            char[] passphrase
    ) {
        if (!isActivityUsable(activity)) {
            Arrays.fill(passphrase, '\0');
            return;
        }

        int codePointCount = Character.codePointCount(passphrase, 0, passphrase.length);
        if (codePointCount > AuthorGramChatKeyStore.getMaxPassphraseCodePoints()) {
            Arrays.fill(passphrase, '\0');
            toast(activity, R.string.AuthorGramPassphraseTooLong, Toast.LENGTH_LONG);
            return;
        }

        AlertDialog progress = new AlertDialog.Builder(activity)
                .setTitle(LocaleController.getString(R.string.AuthorGramPassphraseSettings))
                .setMessage(LocaleController.getString(R.string.AuthorGramDerivingPassphrase))
                .create();
        progress.setCancelable(false);
        progress.setCanceledOnTouchOutside(false);
        progress.show();

        Utilities.globalQueue.postRunnable(() -> {
            boolean success = false;
            boolean invalid = false;
            try {
                AuthorGramChatKeyStore.deriveAndStore(account, dialogId, passphrase);
                success = true;
            } catch (GeneralSecurityException exception) {
                invalid = exception.getMessage() != null
                        && exception.getMessage().startsWith("Passphrase");
            } finally {
                Arrays.fill(passphrase, '\0');
            }

            final boolean operationSucceeded = success;
            final boolean invalidPassphrase = invalid;
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    progress.dismiss();
                } catch (Exception ignored) {
                    // The activity may have been recreated while deriving the key.
                }
                if (!isActivityUsable(activity)) {
                    return;
                }
                toast(
                        activity,
                        operationSucceeded
                                ? R.string.AuthorGramPassphraseSaved
                                : invalidPassphrase
                                ? R.string.AuthorGramPassphraseInvalid
                                : R.string.AuthorGramPassphraseOperationFailed,
                        Toast.LENGTH_LONG
                );
            });
        });
    }

    private static void confirmSystemKey(Activity activity, int account, long dialogId) {
        new AlertDialog.Builder(activity)
                .setTitle(LocaleController.getString(R.string.AuthorGramUseSystemKey))
                .setMessage(LocaleController.getString(R.string.AuthorGramUseSystemKeyWarning))
                .setPositiveButton(LocaleController.getString(R.string.OK), (dialog, which) -> {
                    boolean restored = AuthorGramChatKeyStore.useSystemKey(account, dialogId);
                    toast(
                            activity,
                            restored
                                    ? R.string.AuthorGramSystemKeyRestored
                                    : R.string.AuthorGramPassphraseOperationFailed,
                            Toast.LENGTH_LONG
                    );
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .show();
    }

    private static TextView createText(Activity activity, String value, int size, int color) {
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
