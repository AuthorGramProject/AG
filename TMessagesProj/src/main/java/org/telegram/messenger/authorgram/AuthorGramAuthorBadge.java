package org.telegram.messenger.authorgram;

import android.util.LruCache;

import org.telegram.messenger.FileLog;

import java.security.GeneralSecurityException;
import java.util.Arrays;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * AuthorGram-local author badge policy.
 *
 * The allowed Telegram identifiers are not stored as decimal strings or raw
 * long constants. Release builds compare a keyed 128-bit token instead, and
 * the badge is disabled when the installed APK does not carry a trusted
 * AuthorGram signing certificate.
 */
public final class AuthorGramAuthorBadge {
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

    // HMAC-SHA-256(id), truncated to 128 bits. Two longs per allowed ID.
    private static final long[] ALLOWED_TOKENS = {
            0x0f259ffd5df5e971L, 0x8a41be1cfe96d4fbL,
            0x16c0d73ff2f3db0dL, 0x90461d146f0cd07cL,
            0x38e1f81c468c4d8aL, 0x1170290f5105f1dcL,
            0x5352bc7e0d427b34L, 0x293cc1cb4c8fe778L,
            0x6b59816d18c0de85L, 0xab0bd9c6452ff8caL,
            0x77357f6e0b552d43L, 0x73b90dbfb54a32d0L,
            0x7fda0da6a732d638L, 0x5cc1cc8dcf2078ddL,
            0x908c337b8fe995b6L, 0xffb1041cecd1a7c4L,
            0xb61c8759e592ce24L, 0x72395ff93822469eL,
            0xb654728b2a94d189L, 0x69ed8b3f365254f2L,
            0xbafb0bcb4f06825bL, 0x62bbffe86bbf981dL,
            0xc181050d8c94602dL, 0xbc77509dbab33e45L,
            0xc7bcb2bd0415975bL, 0xdc463b41501eb685L,
            0xcab093d68f3b1c7bL, 0xcedc3a920d93144bL,
            0xccb5de3c96fa3221L, 0x4037dd203908407eL
    };

    private static final LruCache<Long, Boolean> CACHE = new LruCache<>(512);

    private AuthorGramAuthorBadge() {
    }

    public static boolean matches(long objectId) {
        // User IDs and chat.id values are positive, while Telegram dialog IDs for
        // groups/channels are negative. Normalize both forms to the same internal
        // peer identifier before evaluating the protected token.
        long normalizedId = objectId == Long.MIN_VALUE ? 0 : Math.abs(objectId);
        if (normalizedId == 0 || !AuthorGramBuildIntegrity.isTrustedBuild()) {
            return false;
        }

        synchronized (CACHE) {
            Boolean cached = CACHE.get(normalizedId);
            if (cached != null) {
                return cached;
            }
        }

        boolean allowed = matchesToken(normalizedId);
        synchronized (CACHE) {
            CACHE.put(normalizedId, allowed);
        }
        return allowed;
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
            FileLog.e("AuthorGram: unable to evaluate author badge token", exception);
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
