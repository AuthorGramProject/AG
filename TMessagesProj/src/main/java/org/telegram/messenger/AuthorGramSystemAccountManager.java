package org.telegram.messenger;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AuthenticatorDescription;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SyncAdapterType;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.text.TextUtils;

import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import tw.nekomimi.nekogram.NekoConfig;

/**
 * Owns AuthorGram's Android AccountManager integration.
 *
 * The generated string resource and BuildConfig.APPLICATION_ID are both derived
 * from APP_PACKAGE. The runtime equality check prevents a release from silently
 * registering a different account type in Java and XML metadata.
 */
public final class AuthorGramSystemAccountManager {

    private static final String LOG_PREFIX = "AuthorGram AccountManager: ";
    private static final String USER_ID_KEY = "authorgram.telegram_user_id";
    private static final String OWNER_PACKAGE_KEY = "authorgram.owner_application_id";
    private static final String SCHEMA_KEY = "authorgram.account_schema";
    private static final String SCHEMA_VERSION = "2";
    private static final String LEGACY_PLAY_ACCOUNT_TYPE = "toss.authorgram.apk";
    private static final Object LOCK = new Object();

    public static final String CONTACTS_AUTHORITY = ContactsContract.AUTHORITY;

    private AuthorGramSystemAccountManager() {
    }

    public static String getAccountType(Context context) {
        String generatedType = null;
        if (context != null) {
            try {
                generatedType = context.getString(R.string.authorgram_account_type);
            } catch (Throwable e) {
                FileLog.e(LOG_PREFIX + "failed to read generated account type");
                FileLog.e(e);
            }
        }
        if (!BuildConfig.APPLICATION_ID.equals(generatedType)) {
            FileLog.e(LOG_PREFIX + "generated account type mismatch; using applicationId");
            return BuildConfig.APPLICATION_ID;
        }
        return generatedType;
    }

    public static Map<Long, Account> reconcile(Context context) {
        if (context == null) {
            FileLog.e(LOG_PREFIX + "reconciliation skipped because context is null");
            return new LinkedHashMap<>();
        }
        Context appContext = context.getApplicationContext();
        if (appContext == null) {
            appContext = context;
        }
        synchronized (LOCK) {
            return reconcileLocked(appContext);
        }
    }

    public static Long getTelegramUserId(Context context, Account account) {
        if (context == null || account == null) {
            return null;
        }
        try {
            AccountManager accountManager = AccountManager.get(context);
            return readTelegramUserId(accountManager, account);
        } catch (Throwable e) {
            FileLog.e(LOG_PREFIX + "failed to read stable account identity");
            FileLog.e(e);
            return null;
        }
    }

