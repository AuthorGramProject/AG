package org.telegram.messenger.authorgram;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.Arrays;

import javax.crypto.Mac;
import javax.crypto.ShortBufferException;
import javax.crypto.spec.SecretKeySpec;

/** Pure-Java, deterministic AuthorGram chat passphrase derivation. */
final class AuthorGramPassphraseKdf {
    static final String DOMAIN = "AuthorGram-Chat-KDF-v1";
    static final int ITERATIONS = 600_000;
    static final int KEY_BYTES = 32;
    static final int MAX_CODE_POINTS = 256;

    private AuthorGramPassphraseKdf() {
    }

    static byte[] derive(char[] passphrase, String stableScope)
            throws GeneralSecurityException {
        if (passphrase == null) {
            throw new GeneralSecurityException("Missing passphrase");
        }
        if (stableScope == null || stableScope.isEmpty()) {
            throw new GeneralSecurityException("Missing KDF scope");
        }

        String normalized = normalize(passphrase);
        byte[] passwordBytes = normalized.getBytes(StandardCharsets.UTF_8);
        byte[] scopeBytes = stableScope.getBytes(StandardCharsets.UTF_8);
        byte[] salt = null;
        try {
            salt = MessageDigest.getInstance("SHA-256").digest(scopeBytes);
            return pbkdf2HmacSha256(passwordBytes, salt, ITERATIONS, KEY_BYTES);
        } finally {
            Arrays.fill(passwordBytes, (byte) 0);
            Arrays.fill(scopeBytes, (byte) 0);
            if (salt != null) {
                Arrays.fill(salt, (byte) 0);
            }
        }
    }

    static String normalize(char[] passphrase) throws GeneralSecurityException {
        if (passphrase == null) {
            throw new GeneralSecurityException("Missing passphrase");
        }
        String value = stripUnicodeWhitespace(new String(passphrase));
        value = Normalizer.normalize(value, Normalizer.Form.NFKC);
        if (value.isEmpty()) {
            throw new GeneralSecurityException("Passphrase must not be empty");
        }
        if (value.codePointCount(0, value.length()) > MAX_CODE_POINTS) {
            throw new GeneralSecurityException("Passphrase is too long");
        }
        return value;
    }

    private static String stripUnicodeWhitespace(String value) {
        int start = 0;
        int end = value.length();
        while (start < end) {
            int codePoint = value.codePointAt(start);
            if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)) {
                break;
            }
            start += Character.charCount(codePoint);
        }
        while (end > start) {
            int codePoint = value.codePointBefore(end);
            if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)) {
                break;
            }
            end -= Character.charCount(codePoint);
        }
        return value.substring(start, end);
    }

    private static byte[] pbkdf2HmacSha256(
            byte[] password,
            byte[] salt,
            int iterations,
            int outputLength
    ) throws GeneralSecurityException {
        if (iterations < 1 || outputLength < 1) {
            throw new GeneralSecurityException("Invalid KDF parameters");
        }

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(password, "HmacSHA256"));
        int macLength = mac.getMacLength();
        int blockCount = (outputLength + macLength - 1) / macLength;
        byte[] output = new byte[outputLength];
        byte[] blockInput = new byte[salt.length + 4];
        byte[] u = new byte[macLength];
        byte[] next = new byte[macLength];
        byte[] block = new byte[macLength];
        System.arraycopy(salt, 0, blockInput, 0, salt.length);

        try {
            for (int blockIndex = 1; blockIndex <= blockCount; blockIndex++) {
                int offset = salt.length;
                blockInput[offset] = (byte) (blockIndex >>> 24);
                blockInput[offset + 1] = (byte) (blockIndex >>> 16);
                blockInput[offset + 2] = (byte) (blockIndex >>> 8);
                blockInput[offset + 3] = (byte) blockIndex;

                mac.update(blockInput);
                mac.doFinal(u, 0);
                System.arraycopy(u, 0, block, 0, macLength);

                for (int iteration = 1; iteration < iterations; iteration++) {
                    mac.update(u);
                    mac.doFinal(next, 0);
                    for (int index = 0; index < macLength; index++) {
                        block[index] ^= next[index];
                    }
                    byte[] swap = u;
                    u = next;
                    next = swap;
                }

                int destination = (blockIndex - 1) * macLength;
                int count = Math.min(macLength, outputLength - destination);
                System.arraycopy(block, 0, output, destination, count);
                Arrays.fill(u, (byte) 0);
                Arrays.fill(next, (byte) 0);
                Arrays.fill(block, (byte) 0);
            }
            return output;
        } catch (ShortBufferException exception) {
            Arrays.fill(output, (byte) 0);
            throw new GeneralSecurityException("Unable to derive AuthorGram chat key", exception);
        } finally {
            Arrays.fill(blockInput, (byte) 0);
            Arrays.fill(u, (byte) 0);
            Arrays.fill(next, (byte) 0);
            Arrays.fill(block, (byte) 0);
        }
    }
}
