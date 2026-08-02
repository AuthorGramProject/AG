package org.telegram.messenger.authorgram;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * First-run AuthorGram defaults.
 *
 * Existing user choices are never overwritten.
 */
public final class AuthorGramDefaults {

    private AuthorGramDefaults() {
    }

    public static void apply(
            Context context
    ) {
        if (context == null) {
            return;
        }

        applyDefaults(
                context,
                "mainconfig",
                new Object[][] {
                {"autoNightSunriseTime", 480},
                {"pauseMusicOnRecord", false},
                {"autoNightBrighnessThreshold_float", 0.25f},
                {"autoNightDayEndTime", 480},
                {"repeatMode", 2},
                {"theme", "Monet Dark"},
                {"selectedAutoNightType", 0},
                {"archiveHidden", true},
                {"autoNightDayStartTime", 1320},
                {"autoNightScheduleByLocation", false},
                {"useThreeLinesLayout", true},
                {"autoNightSunsetTime", 1320}
                }
        );

        applyDefaults(
                context,
                "themeconfig",
                new Object[][] {
                {"lastDarkCustomTheme", "Monet Dark"},
                {"lastDayCustomTheme", "Monet Light"},
                {"lastDayTheme", "Monet Light"},
                {"lastDarkTheme", "Monet Dark"}
                }
        );

        applyDefaults(
                context,
                "nkmrcfg",
                new Object[][] {
                {"BackAnimationStyle", 1},
                {"PremiumItemStickerEffects", false},
                {"EventLog", true},
                {"DrawerItemRestartApp", true},
                {"MainTabsHideTitles", true},
                {"SaveMediaOnWiFiLimit_long", 512000L},
                {"CustomFilteredUsersData", "[]"},
                {"staticZoom", true},
                {"DoubleTapAction", 1},
                {"RegexFiltersEnableInChats", true},
                {"DisableSystemAccount", true},
                {"ShowTimeHint", true},
                {"showChangePermissions", false},
                {"DrawerItemNewGroup", false},
                {"RepeatAsCopy", true},
                {"AttachmentFolderSizeLimitPreset", 0},
                {"showGhostInDrawer", true},
                {"videoMessagesCamera", 2},
                {"IconReplacements", 1},
                {"ActionBarDecoration", 0},
                {"ChannelAdministrators", true},
                {"PremiumItemEmojiStatus", false},
                {"DefaultDeleteMenu", 0},
                {"GroupMembers", true},
                {"LlmTemperature_float", 0.0f},
                {"DateOfForwardedMsg", true},
                {"RegexFilters", true},
                {"showSeconds", true},
                {"DisableMarkdown", false},
                {"ReplaceBlockedMyInfo", true},
                {"UnlimitedFavoredStickers", true},
                {"MessageMenuCopyFrame", true},
                {"SaveMediaOnCellularDataLimit_long", 512000L},
                {"MainTabsOrder", "CHATS,!CONTACTS,SETTINGS,PROFILE,!CALLS"},
                {"useScheduledMessages", false},
                {"navigationDrawerEnabled", true},
                {"showAddToSavedMessages", true},
                {"Statistics", true},
                {"DeletedIconColor", 7},
                {"ZalgoFilter", true},
                {"Reply", true},
                {"SliderStyle", 2},
                {"PerformanceClass", 0},
                {"NoQuoteForward", false},
                {"PremiumItemStarInReactions", false},
                {"ReplyInPrivate", true},
                {"CustomEditedMessage", "✍️"},
                {"DisableChannelMuteButton", true},
                {"EnableSaveDeletedMessages", true},
                {"PreferredTranslateTargetLang", ""},
                {"PremiumItemBoosts", false},
                {"DisableVibration", true},
                {"showReport", true},
                {"EnableSaveEditsHistory", false},
                {"DnsType", 0},
                {"NowPlayingServiceType", 0},
                {"sendReadMessagePackets", true},
                {"hideGroupSticker", true},
                {"TransToLang", "uk"},
                {"rememberAllBackMessages", false},
                {"HideProxySponsorChannel", true},
                {"HideKeyboardOnChatScroll", true},
                {"RemoveMessageTail", true},
                {"HidePremiumSection", true},
                {"showRepeat", true},
                {"DoNotUnarchiveBySwipe", true},
                {"SendLockedCustomEmojiAsSticker", true},
                {"TextStyleOrder", "translate,bold,italic,mono,code,strike,underline,quote,spoiler,link,mention,regular"},
                {"DisableClickCommandToSend", false},
                {"DrawerItemSetEmojiStatus", false},
                {"ShowRPCError", false},
                {"GroupedMessageMenu", true},
                {"TranslatorMode", 1},
                {"HideDividers", false},
                {"hideSendAsChannel", true},
                {"ChatDecoration", 0},
                {"DisableNumberRounding", true},
                {"MainTabsHideContacts", true},
                {"DrawerItemCalls", false},
                {"MessageSavingSaveMedia", false},
                {"AutoPauseVideo", true},
                {"SwitchStyle", 2},
                {"ConfirmAllLinks", false},
                {"dataSaverMode", true},
                {"UseDeletedIcon", true},
                {"disableSwipeToNextChannel", true},
                {"sendReadStoriesPackets", true},
                {"SendMp4DocumentAsVideo", false},
                {"sendUploadProgress", true},
                {"AddToStickers", false},
                {"SaveMediaInPrivateChannels", false},
                {"HideShareButtonInChannel", true},
                {"RegexFiltersData", "[{\"caseInsensitive\":true,\"enabled\":true,\"regex\":\"по ссылке\"},{\"caseInsensitive\":true,\"enabled\":true,\"pattern\":{},\"regex\":\"WB\"},{\"caseInsensitive\":true,\"enabled\":true,\"pattern\":{},\"regex\":\"промокод\"},{\"caseInsensitive\":true,\"enabled\":true,\"pattern\":{},\"regex\":\"депозит\"},{\"caseInsensitive\":true,\"enabled\":true,\"pattern\":{},\"regex\":\"казино\"},{\"caseInsensitive\":true,\"enabled\":true,\"pattern\":{},\"regex\":\"spin\"},{\"caseInsensitive\":true,\"enabled\":true,\"pattern\":{},\"regex\":\"jalwa\"},{\"caseInsensitive\":true,\"enabled\":true,\"pattern\":{},\"regex\":\"deposit\"},{\"caseInsensitive\":true,\"enabled\":true,\"pattern\":{},\"regex\":\"erid:\"},{\"caseInsensitive\":true,\"enabled\":true,\"pattern\":{},\"regex\":\"#реклама\"},{\"caseInsensitive\":true,\"enabled\":true,\"pattern\":{},\"regex\":\"#ads\"}]"},
                {"DrawerItemSettings", true},
                {"translationProvider", 1},
                {"TranscribeProvider", 2},
                {"TelegramUIAutoTranslate", true},
                {"DontAutoPlayNextVoice", false},
                {"showShareMessages", true},
                {"ShowMessageID", true},
                {"SaveMediaInPublicGroups", false},
                {"NoiseSuppressAndVoiceEnhance", true},
                {"PremiumItemVideoAvatar", false},
                {"QuoteReply", true},
                {"showAdminActions", false},
                {"DrawerItemSaved", false},
                {"DrawerItemBrowser", true},
                {"MainTabsDisplayMode", 1},
                {"ShowAddToBookmark", true},
                {"AutoUpdateChannel", 0},
                {"ShowOnlineStatus", true},
                {"customSavePath", "AuthorGram"},
                {"DisableCrashlyticsCollection", true},
                {"SwitchStyleModernRemoved", true},
                {"ShowRecentChatsSidebar", false},
                {"CustomTitle", "AuthorGram"},
                {"showGhostModeStatus", true},
                {"disableChoosingSticker", true},
                {"CenterActionBarTitle", true},
                {"CenterActionBarTitleType", 1},
                {"SaveMediaInPublicChannels", false},
                {"sendOfflinePacketAfterOnline", false},
                {"ShowSmallGIF", true},
                {"DrawerItemRecentChats", false},
                {"IgnoreBlocked", false},
                {"EnhancedVideoBitrate", true},
                {"UseEditedIcon", true},
                {"DisableStories", true},
                {"SaveDeletedMessageForBotUser", false},
                {"IdDcType", 2},
                {"CopyPhoto", true},
                {"EnablePanguOnSending", false},
                {"IPv6", false},
                {"SetReminder", true},
                {"PremiumItemCustomWallpaper", false},
                {"showMessageHide", true},
                {"SaveMediaInPrivateGroups", false},
                {"HideTimeForSticker", true},
                {"hideSponsoredMessage", true},
                {"DrawerItemContacts", true},
                {"SilentMessageByDefault", true},
                {"NotificationIcon", 0},
                {"SaveMediaInPrivateChats", true},
                {"sendOnlinePackets", true},
                {"SkipOpenLinkConfirm", false},
                {"CopyPhotoAsSticker", false},
                {"DisableProximityEvents", true},
                {"uploadBoost", false}
                }
        );

        applyDefaults(
                context,
                "pillstackconfig",
                new Object[][] {
                {"hiddenPills", "1,2,3,4,5,6,100"},
                {"activePills", ""}
                }
        );
    }

    private static void applyDefaults(
            Context context,
            String preferenceName,
            Object[][] defaults
    ) {
        SharedPreferences preferences =
                context.getSharedPreferences(
                        preferenceName,
                        Context.MODE_PRIVATE
                );

        SharedPreferences.Editor editor =
                null;

        for (Object[] item : defaults) {
            String key =
                    (String) item[0];

            if (preferences.contains(key)) {
                continue;
            }

            if (editor == null) {
                editor =
                        preferences.edit();
            }

            Object value =
                    item[1];

            if (value instanceof Boolean) {
                editor.putBoolean(
                        key,
                        (Boolean) value
                );

            } else if (value instanceof Integer) {
                editor.putInt(
                        key,
                        (Integer) value
                );

            } else if (value instanceof Long) {
                editor.putLong(
                        key,
                        (Long) value
                );

            } else if (value instanceof Float) {
                editor.putFloat(
                        key,
                        (Float) value
                );

            } else if (value instanceof String) {
                editor.putString(
                        key,
                        (String) value
                );

            } else {
                throw new IllegalArgumentException(
                        "Unsupported AuthorGram default: "
                                + key
                );
            }
        }

        if (editor != null) {
            editor.apply();
        }
    }
}
