package tw.nekomimi.nekogram;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import static android.view.View.MeasureSpec;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.ActionBarPopupWindow;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BlurredRecyclerView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.ShareAlert;
import org.telegram.ui.Components.SizeNotifierFrameLayout;
import org.telegram.ui.Components.ViewPagerFixed;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.ChatActivity;
import org.telegram.messenger.browser.Browser;

import tw.nekomimi.nekogram.helpers.PasscodeHelper;

import java.util.ArrayList;
import java.util.LinkedList;

import tw.nekomimi.nekogram.BackButtonMenuRecent;

public class ChatHistoryActivity extends BaseFragment {

    // Chat categories
    public enum ChatCategory {
        ALL(0, "All"),
        CHANNELS(1, "Channels"),
        GROUPS(2, "Groups"),
        USERS(3, "Users"),
        BOTS(4, "Bots");

        public final int id;
        public final String title;

        ChatCategory(int id, String title) {
            this.id = id;
            this.title = title;
        }
    }

    // UI Components
    private ViewPagerFixed viewPager;
    private ViewPagerFixed.TabsView tabsView;

    // Data
    private ArrayList<HistoryItem> allHistoryItems = new ArrayList<>();
    private ArrayList<HistoryItem> filteredHistoryItems = new ArrayList<>();

    // Search
    private boolean isSearchMode = false;
    private String searchQuery = "";
    private ActionBarMenuItem searchItem;

