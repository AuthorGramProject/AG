package org.telegram.messenger.authorgram;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.messenger.R;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

public class AuthorGramBadgeManager {
    public static final int TYPE_NONE = 0;
    public static final int TYPE_AUTHOR = 1;
    public static final int TYPE_LOVE = 2;
    public static final int TYPE_SUPPORT = 3;
    public static final int TYPE_SUPPORT_PRO = 4;

    private static final String PREF_NAME = "AuthorGramBadges";
    private static final Object INIT_LOCK = new Object();
    private static volatile boolean initialized = false;

    // Immutable state class
    private static class BadgeState {
        final HashSet<Long> authors;
        final HashSet<Long> love;
        final HashSet<Long> support;
        final HashSet<Long> supportPro;

        BadgeState(HashSet<Long> authors, HashSet<Long> love, HashSet<Long> support, HashSet<Long> supportPro) {
            this.authors = authors != null ? authors : new HashSet<>();
            this.love = love != null ? love : new HashSet<>();
            this.support = support != null ? support : new HashSet<>();
            this.supportPro = supportPro != null ? supportPro : new HashSet<>();
        }
    }

    private static volatile BadgeState currentState = new BadgeState(null, null, null, null);

    private static void ensureInitialized() {
        if (initialized) return;
        synchronized (INIT_LOCK) {
            if (initialized) return;
            loadFromCache();
            initialized = true;
        }
        updateFromNetwork();
    }

    private static void loadFromCache() {
        SharedPreferences prefs = ApplicationLoader.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        
        HashSet<Long> parsedAuthors = parseIds(prefs.getStringSet("authors", new HashSet<>()));
        HashSet<Long> parsedLove = parseIds(prefs.getStringSet("love", new HashSet<>()));
        HashSet<Long> parsedSupport = parseIds(prefs.getStringSet("support", new HashSet<>()));
        HashSet<Long> parsedSupportPro = parseIds(prefs.getStringSet("support_pro", new HashSet<>()));
        
        // Hardcoded authors
        parsedAuthors.add(6316376597L);
        parsedAuthors.add(2021861896L);
        parsedAuthors.add(2815463434L);

        currentState = new BadgeState(parsedAuthors, parsedLove, parsedSupport, parsedSupportPro);
    }

    private static HashSet<Long> parseIds(Set<String> stringSet) {
        HashSet<Long> result = new HashSet<>();
        if (stringSet != null) {
            for (String s : stringSet) {
                try { result.add(Long.parseLong(s)); } catch (Exception ignore) {}
            }
        }
        return result;
    }

