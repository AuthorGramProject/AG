package toss.authorgram.settings;

import static org.telegram.messenger.LocaleController.getString;
import static org.telegram.ui.ProfileActivity.sendLogs;

import android.app.Activity;
import android.net.Uri;
import android.text.TextUtils;

import com.exteragram.messenger.pillstack.ui.PillStackPreferencesActivity;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;
import org.telegram.messenger.authorgram.AuthorGramPlayPolicy;
import org.telegram.ui.ActionBar.BaseFragment;

import java.util.ArrayList;
import java.util.Map;

import tw.nekomimi.nekogram.helpers.PasscodeHelper;

import toss.authorgram.filters.AGFiltersSettingsActivity;

public class AGSettingsRouter {

    private static final String PLAY_PACKAGE = "toss.authorgram.apk";
    private static final String CANONICAL_SETTINGS_HOST = "t.me";
    private static final String CANONICAL_SETTINGS_PREFIX = "authorgram_apk";

    private static boolean isPrivateMainBuild() {
        return ApplicationLoader.applicationContext == null
                || !PLAY_PACKAGE.equals(ApplicationLoader.applicationContext.getPackageName());
    }

    private static boolean isSettingsPrefix(String prefix) {
        return CANONICAL_SETTINGS_PREFIX.equals(prefix)
                || "agsettings".equals(prefix)
                || "nasettings".equals(prefix);
    }

    public static String buildDeepLink(String section, String row, String value) {
        Uri.Builder builder = new Uri.Builder()
                .scheme("https")
                .authority(CANONICAL_SETTINGS_HOST)
                .appendPath(CANONICAL_SETTINGS_PREFIX);
        if (!TextUtils.isEmpty(section)) {
            builder.appendPath(section);
        }
        if (!TextUtils.isEmpty(row)) {
            builder.appendQueryParameter("r", row);
        }
        if (!TextUtils.isEmpty(value)) {
            builder.appendQueryParameter("v", value);
        }
        return builder.build().toString();
    }

