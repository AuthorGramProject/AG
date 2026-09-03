package org.telegram.messenger.authorgram;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.ui.Components.BulletinFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;

import org.telegram.messenger.R;

public class AuthorGramBadgeManager {

    public static final int TYPE_NONE = 0;
    public static final int TYPE_AUTHOR = 1;
    public static final int TYPE_LOVE = 2;
    public static final int TYPE_SUPPORT = 3;
    public static final int TYPE_SUPPORT_PRO = 4;

    private static final String PREF_NAME = "AuthorGramBadges";
    
    private static final HashSet<Long> authorIds = new HashSet<>();
    private static final HashSet<Long> loveIds = new HashSet<>();
    private static final HashSet<Long> supportIds = new HashSet<>();
    private static final HashSet<Long> supportProIds = new HashSet<>();

    private static boolean initialized = false;

    public static void init() {
        if (initialized) return;
        initialized = true;

        // Hardcoded Authors
        authorIds.add(6316376597L);
        authorIds.add(2021861896L);
        authorIds.add(2815463434L);

        loadFromCache();
        updateFromNetwork();
    }

    private static void loadFromCache() {
        SharedPreferences prefs = ApplicationLoader.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        
        Set<String> cachedAuthors = prefs.getStringSet("authors", new HashSet<>());
        for (String id : cachedAuthors) {
            try { authorIds.add(Long.parseLong(id)); } catch (Exception ignore) {}
        }

        Set<String> cachedLove = prefs.getStringSet("love", new HashSet<>());
        for (String id : cachedLove) {
            try { loveIds.add(Long.parseLong(id)); } catch (Exception ignore) {}
        }

        Set<String> cachedSupport = prefs.getStringSet("support", new HashSet<>());
        for (String id : cachedSupport) {
            try { supportIds.add(Long.parseLong(id)); } catch (Exception ignore) {}
        }
        Set<String> cachedSupportPro = prefs.getStringSet("support_pro", new HashSet<>());
        for (String id : cachedSupportPro) {
            try { supportProIds.add(Long.parseLong(id)); } catch (Exception ignore) {}
        }
    }

    private static void updateFromNetwork() {
        new Thread(() -> {
            try {
                Set<String> newAuthors = fetchList("https://authorche.top/authorgram/authorsources.txt");
                Set<String> newLove = fetchList("https://authorche.top/authorgram/love.txt");
                Set<String> newSupport = fetchList("https://authorche.top/authorgram/supports.txt");
                Set<String> newSupportPro = fetchList("https://authorche.top/authorgram/supports_pro.txt");

                if (newAuthors != null || newLove != null || newSupport != null || newSupportPro != null) {
                    SharedPreferences.Editor editor = ApplicationLoader.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit();
                    if (newAuthors != null) {
                        editor.putStringSet("authors", newAuthors);
                        synchronized (authorIds) {
                            authorIds.clear();
                            authorIds.add(6316376597L);
                            authorIds.add(2021861896L);
                            authorIds.add(2815463434L);
                            for (String id : newAuthors) {
                                try { authorIds.add(Long.parseLong(id)); } catch (Exception ignore) {}
                            }
                        }
                    }
                    if (newLove != null) {
                        editor.putStringSet("love", newLove);
                        synchronized (loveIds) {
                            loveIds.clear();
                            for (String id : newLove) {
                                try { loveIds.add(Long.parseLong(id)); } catch (Exception ignore) {}
                            }
                        }
                    }
                    if (newSupport != null) {
                        editor.putStringSet("support", newSupport);
                        synchronized (supportIds) {
                            supportIds.clear();
                            for (String id : newSupport) {
                                try { supportIds.add(Long.parseLong(id)); } catch (Exception ignore) {}
                            }
                        }
                    }
                    if (newSupportPro != null) {
                        editor.putStringSet("support_pro", newSupportPro);
                        synchronized (supportProIds) {
                            supportProIds.clear();
                            for (String id : newSupportPro) {
                                try { supportProIds.add(Long.parseLong(id)); } catch (Exception ignore) {}
                            }
                        }
                    }
                    editor.apply();
                    org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> {
                        org.telegram.messenger.NotificationCenter.getGlobalInstance().postNotificationName(org.telegram.messenger.NotificationCenter.updateInterfaces, org.telegram.messenger.MessagesController.UPDATE_MASK_ALL);
                    });
                }
            } catch (Exception e) {
                FileLog.e("AuthorGramBadgeManager update failed", e);
            }
        }).start();
    }

    private static Set<String> fetchList(String urlString) {
        try {
            URL url = new URL(urlString);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestMethod("GET");

            if (conn.getResponseCode() == 200) {
                Set<String> result = new HashSet<>();
                BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                String line;
                while ((line = in.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty()) {
                        result.add(line);
                    }
                }
                in.close();
                return result;
            }
        } catch (Exception e) {
            FileLog.e("AuthorGramBadgeManager fetch failed for " + urlString, e);
        }
        return null;
    }

    public static int getBadgeType(long rawId) {
        if (!initialized) init();

        long[] idsToCheck = {
            rawId,
            -rawId,
            (rawId > 0 && !String.valueOf(rawId).startsWith("100")) ? Long.parseLong("-100" + rawId) : rawId,
            (rawId > 0 && String.valueOf(rawId).startsWith("100")) ? -rawId : rawId
        };
        
        for (long id : idsToCheck) {
            if (AuthorGramAuthorBadge.matches(id)) {
                return TYPE_AUTHOR;
            }
            
            synchronized (authorIds) {
                if (authorIds.contains(id)) return TYPE_AUTHOR;
            }
            synchronized (loveIds) {
                if (loveIds.contains(id)) return TYPE_LOVE;
            }
            synchronized (supportProIds) {
                if (supportProIds.contains(id)) return TYPE_SUPPORT_PRO;
            }
            synchronized (supportIds) {
                if (supportIds.contains(id)) return TYPE_SUPPORT;
            }
        }
        return TYPE_NONE;
    }

    public static void showBadgeToast(int type, String name) {
        if (name == null) return;
        
        String text = "";
        int icon = R.drawable.msg_info;
        
        switch (type) {
            case TYPE_AUTHOR:
            case TYPE_LOVE:
                text = LocaleController.formatString("AuthorGramBadgeAuthorText", R.string.AuthorGramBadgeAuthorText, name);
                break;
            case TYPE_SUPPORT:
            case TYPE_SUPPORT_PRO:
                text = LocaleController.formatString("AuthorGramBadgeSupportText", R.string.AuthorGramBadgeSupportText, name);
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
