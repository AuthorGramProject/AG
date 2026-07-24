package toss.authorgram.filters.popup;

import static org.telegram.messenger.LocaleController.getString;

import android.view.View;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.ActionBarPopupWindow;

import toss.authorgram.filters.AGFilter;
import toss.authorgram.filters.RegexChatFiltersListActivity;

public class RegexExcludedChatFilterPopup {

    public static void show(RegexChatFiltersListActivity fragment, View anchorView, float touchedX, float touchedY, long dialogId, AGFilter.FilterModel filterModel) {
        if (fragment.getFragmentView() == null || filterModel == null) {
            return;
        }

        var layout = RegexPopupUtils.createLayout(fragment);
        var popupWindow = RegexPopupUtils.createPopupWindow(layout);
        var windowLayout = createPopupLayout(layout, popupWindow, fragment, dialogId, filterModel);
        RegexPopupUtils.showPopupAtTouch(fragment, anchorView, touchedX, touchedY, popupWindow, windowLayout);
    }

    private static ActionBarPopupWindow.ActionBarPopupWindowLayout createPopupLayout(ActionBarPopupWindow.ActionBarPopupWindowLayout layout, ActionBarPopupWindow popupWindow, RegexChatFiltersListActivity fragment, long dialogId, AGFilter.FilterModel filterModel) {
        layout.setFitItems(true);

        var deleteBtn = ActionBarMenuItem.addItem(layout, R.drawable.msg_delete, getString(R.string.Delete), false, fragment.getResourceProvider());
        deleteBtn.setOnClickListener(view -> {
            AGFilter.setSharedFilterExcluded(dialogId, filterModel.id, false);
            fragment.onResume();
            popupWindow.dismiss();
        });
        RegexPopupUtils.applyDeleteItemColor(deleteBtn);

        return layout;
    }
}
