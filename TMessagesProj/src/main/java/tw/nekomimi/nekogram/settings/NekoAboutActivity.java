package tw.nekomimi.nekogram.settings;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextSettingsCell;

import tw.nekomimi.nekogram.DatacenterActivity;

public class NekoAboutActivity extends BaseNekoSettingsActivity {

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
            Browser.openUrl(getParentActivity(), "https://github.com/D1ZZY4/NagramXF-Extera");
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
        } else if (position == datacenterStatusRow) {
            presentFragment(new DatacenterActivity(0));
        }
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
                    textCell.setTextAndValue(getString(R.string.ExteraGramChannel), "@exteraGram", false);
                } else if (position == translationRow) {
                    textCell.setTextAndValue(getString(R.string.TransSite), "Crowdin", true);
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
