package tw.nekomimi.nekogram.settings;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.method.MovementMethod;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.BackgroundColorSpan;
import android.text.style.BulletSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.TextViewEffects;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tw.nekomimi.nekogram.DatacenterActivity;

public class NekoAboutActivity extends BaseNekoSettingsActivity {

    /** GitHub repo where Nagram Extera changelogs live. */
    private static final String GITHUB_OWNER = "D1ZZY4";
    private static final String GITHUB_REPO = "NagramXF-Extera";
    private static final String GITHUB_BRANCH = "main";
    private static final String CHANGELOG_DIR = "documentations/changelogs";
    private static final String GITHUB_API_LIST =
            "https://api.github.com/repos/" + GITHUB_OWNER + "/" + GITHUB_REPO +
                    "/contents/" + CHANGELOG_DIR + "?ref=" + GITHUB_BRANCH;
    private static final String GITHUB_RAW_BASE =
            "https://raw.githubusercontent.com/" + GITHUB_OWNER + "/" + GITHUB_REPO +
                    "/" + GITHUB_BRANCH + "/" + CHANGELOG_DIR + "/";

    private int exteraChannelRow;
    private int exteraGroupRow;
    private int sourceCodeRow;
    private int divider1Row;
    private int forkChannelRow;
    private int xChannelRow;
    private int channelRow;
    private int channelTipsRow;
    private int divider2Row;
    private int ayugramChannelRow;
    private int exteragramChannelRow;
    private int divider3Row;
    private int translationRow;
    private int changelogRow;
    private int datacenterStatusRow;

    @Override
    protected void updateRows() {
        super.updateRows();

        exteraChannelRow = addRow();
        exteraGroupRow = addRow();
        sourceCodeRow = addRow();
        divider1Row = addRow();
        forkChannelRow = addRow();
        xChannelRow = addRow();
        channelRow = addRow();
        channelTipsRow = addRow();
        divider2Row = addRow();
        ayugramChannelRow = addRow();
        exteragramChannelRow = addRow();
        divider3Row = addRow();
        translationRow = addRow();
        changelogRow = addRow();
        datacenterStatusRow = addRow();
    }

    @Override
    protected String getActionBarTitle() {
        return getString(R.string.About);
    }

    @Override
    protected void onItemClick(View view, int position, float x, float y) {
        if (position == exteraChannelRow) {
            MessagesController.getInstance(currentAccount).openByUserName("NagramExteraOfficial", NekoAboutActivity.this, 1);
        } else if (position == exteraGroupRow) {
            MessagesController.getInstance(currentAccount).openByUserName("NagramExteraCommunity", NekoAboutActivity.this, 1);
        } else if (position == sourceCodeRow) {
            Browser.openUrl(getParentActivity(), "https://github.com/" + GITHUB_OWNER + "/" + GITHUB_REPO);
        } else if (position == forkChannelRow) {
            MessagesController.getInstance(currentAccount).openByUserName("NagramX_Fork", NekoAboutActivity.this, 1);
        } else if (position == xChannelRow) {
            MessagesController.getInstance(currentAccount).openByUserName("NagramX", NekoAboutActivity.this, 1);
        } else if (position == channelRow) {
            MessagesController.getInstance(currentAccount).openByUserName("Nagram_Channel", NekoAboutActivity.this, 1);
        } else if (position == channelTipsRow) {
            MessagesController.getInstance(currentAccount).openByUserName("NagramTips", NekoAboutActivity.this, 1);
        } else if (position == ayugramChannelRow) {
            MessagesController.getInstance(currentAccount).openByUserName("AyuGram4A", NekoAboutActivity.this, 1);
        } else if (position == exteragramChannelRow) {
            MessagesController.getInstance(currentAccount).openByUserName("exteraGram", NekoAboutActivity.this, 1);
        } else if (position == translationRow) {
            Browser.openUrl(getParentActivity(), "https://crowdin.com/project/NagramX");
        } else if (position == changelogRow) {
            showChangelogDialog();
        } else if (position == datacenterStatusRow) {
            presentFragment(new DatacenterActivity(0));
        }
    }