    private static Map<Long, Account> reconcileLocked(Context context) {
        LinkedHashMap<Long, Account> resolvedAccounts = new LinkedHashMap<>();
        AccountManager accountManager = AccountManager.get(context);
        String accountType = getAccountType(context);
        boolean disabled = NekoConfig.disableSystemAccount.Bool();
        boolean authenticatorAvailable = isAuthenticatorAvailable(
                accountManager,
                accountType,
                context.getPackageName()
        );
        boolean syncAdapterAvailable = isSyncAdapterAvailable(accountType);
        Account[] existingAccounts = getAccounts(accountManager, accountType);

        FileLog.d(LOG_PREFIX + "applicationId = " + BuildConfig.APPLICATION_ID);
        FileLog.d(LOG_PREFIX + "accountType = " + accountType);
        FileLog.d(LOG_PREFIX + "DisableSystemAccount = " + disabled);
        FileLog.d(LOG_PREFIX + "Authenticator available = " + authenticatorAvailable);
        FileLog.d(LOG_PREFIX + "SyncAdapter available = " + syncAdapterAvailable);
        FileLog.d(LOG_PREFIX + "Existing accounts = " + existingAccounts.length);

        migrateOwnedLegacyAccounts(accountManager, accountType);

        if (disabled) {
            for (Account account : existingAccounts) {
                removeAccount(accountManager, account, "disabled by user");
            }
            FileLog.d(LOG_PREFIX + "Contacts syncable = disabled");
            FileLog.d(LOG_PREFIX + "Contacts auto sync = disabled");
            return resolvedAccounts;
        }

        if (!authenticatorAvailable) {
            FileLog.e(LOG_PREFIX + "cannot create accounts because authenticator is unavailable");
            return resolvedAccounts;
        }

        LinkedHashMap<Long, ActiveTelegramAccount> activeAccounts = getActiveTelegramAccounts();
        ArrayList<Account> legacyCandidates = new ArrayList<>();
        ArrayList<Account> accountsToRemove = new ArrayList<>();
        ArrayList<Account> protectedForeignAccounts = new ArrayList<>();

        for (Account account : existingAccounts) {
            String ownerPackage = getUserData(accountManager, account, OWNER_PACKAGE_KEY);
            Long userId = readTelegramUserId(accountManager, account);
            if (!TextUtils.isEmpty(ownerPackage)
                    && !BuildConfig.APPLICATION_ID.equals(ownerPackage)) {
                protectedForeignAccounts.add(account);
                continue;
            }

            if (userId == null) {
                legacyCandidates.add(account);
                continue;
            }

            ActiveTelegramAccount activeAccount = activeAccounts.get(userId);
            if (activeAccount != null && !resolvedAccounts.containsKey(userId)) {
                resolvedAccounts.put(userId, account);
                writeStableIdentity(accountManager, account, userId);
            } else {
                accountsToRemove.add(account);
            }
        }

        for (ActiveTelegramAccount activeAccount : activeAccounts.values()) {
            if (resolvedAccounts.containsKey(activeAccount.userId)) {
                continue;
            }
            Account legacyAccount = findLegacyNameMatch(legacyCandidates, activeAccount);
            if (legacyAccount != null) {
                legacyCandidates.remove(legacyAccount);
                writeStableIdentity(accountManager, legacyAccount, activeAccount.userId);
                resolvedAccounts.put(activeAccount.userId, legacyAccount);
                FileLog.d(LOG_PREFIX + "migrated legacy account identity for slot "
                        + activeAccount.accountSlot);
            }
        }

        // Old builds stored only a mutable display name. If it changed, the old
        // account cannot be matched by text. Pair remaining legacy accounts with
        // remaining active slots instead of deleting a potentially valid account.
        for (ActiveTelegramAccount activeAccount : activeAccounts.values()) {
            if (resolvedAccounts.containsKey(activeAccount.userId)
                    || legacyCandidates.isEmpty()) {
                continue;
            }
            Account legacyAccount = legacyCandidates.remove(0);
            writeStableIdentity(accountManager, legacyAccount, activeAccount.userId);
            resolvedAccounts.put(activeAccount.userId, legacyAccount);
            FileLog.d(LOG_PREFIX + "self-healed renamed legacy account for slot "
                    + activeAccount.accountSlot);
        }

        for (ActiveTelegramAccount activeAccount : activeAccounts.values()) {
            if (resolvedAccounts.containsKey(activeAccount.userId)) {
                continue;
            }
            Account createdAccount = createAndVerifyAccount(
                    accountManager,
                    accountType,
                    activeAccount
            );
            if (createdAccount != null) {
                resolvedAccounts.put(activeAccount.userId, createdAccount);
            }
        }

        accountsToRemove.addAll(legacyCandidates);
        for (Account account : accountsToRemove) {
            removeAccount(accountManager, account, "orphan or duplicate");
        }

        if (!protectedForeignAccounts.isEmpty()) {
            FileLog.w(LOG_PREFIX + "preserved " + protectedForeignAccounts.size()
                    + " account(s) marked as owned by another package");
        }

        for (ActiveTelegramAccount activeAccount : activeAccounts.values()) {
            Account account = resolvedAccounts.get(activeAccount.userId);
            if (account != null) {
                configureContactsSync(account, activeAccount);
            }
        }

        Account[] verifiedAccounts = getAccounts(accountManager, accountType);
        FileLog.d(LOG_PREFIX + "Account verified after reconciliation = "
                + resolvedAccounts.size() + "/" + activeAccounts.size());
        FileLog.d(LOG_PREFIX + "Verified account count = " + verifiedAccounts.length);
        FileLog.d(LOG_PREFIX + "Master sync = "
                + ContentResolver.getMasterSyncAutomatically());
        return resolvedAccounts;
    }

    private static LinkedHashMap<Long, ActiveTelegramAccount> getActiveTelegramAccounts() {
        LinkedHashMap<Long, ActiveTelegramAccount> result = new LinkedHashMap<>();
        for (int accountSlot = 0; accountSlot < UserConfig.MAX_ACCOUNT_COUNT; accountSlot++) {
            UserConfig userConfig = UserConfig.getInstance(accountSlot);
            TLRPC.User user = userConfig.getCurrentUser();
            long userId = userConfig.getClientUserId();
            if (!userConfig.isClientActivated() || user == null || userId == 0) {
                continue;
            }
            String displayName = ContactsController.formatName(user.first_name, user.last_name);
            if (TextUtils.isEmpty(displayName)) {
                displayName = "AuthorGram";
            }
            String stableSuffix = Long.toUnsignedString(userId, 36).toUpperCase(Locale.ROOT);
            String desiredAccountName = displayName + " · AG-" + stableSuffix;
            result.put(userId, new ActiveTelegramAccount(
                    accountSlot,
                    userId,
                    displayName,
                    desiredAccountName,
                    userConfig.syncContacts
            ));
        }
        return result;
    }

