package org.telegram.messenger.authorgram;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.os.PersistableBundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;

import java.security.GeneralSecurityException;
import java.util.ArrayList;

/** Dialog-based management for a dialog-specific AuthorGram key. */
public final class AuthorGramKeyDialog {
    private AuthorGramKeyDialog() {
    }

    public static void show(Activity activity, int account, long dialogId) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        if (AuthorGramChatKeyStore.isSystemKeyLocked(dialogId)) {
            new AlertDialog.Builder(activity)
                    .setTitle(LocaleController.getString(R.string.AuthorGramKeySettings))
                    .setMessage(LocaleController.getString(R.string.AuthorGramSystemKeyLocked))
                    .setPositiveButton(LocaleController.getString(R.string.OK), null)
                    .show();
            return;
        }

        boolean hasKey = AuthorGramChatKeyStore.hasCustomKey(account, dialogId);
        ArrayList<CharSequence> labels = new ArrayList<>();
        ArrayList<Integer> actions = new ArrayList<>();
        if (hasKey) {
            labels.add(LocaleController.getString(R.string.AuthorGramRemoveKey));
            actions.add(0);
        }
        labels.add(LocaleController.getString(
                hasKey ? R.string.AuthorGramRotateKey : R.string.AuthorGramGenerateKey
        ));
        actions.add(1);
        labels.add(LocaleController.getString(R.string.AuthorGramImportKey));
        actions.add(2);
        if (hasKey) {
            labels.add(LocaleController.getString(R.string.AuthorGramExportKey));
            actions.add(3);
        }

        new AlertDialog.Builder(activity)
                .setTitle(LocaleController.getString(R.string.AuthorGramKeySettings))
                .setMessage(LocaleController.getString(
                        hasKey
                                ? R.string.AuthorGramCustomKeyActive
                                : R.string.AuthorGramSystemKeyActive
                ))
                .setItems(labels.toArray(new CharSequence[0]), (dialog, which) -> {
                    int action = actions.get(which);
                    if (action == 0) {
                        confirmRemove(activity, account, dialogId);
                    } else if (action == 1) {
                        confirmGenerate(activity, account, dialogId, hasKey);
                    } else if (action == 2) {
                        showImport(activity, account, dialogId);
                    } else if (action == 3) {
                        showExport(activity, account, dialogId);
                    }
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .show();
    }

    private static void confirmGenerate(
            Activity activity,
            int account,
            long dialogId,
            boolean rotating
    ) {
        new AlertDialog.Builder(activity)
                .setTitle(LocaleController.getString(
                        rotating ? R.string.AuthorGramRotateKey : R.string.AuthorGramGenerateKey
                ))
                .setMessage(LocaleController.getString(
                        rotating
                                ? R.string.AuthorGramRotateKeyWarning
                                : R.string.AuthorGramGenerateKeyInfo
                ))
                .setPositiveButton(LocaleController.getString(R.string.OK), (dialog, which) -> {
                    try {
                        AuthorGramChatKeyStore.generateAndStore(account, dialogId);
                        toast(activity, R.string.AuthorGramKeySaved);
                    } catch (GeneralSecurityException exception) {
                        toast(activity, R.string.AuthorGramKeyOperationFailed);
                    }
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .show();
    }

    private static void showImport(Activity activity, int account, long dialogId) {
        EditText input = new EditText(activity);
        input.setMinLines(2);
        input.setInputType(
                InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                        | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        );
        input.setHint(LocaleController.getString(R.string.AuthorGramKeyInputHint));
        input.setFilterTouchesWhenObscured(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        }

        FrameLayout container = new FrameLayout(activity);
        int padding = AndroidUtilities.dp(20);
        container.setPadding(padding, 0, padding, 0);
        container.addView(input, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        new AlertDialog.Builder(activity)
                .setTitle(LocaleController.getString(R.string.AuthorGramImportKey))
                .setMessage(LocaleController.getString(R.string.AuthorGramImportKeyInfo))
                .setView(container)
                .setPositiveButton(LocaleController.getString(R.string.Save), (dialog, which) -> {
                    try {
                        AuthorGramChatKeyStore.importAndStore(
                                account,
                                dialogId,
                                input.getText().toString()
                        );
                        input.setText("");
                        toast(activity, R.string.AuthorGramKeySaved);
                    } catch (GeneralSecurityException exception) {
                        input.setText("");
                        toast(activity, R.string.AuthorGramInvalidKey);
                    }
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .show();
    }

    private static void showExport(Activity activity, int account, long dialogId) {
        final String key;
        try {
            key = AuthorGramChatKeyStore.exportCurrentKey(account, dialogId);
        } catch (GeneralSecurityException exception) {
            toast(activity, R.string.AuthorGramKeyOperationFailed);
            return;
        }
        if (key == null) {
            toast(activity, R.string.AuthorGramNoCustomKey);
            return;
        }
        new AlertDialog.Builder(activity)
                .setTitle(LocaleController.getString(R.string.AuthorGramExportKey))
                .setMessage(key)
                .setPositiveButton(LocaleController.getString(R.string.Copy), (dialog, which) -> {
                    ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(
                            Context.CLIPBOARD_SERVICE
                    );
                    if (clipboard != null) {
                        ClipData clip = ClipData.newPlainText("AuthorGram key", key);
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            PersistableBundle extras = new PersistableBundle();
                            extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
                            clip.getDescription().setExtras(extras);
                        }
                        clipboard.setPrimaryClip(clip);
                        toast(activity, R.string.TextCopied);
                    }
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .show();
    }

    private static void confirmRemove(Activity activity, int account, long dialogId) {
        new AlertDialog.Builder(activity)
                .setTitle(LocaleController.getString(R.string.AuthorGramRemoveKey))
                .setMessage(LocaleController.getString(R.string.AuthorGramRemoveKeyWarning))
                .setPositiveButton(LocaleController.getString(R.string.Remove), (dialog, which) -> {
                    boolean removed = AuthorGramChatKeyStore.clearCustomKeys(account, dialogId);
                    toast(
                            activity,
                            removed
                                    ? R.string.AuthorGramKeyRemoved
                                    : R.string.AuthorGramKeyOperationFailed
                    );
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .show();
    }

    private static void toast(Activity activity, int stringId) {
        Toast.makeText(
                activity,
                LocaleController.getString(stringId),
                Toast.LENGTH_SHORT
        ).show();
    }
}