    /**
     * Shows the latest changelog.  Fetches the file list from the project's
     * GitHub repository, picks the highest-versioned changelog, downloads it,
     * renders the Markdown, and shows it in a dialog.  Network work runs on
     * a background thread; UI updates are posted back to the main thread.
     */
    private void showChangelogDialog() {
        if (getParentActivity() == null) return;

        AlertDialog progress = new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
        progress.setCanCancel(true);
        showDialog(progress);

        new Thread(() -> {
            String markdown = null;
            String filename = null;
            try {
                List<String> files = listChangelogFilesOnGitHub();
                if (!files.isEmpty()) {
                    Collections.sort(files, Collections.reverseOrder(this::compareChangelogNames));
                    // Prefer the changelog matching the installed build code if present.
                    // ///added from NagramExtera: stable selection across forks/branches
                    filename = pickBestChangelogFile(files, BuildConfig.VERSION_CODE);
                    markdown = httpGet(GITHUB_RAW_BASE + filename);
                }
            } catch (Exception ignored) {
            }

            final String finalMarkdown = markdown;
            final String finalFilename = filename;
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    progress.dismiss();
                } catch (Exception ignored) {
                }
                if (getParentActivity() == null) return;

                AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
                builder.setTitle(getString(R.string.Changelog));
                if (finalMarkdown == null || finalMarkdown.isEmpty()) {
                    builder.setMessage(AndroidUtilities.replaceTags(
                            getString(R.string.AppUpdateChangelogEmpty)));
                } else {
                    // Render markdown into a scrollable TextView so headings, lists,
                    // monospace and links work without opening GitHub.
                    // ///added from NagramExtera
                    builder.setView(buildChangelogView(renderMarkdown(finalMarkdown)));
                }
                builder.setPositiveButton(getString(R.string.OK), null);
                if (finalFilename != null) {
                    builder.setNeutralButton(getString(R.string.SourceCode),
                            (d, w) -> Browser.openUrl(getParentActivity(),
                                    "https://github.com/" + GITHUB_OWNER + "/" + GITHUB_REPO +
                                            "/blob/" + GITHUB_BRANCH + "/" + CHANGELOG_DIR + "/" + finalFilename));
                }
                showDialog(builder.create());
            });
        }, "ChangelogFetcher").start();
    }

    // ///added from NagramExtera
    private String pickBestChangelogFile(List<String> sortedDesc, int installedVersionCode) {
        if (sortedDesc == null || sortedDesc.isEmpty()) {
            return null;
        }
        // Files are already sorted descending by compareChangelogNames.
        // First try exact match by trailing versionCode.
        for (String name : sortedDesc) {
            if (extractTrailingNumber(name) == installedVersionCode) {
                return name;
            }
        }
        return sortedDesc.get(0);
    }

    // ///added from NagramExtera
    private View buildChangelogView(CharSequence rendered) {
        Context ctx = getParentActivity();
        FrameLayout container = new FrameLayout(ctx);

        ScrollView scrollView = new ScrollView(ctx);
        scrollView.setFillViewport(true);
        container.addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, 0, 0, 0, 0, 0));

        TextView textView = new TextViewEffects(ctx);
        textView.setText(rendered);
        textView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        textView.setLinkTextColor(Theme.getColor(Theme.key_dialogTextLink));
        textView.setTextSize(14);
        textView.setMovementMethod(new AndroidUtilities.LinkMovementMethodMy());
        textView.setEllipsize(TextUtils.TruncateAt.END);
        textView.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(12), AndroidUtilities.dp(16), AndroidUtilities.dp(12));

        scrollView.addView(textView, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        return container;
    }

    private List<String> listChangelogFilesOnGitHub() throws Exception {
        List<String> out = new ArrayList<>();
        String body = httpGet(GITHUB_API_LIST);
        if (body == null || body.isEmpty()) return out;
        JSONArray arr = new JSONArray(body);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            String name = o.optString("name", "");
            String type = o.optString("type", "");
            if ("file".equals(type) && name.toLowerCase().endsWith(".md")) {
                out.add(name);
            }
        }
        return out;
    }

    /**
     * Compares two changelog filenames so that the one belonging to the higher
     * version code (and, secondarily, the higher semver) sorts greater.
     * Expected format: changelog-<verName>-<verCode>.md.  Older files such as
     * "1.0.0.md" or any non-conforming names fall back to a lexical comparison.
     */
    private int compareChangelogNames(String a, String b) {
        long ca = extractTrailingNumber(a);
        long cb = extractTrailingNumber(b);
        if (ca != cb) return Long.compare(ca, cb);
        return a.compareToIgnoreCase(b);
    }

    private long extractTrailingNumber(String name) {
        // Strip ".md", then take whatever digits appear at the very end.
        String trimmed = name.toLowerCase().endsWith(".md")
                ? name.substring(0, name.length() - 3)
                : name;
        Matcher m = Pattern.compile("(\\d+)$").matcher(trimmed);
        if (m.find()) {
            try {
                return Long.parseLong(m.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return -1L;
    }

    private static String httpGet(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(15_000);
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json, text/plain, */*");
            conn.setRequestProperty("User-Agent", "NagramExtera-Android");
            int code = conn.getResponseCode();
            if (code != 200) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            return sb.toString();
        } catch (Exception ignored) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Lightweight Markdown → Spannable converter for changelog content.
     * Supports headings (#, ##, ###), bold (**), italic (*), inline code (`),
     * unordered list bullets (- / *), horizontal rules (---), links ([t](u)),
     * and fenced code blocks (```).
     * This is intentionally narrow in scope — full GFM is not needed here.
     */
    private CharSequence renderMarkdown(String src) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        String[] lines = src.replace("\r\n", "\n").split("\n");
        int defaultText = AndroidUtilities.dp(14);
        boolean inCodeFence = false;

        for (String raw : lines) {
            String line = raw;

            // Fenced code blocks
            if (line.trim().startsWith("```")) {
                inCodeFence = !inCodeFence;
                if (!inCodeFence) {
                    out.append("\n");
                }
                continue;
            }
            if (inCodeFence) {
                int start = out.length();
                out.append(line).append("\n");
                int end = out.length();
                out.setSpan(new TypefaceSpan("monospace"), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new RelativeSizeSpan(0.95f), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                int bg = Theme.getColor(Theme.key_dialogBackground);
                int codeBg = blendWith(bg, Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3), 0.12f);
                out.setSpan(new BackgroundColorSpan(codeBg), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new LeadingMarginSpan.Standard(AndroidUtilities.dp(12)), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                continue;
            }

            // Horizontal rule
            if (line.trim().equals("---") || line.trim().equals("***")) {
                int start = out.length();
                out.append("\u2014\u2014\u2014\u2014\u2014\u2014\n");
                out.setSpan(new RelativeSizeSpan(0.9f), start, out.length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                continue;
            }

            // Headings
            int headingLevel = 0;
            while (headingLevel < line.length() && line.charAt(headingLevel) == '#' && headingLevel < 6) {
                headingLevel++;
            }
            if (headingLevel > 0 && headingLevel < line.length() && line.charAt(headingLevel) == ' ') {
                String text = line.substring(headingLevel + 1);
                int start = out.length();
                appendInline(out, text);
                int end = out.length();
                float size;
                switch (headingLevel) {
                    case 1: size = 1.35f; break;
                    case 2: size = 1.20f; break;
                    case 3: size = 1.10f; break;
                    default: size = 1.05f; break;
                }
                out.setSpan(new RelativeSizeSpan(size), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.append("\n");
                continue;
            }

            // Unordered list bullet
            String trimmed = line.replaceFirst("^\\s+", "");
            if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                int start = out.length();
                appendInline(out, trimmed.substring(2));
                int end = out.length();
                out.setSpan(new BulletSpan(AndroidUtilities.dp(8)), start, end,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new LeadingMarginSpan.Standard(AndroidUtilities.dp(16)),
                        start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.append("\n");
                continue;
            }

            // Plain paragraph line
            appendInline(out, line);
            out.append("\n");
        }

        // Apply default body size to the whole document (so RelativeSizeSpan
        // headings scale relative to a known baseline).
        out.setSpan(new AbsoluteSizeSpan(defaultText), 0, out.length(),
                Spanned.SPAN_INCLUSIVE_INCLUSIVE);
        return out;
    }

    /** Apply inline Markdown (bold/italic/code) while appending to {@code out}. */
    private void appendInline(SpannableStringBuilder out, String src) {
        // Order matters: links first, then bold (**), then italic (*), then code (`).
        // ///added from NagramExtera
        Pattern p = Pattern.compile(
                "\\[([^\\]]+?)\\]\\(([^)\\s]+?)\\)" + // group 1-2: [text](url)
                "|\\*\\*(.+?)\\*\\*" +                // group 3: bold
                "|\\*(.+?)\\*" +                      // group 4: italic
                "|`([^`]+?)`"                         // group 5: inline code
        );
        Matcher m = p.matcher(src);
        int cursor = 0;
        while (m.find()) {
            if (m.start() > cursor) {
                out.append(src, cursor, m.start());
            }
            int spanStart = out.length();
            if (m.group(1) != null && m.group(2) != null) {
                out.append(m.group(1));
                out.setSpan(new URLSpan(m.group(2)), spanStart, out.length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new ForegroundColorSpan(Theme.getColor(Theme.key_dialogTextLink)),
                        spanStart, out.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (m.group(3) != null) {
                out.append(m.group(3));
                out.setSpan(new StyleSpan(Typeface.BOLD), spanStart, out.length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (m.group(4) != null) {
                out.append(m.group(4));
                out.setSpan(new StyleSpan(Typeface.ITALIC), spanStart, out.length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (m.group(5) != null) {
                out.append(m.group(5));
                out.setSpan(new TypefaceSpan("monospace"),
                        spanStart, out.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new RelativeSizeSpan(0.95f), spanStart, out.length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            cursor = m.end();
        }
        if (cursor < src.length()) {
            out.append(src, cursor, src.length());
        }
    }

    // ///added from NagramExtera
    private static int blendWith(int baseColor, int overlayColor, float alpha) {
        alpha = Math.max(0f, Math.min(1f, alpha));
        int br = Color.red(baseColor);
        int bg = Color.green(baseColor);
        int bb = Color.blue(baseColor);
        int or = Color.red(overlayColor);
        int og = Color.green(overlayColor);
        int ob = Color.blue(overlayColor);
        int r = (int) (br * (1f - alpha) + or * alpha);
        int g = (int) (bg * (1f - alpha) + og * alpha);
        int b = (int) (bb * (1f - alpha) + ob * alpha);
        return Color.rgb(r, g, b);
    }

    @Override
    protected BaseListAdapter createAdapter(Context context) {
        return new ListAdapter(context);
    }

    private class ListAdapter extends BaseListAdapter {

        public ListAdapter(Context context) {
            super(context);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position, boolean partial) {
            int viewType = holder.getItemViewType();
            if (viewType == TYPE_SHADOW) {
                holder.itemView.setBackground(Theme.getThemedDrawable(mContext, R.drawable.greydivider, Theme.key_windowBackgroundGrayShadow));
            } else if (viewType == TYPE_SETTINGS) {
                TextSettingsCell textCell = (TextSettingsCell) holder.itemView;
                if (position == exteraChannelRow) {
                    textCell.setTextAndValue(getString(R.string.NagramExteraChannel), "@NagramExteraOfficial", true);
                } else if (position == exteraGroupRow) {
                    textCell.setTextAndValue(getString(R.string.NagramExteraGroup), "@NagramExteraCommunity", true);
                } else if (position == sourceCodeRow) {
                    textCell.setTextAndValue(getString(R.string.SourceCode), "GitHub", false);
                } else if (position == forkChannelRow) {
                    textCell.setTextAndValue(getString(R.string.NagramXForkChannel), "@NagramX_Fork", true);
                } else if (position == xChannelRow) {
                    textCell.setTextAndValue(getString(R.string.XChannel), "@NagramX", true);
                } else if (position == channelRow) {
                    textCell.setTextAndValue(getString(R.string.OfficialChannel), "@Nagram_Channel", true);
                } else if (position == channelTipsRow) {
                    textCell.setTextAndValue(getString(R.string.TipsChannel), "@NagramTips", true);
                } else if (position == ayugramChannelRow) {
                    textCell.setTextAndValue(getString(R.string.AyuGramChannel), "@AyuGram4A", true);
                } else if (position == exteragramChannelRow) {
                    textCell.setTextAndValue(getString(R.string.ExteraGramChannel), "@exteraGram", true);
                } else if (position == translationRow) {
                    textCell.setTextAndValue(getString(R.string.TransSite), "Crowdin", true);
                } else if (position == changelogRow) {
                    textCell.setTextAndValue(getString(R.string.Changelog), "v" + BuildConfig.VERSION_NAME, true);
                } else if (position == datacenterStatusRow) {
                    textCell.setText(getString(R.string.DatacenterStatus), false);
                }
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == divider1Row || position == divider2Row || position == divider3Row) {
                return TYPE_SHADOW;
            }
            return TYPE_SETTINGS;
        }
    }
}