    private static Account findLegacyNameMatch(
            List<Account> candidates,
            ActiveTelegramAccount activeAccount
    ) {
        for (Account account : candidates) {
            if (account.name.equals(activeAccount.desiredAccountName)
                    || account.name.equals(activeAccount.displayName)
                    || account.name.equals(Long.toString(activeAccount.userId))) {
                return account;
            }
        }
        return null;
    }

    private static Account createAndVerifyAccount(
            AccountManager accountManager,
            String accountType,
            ActiveTelegramAccount activeAccount
    ) {
        Account account = new Account(activeAccount.desiredAccountName, accountType);
        Bundle userData = new Bundle();
        userData.putString(USER_ID_KEY, Long.toString(activeAccount.userId));
        userData.putString(OWNER_PACKAGE_KEY, BuildConfig.APPLICATION_ID);
        userData.putString(SCHEMA_KEY, SCHEMA_VERSION);

        boolean creationResult;
        try {
            creationResult = accountManager.addAccountExplicitly(account, null, userData);
            FileLog.d(LOG_PREFIX + "Account creation result = " + creationResult
                    + " for slot " + activeAccount.accountSlot);
        } catch (Throwable e) {
            FileLog.e(LOG_PREFIX + "AccountManager failed to create account for slot "
                    + activeAccount.accountSlot);
            FileLog.e(e);
            return null;
        }

        Account verifiedAccount = null;
        for (Account candidate : getAccounts(accountManager, accountType)) {
            Long candidateUserId = readTelegramUserId(accountManager, candidate);
            if (candidateUserId != null && candidateUserId == activeAccount.userId) {
                verifiedAccount = candidate;
                break;
            }
            if (candidate.name.equals(activeAccount.desiredAccountName)) {
                String ownerPackage = getUserData(
                        accountManager,
                        candidate,
                        OWNER_PACKAGE_KEY
                );
                if (TextUtils.isEmpty(ownerPackage)
                        || BuildConfig.APPLICATION_ID.equals(ownerPackage)) {
                    writeStableIdentity(
                            accountManager,
                            candidate,
                            activeAccount.userId
                    );
                    verifiedAccount = candidate;
                    break;
                }
            }
        }

        FileLog.d(LOG_PREFIX + "Account verified after creation = "
                + (verifiedAccount != null) + " for slot " + activeAccount.accountSlot);
        return verifiedAccount;
    }

    private static void writeStableIdentity(
            AccountManager accountManager,
            Account account,
            long userId
    ) {
        try {
            accountManager.setUserData(account, USER_ID_KEY, Long.toString(userId));
            accountManager.setUserData(
                    account,
                    OWNER_PACKAGE_KEY,
                    BuildConfig.APPLICATION_ID
            );
            accountManager.setUserData(account, SCHEMA_KEY, SCHEMA_VERSION);
        } catch (Throwable e) {
            FileLog.e(LOG_PREFIX + "failed to persist stable account identity");
            FileLog.e(e);
        }
    }

