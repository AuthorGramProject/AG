package org.telegram.messenger.authorgram;

/** Play build: system-key AuthorGram crypto is absent; only payload recognition remains. */
public final class AuthorGramCrypto {
    public static final String MARKER = "🛡AG:";

    private AuthorGramCrypto() { }

    public static boolean isAuthorGramPayload(String text) {
        return text != null && text.startsWith(MARKER);
    }

    public static String encryptText(String plaintext) {
        return null;
    }

    public static String decryptTextOrNull(String payload) {
        return null;
    }
}
