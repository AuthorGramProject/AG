/*
 * This is the source code of Telegram for Android v. 1.3.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

public class NotificationsService extends Service {

    private static final int NOTIFICATION_ID = 9999;
    private static final String CHANNEL_ID = "push_service_channel";

    @Override
    public void onCreate() {
        super.onCreate();

        if (!ApplicationLoader.shouldKeepPushServiceRunning()) {
            stopSelf();
            return;
        }

        NotificationManager notificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                LocaleController.getString(R.string.NagramXPushService),
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setSound(null, null);
        channel.enableVibration(false);
        notificationManager.createNotificationChannel(channel);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setShowWhen(false)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setSmallIcon(R.drawable.neko_notification)
                .setContentText(LocaleController.getString(R.string.NagramXPushService))
                .build();

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
                );
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Throwable e) {
            Log.e("TFOSS", "Failed to start remote messaging service", e);
            stopSelf();
            return;
        }

        ApplicationLoader.postInitApplication();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!ApplicationLoader.shouldKeepPushServiceRunning()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        sendRestartBroadcastIfNeeded();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (Throwable ignore) {
        }
        sendRestartBroadcastIfNeeded();
        super.onDestroy();
    }

    private void sendRestartBroadcastIfNeeded() {
        if (!ApplicationLoader.shouldKeepPushServiceRunning()) {
            return;
        }
        Intent intent = new Intent("org.telegram.start");
        intent.setPackage(getPackageName());
        try {
            sendBroadcast(intent);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    @Override
    public void onTimeout(int startId, int fgsType) {
        super.onTimeout(startId, fgsType);
        FileLog.w("Remote messaging fallback received an unexpected foreground-service timeout");
        stopSelf();
    }
}
