/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger;

import android.accounts.Account;
import android.app.Service;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.Context;
import android.content.Intent;
import android.content.SyncResult;
import android.os.Bundle;
import android.os.IBinder;

public class ContactsSyncAdapterService extends Service {

    private static final Object SYNC_ADAPTER_LOCK = new Object();
    private static SyncAdapterImpl syncAdapter;

    private static class SyncAdapterImpl extends AbstractThreadedSyncAdapter {

        private final Context context;

        SyncAdapterImpl(Context context) {
            super(context, true, true);
            this.context = context.getApplicationContext();
        }

        @Override
        public void onPerformSync(
                Account account,
                Bundle extras,
                String authority,
                ContentProviderClient provider,
                SyncResult syncResult
        ) {
            ContactsSyncAdapterService.performSync(
                    context,
                    account,
                    authority,
                    syncResult
            );
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        synchronized (SYNC_ADAPTER_LOCK) {
            if (syncAdapter == null) {
                syncAdapter = new SyncAdapterImpl(this);
            }
        }
        FileLog.d("AuthorGram AccountManager: Contacts SyncAdapter registered; accountType = "
                + AuthorGramSystemAccountManager.getAccountType(this)
                + ", authority = "
                + AuthorGramSystemAccountManager.CONTACTS_AUTHORITY);
    }

    @Override
    public IBinder onBind(Intent intent) {
        String action = intent == null ? null : intent.getAction();
        boolean validAction = "android.content.SyncAdapter".equals(action);
        FileLog.d("AuthorGram AccountManager: Contacts SyncAdapter bind = "
                + validAction);
        if (!validAction) {
            return null;
        }
        synchronized (SYNC_ADAPTER_LOCK) {
            if (syncAdapter == null) {
                syncAdapter = new SyncAdapterImpl(this);
            }
            return syncAdapter.getSyncAdapterBinder();
        }
    }

    private static void performSync(
            Context context,
            Account account,
            String authority,
            SyncResult syncResult
    ) {
        try {
            String expectedType =
                    AuthorGramSystemAccountManager.getAccountType(context);
            if (account == null
                    || !expectedType.equals(account.type)
                    || !AuthorGramSystemAccountManager.CONTACTS_AUTHORITY.equals(authority)) {
                syncResult.stats.numAuthExceptions++;
                FileLog.e("AuthorGram AccountManager: rejected SyncAdapter invocation"
                        + " because account type or authority does not match");
                return;
            }

            ApplicationLoader.postInitApplication();
            Long userId = AuthorGramSystemAccountManager.getTelegramUserId(
                    context,
                    account
            );
            if (userId == null) {
                syncResult.stats.numAuthExceptions++;
                FileLog.e("AuthorGram AccountManager: SyncAdapter account has no"
                        + " stable Telegram identity");
                return;
            }

            for (int accountSlot = 0;
                    accountSlot < UserConfig.MAX_ACCOUNT_COUNT;
                    accountSlot++) {
                UserConfig userConfig = UserConfig.getInstance(accountSlot);
                if (userConfig.getClientUserId() != userId) {
                    continue;
                }
                if (!userConfig.syncContacts) {
                    FileLog.d("AuthorGram AccountManager: contacts sync skipped"
                            + " by Telegram setting for slot " + accountSlot);
                    return;
                }
                ContactsController.getInstance(accountSlot).checkContacts();
                syncResult.stats.numEntries++;
                FileLog.d("AuthorGram AccountManager: contacts sync queued"
                        + " for slot " + accountSlot);
                return;
            }

            syncResult.stats.numAuthExceptions++;
            FileLog.e("AuthorGram AccountManager: SyncAdapter could not map"
                    + " Android account to an active Telegram slot");
        } catch (Throwable e) {
            syncResult.stats.numIoExceptions++;
            FileLog.e("AuthorGram AccountManager: contacts SyncAdapter failed");
            FileLog.e(e);
        }
    }
}
