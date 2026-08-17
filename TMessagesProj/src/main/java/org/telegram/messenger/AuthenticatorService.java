/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger;

import android.accounts.AbstractAccountAuthenticator;
import android.accounts.Account;
import android.accounts.AccountAuthenticatorResponse;
import android.accounts.AccountManager;
import android.accounts.NetworkErrorException;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;

public class AuthenticatorService extends Service {

    private Authenticator authenticator;

    private static class Authenticator extends AbstractAccountAuthenticator {

        Authenticator(Context context) {
            super(context);
        }

        @Override
        public Bundle addAccount(
                AccountAuthenticatorResponse response,
                String accountType,
                String authTokenType,
                String[] requiredFeatures,
                Bundle options
        ) throws NetworkErrorException {
            FileLog.d("AuthorGram AccountManager: external addAccount request for type = "
                    + accountType);
            return null;
        }

        @Override
        public Bundle getAccountRemovalAllowed(
                AccountAuthenticatorResponse response,
                Account account
        ) throws NetworkErrorException {
            FileLog.d("AuthorGram AccountManager: account removal requested for type = "
                    + (account == null ? "null" : account.type));
            return super.getAccountRemovalAllowed(response, account);
        }

        @Override
        public Bundle confirmCredentials(
                AccountAuthenticatorResponse response,
                Account account,
                Bundle options
        ) throws NetworkErrorException {
            return null;
        }

        @Override
        public Bundle editProperties(
                AccountAuthenticatorResponse response,
                String accountType
        ) {
            return null;
        }

        @Override
        public Bundle getAuthToken(
                AccountAuthenticatorResponse response,
                Account account,
                String authTokenType,
                Bundle options
        ) throws NetworkErrorException {
            return null;
        }

        @Override
        public String getAuthTokenLabel(String authTokenType) {
            return null;
        }

        @Override
        public Bundle hasFeatures(
                AccountAuthenticatorResponse response,
                Account account,
                String[] features
        ) throws NetworkErrorException {
            return null;
        }

        @Override
        public Bundle updateCredentials(
                AccountAuthenticatorResponse response,
                Account account,
                String authTokenType,
                Bundle options
        ) throws NetworkErrorException {
            return null;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        authenticator = new Authenticator(getApplicationContext());
        FileLog.d("AuthorGram AccountManager: AuthenticatorService created; applicationId = "
                + BuildConfig.APPLICATION_ID + ", accountType = "
                + AuthorGramSystemAccountManager.getAccountType(this));
    }

    @Override
    public IBinder onBind(Intent intent) {
        String action = intent == null ? null : intent.getAction();
        boolean validAction = AccountManager.ACTION_AUTHENTICATOR_INTENT.equals(action);
        FileLog.d("AuthorGram AccountManager: AuthenticatorService bind = "
                + validAction);
        if (!validAction) {
            return null;
        }
        if (authenticator == null) {
            authenticator = new Authenticator(getApplicationContext());
        }
        return authenticator.getIBinder();
    }
}
