package org.telegram.messenger.authorgram;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/**
 * Splits plaintext before AuthorGram AES-GCM encryption.
 *
 * Wire format:
 *
 *     🛡AG:Base64(12-byte IV || ciphertext || 16-byte GCM tag)
 *
 * Every returned part is independent and is encrypted separately
 * later by AuthorGramCryptoInterceptor, therefore every network
 * message receives its own random IV.
 */
public final class AuthorGramMessageSplitter {

    private static final String MARKER =
            "🛡AG:";

    private static final int IV_BYTES =
            12;

    private static final int GCM_TAG_BYTES =
            16;

    private AuthorGramMessageSplitter() {
    }

    /**
     * Conservative plaintext character limit for Telegram's normal
     * splitter.
     *
     * A valid Java UTF-16 char requires at most three UTF-8 bytes
     * per individual char unit. Supplementary Unicode characters
     * use two UTF-16 chars for four UTF-8 bytes, so this estimate
     * remains conservative.
     */
    public static int getSafePlaintextCharLimit(
            int encryptedWireLimit
    ) {
        if (encryptedWireLimit <= MARKER.length() + 40) {
            return 1;
        }

        int low = 1;
        int high = encryptedWireLimit;
        int best = 1;

        while (low <= high) {
            int middle =
                    low + (high - low) / 2;

            long worstCaseUtf8Bytes =
                    (long) middle * 3L;

            long packedBytes =
                    worstCaseUtf8Bytes
                            + IV_BYTES
                            + GCM_TAG_BYTES;

            long base64Length =
                    4L
                            * ((packedBytes + 2L) / 3L);

            long wireLength =
                    MARKER.length()
                            + base64Length;

            if (wireLength <= encryptedWireLimit) {
                best = middle;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }

        return Math.max(
                1,
                best
        );
    }

    /**
     * Exact encrypted wire-length estimate for a concrete plaintext
     * part.
     */
    public static int estimateEncryptedWireLength(
            CharSequence plaintext
    ) {
        if (plaintext == null) {
            return 0;
        }

        int plaintextBytes =
                plaintext.toString()
                        .getBytes(
                                StandardCharsets.UTF_8
                        )
                        .length;

        long packedBytes =
                (long) plaintextBytes
                        + IV_BYTES
                        + GCM_TAG_BYTES;

        long base64Length =
                4L
                        * ((packedBytes + 2L) / 3L);

        long total =
                MARKER.length()
                        + base64Length;

        if (total > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }

        return (int) total;
    }

    /**
     * Splits arbitrary plaintext while ensuring that every encrypted
     * AuthorGram payload stays inside the supplied wire limit.
     *
     * Preference:
     *
     * 1. newline
     * 2. whitespace
     * 3. exact safe Unicode boundary
     */
    public static ArrayList<CharSequence> split(
            CharSequence text,
            int encryptedWireLimit
    ) {
        ArrayList<CharSequence> result =
                new ArrayList<>();

        if (text == null ||
                text.length() == 0) {

            return result;
        }

        int start = 0;

        while (start < text.length()) {

            int proposedEnd =
                    Math.min(
                            text.length(),
                            start
                                    + Math.max(
                                            1,
                                            encryptedWireLimit
                                    )
                    );

            int end =
                    findLargestSafeEnd(
                            text,
                            start,
                            proposedEnd,
                            encryptedWireLimit
                    );

            end =
                    moveToNaturalBoundary(
                            text,
                            start,
                            end
                    );

            end =
                    avoidBrokenSurrogatePair(
                            text,
                            start,
                            end
                    );

            if (end <= start) {

                int codePoint =
                        Character.codePointAt(
                                text,
                                start
                        );

                end =
                        Math.min(
                                text.length(),
                                start
                                        + Character.charCount(
                                                codePoint
                                        )
                        );
            }

            /*
             * Natural-boundary adjustment must never accidentally
             * create an oversized encrypted payload.
             */
            while (end > start
                    && estimateEncryptedWireLength(
                            text.subSequence(
                                    start,
                                    end
                            )
                    ) > encryptedWireLimit) {

                end--;

                end =
                        avoidBrokenSurrogatePair(
                                text,
                                start,
                                end
                        );
            }

            if (end <= start) {
                throw new IllegalStateException(
                        "AuthorGram: unable to create safe message chunk"
                );
            }

            result.add(
                    text.subSequence(
                            start,
                            end
                    )
            );

            start =
                    end;
        }

        return result;
    }

    private static int findLargestSafeEnd(
            CharSequence text,
            int start,
            int proposedEnd,
            int encryptedWireLimit
    ) {
        if (estimateEncryptedWireLength(
                text.subSequence(
                        start,
                        proposedEnd
                )
        ) <= encryptedWireLimit) {

            return proposedEnd;
        }

        int low =
                start + 1;

        int high =
                proposedEnd;

        int best =
                start;

        while (low <= high) {

            int middle =
                    low
                            + (high - low) / 2;

            int length =
                    estimateEncryptedWireLength(
                            text.subSequence(
                                    start,
                                    middle
                            )
                    );

            if (length <= encryptedWireLimit) {

                best =
                        middle;

                low =
                        middle + 1;

            } else {

                high =
                        middle - 1;
            }
        }

        return best;
    }

    private static int moveToNaturalBoundary(
            CharSequence text,
            int start,
            int end
    ) {
        if (end >= text.length()) {
            return end;
        }

        int distance =
                end - start;

        int minimumSearchPosition =
                start
                        + Math.max(
                                1,
                                distance * 2 / 3
                        );

        /*
         * Prefer a newline.
         */
        for (int index = end - 1;
             index >= minimumSearchPosition;
             index--) {

            if (text.charAt(index) == '\n') {
                return index + 1;
            }
        }

        /*
         * Otherwise prefer ordinary whitespace.
         */
        for (int index = end - 1;
             index >= minimumSearchPosition;
             index--) {

            if (Character.isWhitespace(
                    text.charAt(index)
            )) {

                return index + 1;
            }
        }

        return end;
    }

    private static int avoidBrokenSurrogatePair(
            CharSequence text,
            int start,
            int end
    ) {
        if (end > start
                && end < text.length()
                && Character.isHighSurrogate(
                        text.charAt(
                                end - 1
                        )
                )
                && Character.isLowSurrogate(
                        text.charAt(
                                end
                        )
                )) {

            return end - 1;
        }

        return end;
    }
}
