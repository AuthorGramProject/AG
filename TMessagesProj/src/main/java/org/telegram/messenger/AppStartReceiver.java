/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class AppStartReceiver extends BroadcastReceiver {

    private static final String ACTION_RESTART_PUSH_SERVICE = "org.telegram.start";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        boolean bootCompleted = Intent.ACTION_BOOT_COMPLETED.equals(action);
        boolean restartPushService = ACTION_RESTART_PUSH_SERVICE.equals(action);
        if (!bootCompleted && !restartPushService) {
            return;
        }

        AndroidUtilities.runOnUIThread(() -> {
            if (bootCompleted) {
                SharedConfig.loadConfig();
                if (SharedConfig.passcodeHash.length() > 0) {
                    SharedConfig.appLocked = true;
                    SharedConfig.saveConfig();
                }
            }
            ApplicationLoader.startPushService();
        });
    }
}
