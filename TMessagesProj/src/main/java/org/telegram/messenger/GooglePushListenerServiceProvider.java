package org.telegram.messenger;

import android.os.SystemClock;
import android.text.TextUtils;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import xyz.nextalone.nagram.NaConfig;

public class GooglePushListenerServiceProvider implements PushListenerController.IPushListenerServiceProvider {

    private static final long[] TOKEN_RETRY_DELAYS_MS = {
            30_000L,
            2 * 60_000L,
            10 * 60_000L,
            60 * 60_000L
    };

    private final AtomicBoolean tokenRequestInFlight = new AtomicBoolean(false);
    private final AtomicInteger retryAttempt = new AtomicInteger(0);

    public GooglePushListenerServiceProvider() {
    }

    @Override
    public String getLogTitle() {
        return "Google Play Services";
    }

    @Override
    public int getPushType() {
        return PushListenerController.PUSH_TYPE_FIREBASE;
    }

    @Override
    public void onRequestPushToken() {
        if (!tokenRequestInFlight.compareAndSet(false, true)) {
            return;
        }

        String currentPushString = SharedConfig.pushString;
        if (!TextUtils.isEmpty(currentPushString)) {
            if (BuildVars.DEBUG_PRIVATE_VERSION && BuildVars.LOGS_ENABLED) {
                FileLog.d("FCM registration token already cached");
            }
        } else if (BuildVars.LOGS_ENABLED) {
            FileLog.d("FCM registration token not found; requesting a new token");
        }

        Utilities.globalQueue.postRunnable(() -> {
            try {
                SharedConfig.pushStringGetTimeStart = SystemClock.elapsedRealtime();
                FirebaseApp firebaseApp = FirebaseApp.initializeApp(ApplicationLoader.applicationContext);
                if (firebaseApp == null) {
                    throw new IllegalStateException("Firebase initialization returned null");
                }
                FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
                    tokenRequestInFlight.set(false);
                    SharedConfig.pushStringGetTimeEnd = SystemClock.elapsedRealtime();
                    if (!task.isSuccessful() || TextUtils.isEmpty(task.getResult())) {
                        handleTokenFailure(task.getException());
                        return;
                    }

                    retryAttempt.set(0);
                    String token = task.getResult();
                    PushListenerController.sendRegistrationToServer(getPushType(), token);
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.d("FCM registration token acquired and queued for Telegram registration");
                    }
                });
            } catch (Throwable e) {
                tokenRequestInFlight.set(false);
                SharedConfig.pushStringGetTimeEnd = SystemClock.elapsedRealtime();
                handleTokenFailure(e);
            }
        });
    }

    private void handleTokenFailure(Throwable error) {
        if (BuildVars.LOGS_ENABLED) {
            if (error != null) {
                FileLog.e(error);
            }
            FileLog.w("FCM token acquisition failed; enabling local push fallback");
        }
        SharedConfig.pushStringStatus = "__FIREBASE_FAILED__";
        PushListenerController.sendRegistrationToServer(getPushType(), null);
        ApplicationLoader.startPushServiceFallback();

        int attempt = retryAttempt.getAndUpdate(value ->
                Math.min(value + 1, TOKEN_RETRY_DELAYS_MS.length - 1));
        long delay = TOKEN_RETRY_DELAYS_MS[Math.min(attempt, TOKEN_RETRY_DELAYS_MS.length - 1)];
        Utilities.globalQueue.postRunnable(() -> {
            int selectedType = NaConfig.INSTANCE.getPushServiceType().Int();
            if (selectedType == 1 || selectedType == 3) {
                onRequestPushToken();
            }
        }, delay);
    }

    @Override
    public boolean hasServices() {
        try {
            int resultCode = GoogleApiAvailability.getInstance()
                    .isGooglePlayServicesAvailable(ApplicationLoader.applicationContext);
            return resultCode == ConnectionResult.SUCCESS;
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }
}