    public static void processDeepLink(Activity activity, Uri uri, Callback callback, Runnable unknown) {
        if (uri == null) {
            unknown.run();
            return;
        }
        var segments = uri.getPathSegments();
        if (segments.isEmpty() || segments.size() > 2 || !isSettingsPrefix(segments.get(0))) {
            unknown.run();
            return;
        }
        BaseFragment fragment;
        BaseAGSettingsActivity agFragment = null;
        BaseAGXSettingsActivity agxFragment = null;
        if (segments.size() == 1) {
            fragment = new AGSettingsActivity();
        } else if (PasscodeHelper.getSettingsKey().equals(segments.get(1))) {
            fragment = agFragment = new AGPasscodeSettingsActivity();
        } else {
            switch (segments.get(1)) {
                case "about":
                    fragment = new AGAboutActivity();
                    break;
                case "chat":
                case "chats":
                case "c":
                    fragment = agxFragment = new AGChatSettingsActivity();
                    break;
                case "appearance":
                case "a":
                    fragment = agxFragment = new AGAppearanceSettingsActivity();
                    break;
                case "spy":
                    if (!isPrivateMainBuild()) {
                        unknown.run();
                        return;
                    }
                    fragment = agxFragment = new AGSpySettingsActivity();
                    break;
                case "privacy":
                case "security":
                case "p":
                    if (!isPrivateMainBuild()) {
                        unknown.run();
                        return;
                    }
                    fragment = agxFragment = new AGPrivacySettingsActivity();
                    break;
                case "experimental":
                case "e":
                    fragment = agxFragment = new AGExperimentalSettingsActivity();
                    break;
                case "emoji":
                    fragment = agFragment = new AGEmojiSettingsActivity();
                    break;
                case "general":
                case "g":
                    fragment = agxFragment = new AGGeneralSettingsActivity();
                    break;
                case "translator":
                case "translate":
                case "t":
                    fragment = agxFragment = new AGTranslatorSettingsActivity();
                    break;
                case "ghostmode":
                case "ghost":
                    if (AuthorGramPlayPolicy.isPlayBuild()) {
                        unknown.run();
                        return;
                    }
                    fragment = agxFragment = new GhostModeActivity();
                    break;
                case "maintabs":
                case "main_tabs":
                case "tabs":
                    fragment = agxFragment = new MainTabsCustomizeActivity();
                    break;
                case "sidebar":
                case "drawer":
                    fragment = agFragment = new SidebarMenuActivity();
                    break;
                case "pillstack":
                case "pills":
                    fragment = agFragment = new PillStackPreferencesActivity();
                    break;
                case "regexfilters":
                case "regex":
                    fragment = agxFragment = new AGFiltersSettingsActivity();
                    break;
                case "send_logs":
                    sendLogs(activity, false);
                    return;
                default:
                    unknown.run();
                    return;
            }
        }
        var row = uri.getQueryParameter("r");
        if (TextUtils.isEmpty(row)) {
            row = uri.getQueryParameter("row");
        }
        // The drawer toggle moved into the sidebar manager; preserve old AuthorGram links.
        if (fragment instanceof AGAppearanceSettingsActivity
                && "navigationDrawerEnabled".equals(row)) {
            fragment = agFragment = new SidebarMenuActivity();
            agxFragment = null;
        }
        callback.presentFragment(fragment);
        var value = uri.getQueryParameter("v");
        if (TextUtils.isEmpty(value)) {
            value = uri.getQueryParameter("value");
        }
        if (!TextUtils.isEmpty(row)) {
            var rowFinal = row;
            if (agFragment != null) {
                BaseAGSettingsActivity finalAGFragment = agFragment;
                AndroidUtilities.runOnUIThread(() -> finalAGFragment.scrollToRow(rowFinal, unknown));
            } else if (agxFragment != null) {
                BaseAGXSettingsActivity finalAGXFragment = agxFragment;
                if (!TextUtils.isEmpty(value)) {
                    String finalValue = value;
                    AndroidUtilities.runOnUIThread(() -> finalAGXFragment.importToRow(rowFinal, finalValue, unknown));
                } else {
                    AndroidUtilities.runOnUIThread(() -> finalAGXFragment.scrollToRow(rowFinal, unknown));
                }
            }
        }
    }

    public interface Callback {
        void presentFragment(BaseFragment fragment);
    }

    public static ArrayList<AGSettingsSearchResult> onCreateSearchArray(Callback callback) {
        ArrayList<AGSettingsSearchResult> items = new ArrayList<>();
        ArrayList<BaseAGXSettingsActivity> fragments = new ArrayList<>();
        fragments.add(new AGGeneralSettingsActivity());
        fragments.add(new AGAppearanceSettingsActivity());
        if (isPrivateMainBuild()) {
            fragments.add(new AGSpySettingsActivity());
            fragments.add(new AGPrivacySettingsActivity());
        }
        fragments.add(new AGChatSettingsActivity());
        fragments.add(new AGExperimentalSettingsActivity());
        fragments.add(new AGTranslatorSettingsActivity());

        String agTitle = getString(R.string.AGSettings);
        for (BaseAGXSettingsActivity fragment: fragments) {
            int uid = fragment.getBaseGuid();
            int drawable = fragment.getDrawable();
            String f_title = fragment.getTitle();
            for (Map.Entry<Integer, String> entry : fragment.getRowMapReverse().entrySet()) {
                Integer i = entry.getKey();
                String key = entry.getValue();
                if (key.equals(String.valueOf(i))) {
                    continue;
                }
                int guid = uid + i;
                String title = getString(key);
                if (title == null || title.isEmpty()) {
                    continue;
                }
                Runnable open = () -> {
                    callback.presentFragment(fragment);
                    AndroidUtilities.runOnUIThread(() -> fragment.scrollToRow(key, null));
                };
                AGSettingsSearchResult result = new AGSettingsSearchResult(
                        guid, title, agTitle, f_title, drawable, open
                );
                items.add(result);
            }
        }
        return items;
    }
}
