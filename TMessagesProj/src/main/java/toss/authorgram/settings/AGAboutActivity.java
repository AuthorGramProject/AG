package toss.authorgram.settings;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextDetailSettingsCell;
import org.telegram.ui.ProfileActivity;

import kotlin.Unit;
import tw.nekomimi.nekogram.DatacenterActivity;
import tw.nekomimi.nekogram.ui.BottomBuilder;
import tw.nekomimi.nekogram.ui.cells.HeaderCell;
import tw.nekomimi.nekogram.utils.AndroidUtil;
import xyz.nextalone.nagram.NaConfig;

public class AGAboutActivity extends BaseAGSettingsActivity {

    private int infoHeaderRow;
    private int versionRow;
    private int creditsRow;
    private int toggleLogsRow;
    private int sendLogsRow;
    private int clearLogsRow;
    private int infoShadowRow;

    private int linksHeaderRow;
    private int mainSiteRow;
    private int poemsRow;
    private int otherLinksRow;
    private int donateRow;
    private int datacenterStatusRow;
    private int linksShadowRow;

    // ВАЖЛИВО: за потреби заміни URL нижче на актуальні значення.
    private static final String URL_MAIN_SITE = "https://authorche.top";
    private static final String URL_POEMS = "https://authorche.top/poems";
    private static final String URL_OTHER_LINKS = "https://authorche.top/links";
    private static final String URL_DONATE = "https://authorche.top/donate";

    @Override
    protected void updateRows() {
        super.updateRows();

        infoHeaderRow = addRow();
        versionRow = addRow();
        creditsRow = addRow();
        toggleLogsRow = addRow();
        if (BuildVars.LOGS_ENABLED) {
            sendLogsRow = addRow();
            clearLogsRow = addRow();
        } else {
            sendLogsRow = -1;
            clearLogsRow = -1;
        }
        infoShadowRow = addRow();

        linksHeaderRow = addRow();
        mainSiteRow = addRow();
        poemsRow = addRow();
        otherLinksRow = addRow();
        donateRow = addRow();
        datacenterStatusRow = addRow();
        linksShadowRow = addRow();
    }

    @Override
    protected String getActionBarTitle() {
        return getString(R.string.About);
    }


    private String getSimpleVersion() {
        String versionName = BuildConfig.VERSION_NAME.split("-")[0];
        return "AuthorGram v" + versionName;
    }

    @Override
    protected void onItemClick(View view, int position, float x, float y) {
        if (position == versionRow) {
            Browser.openUrl(getParentActivity(), URL_MAIN_SITE);
        } else if (position == toggleLogsRow) {
            boolean wasLogsEnabled = BuildVars.LOGS_ENABLED;
            AndroidUtil.toggleLogs();
            listAdapter.notifyItemChanged(toggleLogsRow);
            if (!wasLogsEnabled && BuildVars.LOGS_ENABLED) {
                sendLogsRow = toggleLogsRow + 1;
                clearLogsRow = toggleLogsRow + 2;
                listAdapter.notifyItemInserted(sendLogsRow);
                listAdapter.notifyItemInserted(clearLogsRow);
                shiftRowsAfterLogsEnabled();
            } else if (wasLogsEnabled && !BuildVars.LOGS_ENABLED) {
                listAdapter.notifyItemRemoved(toggleLogsRow + 1);
                listAdapter.notifyItemRemoved(toggleLogsRow + 1);
                sendLogsRow = -1;
                clearLogsRow = -1;
                shiftRowsAfterLogsDisabled();
            }
        } else if (position == sendLogsRow) {
            ProfileActivity.sendLogs(getParentActivity(), false);
        } else if (position == clearLogsRow) {
            FileLog.cleanupLogs();
        } else if (position == mainSiteRow) {
            Browser.openUrl(getParentActivity(), URL_MAIN_SITE);
        } else if (position == poemsRow) {
            Browser.openUrl(getParentActivity(), URL_POEMS);
        } else if (position == otherLinksRow) {
            Browser.openUrl(getParentActivity(), URL_OTHER_LINKS);
        } else if (position == donateRow) {
            Browser.openUrl(getParentActivity(), URL_DONATE);
        } else if (position == datacenterStatusRow) {
            presentFragment(new DatacenterActivity(0));
        }
    }

