package tw.nekomimi.nekogram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.DialogCell;
import org.telegram.ui.Components.BlurredFrameLayout;
import org.telegram.ui.Components.ColoredImageSpan;
import org.telegram.ui.Components.FireworksEffect;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.SizeNotifierFrameLayout;
import org.telegram.ui.Components.SnowflakesEffect;

import tw.nekomimi.nekogram.NekoConfig;
import xyz.nextalone.nagram.NaConfig;

@SuppressLint("ViewConstructor")
public class GlobalSearchView extends BlurredFrameLayout {

    private TextView searchTextView;
    private BlurredFrameLayout searchFrame;



    public GlobalSearchView(Context context, SizeNotifierFrameLayout sizeNotifierFrameLayout) {
        super(context, sizeNotifierFrameLayout);

        setWillNotDraw(false);

        searchFrame = new BlurredFrameLayout(context, sizeNotifierFrameLayout);
        searchFrame.isTopView = false;
        searchTextView = new TextView(context);
        searchTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        searchTextView.setEllipsize(TextUtils.TruncateAt.MIDDLE);

        SpannableStringBuilder spannableStringBuilder = new SpannableStringBuilder();
        spannableStringBuilder.append("..").setSpan(new ColoredImageSpan(ContextCompat.getDrawable(context, R.drawable.ic_ab_search)), 0, 1, 0);
        spannableStringBuilder.setSpan(new DialogCell.FixedWidthSpan(AndroidUtilities.dp(4)), 1, 2, 0);
        spannableStringBuilder.append(getString(R.string.Search));

        searchTextView.setText(spannableStringBuilder);
        searchTextView.setGravity(Gravity.CENTER);
        searchTextView.setSingleLine(true);

        searchFrame.addView(searchTextView);
        addView(searchFrame, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 38, 0, dp(5), 0, dp(5), 0));

        updateColors();
    }

    public void updateColors() {
        int backgroundColor;

        if (Theme.getActiveTheme().isMonet()) {
            backgroundColor = ColorUtils.setAlphaComponent(
                    Theme.getColor(Theme.isCurrentThemeDay() ? Theme.key_chats_unreadCounter : Theme.key_chats_unreadCounterMuted),
                    (int) (255 * 0.5f)
            );
            int textColor = Theme.isCurrentThemeDay()
                    ? Theme.getColor(Theme.key_actionBarDefault)
                    : Theme.getColor(Theme.key_dialogTextGray3);
            searchTextView.setTextColor(textColor);
        } else {
            if (!Theme.isCurrentThemeDay()) {
                backgroundColor = ColorUtils.setAlphaComponent(
                        Theme.getColor(Theme.key_graySection),
                        (int) (255 * 0.35f)
                );
            } else {
                backgroundColor = Theme.getColor(Theme.key_graySection);
            }
            searchTextView.setTextColor(Theme.getColor(Theme.key_dialogTextGray3));
        }

        Drawable background = Theme.createSimpleSelectorRoundRectDrawable(
                dp(10),
                backgroundColor,
                ColorUtils.setAlphaComponent(Theme.getColor(Theme.key_listSelector), (int) (255 * 0.3f))
        );
        searchFrame.setBackground(background);


    }

    public BlurredFrameLayout getSearchFrame() {
        return searchFrame;
    }

    public TextView getSearchTextView() {
        return searchTextView;
    }

    public static void saveFoldersExistence() {
        MessagesController messagesController = MessagesController.getInstance(UserConfig.selectedAccount);
        messagesController.getMainSettings()
                .edit()
                .putBoolean("user_has_folders_for_search", messagesController.getDialogFilters() != null && messagesController.getDialogFilters().size() > 1)
                .apply();
    }

    public boolean getFoldersExistence() {
        return MessagesController.getMainSettings(UserConfig.selectedAccount)
                .getBoolean("user_has_folders_for_search", NaConfig.INSTANCE.getIosSearchPanel().Bool());
    }




}
