package com.radolyn.ayugram.utils;

public class AyuGhostPreferences {
    public static final String ghostReadExclusionPrefix = "ghostModeReadExclusion_";
    public static final String ghostTypingExclusionPrefix = "ghostModeTypingExclusion_";

    public static void setGhostModeReadExclusion(long chatId, boolean value) {}
    public static boolean getGhostModeReadExclusion(long chatId) { return false; }
    public static void setGhostModeTypingExclusion(long chatId, boolean value) {}
    public static boolean getGhostModeTypingExclusion(long chatId) { return false; }
}