    private static Long readTelegramUserId(
            AccountManager accountManager,
            Account account
    ) {
        String value = getUserData(accountManager, account, USER_ID_KEY);
        if (TextUtils.isEmpty(value)) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            FileLog.e(LOG_PREFIX + "invalid stable account identity metadata");
            FileLog.e(e);
            return null;
        }
    }

    private static String getUserData(
            AccountManager accountManager,
            Account account,
            String key
    ) {
        try {
            return accountManager.getUserData(account, key);
        } catch (Throwable e) {
            FileLog.e(LOG_PREFIX + "AccountManager failed to read account metadata");
            FileLog.e(e);
            return null;
        }
    }

    private static void configureContactsSync(
            Account account,
            ActiveTelegramAccount activeAccount
    ) {
        try {
            int syncable = ContentResolver.getIsSyncable(account, CONTACTS_AUTHORITY);
            if (syncable != 1) {
                ContentResolver.setIsSyncable(account, CONTACTS_AUTHORITY, 1);
            }
            boolean autoSync = ContentResolver.getSyncAutomatically(
                    account,
                    CONTACTS_AUTHORITY
            );
            if (autoSync != activeAccount.syncContacts) {
                ContentResolver.setSyncAutomatically(
                        account,
                        CONTACTS_AUTHORITY,
                        activeAccount.syncContacts
                );
            }
            int verifiedSyncable = ContentResolver.getIsSyncable(
                    account,
                    CONTACTS_AUTHORITY
            );
            boolean verifiedAutoSync = ContentResolver.getSyncAutomatically(
                    account,
                    CONTACTS_AUTHORITY
            );
            FileLog.d(LOG_PREFIX + "Contacts syncable = " + verifiedSyncable
                    + " for slot " + activeAccount.accountSlot);
            FileLog.d(LOG_PREFIX + "Contacts auto sync = " + verifiedAutoSync
                    + " for slot " + activeAccount.accountSlot);
        } catch (Throwable e) {
            FileLog.e(LOG_PREFIX + "failed to configure contacts sync for slot "
                    + activeAccount.accountSlot);
            FileLog.e(e);
        }
    }

    private static boolean isAuthenticatorAvailable(
            AccountManager accountManager,
            String accountType,
            String packageName
    ) {
        try {
            for (AuthenticatorDescription description
                    : accountManager.getAuthenticatorTypes()) {
                if (accountType.equals(description.type)
                        && packageName.equals(description.packageName)) {
                    return true;
                }
            }
        } catch (Throwable e) {
            FileLog.e(LOG_PREFIX + "failed to inspect authenticators");
            FileLog.e(e);
        }
        return false;
    }

    private static boolean isSyncAdapterAvailable(String accountType) {
        try {
            for (SyncAdapterType syncAdapterType
                    : ContentResolver.getSyncAdapterTypes()) {
                if (accountType.equals(syncAdapterType.accountType)
                        && CONTACTS_AUTHORITY.equals(syncAdapterType.authority)) {
                    return true;
                }
            }
        } catch (Throwable e) {
            FileLog.e(LOG_PREFIX + "failed to inspect sync adapters");
            FileLog.e(e);
        }
        return false;
    }

    private static Account[] getAccounts(
            AccountManager accountManager,
            String accountType
    ) {
        try {
            Account[] accounts = accountManager.getAccountsByType(accountType);
            return accounts == null ? new Account[0] : accounts;
        } catch (Throwable e) {
            FileLog.e(LOG_PREFIX + "AccountManager failed to list type "
                    + accountType);
            FileLog.e(e);
            return new Account[0];
        }
    }

    private static void removeAccount(
            AccountManager accountManager,
            Account account,
            String reason
    ) {
        try {
            boolean result = accountManager.removeAccountExplicitly(account);
            FileLog.d(LOG_PREFIX + "Account removal result = " + result
                    + " reason = " + reason);
        } catch (Throwable e) {
            FileLog.e(LOG_PREFIX + "AccountManager failed to remove account; reason = "
                    + reason);
            FileLog.e(e);
        }
    }

    private static void migrateOwnedLegacyAccounts(
            AccountManager accountManager,
            String accountType
    ) {
        if (LEGACY_PLAY_ACCOUNT_TYPE.equals(accountType)) {
            return;
        }
        Account[] legacyAccounts = getAccounts(
                accountManager,
                LEGACY_PLAY_ACCOUNT_TYPE
        );
        int removed = 0;
        int preserved = 0;
        for (Account legacyAccount : legacyAccounts) {
            String ownerPackage = getUserData(
                    accountManager,
                    legacyAccount,
                    OWNER_PACKAGE_KEY
            );
            if (BuildConfig.APPLICATION_ID.equals(ownerPackage)) {
                removeAccount(
                        accountManager,
                        legacyAccount,
                        "owned legacy account type migration"
                );
                removed++;
            } else {
                preserved++;
            }
        }
        if (legacyAccounts.length > 0) {
            FileLog.d(LOG_PREFIX + "Legacy account type visible = "
                    + legacyAccounts.length + ", safely removed = " + removed
                    + ", preserved = " + preserved);
        }
    }

    private static final class ActiveTelegramAccount {
        final int accountSlot;
        final long userId;
        final String displayName;
        final String desiredAccountName;
        final boolean syncContacts;

        ActiveTelegramAccount(
                int accountSlot,
                long userId,
                String displayName,
                String desiredAccountName,
                boolean syncContacts
        ) {
            this.accountSlot = accountSlot;
            this.userId = userId;
            this.displayName = displayName;
            this.desiredAccountName = desiredAccountName;
            this.syncContacts = syncContacts;
        }
    }
}
