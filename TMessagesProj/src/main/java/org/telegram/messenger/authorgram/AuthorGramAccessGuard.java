package org.telegram.messenger.authorgram;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;

import org.telegram.messenger.UserConfig;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.FileLog;
import org.telegram.ui.ActionBar.AlertDialog;

import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class AuthorGramAccessGuard {
    private static final byte[] KEY_PART_A = {
            (byte) 0x51, (byte) 0x39, (byte) 0x19, (byte) 0xd3,
            (byte) 0xb9, (byte) 0x26, (byte) 0xd4, (byte) 0xc3,
            (byte) 0xd0, (byte) 0x29, (byte) 0x13, (byte) 0x8e,
            (byte) 0xf3, (byte) 0x69, (byte) 0x75, (byte) 0x40,
            (byte) 0x7e, (byte) 0x64, (byte) 0xab, (byte) 0xa3,
            (byte) 0x09, (byte) 0x05, (byte) 0x6f, (byte) 0x56,
            (byte) 0xc2, (byte) 0x58, (byte) 0x47, (byte) 0xc8,
            (byte) 0x79, (byte) 0x5a, (byte) 0x17, (byte) 0xd5
    };
    private static final byte[] KEY_PART_B = {
            (byte) 0xb4, (byte) 0xb2, (byte) 0xee, (byte) 0x77,
            (byte) 0xb0, (byte) 0x6b, (byte) 0x56, (byte) 0x32,
            (byte) 0x76, (byte) 0x65, (byte) 0x48, (byte) 0x19,
            (byte) 0xf0, (byte) 0x91, (byte) 0x2f, (byte) 0x0d,
            (byte) 0x08, (byte) 0xb1, (byte) 0x1a, (byte) 0x21,
            (byte) 0x99, (byte) 0x19, (byte) 0xa0, (byte) 0xfd,
            (byte) 0x8c, (byte) 0x30, (byte) 0xb7, (byte) 0xe2,
            (byte) 0xee, (byte) 0x89, (byte) 0x5c, (byte) 0xc0
    };

    private static final long[] ALLOWED_TOKENS = {
            0xda9da78fe9dc1006L, 0xd7b3ab94eb5de38aL, // ID: 8615751871
            0x16c0d73ff2f3db0dL, 0x90461d146f0cd07cL, // ID: 6802848305
            0xd07f521a40257454L, 0x308293349196d6ccL, // ID: 953860978
            0xf6b7b15c0d7a46daL, 0x69cce8b1cfcfcaf7L, // ID: 1257662278
            0x79405c317e8b57a9L, 0xc9ca3812c2461a71L, // ID: 1661748225
            0x77357f6e0b552d43L, 0x73b90dbfb54a32d0L, // ID: 6316376597
            0x7c485357afdd4d04L, 0x7dae854b15930bebL, // ID: 8734787799
    };

    private static boolean isChecked = false;
    private static AlertDialog accessDialog;

    private AuthorGramAccessGuard() {
    }

    public static void checkAccess(Activity activity) {
        if (isChecked || AuthorGramPlayPolicy.isPlayBuild()) {
            return;
        }

        ArrayList<Integer> unauthorizedAccounts = new ArrayList<>();
        ArrayList<Long> unauthorizedIds = new ArrayList<>();

        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
            UserConfig config = UserConfig.getInstance(i);
            if (config.isClientActivated()) {
                long userId = config.getClientUserId();
                if (userId != 0 && !matchesToken(userId)) {
                    unauthorizedAccounts.add(i);
                    unauthorizedIds.add(userId);
                }
            }
        }

        if (unauthorizedAccounts.isEmpty()) {
            isChecked = true;
            return;
        }

        StringBuilder msg = new StringBuilder("AuthorGram - приватний доступ\n\nНаступні аккаунти не мають дозволу на використання AuthorGram:\n");
        for (Long id : unauthorizedIds) {
            msg.append("- ").append(id).append("\n");
        }
        msg.append("\nДодаток буде закрито через 10 секунд");

        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle("Доступ не придбано");
        builder.setMessage(msg.toString());
        builder.setCancelable(false);
        builder.setPositiveButton("ОК", null);

        accessDialog = builder.create();
        accessDialog.show();

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (accessDialog != null && accessDialog.isShowing()) {
                try {
                    accessDialog.dismiss();
                } catch (Exception ignore) {}
            }
            for (Integer accountIndex : unauthorizedAccounts) {
                MessagesController.getInstance(accountIndex).performLogout(1);
            }
        }, 10000);

        isChecked = true;
    }

    private static boolean matchesToken(long objectId) {
        byte[] key = new byte[KEY_PART_A.length];
        byte[] input = new byte[Long.BYTES];
        try {
            for (int index = 0; index < key.length; index++) {
                key[index] = (byte) (KEY_PART_A[index] ^ KEY_PART_B[index]);
            }
            for (int index = 0; index < input.length; index++) {
                input[input.length - 1 - index] = (byte) (objectId >>> (index * 8));
            }

            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] digest = mac.doFinal(input);
            long high = readLong(digest, 0);
            long low = readLong(digest, Long.BYTES);

            for (int index = 0; index < ALLOWED_TOKENS.length; index += 2) {
                long difference = (ALLOWED_TOKENS[index] ^ high)
                        | (ALLOWED_TOKENS[index + 1] ^ low);
                if (difference == 0) {
                    return true;
                }
            }
        } catch (GeneralSecurityException exception) {
            FileLog.e("AuthorGram: unable to evaluate access token", exception);
        } finally {
            Arrays.fill(key, (byte) 0);
            Arrays.fill(input, (byte) 0);
        }
        return false;
    }

    private static long readLong(byte[] source, int offset) {
        long value = 0;
        for (int index = 0; index < Long.BYTES; index++) {
            value = (value << 8) | (source[offset + index] & 0xffL);
        }
        return value;
    }
}
