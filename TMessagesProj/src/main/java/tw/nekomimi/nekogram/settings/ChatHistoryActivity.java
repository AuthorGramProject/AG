package tw.nekomimi.nekogram.settings;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import static android.view.View.MeasureSpec;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BlurredRecyclerView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.SizeNotifierFrameLayout;
import org.telegram.ui.Components.ViewPagerFixed;
import org.telegram.ui.LaunchActivity;

import tw.nekomimi.nekogram.helpers.PasscodeHelper;

import java.util.ArrayList;
import java.util.LinkedList;

import tw.nekomimi.nekogram.BackButtonMenuRecent;

public class ChatHistoryActivity extends BaseFragment {

    // Chat categories
    public enum ChatCategory {
        ALL(0, "All"),
        USERS(1, "Users"),
        BOTS(2, "Bots"),
        GROUPS(3, "Groups"),
        CHANNELS(4, "Channels");

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

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        loadHistoryItems();
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
                } else if (id == 1) {
                    showClearHistoryDialog();
                } else if (id == 2) {
                    showAccountSwitchDialog();
                }
            }
        });

        // Create menu (clear existing menu first)
        actionBar.createMenu().clearItems();
        ActionBarMenu menu = actionBar.createMenu();

        // Add account switch button (only if multiple accounts and not hidden by passcode)
        if (shouldShowAccountSwitch()) {
            menu.addItem(2, R.drawable.msg_settings);
        }

        // Add clear button
        menu.addItem(1, R.drawable.msg_delete);

        // Create main layout
        SizeNotifierFrameLayout fragmentView = new SizeNotifierFrameLayout(context);
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        // Create ViewPager with tabs
        createViewPager(context, fragmentView);

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
                    item.user = MessagesController.getInstance(currentAccount).getUser(dialogId);
                } else {
                    // Chat dialog
                    long chatId = -dialogId;
                    item.chat = MessagesController.getInstance(currentAccount).getChat(chatId);
                }

                if (item.user != null || item.chat != null) {
                    allHistoryItems.add(item);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Update tabs after loading data
        updateTabs();
    }

    private boolean isOfficialDialog(long dialogId) {
        if (dialogId > 0) {
            // 过滤官方 Telegram 用户
            if (dialogId == 777000 || // Telegram service notifications
                dialogId == 708513 ||  // Replies bot
                dialogId == UserConfig.getInstance(currentAccount).getClientUserId()) { // Self
                return true;
            }

            TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(dialogId);
            if (user != null) {
                // 检查是否为 回复
                if ("replies".equals(user.username) && "Replies".equals(user.first_name)) {
                    return true;
                }
                // 检查是否为官方认证或支持账号
                if (user.verified || user.support) {
                    return true;
                }
            }
        }
        return false;
    }

    private void updateTitle() {
        String accountName = UserConfig.getInstance(currentAccount).getCurrentUser().first_name;
        if (accountName.length() > 15) {
            accountName = accountName.substring(0, 15) + "...";
        }
        actionBar.setTitle(getString(R.string.RecentChats) + " - " + accountName);
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

        // Immediately refresh the interface
        loadHistoryItems();
        refreshAllPages();
        BulletinFactory.of(this).createSimpleBulletin(R.raw.ic_delete, getString(R.string.ClearRecentChats)).show();
    }

    @Override
    public void onResume() {
        super.onResume();
        loadHistoryItems();
        refreshAllPages();
    }

    private void refreshAllPages() {
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

                    // Scroll to top to show fresh content
                    listView.scrollToPosition(0);
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

            // Ensure we're working with the latest data
            if (allHistoryItems == null || allHistoryItems.isEmpty()) {
                android.util.Log.d("CategoryListAdapter",
                    "No data available for " + category.name() + " category");
                return;
            }

            for (HistoryItem item : allHistoryItems) {
                if (shouldIncludeItem(item, category)) {
                    categoryItems.add(item);
                }
            }

            // Debug log
            android.util.Log.d("CategoryListAdapter",
                "Updated " + category.name() + " category: " + categoryItems.size() + " items from " + allHistoryItems.size() + " total");
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

                    if (category == ChatCategory.ALL) {
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
        if (item.user != null) {
            MessagesController.getInstance(currentAccount).putUser(item.user, true);
        } else {
            MessagesController.getInstance(currentAccount).putChat(item.chat, true);
        }

        Bundle args = new Bundle();
        args.putInt("currentAccount", currentAccount);

        if (item.user != null) {
            args.putLong("user_id", item.dialogId);
            if (!TextUtils.isEmpty(item.user.username)) {
                args.putString("username", item.user.username);
            }
        } else if (item.chat != null) {
            args.putLong("chat_id", -item.dialogId);
            if (!TextUtils.isEmpty(item.chat.username)) {
                args.putString("username", item.chat.username);
            }
        }
        presentFragment(new org.telegram.ui.ChatActivity(args));
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
            addView(nameTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 82, 16, 16, 0));

            usernameTextView = new TextView(context);
            usernameTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3));
            usernameTextView.setTextSize(14);
            usernameTextView.setLines(1);
            usernameTextView.setMaxLines(1);
            usernameTextView.setSingleLine(true);
            usernameTextView.setEllipsize(TextUtils.TruncateAt.END);
            usernameTextView.setGravity(Gravity.LEFT);
            addView(usernameTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 82, 38, 16, 0));

            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(72), MeasureSpec.EXACTLY));
        }

        public void setDialog(HistoryItem item) {
            if (item.user != null) {
                // User dialog
                avatarDrawable.setInfo(item.user);
                avatarImageView.setForUserOrChat(item.user, avatarDrawable);
                nameTextView.setText(ContactsController.formatName(item.user.first_name, item.user.last_name));

                // Show username or special status
                String usernameText = getUsernameText(item.user);
                if (!TextUtils.isEmpty(usernameText)) {
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
            // Show primary username if available (including for self/saved messages)
            if (!TextUtils.isEmpty(user.username)) {
                return "@" + user.username;
            }

            // For users without username, don't show anything
            return null;
        }

        private String getChatUsernameText(TLRPC.Chat chat) {
            // Show username if available (public channel/group)
            if (!TextUtils.isEmpty(chat.username)) {
                return "@" + chat.username;
            }

            // Show private status for private channels/groups
            if (chat.broadcast) {
                return getString(R.string.ChannelPrivate);
            } else {
                return getString(R.string.MegaPrivate);
            }
        }
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