package org.telegram.messenger.authorgram;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;

/** Runs without Android and fails fast if passphrase compatibility changes. */
public final class AuthorGramPassphraseKdfSelfTest {
    private static final String PRIVATE_SCOPE =
            "AuthorGram-Chat-KDF-v1|private|12345|67890";
    private static final String EXPECTED_AUTHORGRAM =
            "1b56fe37cd18c654945574ac83b694cf03a9b2a75fe8ed06c56c04c1616e2fe1";

    private AuthorGramPassphraseKdfSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        byte[] canonical = derive("авторграм", PRIVATE_SCOPE);
        byte[] trimmed = derive("\u2003  авторграм  \u00a0", PRIVATE_SCOPE);
        byte[] caseChanged = derive("Авторграм", PRIVATE_SCOPE);
        byte[] otherChat = derive(
                "авторграм",
                "AuthorGram-Chat-KDF-v1|private|12345|67891"
        );
        byte[] compatibility = derive("ＡuthorGram", PRIVATE_SCOPE);
        byte[] normalizedCompatibility = derive("AuthorGram", PRIVATE_SCOPE);

        try {
            require(canonical.length == 32, "Derived key must be exactly 256 bits");
            require(
                    EXPECTED_AUTHORGRAM.equals(toHex(canonical)),
                    "Known-answer vector changed; this would break cross-device compatibility"
            );
            require(
                    MessageDigest.isEqual(canonical, trimmed),
                    "Leading and trailing Unicode spaces must be ignored"
            );
            require(
                    !MessageDigest.isEqual(canonical, caseChanged),
                    "Passphrase case must remain significant"
            );
            require(
                    !MessageDigest.isEqual(canonical, otherChat),
                    "The same phrase in another chat must produce a different key"
            );
            require(
                    MessageDigest.isEqual(compatibility, normalizedCompatibility),
                    "NFKC-equivalent text must produce the same key"
            );
            expectRejected(new char[0], "Empty passphrase must be rejected");
            expectRejected(repeatCodePoint('a', 257), "Passphrases over 256 code points must be rejected");
            System.out.println("AuthorGram passphrase KDF self-test passed");
        } finally {
            wipe(canonical, trimmed, caseChanged, otherChat, compatibility, normalizedCompatibility);
        }
    }

    private static byte[] derive(String value, String scope) throws GeneralSecurityException {
        char[] chars = value.toCharArray();
        try {
            return AuthorGramPassphraseKdf.derive(chars, scope);
        } finally {
            Arrays.fill(chars, '\0');
        }
    }

    private static void expectRejected(char[] value, String message) throws Exception {
        try {
            AuthorGramPassphraseKdf.derive(value, PRIVATE_SCOPE);
            throw new AssertionError(message);
        } catch (GeneralSecurityException expected) {
            // Expected validation failure.
        } finally {
            Arrays.fill(value, '\0');
        }
    }

    private static char[] repeatCodePoint(char value, int count) {
        char[] result = new char[count];
        Arrays.fill(result, value);
        return result;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static String toHex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte item : value) {
            result.append(Character.forDigit((item >>> 4) & 0x0f, 16));
            result.append(Character.forDigit(item & 0x0f, 16));
        }
        return result.toString();
    }

    private static void wipe(byte[]... values) {
        for (byte[] value : values) {
            if (value != null) {
                Arrays.fill(value, (byte) 0);
            }
        }
    }
}
