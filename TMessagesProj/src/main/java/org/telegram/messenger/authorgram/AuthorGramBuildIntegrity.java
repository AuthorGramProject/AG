package org.telegram.messenger.authorgram;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.FileLog;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Verifies the installed release signature before shared system-key access. */
public final class AuthorGramBuildIntegrity {
    private static volatile Boolean trusted;

    private AuthorGramBuildIntegrity() {
    }

    public static boolean canUseSystemKey() {
        if (!AuthorGramPlayPolicy.hasEmbeddedSystemKey()) {
            return false;
        }
        if (!BuildConfig.OFFICIAL_BUILD) {
            return true;
        }
        Boolean cached = trusted;
        if (cached != null) {
            return cached;
        }
        synchronized (AuthorGramBuildIntegrity.class) {
            if (trusted == null) {
                trusted = verifyInstalledSignature();
                if (!trusted) {
                    FileLog.e("AuthorGram: APK signature verification failed; system-key crypto disabled");
                }
            }
            return trusted;
        }
    }

    private static boolean verifyInstalledSignature() {
        String configured = BuildConfig.TRUSTED_SIGNING_CERT_SHA256;
        if (configured == null || configured.trim().isEmpty()) {
            return false;
        }
        Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            return false;
        }
        List<byte[]> expected = new ArrayList<>();
        for (String item : configured.split(",")) {
            byte[] digest = decodeDigest(item);
            if (digest != null) {
                expected.add(digest);
            }
        }
        if (expected.isEmpty()) {
            return false;
        }
        try {
            PackageManager manager = context.getPackageManager();
            PackageInfo info;
            Signature[] signatures;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info = manager.getPackageInfo(
                        context.getPackageName(),
                        PackageManager.GET_SIGNING_CERTIFICATES
                );
                SigningInfo signingInfo = info.signingInfo;
                if (signingInfo == null) {
                    return false;
                }
                signatures = signingInfo.hasMultipleSigners()
                        ? signingInfo.getApkContentsSigners()
                        : signingInfo.getSigningCertificateHistory();
            } else {
                //noinspection deprecation
                info = manager.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNATURES);
                //noinspection deprecation
                signatures = info.signatures;
            }
            if (signatures == null || signatures.length == 0) {
                return false;
            }
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            for (Signature signature : signatures) {
                byte[] actual = sha256.digest(signature.toByteArray());
                for (byte[] allowed : expected) {
                    if (MessageDigest.isEqual(actual, allowed)) {
                        return true;
                    }
                }
            }
        } catch (PackageManager.NameNotFoundException | NoSuchAlgorithmException exception) {
            FileLog.e("AuthorGram: unable to verify APK signature", exception);
        }
        return false;
    }

    private static byte[] decodeDigest(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.replaceAll("[^0-9A-Fa-f]", "").toLowerCase(Locale.ROOT);
        if (normalized.length() != 64) {
            return null;
        }
        byte[] result = new byte[32];
        for (int index = 0; index < result.length; index++) {
            int high = Character.digit(normalized.charAt(index * 2), 16);
            int low = Character.digit(normalized.charAt(index * 2 + 1), 16);
            if (high < 0 || low < 0) {
                return null;
            }
            result[index] = (byte) ((high << 4) | low);
        }
        return result;
    }
}