    // State preservation
    private boolean shouldPreserveState = false;
    private int[] savedScrollPositions = new int[ChatCategory.values().length];
    private boolean wasInSearchMode = false;
    private String savedSearchQuery = "";
    private int currentTabPosition = 0;

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        // Only load items if we're not preserving state
        if (!shouldPreserveState) {
            loadHistoryItems();
        }
        return true;
    }

    @Override
    public View createView(Context context) {
        // Setup ActionBar
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        updateTitle();

        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == 2) {
                    showOptionsMenu();
                }
            }
        });

        // Create menu (clear existing menu first)
        actionBar.createMenu().clearItems();
        ActionBarMenu menu = actionBar.createMenu();

        // Add search button
        searchItem = menu.addItem(3, R.drawable.ic_ab_search).setIsSearchField(true).setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener() {
            @Override
            public void onSearchExpand() {
                isSearchMode = true;
                if (TextUtils.isEmpty(searchQuery)) {
                    searchQuery = "";
                }
                updateTitle();
                performSearch(searchQuery);
            }

            @Override
            public void onSearchCollapse() {
                exitSearchMode();
            }

            @Override
            public void onTextChanged(EditText editText) {
                searchQuery = editText.getText().toString();
                performSearch(searchQuery);
            }
        });

        // Add options button (settings icon for menu)
        menu.addItem(2, R.drawable.msg_settings);

        // Create main layout
        SizeNotifierFrameLayout fragmentView = new SizeNotifierFrameLayout(context);
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        // Create ViewPager with tabs
        createViewPager(context, fragmentView);

        // Restore search state if needed
        if (wasInSearchMode && !TextUtils.isEmpty(savedSearchQuery)) {
            searchItem.expandSearchFilters();
            searchItem.getSearchField().setText(savedSearchQuery);
            isSearchMode = true;
            searchQuery = savedSearchQuery;
            updateTitle();
            performSearch(searchQuery);
        }

        return fragmentView;
    }

    private void createViewPager(Context context, SizeNotifierFrameLayout fragmentView) {
        // Create ViewPager
        viewPager = new ViewPagerFixed(context);
        viewPager.setAdapter(new CategoryPagerAdapter());

        // Create tabs
        tabsView = viewPager.createTabsView(true, 3);
        tabsView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

        // Add tabs and viewpager to main view
        fragmentView.addView(tabsView,
            LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, Gravity.TOP));
        fragmentView.addView(viewPager,
            LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP, 0, 48, 0, 0));

        // Update tabs
        updateTabs();

        // Set current tab position
        if (shouldPreserveState) {
            viewPager.setCurrentItem(currentTabPosition, false);
        }

        // Set page change listener to track current tab
        viewPager.addOnPageChangeListener(new ViewPagerFixed.OnPageChangeListener() {
            @Override
            public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {}

            @Override
            public void onPageSelected(int position) {
                currentTabPosition = position;
            }

            @Override
            public void onPageScrollStateChanged(int state) {}
        });
    }

    private void updateTabs() {
        if (tabsView != null) {
            tabsView.removeTabs();
            for (int i = 0; i < ChatCategory.values().length; i++) {
                ChatCategory category = ChatCategory.values()[i];
                tabsView.addTab(i, getTabTitle(category));
            }
            tabsView.finishAddingTabs();
        }
    }

    private String getTabTitle(ChatCategory category) {
        int count = getCategoryCount(category);
        String baseTitle = category.title;
        return count > 0 ? baseTitle + " (" + count + ")" : baseTitle;
    }

    private int getCategoryCount(ChatCategory category) {
        int count = 0;
        for (HistoryItem item : allHistoryItems) {
            if (shouldIncludeItem(item, category)) {
                count++;
            }
        }
        return count;
    }

    private boolean shouldIncludeItem(HistoryItem item, ChatCategory category) {
        // Filter out official Telegram chats (Saved Messages, Replies, etc.)
        if (item.user != null) {
            // Skip official Telegram users (like Replies bot, Saved Messages)
            if (item.user.id == 777000 || // Telegram service notifications
                item.user.id == 708513 ||  // Replies bot
                item.user.id == UserConfig.getInstance(currentAccount).getClientUserId()) { // Self
                return false;
            }
        }

        if (category == ChatCategory.ALL) {
            return true;
        }

        if (item.user != null) {
            // User dialog
            if (item.user.bot) {
                return category == ChatCategory.BOTS;
            } else {
                return category == ChatCategory.USERS;
            }
        } else if (item.chat != null) {
            // Chat dialog
            if (item.chat.broadcast) {
                return category == ChatCategory.CHANNELS;
            } else {
                return category == ChatCategory.GROUPS;
            }
        }
        return false;
    }

    private void loadHistoryItems() {
        allHistoryItems.clear();

        try {
            // Get recent dialogs from BackButtonMenuRecent
            java.lang.reflect.Method getRecentDialogsMethod = BackButtonMenuRecent.class.getDeclaredMethod("getRecentDialogs", int.class);
            getRecentDialogsMethod.setAccessible(true);

            @SuppressWarnings("unchecked")
            LinkedList<Long> recentDialogIds = (LinkedList<Long>) getRecentDialogsMethod.invoke(null, currentAccount);

            for (Long dialogId : recentDialogIds) {
                // Skip official/system dialogs
                if (isOfficialDialog(dialogId)) {
                    continue;
                }

                HistoryItem item = new HistoryItem();
                item.dialogId = dialogId;

                if (dialogId > 0) {
                    // User dialog
                    // First try to get from memory cache
                    item.user = MessagesController.getInstance(currentAccount).getUser(dialogId);

                    // Revert: do not force-load user from database here

                    // Skip if user is still null (couldn't load from database either)
                    if (item.user == null) {
                        continue;
                    }


                } else {
                    // Chat dialog
                    long chatId = -dialogId;
                    item.chat = MessagesController.getInstance(currentAccount).getChat(chatId);
                    // If chat is null, try to load it from database
                    if (item.chat == null) {
                        try {
                            java.util.ArrayList<Long> chatIds = new java.util.ArrayList<>();
                            chatIds.add(chatId);
                            java.util.ArrayList<TLRPC.Chat> chats = MessagesStorage.getInstance(currentAccount).getChats(chatIds);
                            if (!chats.isEmpty()) {
                                item.chat = chats.get(0);
                                // Put it in memory cache for future use
                                MessagesController.getInstance(currentAccount).putChat(item.chat, true);
                            }
                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                    }

                    // Skip if chat is still null (couldn't load from database either)
                    if (item.chat == null) {
                        continue;
                    }
                }

                allHistoryItems.add(item);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Initialize filtered data
        if (isSearchMode) {
            performSearch(searchQuery);
        } else {
            filteredHistoryItems.clear();
            filteredHistoryItems.addAll(allHistoryItems);
        }

        // Update tabs after loading data
        updateTabs();
    }

    private boolean isOfficialDialog(long dialogId) {
        if (dialogId > 0) {
            // User dialog - filter official/system users
            TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(dialogId);
            if (user != null) {
                // Filter special users: self, replies, official bots
                if (UserObject.isUserSelf(user) || UserObject.isReplyUser(user)) {
                    return true;
                }
                // Filter official verified or support accounts if desired
                // Uncomment the next line if you want to hide verified accounts too
                // if (user.verified || user.support) return true;
            }

            // Filter specific official user IDs
            if (dialogId == 777000 || // Telegram service notifications
                dialogId == 429000 || // Stickers bot
                dialogId == 136817688) { // @BotFather
                return true;
            }
        }
        return false;
    }

    private void updateTitle() {
        if (isSearchMode) {
            actionBar.setTitle(getString(R.string.Search));
        } else {
            actionBar.setTitle(getString(R.string.RecentChats));
        }
    }

    private void exitSearchMode() {
        // Save search state before exiting
        wasInSearchMode = true;
        savedSearchQuery = searchQuery;
        
        isSearchMode = false;
        searchQuery = "";

        updateTitle();
        refreshAllPages();
    }

    private void performSearch(String query) {
        filteredHistoryItems.clear();

        if (TextUtils.isEmpty(query)) {
            filteredHistoryItems.addAll(allHistoryItems);
        } else {
            String lowerQuery = query.toLowerCase();
            for (HistoryItem item : allHistoryItems) {
                if (matchesSearchQuery(item, lowerQuery)) {
                    filteredHistoryItems.add(item);
                }
            }
        }

        // Save search state
        isSearchMode = true;
        searchQuery = query;
        wasInSearchMode = true;
        savedSearchQuery = query;

        refreshAllPages();
    }

    private boolean matchesSearchQuery(HistoryItem item, String query) {
        // Search in user/chat name
        String name = "";
        if (item.user != null) {
            name = ContactsController.formatName(item.user.first_name, item.user.last_name);
        } else if (item.chat != null) {
            name = item.chat.title;
        }

        if (name.toLowerCase().contains(query)) {
            return true;
        }

        // Search in username (prefer public username, fallback to local fields)
        String username = "";
        if (item.user != null) {
            username = getBestLocalUsername(item.user);
        } else if (item.chat != null) {
            username = getBestLocalUsername(item.chat);
        }

        if (!TextUtils.isEmpty(username) && username.toLowerCase().contains(query)) {
            return true;
        }

        return false;
    }

    private boolean shouldShowAccountSwitch() {
        // Don't show if only one account
        if (UserConfig.getActivatedAccountsCount() <= 1) {
            return false;
        }

        // Check if any accounts are hidden by passcode
        // If all other accounts are hidden, don't show the switch button
        int visibleAccounts = 0;
        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
            if (UserConfig.getInstance(i).isClientActivated() && !PasscodeHelper.isAccountHidden(i)) {
                visibleAccounts++;
            }
        }

        return visibleAccounts > 1;
    }

    private void showOptionsMenu() {
        // Create popup menu items
        ArrayList<String> items = new ArrayList<>();
        ArrayList<Integer> icons = new ArrayList<>();
        ArrayList<Runnable> actions = new ArrayList<>();

        // Add account switch option (only if multiple accounts and not hidden by passcode)
        if (shouldShowAccountSwitch()) {
            items.add(getString(R.string.SwitchAccountNax));
            icons.add(R.drawable.left_status_profile);
            actions.add(() -> showAccountSwitchDialog());
        }

        // Add clear history option
        items.add(getString(R.string.ClearRecentChats));
        icons.add(R.drawable.msg_delete);
        actions.add(() -> showClearHistoryDialog());

        // Create and show popup
        ActionBarPopupWindow.ActionBarPopupWindowLayout popupLayout = new ActionBarPopupWindow.ActionBarPopupWindowLayout(getParentActivity());
        ActionBarPopupWindow popupWindow = new ActionBarPopupWindow(popupLayout, LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT);

        for (int i = 0; i < items.size(); i++) {
            ActionBarMenuSubItem subItem = new ActionBarMenuSubItem(getParentActivity(), i == 0, i == items.size() - 1);
            subItem.setTextAndIcon(items.get(i), icons.get(i));
            final int index = i;
            subItem.setOnClickListener(v -> {
                popupWindow.dismiss();
                actions.get(index).run();
            });
            popupLayout.addView(subItem);
        }
        popupWindow.setPauseNotifications(true);
        popupWindow.setDismissAnimationDuration(220);
        popupWindow.setOutsideTouchable(true);
        popupWindow.setClippingEnabled(true);
        popupWindow.setAnimationStyle(R.style.PopupContextAnimation);
        popupWindow.setFocusable(true);
        popupLayout.measure(View.MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(1000), View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(1000), View.MeasureSpec.AT_MOST));
        popupWindow.setInputMethodMode(ActionBarPopupWindow.INPUT_METHOD_NOT_NEEDED);
        popupWindow.getContentView().setFocusableInTouchMode(true);

        // Show popup at the right position
        View anchor = actionBar.createMenu().getChildAt(actionBar.createMenu().getChildCount() - 1);
        if (anchor != null) {
            popupWindow.showAsDropDown(anchor, -popupLayout.getMeasuredWidth() + AndroidUtilities.dp(14), -AndroidUtilities.dp(18));
        }
    }

    private void showAccountSwitchDialog() {
        if (!shouldShowAccountSwitch()) {
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(getString(R.string.SwitchAccountNax));

        ArrayList<String> accounts = new ArrayList<>();
        ArrayList<Integer> accountIds = new ArrayList<>();

        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
            if (UserConfig.getInstance(i).isClientActivated() && !PasscodeHelper.isAccountHidden(i)) {
                TLRPC.User user = UserConfig.getInstance(i).getCurrentUser();
                if (user != null) {
                    String name = ContactsController.formatName(user.first_name, user.last_name);
                    if (i == currentAccount) {
                        name += " (" + getString(R.string.CurrentNax) + ")";
                    }
                    accounts.add(name);
                    accountIds.add(i);
                }
            }
        }

        builder.setItems(accounts.toArray(new String[0]), (dialog, which) -> {
            int selectedAccount = accountIds.get(which);
            if (selectedAccount != currentAccount) {
                switchToAccount(selectedAccount);
            }
        });

        builder.setNegativeButton(getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void switchToAccount(int accountId) {
        currentAccount = accountId;
        // Reset state preservation when switching accounts
        shouldPreserveState = false;
        wasInSearchMode = false;
        savedSearchQuery = "";
        for (int i = 0; i < savedScrollPositions.length; i++) {
            savedScrollPositions[i] = 0;
        }
        currentTabPosition = 0;
        
        updateTitle();
        loadHistoryItems();
        refreshAllPages();
    }

    private void showClearHistoryDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(getString(R.string.ClearRecentChats));
        builder.setMessage(getString(R.string.ClearRecentChatAlert));

        builder.setPositiveButton(getString(R.string.Clear), (dialog, which) -> {
            clearHistory();
        });

        builder.setNegativeButton(getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void clearHistory() {
        try {
            java.lang.reflect.Method clearRecentDialogsMethod = BackButtonMenuRecent.class.getDeclaredMethod("clearRecentDialogs", int.class);
            clearRecentDialogsMethod.setAccessible(true);
            clearRecentDialogsMethod.invoke(null, currentAccount);
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Reset state preservation when clearing history
        shouldPreserveState = false;
        wasInSearchMode = false;
        savedSearchQuery = "";
        for (int i = 0; i < savedScrollPositions.length; i++) {
            savedScrollPositions[i] = 0;
        }
        currentTabPosition = 0;

        // Immediately refresh the interface
        loadHistoryItems();
        refreshAllPages();
        BulletinFactory.of(this).createSimpleBulletin(R.raw.ic_delete, getString(R.string.ClearRecentChats)).show();
    }

    @Override
    public void onResume() {
        super.onResume();
        // Only refresh if we're not preserving state
        if (!shouldPreserveState) {
            loadHistoryItems();
            refreshAllPages();
        }
        // Reset the flag after onResume
        shouldPreserveState = false;
    }



    @Override
    public boolean onBackPressed() {
        if (isSearchMode && searchItem != null) {
            // Save search state before collapsing
            wasInSearchMode = true;
            savedSearchQuery = searchQuery;
            
            searchItem.collapseSearchFilters();
            return false;
        }
        return super.onBackPressed();
    }

    private void refreshAllPages() {
        // Don't refresh if we're preserving state
        if (shouldPreserveState) {
            return;
        }
        
        if (viewPager != null) {
            // Clear all cached views to prevent old content from showing
            clearViewPagerCache();

            // Force refresh all pages by recreating the adapter
            viewPager.setAdapter(new CategoryPagerAdapter());
            updateTabs();
        }
    }

    private void clearViewPagerCache() {
        if (viewPager != null) {
            try {
                // Force ViewPager to clear its view cache
                viewPager.removeAllViews();

                // Request layout to ensure proper refresh
                viewPager.requestLayout();

                // Small delay to ensure views are properly cleared
                viewPager.post(() -> {
                    if (viewPager != null) {
                        viewPager.invalidate();
                    }
                });
            } catch (Exception e) {
                // Ignore any exceptions during cache clearing
            }
        }
    }

    // ViewPager Adapter
    private class CategoryPagerAdapter extends ViewPagerFixed.Adapter {
        @Override
        public int getItemCount() {
            return ChatCategory.values().length;
        }

        @Override
        public String getItemTitle(int position) {
            return getTabTitle(ChatCategory.values()[position]);
        }

        @Override
        public View createView(int viewType) {
            Context context = getContext();
            if (context == null) return new View(getParentActivity());

            // Create a container to ensure proper isolation between pages
            FrameLayout container = new FrameLayout(context) {
                @Override
                protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                    super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                    // Ensure container fills the entire available space
                    setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec));
                }
            };
            container.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

            // Create RecyclerView for this category
            BlurredRecyclerView listView = new BlurredRecyclerView(context);
            listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
            listView.setVerticalScrollBarEnabled(false);

            // Add RecyclerView to container
            container.addView(listView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ));

            return container;
        }

        @Override
        public void bindView(View view, int position, int viewType) {
            if (view instanceof FrameLayout) {
                FrameLayout container = (FrameLayout) view;

                // Find the RecyclerView inside the container
                BlurredRecyclerView listView = null;
                for (int i = 0; i < container.getChildCount(); i++) {
                    View child = container.getChildAt(i);
                    if (child instanceof BlurredRecyclerView) {
                        listView = (BlurredRecyclerView) child;
                        break;
                    }
                }

                if (listView != null) {
                    // Clear any existing adapter to prevent data mixing
                    listView.setAdapter(null);

                    // Create fresh adapter with current data
                    CategoryListAdapter adapter = new CategoryListAdapter(getContext(), position);
                    listView.setAdapter(adapter);

                    // Set click listener
                    listView.setOnItemClickListener((itemView, itemPosition) -> {
                        adapter.onItemClick(itemView, itemPosition);
                    });

                    // Restore scroll position if preserving state
                    if (shouldPreserveState && savedScrollPositions[position] > 0) {
                        listView.post(() -> {
                            if (listView.getLayoutManager() instanceof LinearLayoutManager) {
                                LinearLayoutManager layoutManager = (LinearLayoutManager) listView.getLayoutManager();
                                layoutManager.scrollToPosition(savedScrollPositions[position]);
                            }
                        });
                    } else {
                        // Scroll to top to show fresh content only when not preserving state
                        listView.scrollToPosition(0);
                    }
                }
            }
        }
    }

    // Category List Adapter
    private class CategoryListAdapter extends RecyclerListView.SelectionAdapter {
        private Context mContext;
        private ChatCategory category;
        private ArrayList<HistoryItem> categoryItems = new ArrayList<>();

        public CategoryListAdapter(Context context, int categoryIndex) {
            mContext = context;
            category = ChatCategory.values()[categoryIndex];
            updateCategoryData();
        }

        private void updateCategoryData() {
            categoryItems.clear();

            // Use filtered data in search mode, otherwise use all data
            ArrayList<HistoryItem> sourceItems = isSearchMode ? filteredHistoryItems : allHistoryItems;

            // Ensure we're working with the latest data
            if (sourceItems == null || sourceItems.isEmpty()) {
                android.util.Log.d("CategoryListAdapter",
                    "No data available for " + category.name() + " category");
                return;
            }

            for (HistoryItem item : sourceItems) {
                if (shouldIncludeItem(item, category)) {
                    categoryItems.add(item);
                }
            }

            // Debug log
            android.util.Log.d("CategoryListAdapter",
                "Updated " + category.name() + " category: " + categoryItems.size() + " items from " + sourceItems.size() + " total" + (isSearchMode ? " (search mode)" : ""));
        }

        public void onItemClick(View view, int position) {
            if (position >= 0 && position < categoryItems.size()) {
                HistoryItem item = categoryItems.get(position);
                openChat(item);
            }
        }

        @Override
        public int getItemCount() {
            return categoryItems.isEmpty() ? 1 : categoryItems.size(); // Show empty state if no items
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            int viewType = getItemViewType(position);

            if (viewType == 1) { // Empty state
                if (holder.itemView instanceof EmptyStateCell) {
                    EmptyStateCell emptyStateCell = (EmptyStateCell) holder.itemView;

                    if (isSearchMode) {
                        // In search mode, show search results
                        if (TextUtils.isEmpty(searchQuery)) {
                            emptyStateCell.setText("", "Enter search query");
                        } else {
                            emptyStateCell.setText("", "No results found for \"" + searchQuery + "\"");
                        }
                    } else if (category == ChatCategory.ALL) {
                        // For ALL category, show "Recent Chats Empty"
                        emptyStateCell.setText("","No recent chats");
                    } else {
                        // For specific categories, show "No xx found" (no title)
                        String categoryDisplayName = getCategoryDisplayName(category);
                        emptyStateCell.setText("", "No " + categoryDisplayName + " found");
                    }
                }
            } else { // History item
                if (holder.itemView instanceof HistoryCell && position >= 0 && position < categoryItems.size()) {
                    HistoryCell historyCell = (HistoryCell) holder.itemView;
                    HistoryItem item = categoryItems.get(position);
                    historyCell.setDialog(item);
                }
            }
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return !categoryItems.isEmpty();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            if (viewType == 1) {
                view = new EmptyStateCell(mContext);
            } else {
                view = new HistoryCell(mContext);
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public int getItemViewType(int position) {
            return categoryItems.isEmpty() ? 1 : 0; // 0 = history item, 1 = empty state
        }
    }

    private String getCategoryDisplayName(ChatCategory category) {
        switch (category) {
            case USERS:
                return "users";
            case BOTS:
                return "bots";
            case GROUPS:
                return "groups";
            case CHANNELS:
                return "channels";
            default:
                return "items";
        }
    }
    

    private void openChat(HistoryItem item) {
        if (item == null || (item.user == null && item.chat == null)) {
            return;
        }

        // Save current state before opening chat
        saveCurrentState();

        // Check if we're viewing the current user's own account
        boolean isViewingOwnAccount = (currentAccount == UserConfig.selectedAccount);

        // If viewing own account, always use internal ChatActivity
        if (isViewingOwnAccount) {
            Bundle args = new Bundle();
            if (item.dialogId < 0) {
                args.putLong("chat_id", -item.dialogId);
                presentFragment(new ChatActivity(args));
            } else {
                args.putLong("user_id", item.dialogId);
                presentFragment(new ChatActivity(args));
            }
            return;
        }

        String username = null;

        if (item.user != null) {
            username = getBestLocalUsername(item.user);
        } else if (item.chat != null) {
            username = getBestLocalUsername(item.chat);
        }

        if (!TextUtils.isEmpty(username)) {
            MessagesController.getInstance(UserConfig.selectedAccount).openByUserName(username, this, 1);
        } else {
            // Check if this private chat exists in current account
            if (chatExistsInCurrentAccount(item)) {
                // Open directly if exists in current account
                Bundle args = new Bundle();
                if (item.dialogId < 0) {
                    args.putLong("chat_id", -item.dialogId);
                    presentFragment(new ChatActivity(args));
                } else {
                    args.putLong("user_id", item.dialogId);
                    presentFragment(new ChatActivity(args));
                }
            } else {
                // Show dialog for private chats when viewing other accounts
                showPrivateChatDialog(item);
            }
        }
    }

    private void saveCurrentState() {
        // Save scroll positions for all categories
        if (viewPager != null) {
            for (int i = 0; i < ChatCategory.values().length; i++) {
                View pageView = viewPager.getChildAt(i);
                if (pageView instanceof FrameLayout) {
                    FrameLayout container = (FrameLayout) pageView;
                    for (int j = 0; j < container.getChildCount(); j++) {
                        View child = container.getChildAt(j);
                        if (child instanceof BlurredRecyclerView) {
                            BlurredRecyclerView listView = (BlurredRecyclerView) child;
                            if (listView.getLayoutManager() instanceof LinearLayoutManager) {
                                LinearLayoutManager layoutManager = (LinearLayoutManager) listView.getLayoutManager();
                                savedScrollPositions[i] = layoutManager.findFirstVisibleItemPosition();
                            }
                            break;
                        }
                    }
                }
            }
        }

        // Save search state
        wasInSearchMode = isSearchMode;
        savedSearchQuery = searchQuery;
        currentTabPosition = viewPager != null ? viewPager.getCurrentItem() : 0;

        // Mark that we should preserve state on return
        shouldPreserveState = true;
    }

    private boolean chatExistsInCurrentAccount(HistoryItem item) {
        int selectedAccount = UserConfig.selectedAccount;

        if (item.dialogId > 0) {
            // User dialog - check if user exists in current account
            TLRPC.User user = MessagesController.getInstance(selectedAccount).getUser(item.dialogId);
            return user != null;
        } else {
            // Chat dialog - check if chat exists in current account
            long chatId = -item.dialogId;
            TLRPC.Chat chat = MessagesController.getInstance(selectedAccount).getChat(chatId);
            return chat != null;
        }
    }

    private void showPrivateChatDialog(HistoryItem item) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(getString(R.string.AppName));

        String chatName = "";
        if (item.user != null) {
            chatName = ContactsController.formatName(item.user.first_name, item.user.last_name);
        } else if (item.chat != null) {
            chatName = item.chat.title;
        }

        builder.setMessage(LocaleController.formatString("PrivateChatMessage", R.string.PrivateChatMessage, chatName));

        builder.setPositiveButton(getString(R.string.OK), (dialog, which) -> {
            // Try to open anyway
            Bundle args = new Bundle();
            if (item.dialogId < 0) {
                args.putLong("chat_id", -item.dialogId);
                presentFragment(new ChatActivity(args));
            } else {
                args.putLong("user_id", item.dialogId);
                presentFragment(new ChatActivity(args));
            }
        });

        builder.setNegativeButton(getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void showChatOptionsMenu(HistoryItem item, View anchorView) {
        // Determine available options
        boolean hasUsername = false;
        String username = null;
        if (item.user != null) {
            username = getBestLocalUsername(item.user);
            hasUsername = !TextUtils.isEmpty(username);
        } else if (item.chat != null) {
            username = getBestLocalUsername(item.chat);
            hasUsername = !TextUtils.isEmpty(username);
        }

        boolean isViewingOwnAccount = (currentAccount == UserConfig.selectedAccount);
        boolean canOpen = isViewingOwnAccount || hasUsername || (!isViewingOwnAccount && chatExistsInCurrentAccount(item));

        // Create final copies for lambda
        final String finalUsername = username;
        final boolean finalHasUsername = hasUsername;
        final boolean finalCanOpen = canOpen;

        // Create popup layout
        ActionBarPopupWindow.ActionBarPopupWindowLayout popupLayout = new ActionBarPopupWindow.ActionBarPopupWindowLayout(getContext());
        popupLayout.setFitItems(true);

        // Create and show popup window
        ActionBarPopupWindow popupWindow = new ActionBarPopupWindow(popupLayout, LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT);

        // Add Open option
        ActionBarMenuSubItem openItem = ActionBarMenuItem.addItem(popupLayout, R.drawable.msg_openin, getString(R.string.Open), false, getResourceProvider());
        openItem.setEnabled(finalCanOpen);
        if (!finalCanOpen) {
            openItem.setColors(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3), Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3));
        }
        openItem.setOnClickListener(v -> {
            popupWindow.dismiss();
            if (finalCanOpen) {
                openChat(item);
            }
        });

        // Add Share option
        ActionBarMenuSubItem shareItem = ActionBarMenuItem.addItem(popupLayout, R.drawable.msg_share, getString(R.string.ShareFile), false, getResourceProvider());
        shareItem.setEnabled(finalHasUsername);
        if (!finalHasUsername) {
            shareItem.setColors(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3), Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3));
        }
        shareItem.setOnClickListener(v -> {
            popupWindow.dismiss();
            if (finalHasUsername) {
                shareChat(finalUsername);
            }
        });

        // Add Copy option
        ActionBarMenuSubItem copyItem = ActionBarMenuItem.addItem(popupLayout, R.drawable.msg_copy, getString(R.string.Copy), false, getResourceProvider());
        copyItem.setEnabled(finalHasUsername);
        if (!finalHasUsername) {
            copyItem.setColors(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3), Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3));
        }
        copyItem.setOnClickListener(v -> {
            popupWindow.dismiss();
            if (finalHasUsername) {
                copyUsername(finalUsername);
            }
        });

        // Add Delete option (always available)
        ActionBarMenuSubItem deleteItem = ActionBarMenuItem.addItem(popupLayout, R.drawable.msg_delete, getString(R.string.Delete), false, getResourceProvider());
        deleteItem.setOnClickListener(v -> {
            popupWindow.dismiss();
            showDeleteChatDialog(item);
        });
        popupWindow.setPauseNotifications(true);
        popupWindow.setDismissAnimationDuration(220);
        popupWindow.setOutsideTouchable(true);
        popupWindow.setClippingEnabled(true);
        popupWindow.setAnimationStyle(R.style.PopupContextAnimation);
        popupWindow.setFocusable(true);
        popupLayout.measure(View.MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(1000), View.MeasureSpec.AT_MOST),
                           View.MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(1000), View.MeasureSpec.AT_MOST));
        popupWindow.setInputMethodMode(ActionBarPopupWindow.INPUT_METHOD_NOT_NEEDED);
        popupWindow.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED);
        popupWindow.getContentView().setFocusableInTouchMode(true);

        // Calculate position
        int[] location = new int[2];
        anchorView.getLocationInWindow(location);
        int popupX = location[0] + anchorView.getWidth() - popupLayout.getMeasuredWidth();
        int popupY = location[1];

        popupWindow.showAtLocation(anchorView, android.view.Gravity.LEFT | android.view.Gravity.TOP, popupX, popupY);
        popupWindow.dimBehind();
    }

    private void shareChat(String username) {
        try {
            String shareText = "@" + username;
            ShareAlert shareAlert = ShareAlert.createShareAlert(getContext(), null, shareText, false, shareText, false);
            showDialog(shareAlert);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void copyUsername(String username) {
        try {
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
                getParentActivity().getSystemService(Context.CLIPBOARD_SERVICE);
            android.content.ClipData clip = android.content.ClipData.newPlainText("username", "@" + username);
            clipboard.setPrimaryClip(clip);
            BulletinFactory.of(this).createSimpleBulletin(R.raw.copy,
                getString(R.string.TextCopied)).show();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void showDeleteChatDialog(HistoryItem item) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(getString(R.string.DeleteChatTitle));

        String chatName = "";
        if (item.user != null) {
            chatName = ContactsController.formatName(item.user.first_name, item.user.last_name);
        } else if (item.chat != null) {
            chatName = item.chat.title;
        }

        builder.setMessage(LocaleController.formatString("DeleteChatMessage", R.string.DeleteChatMessage, chatName));

        builder.setPositiveButton(getString(R.string.Delete), (dialog, which) -> {
            deleteChatFromHistory(item);
        });

        builder.setNegativeButton(getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void deleteChatFromHistory(HistoryItem item) {
        try {
            // Get recent dialogs using reflection
            java.lang.reflect.Method getRecentDialogsMethod = BackButtonMenuRecent.class.getDeclaredMethod("getRecentDialogs", int.class);
            getRecentDialogsMethod.setAccessible(true);

            @SuppressWarnings("unchecked")
            LinkedList<Long> recentDialogIds = (LinkedList<Long>) getRecentDialogsMethod.invoke(null, currentAccount);

            // Remove the dialog from the list
            recentDialogIds.remove(item.dialogId);

            // Save the updated list using reflection
            java.lang.reflect.Method saveRecentDialogsMethod = BackButtonMenuRecent.class.getDeclaredMethod("saveRecentDialogs", int.class, LinkedList.class);
            saveRecentDialogsMethod.setAccessible(true);
            saveRecentDialogsMethod.invoke(null, currentAccount, recentDialogIds);

            // Reset state preservation when deleting chat
            shouldPreserveState = false;
            wasInSearchMode = false;
            savedSearchQuery = "";
            for (int i = 0; i < savedScrollPositions.length; i++) {
                savedScrollPositions[i] = 0;
            }
            currentTabPosition = 0;

            // Refresh the interface
            loadHistoryItems();
            refreshAllPages();

            BulletinFactory.of(this).createSimpleBulletin(R.raw.ic_delete,
                getString(R.string.ChatRemovedFromRecent)).show();
        } catch (Exception e) {
            e.printStackTrace();
            // Fallback: manually remove from local list and refresh
            allHistoryItems.removeIf(historyItem -> historyItem.dialogId == item.dialogId);
            
            // Reset state preservation when deleting chat
            shouldPreserveState = false;
            wasInSearchMode = false;
            savedSearchQuery = "";
            for (int i = 0; i < savedScrollPositions.length; i++) {
                savedScrollPositions[i] = 0;
            }
            currentTabPosition = 0;
            
            refreshAllPages();
            BulletinFactory.of(this).createSimpleBulletin(R.raw.ic_delete,
                getString(R.string.ChatRemovedFromRecent)).show();
        }
    }


    // Data classes
    private static class HistoryItem {
        long dialogId;
        TLRPC.Chat chat;
        TLRPC.User user;
    }

    // Custom cells
    private class HistoryCell extends FrameLayout {
        private BackupImageView avatarImageView;
        private TextView nameTextView;
        private TextView usernameTextView;
        private AvatarDrawable avatarDrawable;
        private ActionBarMenuItem optionsButton;
        private HistoryItem currentItem;

        public HistoryCell(Context context) {
            super(context);

            avatarDrawable = new AvatarDrawable();
            avatarImageView = new BackupImageView(context);
            avatarImageView.setRoundRadius(AndroidUtilities.dp(25));
            addView(avatarImageView, LayoutHelper.createFrame(50, 50, Gravity.LEFT | Gravity.CENTER_VERTICAL, 16, 0, 0, 0));

            nameTextView = new TextView(context);
            nameTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            nameTextView.setTextSize(16);
            nameTextView.setLines(1);
            nameTextView.setMaxLines(1);
            nameTextView.setSingleLine(true);
            nameTextView.setEllipsize(TextUtils.TruncateAt.END);
            nameTextView.setGravity(Gravity.LEFT);
            addView(nameTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 82, 16, 64, 0));

            usernameTextView = new TextView(context);
            usernameTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3));
            usernameTextView.setTextSize(14);
            usernameTextView.setLines(1);
            usernameTextView.setMaxLines(1);
            usernameTextView.setSingleLine(true);
            usernameTextView.setEllipsize(TextUtils.TruncateAt.END);
            usernameTextView.setGravity(Gravity.LEFT);
            addView(usernameTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 82, 38, 64, 0));

            // Add options button (three dots)
            optionsButton = new ActionBarMenuItem(context, null, 0, Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3));
            optionsButton.setIcon(R.drawable.ic_ab_other);
            optionsButton.setBackgroundDrawable(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 1));
            optionsButton.setOnClickListener(v -> {
                if (currentItem != null) {
                    showChatOptionsMenu(currentItem, v);
                }
            });
            addView(optionsButton, LayoutHelper.createFrame(48, 48, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 8, 0));

            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(72), MeasureSpec.EXACTLY));
        }

        public void setDialog(HistoryItem item) {
            this.currentItem = item;

            if (item.user != null) {
                // User dialog
                String name;
                if (UserObject.isDeleted(item.user)) {
                    name = getString(R.string.HiddenName);
                } else {
                    name = UserObject.getUserName(item.user);
                    // If getUserName returns empty or "HiddenName", try to use username as fallback
                    if (TextUtils.isEmpty(name) || name.equals(getString(R.string.HiddenName))) {
                        if (!TextUtils.isEmpty(item.user.username)) {
                            name = "@" + item.user.username;
                        } else {
                            name = "User " + item.user.id; // Last resort fallback
                        }
                    }
                }

                avatarDrawable.setInfo(item.user);
                avatarImageView.setForUserOrChat(item.user, avatarDrawable);
                nameTextView.setText(name);

                // Show username or special status
                String usernameText = getUsernameText(item.user);
                if (!TextUtils.isEmpty(usernameText) && !usernameText.trim().isEmpty()) {
                    usernameTextView.setText(usernameText);
                    usernameTextView.setVisibility(VISIBLE);
                } else {
                    usernameTextView.setVisibility(GONE);
                }
            } else if (item.chat != null) {
                // Chat dialog
                avatarDrawable.setInfo(item.chat);
                avatarImageView.setForUserOrChat(item.chat, avatarDrawable);
                nameTextView.setText(item.chat.title);

                // Show username or private status
                String usernameText = getChatUsernameText(item.chat);
                if (!TextUtils.isEmpty(usernameText)) {
                    usernameTextView.setText(usernameText);
                    usernameTextView.setVisibility(VISIBLE);
                } else {
                    usernameTextView.setVisibility(GONE);
                }
            }
        }

        private String getUsernameText(TLRPC.User user) {
            // Use UserObject.getPublicUsername to get the primary username (including collectible usernames)
            String username = UserObject.getPublicUsername(user);
            if (!TextUtils.isEmpty(username) && !username.trim().isEmpty()) {
                return "@" + username;
            }
            // fallback to best local username without network
            String fallback = getBestLocalUsername(user);
            if (!TextUtils.isEmpty(fallback)) {
                return "@" + fallback;
            }
            // show user id when no username locally
            return "ID: " + user.id;
        }

        private String getChatUsernameText(TLRPC.Chat chat) {
            // Use ChatObject.getPublicUsername to get the primary username (including collectible usernames)
            String username = ChatObject.getPublicUsername(chat);
            if (!TextUtils.isEmpty(username)) {
                return "@" + username;
            }
            String fallback = getBestLocalUsername(chat);
            if (!TextUtils.isEmpty(fallback)) {
                return "@" + fallback;
            }

            // Show private status for private channels/groups
            if (chat.broadcast) {
                return getString(R.string.ChannelPrivate);
            } else {
                return getString(R.string.MegaPrivate);
            }
        }
    }

    // Removed passive refresh per request

    // Prefer real-time public username; fallback to locally available username fields without network
    private String getBestLocalUsername(TLRPC.User user) {
        if (user == null) return "";
        String username = UserObject.getPublicUsername(user);
        if (!TextUtils.isEmpty(username)) return username;
        if (!TextUtils.isEmpty(user.username)) return user.username; // legacy primary username
        if (user.usernames != null) {
            // pick the first active collectible username if present locally
            for (int i = 0; i < user.usernames.size(); i++) {
                TLRPC.TL_username u = user.usernames.get(i);
                if (u != null && u.active && !TextUtils.isEmpty(u.username)) {
                    return u.username;
                }
            }
        }
        return "";
    }

    private String getBestLocalUsername(TLRPC.Chat chat) {
        if (chat == null) return "";
        String username = ChatObject.getPublicUsername(chat);
        if (!TextUtils.isEmpty(username)) return username;
        if (!TextUtils.isEmpty(chat.username)) return chat.username;
        if (chat.usernames != null) {
            for (int i = 0; i < chat.usernames.size(); i++) {
                TLRPC.TL_username u = chat.usernames.get(i);
                if (u != null && u.active && !TextUtils.isEmpty(u.username)) {
                    return u.username;
                }
            }
        }
        return "";
    }

    private class EmptyStateCell extends FrameLayout {
        private TextView titleTextView;
        private TextView descriptionTextView;

        public EmptyStateCell(Context context) {
            super(context);

            titleTextView = new TextView(context);
            titleTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3));
            titleTextView.setTextSize(17);
            titleTextView.setGravity(Gravity.CENTER);
            addView(titleTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 32, 48, 32, 0));

            descriptionTextView = new TextView(context);
            descriptionTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3));
            descriptionTextView.setTextSize(15);
            descriptionTextView.setGravity(Gravity.CENTER);
            addView(descriptionTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 32, 80, 32, 48));

            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            // Use a reasonable height for empty state, container will handle the full coverage
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(200), MeasureSpec.EXACTLY)
            );
        }

        public void setText(String title, String description) {
            if (TextUtils.isEmpty(title)) {
                titleTextView.setVisibility(GONE);
            } else {
                titleTextView.setText(title);
                titleTextView.setVisibility(VISIBLE);
            }

            if (TextUtils.isEmpty(description)) {
                descriptionTextView.setVisibility(GONE);
            } else {
                descriptionTextView.setText(description);
                descriptionTextView.setVisibility(VISIBLE);
            }
        }
    }
}