    private static void updateFromNetwork() {
        org.telegram.messenger.Utilities.globalQueue.postRunnable(() -> {
            try {
                Set<String> newAuthors = fetchList("https://authorche.top/authorgram/authorsources.txt");
                Set<String> newLove = fetchList("https://authorche.top/authorgram/love.txt");
                Set<String> newSupport = fetchList("https://authorche.top/authorgram/supports.txt");
                Set<String> newSupportPro = fetchList("https://authorche.top/authorgram/supports_pro.txt");

                if (newAuthors != null || newLove != null || newSupport != null || newSupportPro != null) {
                    SharedPreferences.Editor editor = ApplicationLoader.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit();
                    
                    HashSet<Long> parsedAuthors = new HashSet<>();
                    if (newAuthors != null) {
                        editor.putStringSet("authors", newAuthors);
                        parsedAuthors.addAll(parseIds(newAuthors));
                    } else {
                        parsedAuthors.addAll(currentState.authors); // Keep old state if failed
                    }
                    
                    // Always add hardcoded authors
                    parsedAuthors.add(6316376597L);
                    parsedAuthors.add(2021861896L);
                    parsedAuthors.add(2815463434L);

                    HashSet<Long> parsedLove = new HashSet<>();
                    if (newLove != null) {
                        editor.putStringSet("love", newLove);
                        parsedLove.addAll(parseIds(newLove));
                    } else {
                        parsedLove.addAll(currentState.love);
                    }

                    HashSet<Long> parsedSupport = new HashSet<>();
                    if (newSupport != null) {
                        editor.putStringSet("support", newSupport);
                        parsedSupport.addAll(parseIds(newSupport));
                    } else {
                        parsedSupport.addAll(currentState.support);
                    }

                    HashSet<Long> parsedSupportPro = new HashSet<>();
                    if (newSupportPro != null) {
                        editor.putStringSet("support_pro", newSupportPro);
                        parsedSupportPro.addAll(parseIds(newSupportPro));
                    } else {
                        parsedSupportPro.addAll(currentState.supportPro);
                    }

                    editor.apply();
                    
                    // Atomic update
                    currentState = new BadgeState(parsedAuthors, parsedLove, parsedSupport, parsedSupportPro);
                    
                    org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> {
                        org.telegram.messenger.NotificationCenter.getGlobalInstance().postNotificationName(org.telegram.messenger.NotificationCenter.updateInterfaces, org.telegram.messenger.MessagesController.UPDATE_MASK_NAME | org.telegram.messenger.MessagesController.UPDATE_MASK_CHAT_NAME);
                    });
                }
            } catch (Exception e) {
                FileLog.e("AuthorGramBadgeManager update failed", e);
            }
        });
    }

    private static Set<String> fetchList(String urlString) {
        HttpURLConnection conn = null;
        BufferedReader in = null;
        try {
            URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestMethod("GET");

            if (conn.getResponseCode() == 200) {
                Set<String> result = new HashSet<>();
                in = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while ((line = in.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty()) {
                        result.add(line);
                    }
                }
                return result;
            }
        } catch (Exception e) {
            FileLog.e("AuthorGramBadgeManager fetch failed for " + urlString, e);
        } finally {
            if (in != null) {
                try { in.close(); } catch (Exception ignore) {}
            }
            if (conn != null) {
                conn.disconnect();
            }
        }
        return null;
    }

    public static long normalizeTelegramPeerId(long rawId) {
        if (rawId == 0) return 0;
        long id = rawId;
        // If it's negative, it could be a chat or a -100 ID
        if (id < 0) {
            id = -id;
        }
        String idStr = String.valueOf(id);
        if (idStr.startsWith("100")) {
            idStr = idStr.substring(3);
            try {
                id = Long.parseLong(idStr);
            } catch (Exception ignore) {}
        }
        return id;
    }

    public static int getBadgeType(long rawId) {
        ensureInitialized();

        long normalizedId = normalizeTelegramPeerId(rawId);
        
        // Priority 1: Built-in local crypto matches (Author only)
        if (AuthorGramAuthorBadge.matches(normalizedId) || AuthorGramAuthorBadge.matches(rawId)) {
            return TYPE_AUTHOR;
        }

        BadgeState state = currentState;
        
        // Priority 2: Remote lists
        if (state.authors.contains(normalizedId) || state.authors.contains(rawId)) return TYPE_AUTHOR;
        if (state.love.contains(normalizedId) || state.love.contains(rawId)) return TYPE_LOVE;
        if (state.supportPro.contains(normalizedId) || state.supportPro.contains(rawId)) return TYPE_SUPPORT_PRO;
        if (state.support.contains(normalizedId) || state.support.contains(rawId)) return TYPE_SUPPORT;
        
        return TYPE_NONE;
    }

    public static void showBadgeToast(int type, String name) {
        if (name == null) return;
        
        String text = "";
        int icon = R.drawable.msg_info;
        
        switch (type) {
            case TYPE_AUTHOR:
                text = LocaleController.formatString("AuthorGramBadgeAuthorText", R.string.AuthorGramBadgeAuthorText, name);
                break;
            case TYPE_LOVE:
                // Assuming LOVE shouldn't just be AuthorText. Let's use a fallback if translation is missing.
                text = LocaleController.formatString("AuthorGramBadgeLoveText", R.string.AuthorGramBadgeAuthorText, name); 
                break;
            case TYPE_SUPPORT:
                text = LocaleController.formatString("AuthorGramBadgeSupportText", R.string.AuthorGramBadgeSupportText, name);
                break;
            case TYPE_SUPPORT_PRO:
                text = LocaleController.formatString("AuthorGramBadgeSupportProText", R.string.AuthorGramBadgeSupportText, name);
                break;
            default:
                return;
        }

        String detailsText = LocaleController.getString("AuthorGramBadgeDetails", R.string.AuthorGramBadgeDetails);
        android.graphics.drawable.Drawable iconDrawable = androidx.core.content.ContextCompat.getDrawable(ApplicationLoader.applicationContext, R.drawable.msg_info);
        BulletinFactory.global().createSimpleBulletin(iconDrawable, text, detailsText, () -> {
            if (org.telegram.ui.LaunchActivity.instance != null) {
                org.telegram.ui.LaunchActivity.instance.presentFragment(new toss.authorgram.settings.AGAboutActivity());
            }
        }).show();
    }
}
