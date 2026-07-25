package org.telegram.messenger.authorgram;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * One-time Play build migration that removes the previously injected
 * Local Premium preference. New Play installs no longer receive this default.
 */
public final class PlayDefaultsMigrationProvider extends ContentProvider {

    private static final String PREFERENCES = "nkmrcfg";
    private static final String LOCAL_PREMIUM = "localPremium";
    private static final String MIGRATION_DONE =
            "authorgram_play_local_premium_default_removed_v1";

    @Override
    public boolean onCreate() {
        Context context = getContext();
        if (context == null) {
            return false;
        }

        SharedPreferences preferences = context.getSharedPreferences(
                PREFERENCES,
                Context.MODE_PRIVATE
        );

        if (!preferences.getBoolean(MIGRATION_DONE, false)) {
            preferences.edit()
                    .remove(LOCAL_PREMIUM)
                    .putBoolean(MIGRATION_DONE, true)
                    .apply();
        }

        return true;
    }

    @Nullable
    @Override
    public Cursor query(
            @NonNull Uri uri,
            @Nullable String[] projection,
            @Nullable String selection,
            @Nullable String[] selectionArgs,
            @Nullable String sortOrder
    ) {
        return null;
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        return null;
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        return null;
    }

    @Override
    public int delete(
            @NonNull Uri uri,
            @Nullable String selection,
            @Nullable String[] selectionArgs
    ) {
        return 0;
    }

    @Override
    public int update(
            @NonNull Uri uri,
            @Nullable ContentValues values,
            @Nullable String selection,
            @Nullable String[] selectionArgs
    ) {
        return 0;
    }
}
