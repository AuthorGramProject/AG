package org.telegram.messenger.authorgram;

import android.content.Context;
import android.content.pm.ApplicationInfo;
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

/** Verifies the installed AuthorGram release identity before protected features run. */
public final class AuthorGramBuildIntegrity {
    private static volatile Boolean trusted;
    private static volatile boolean failureLogged;

    private AuthorGramBuildIntegrity() {
    }

    /**
     * Returns true only for a non-debuggable official build whose package and
     * signing certificate match the release configuration.
     *
     * Version metadata is deliberately not used as an identity primitive: OEM
     * package managers can expose incomplete PackageInfo metadata, while package
     * name + release flags + APK signer are the stable anti-repack boundary.
     */
    public static boolean isTrustedBuild() {
        if (!BuildConfig.OFFICIAL_BUILD || BuildConfig.DEBUG) {
            return false;
        }

        Boolean cached = trusted;
        if (cached != null) {
            return cached;
        }

        Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            return false;
        }

        synchronized (AuthorGramBuildIntegrity.class) {
            if (trusted == null) {
                trusted = verifyInstalledRelease(context);
                if (!trusted && !failureLogged) {
                    failureLogged = true;
                    FileLog.e("AuthorGram: release integrity verification failed; protected features disabled");
                }
            }
            return trusted;
        }
    }

        public static boolean isTampered() {
        if (!BuildConfig.OFFICIAL_BUILD || BuildConfig.DEBUG) {
            return false;
        }
        return !isTrustedBuild();
    }

        public static void enforceIntegrity(android.app.Activity activity) {
        if (isTampered()) {
            org.telegram.messenger.FileLog.e("AuthorGram: TAMPERING DETECTED! Enforcing protection.");
            
            // Logout all accounts
            for (int i = 0; i < org.telegram.messenger.UserConfig.MAX_ACCOUNT_COUNT; i++) {
                if (org.telegram.messenger.UserConfig.getInstance(i).isClientActivated()) {
                    org.telegram.messenger.MessagesController.getInstance(i).performLogout(1);
                }
            }
            
            // Overlay black screen permanently
            android.view.ViewGroup rootView = (android.view.ViewGroup) activity.getWindow().getDecorView().getRootView();
            android.widget.FrameLayout blackFrame = new android.widget.FrameLayout(activity);
            blackFrame.setBackgroundColor(0xFF000000);
            blackFrame.setClickable(true); // block touches
            blackFrame.setFocusable(true);
            rootView.addView(blackFrame, new android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, 
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }
        }
    }

    public static boolean canUseSystemKey() {
        if (!AuthorGramPlayPolicy.hasEmbeddedSystemKey()) {
            return false;
        }
        if (!BuildConfig.OFFICIAL_BUILD) {
            return false;
        }
        return isTrustedBuild();
    }

    private static boolean verifyInstalledRelease(Context context) {
        String configured = BuildConfig.TRUSTED_SIGNING_CERT_SHA256;
        if (configured == null || configured.trim().isEmpty()) {
            return false;
        }
        if (!BuildConfig.APPLICATION_ID.equals(context.getPackageName())) {
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
            ApplicationInfo applicationInfo = manager.getApplicationInfo(
                    BuildConfig.APPLICATION_ID,
                    0
            );
            if ((applicationInfo.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0
                    || (applicationInfo.flags & ApplicationInfo.FLAG_TEST_ONLY) != 0) {
                return false;
            }

            PackageInfo info;
            Signature[] signatures;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info = manager.getPackageInfo(
                        BuildConfig.APPLICATION_ID,
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
                info = manager.getPackageInfo(
                        BuildConfig.APPLICATION_ID,
                        PackageManager.GET_SIGNATURES
                );
                //noinspection deprecation
                signatures = info.signatures;
            }

            if (!BuildConfig.APPLICATION_ID.equals(info.packageName)
                    || signatures == null
                    || signatures.length == 0) {
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
            FileLog.e("AuthorGram: unable to verify installed release identity", exception);
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