    private void shiftRowsAfterLogsEnabled() {
        infoShadowRow += 2;
        linksHeaderRow += 2;
        mainSiteRow += 2;
        poemsRow += 2;
        otherLinksRow += 2;
        donateRow += 2;
        datacenterStatusRow += 2;
        linksShadowRow += 2;
        rowCount += 2;
    }

    private void shiftRowsAfterLogsDisabled() {
        infoShadowRow -= 2;
        linksHeaderRow -= 2;
        mainSiteRow -= 2;
        poemsRow -= 2;
        otherLinksRow -= 2;
        donateRow -= 2;
        datacenterStatusRow -= 2;
        linksShadowRow -= 2;
        rowCount -= 2;
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
            switch (holder.getItemViewType()) {
                case TYPE_HEADER:
                    HeaderCell headerCell = (HeaderCell) holder.itemView;
                    if (position == infoHeaderRow) {
                        headerCell.setText(getString(R.string.AGAboutInfo));
                    } else if (position == linksHeaderRow) {
                        headerCell.setText(getString(R.string.AGLinks));
                    }
                    break;
                case TYPE_DETAIL_SETTINGS:
                    TextDetailSettingsCell detailCell = (TextDetailSettingsCell) holder.itemView;
                    if (position == versionRow) {
                        detailCell.setMultilineDetail(true);
                        detailCell.setTextAndValue(getSimpleVersion(), getString(R.string.TOSSAboutDesc), true);
                    } else if (position == creditsRow) {
                        detailCell.setMultilineDetail(true);
                        detailCell.setTextAndValue(
                                getString(R.string.AGCredits),
                                getString(R.string.AGCreditsText),
                                false
                        );
                    }
                    break;
                case TYPE_TEXT:
                    TextCell textCell = (TextCell) holder.itemView;
                    if (position == toggleLogsRow) {
                        textCell.setTextAndIcon(BuildVars.LOGS_ENABLED ? getString(R.string.DebugMenuDisableLogs) : getString(R.string.DebugMenuEnableLogs), R.drawable.bug, sendLogsRow != -1);
                    } else if (position == sendLogsRow) {
                        textCell.setTextAndIcon(getString(R.string.DebugSendLogs), R.drawable.ic_upward, true);
                    } else if (position == clearLogsRow) {
                        textCell.setTextAndIcon(getString(R.string.DebugClearLogs), R.drawable.msg_clear, false);
                    } else if (position == mainSiteRow) {
                        textCell.setTextAndValueAndIcon("Main Site", "authorche.top", R.drawable.msg_info, true);
                    } else if (position == poemsRow) {
                        textCell.setTextAndValueAndIcon("Poems", "authorche.top/poems", R.drawable.msg_log, true);
                    } else if (position == otherLinksRow) {
                        textCell.setTextAndValueAndIcon("Other links", "authorche.top/links", R.drawable.msg_discussion, true);
                    } else if (position == donateRow) {
                        textCell.setTextAndValueAndIcon("Donate", "authorche.top/donate", R.drawable.msg_info, true);
                    } else if (position == datacenterStatusRow) {
                        textCell.setTextAndIcon(getString(R.string.DatacenterStatus), R.drawable.msg_info, false);
                    }
                    break;
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == infoHeaderRow || position == linksHeaderRow) {
                return TYPE_HEADER;
            } else if (position == versionRow || position == creditsRow) {
                return TYPE_DETAIL_SETTINGS;
            } else if (position == infoShadowRow || position == linksShadowRow) {
                return TYPE_SHADOW;
            } else {
                return TYPE_TEXT;
            }
        }
    }
}
