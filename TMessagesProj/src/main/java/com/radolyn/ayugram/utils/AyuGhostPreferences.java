package com.radolyn.ayugram.utils;

import org.telegram.messenger.authorgram.AuthorGramSpyPolicy;

import java.util.concurrent.ConcurrentHashMap;

import tw.nekomimi.nekogram.NekoConfig;

public class AyuGhostPreferences {
    public static final String ghostReadExclusionPrefix = "ghostModeReadExclusion_";
    public static final String ghostTypingExclusionPrefix = "ghostModeTypingExclusion_";
    private static final ConcurrentHashMap<Long, Boolean> ghostModeReadExclusions = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, Boolean> ghostModeTypingExclusions = new ConcurrentHashMap<>();

    public static void setGhostModeReadExclusion(long chatId, boolean value) {
        long key = Math.abs(chatId);
        ghostModeReadExclusions.put(key, value);
        NekoConfig.getPreferences().edit().putBoolean(ghostReadExclusionPrefix + key, value).apply();
    }

    public static boolean getGhostModeReadExclusion(long chatId) {
        if (AuthorGramSpyPolicy.isSpyDisabled(chatId)) {
            return true;
        }
        long key = Math.abs(chatId);
        return ghostModeReadExclusions.computeIfAbsent(key, k ->
                NekoConfig.getPreferences().getBoolean(ghostReadExclusionPrefix + k, false)
        );
    }

    public static void setGhostModeTypingExclusion(long chatId, boolean value) {
        long key = Math.abs(chatId);
        ghostModeTypingExclusions.put(key, value);
        NekoConfig.getPreferences().edit().putBoolean(ghostTypingExclusionPrefix + key, value).apply();
    }

    public static boolean getGhostModeTypingExclusion(long chatId) {
        if (AuthorGramSpyPolicy.isSpyDisabled(chatId)) {
            return true;
        }
        long key = Math.abs(chatId);
        return ghostModeTypingExclusions.computeIfAbsent(key, k ->
                NekoConfig.getPreferences().getBoolean(ghostTypingExclusionPrefix + k, false)
        );
    }

}
