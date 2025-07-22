package tw.nekomimi.nekogram.settings;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.R;
import tw.nekomimi.nekogram.BackButtonMenuRecent;
import tw.nekomimi.nekogram.helpers.PasscodeHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.EmptyCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BlurredRecyclerView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.SizeNotifierFrameLayout;
import org.telegram.ui.TopicsFragment;

import java.util.ArrayList;
import java.util.LinkedList;

import tw.nekomimi.nekogram.ui.cells.HeaderCell;
import xyz.nextalone.nagram.NaConfig;

public class ChatHistoryActivity extends BaseFragment {

    private BlurredRecyclerView listView;
    private ListAdapter listAdapter;
    private LinearLayoutManager layoutManager;

    private ArrayList<HistoryItem> historyItems = new ArrayList<>();
    private int rowCount;

    private int emptyRow;
    private int historyHeaderRow;
    private int historyStartRow;
    private int historyEndRow;
    private int shadowRow;

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        loadHistoryItems();
        updateRows();
        return true;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);

        // Count visible accounts (excluding hidden accounts)
        int visibleAccountsCount = 0;
        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
            if (UserConfig.getInstance(i).isClientActivated() && !PasscodeHelper.isAccountHidden(i)) {
                visibleAccountsCount++;
            }
        }

        // Set title with current account info if multiple visible accounts exist
        String title = getString(R.string.RecentChats);
        if (visibleAccountsCount > 1) {
            TLRPC.User currentUser = UserConfig.getInstance(currentAccount).getCurrentUser();
            String accountName = UserObject.getFirstName(currentUser);
            if (accountName.length() > 15) {
                accountName = accountName.substring(0, 15) + "...";
            }
            title = title + " - " + accountName;
        }
        actionBar.setTitle(title);
        actionBar.setAllowOverlayTitle(true);

        // Add account switcher menu if there are multiple visible accounts
        if (visibleAccountsCount > 1) {
            ActionBarMenu menu = actionBar.createMenu();
            ActionBarMenuItem item = menu.addItem(0, R.drawable.ic_ab_other);
            item.setSubMenuOpenSide(1);

            for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
                if (UserConfig.getInstance(i).isClientActivated() && !PasscodeHelper.isAccountHidden(i)) {
                    TLRPC.User user = UserConfig.getInstance(i).getCurrentUser();
                    String accountName = UserObject.getFirstName(user);
                    if (accountName.length() > 15) {
                        accountName = accountName.substring(0, 15) + "...";
                    }
                    item.addSubItem(100 + i, accountName);
                }
            }
        }

        if (AndroidUtilities.isTablet()) {
            actionBar.setOccupyStatusBar(false);
        }

        ActionBarMenu menu = actionBar.createMenu();
        ActionBarMenuItem clearItem = menu.addItem(0, R.drawable.msg_clear);
        clearItem.setContentDescription(getString(R.string.ClearRecentChats));
        clearItem.setOnClickListener(v -> showClearHistoryDialog());

        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id >= 100 && id < 100 + UserConfig.MAX_ACCOUNT_COUNT) {
                    // Switch to different account
                    int accountNum = id - 100;
                    if (accountNum != currentAccount &&
                        UserConfig.getInstance(accountNum).isClientActivated() &&
                        !PasscodeHelper.isAccountHidden(accountNum)) {
                        switchToAccount(accountNum);
                    }
                }
            }
        });

        fragmentView = new SizeNotifierFrameLayout(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            }
        };
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        listView = new BlurredRecyclerView(context);
        listView.setLayoutManager(layoutManager = new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(false);
        listView.setAdapter(listAdapter = new ListAdapter(context));

        ((SizeNotifierFrameLayout) fragmentView).addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        listView.setOnItemClickListener((view, position) -> {
            if (position >= historyStartRow && position < historyEndRow) {
                int index = position - historyStartRow;
                if (index >= 0 && index < historyItems.size()) {
                    HistoryItem item = historyItems.get(index);
                    openChat(item);
                }
            }
        });

        return fragmentView;
    }

    private void loadHistoryItems() {
        historyItems.clear();

        // Get recent dialogs from BackButtonMenuRecent directly
        try {
            // Use reflection to access the private getRecentDialogs method
            java.lang.reflect.Method getRecentDialogsMethod = BackButtonMenuRecent.class.getDeclaredMethod("getRecentDialogs", int.class);
            getRecentDialogsMethod.setAccessible(true);

            @SuppressWarnings("unchecked")
            java.util.LinkedList<Long> recentDialogIds = (java.util.LinkedList<Long>) getRecentDialogsMethod.invoke(null, currentAccount);

            // Debug: Log the number of dialogs for this account
            android.util.Log.d("ChatHistoryActivity", "Loading history for account " + currentAccount + ", found " + recentDialogIds.size() + " dialogs");

            for (Long dialogId : recentDialogIds) {
                // Skip official/system dialogs
                if (isOfficialDialog(dialogId)) {
                    continue;
                }

                HistoryItem item = new HistoryItem();
                item.dialogId = dialogId;

                // Get chat or user info
                if (dialogId > 0) {
                    // User dialog
                    item.user = MessagesController.getInstance(currentAccount).getUser(dialogId);
                    // If user is null, try to load it from database
                    if (item.user == null) {
                        // Load user from database synchronously
                        try {
                            java.util.ArrayList<Long> userIds = new java.util.ArrayList<>();
                            userIds.add(dialogId);
                            java.util.ArrayList<TLRPC.User> users = MessagesStorage.getInstance(currentAccount).getUsers(userIds);
                            if (!users.isEmpty()) {
                                item.user = users.get(0);
                                // Put it in memory cache for future use
                                MessagesController.getInstance(currentAccount).putUser(item.user, true);
                            }
                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                    }
                } else {
                    // Chat dialog
                    item.chat = MessagesController.getInstance(currentAccount).getChat(-dialogId);
                    // If chat is null, try to load it from database
                    if (item.chat == null) {
                        try {
                            java.util.ArrayList<Long> chatIds = new java.util.ArrayList<>();
                            chatIds.add(-dialogId);
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
                }

                historyItems.add(item);
            }
        } catch (Exception e) {
            // Fallback: if reflection fails, the list will remain empty
            e.printStackTrace();
        }

        // Update the UI after loading items
        updateRows();
    }

    private boolean isOfficialDialog(long dialogId) {
        // Filter out official/system dialogs that shouldn't appear in chat history
        if (dialogId > 0) {
            // User dialogs - check for official users
            TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(dialogId);
            if (user != null) {
                // Filter out official Telegram users like Replies, Saved Messages, etc.
                if (UserObject.isReplyUser(user) ||
                    UserObject.isUserSelf(user) ||
                    user.id == 777000 || // Telegram notifications
                    user.id == 429000 || // Stickers bot
                    user.id == 136817688) { // @BotFather
                    return true;
                }
            }
        } else {
            // Chat dialogs - check for official chats
            long chatId = -dialogId;
            TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(chatId);
            if (chat != null) {
                // Filter out official Telegram channels/groups if needed
                // Currently no specific official chats to filter
            }
        }
        return false;
    }

    private void updateRows() {
        rowCount = 0;
        
        if (historyItems.isEmpty()) {
            emptyRow = rowCount++;
            historyHeaderRow = -1;
            historyStartRow = -1;
            historyEndRow = -1;
        } else {
            emptyRow = -1;
            historyHeaderRow = -1; // Remove header row since we have title in action bar
            historyStartRow = rowCount;
            rowCount += historyItems.size();
            historyEndRow = rowCount;
        }
        
        shadowRow = rowCount++;
        
        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
    }

    private void switchToAccount(int accountNum) {
        android.util.Log.d("ChatHistoryActivity", "Switching from account " + currentAccount + " to account " + accountNum);
        currentAccount = accountNum;

        // Update action bar title to show current account
        TLRPC.User currentUser = UserConfig.getInstance(currentAccount).getCurrentUser();
        String accountName = UserObject.getFirstName(currentUser);
        if (accountName.length() > 15) {
            accountName = accountName.substring(0, 15) + "...";
        }
        actionBar.setTitle(getString(R.string.RecentChats) + " - " + accountName);

        // Reload history for the new account
        loadHistoryItems();
        updateRows();
        android.util.Log.d("ChatHistoryActivity", "Account switch completed, historyItems size: " + historyItems.size());
    }

    private void openChat(HistoryItem item) {
        Bundle args = new Bundle();
        if (item.dialogId < 0) {
            args.putLong("chat_id", -item.dialogId);
            if (MessagesController.getInstance(currentAccount).isForum(item.dialogId)) {
                presentFragment(new TopicsFragment(args));
            } else {
                presentFragment(new ChatActivity(args));
            }
        } else {
            args.putLong("user_id", item.dialogId);
            presentFragment(new ChatActivity(args));
        }
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
            Class<?> backButtonMenuRecentClass = Class.forName("tw.nekomimi.nekogram.BackButtonMenuRecent");
            java.lang.reflect.Method clearRecentDialogsMethod = backButtonMenuRecentClass.getDeclaredMethod("clearRecentDialogs", int.class);
            clearRecentDialogsMethod.setAccessible(true);
            clearRecentDialogsMethod.invoke(null, currentAccount);
        } catch (Exception e) {
            e.printStackTrace();
        }

        loadHistoryItems();
        updateRows();
        BulletinFactory.of(this).createSimpleBulletin(R.raw.ic_delete, getString(R.string.ClearRecentChats)).show();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        private Context mContext;

        public ListAdapter(Context context) {
            mContext = context;
        }

        @Override
        public int getItemCount() {
            return rowCount;
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            switch (holder.getItemViewType()) {
                case 0: // Empty
                    break;
                case 1: // Header
                    HeaderCell headerCell = (HeaderCell) holder.itemView;
                    if (position == historyHeaderRow) {
                        headerCell.setText(getString(R.string.RecentChats));
                    }
                    break;
                case 2: // History item
                    HistoryCell historyCell = (HistoryCell) holder.itemView;
                    int index = position - historyStartRow;
                    if (index >= 0 && index < historyItems.size()) {
                        HistoryItem item = historyItems.get(index);
                        historyCell.setDialog(item);
                    }
                    break;
                case 3: // Shadow
                    break;
                case 4: // Empty state
                    EmptyStateCell emptyStateCell = (EmptyStateCell) holder.itemView;
                    emptyStateCell.setText(getString(R.string.RecentChatsEmpty), getString(R.string.RecentChatsEmptyDesc));
                    break;
            }
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int type = holder.getItemViewType();
            return type == 2; // Only history items are clickable
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case 0:
                    view = new EmptyCell(mContext, AndroidUtilities.dp(8));
                    break;
                case 1:
                    view = new HeaderCell(mContext);
                    break;
                case 2:
                    view = new HistoryCell(mContext);
                    break;
                case 3:
                    view = new ShadowSectionCell(mContext);
                    break;
                case 4:
                default:
                    view = new EmptyStateCell(mContext);
                    break;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public int getItemViewType(int position) {
            if (position == emptyRow) {
                return historyItems.isEmpty() ? 4 : 0; // Empty state or spacing
            } else if (position == historyHeaderRow) {
                return 1; // Header
            } else if (position >= historyStartRow && position < historyEndRow) {
                return 2; // History item
            } else if (position == shadowRow) {
                return 3; // Shadow
            }
            return 0;
        }
    }

    private static class HistoryItem {
        long dialogId;
        TLRPC.Chat chat;
        TLRPC.User user;
    }

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
            String name;
            String username = null;

            if (item.chat != null) {
                TLRPC.Chat chat = item.chat;
                name = chat.title;
                if (!TextUtils.isEmpty(chat.username)) {
                    username = "@" + chat.username;
                } else {
                    // For private chats without username, show type
                    if (chat.megagroup) {
                        username = getString(R.string.MegaPrivate);
                    } else if (chat.broadcast) {
                        username = getString(R.string.ChannelPrivate);
                    } else {
                        username = getString(R.string.MegaPrivate);
                    }
                }
                avatarDrawable.setInfo(chat);
                avatarImageView.setForUserOrChat(chat, avatarDrawable);
            } else if (item.user != null) {
                TLRPC.User user = item.user;
                if (UserObject.isUserSelf(user)) {
                    name = getString(R.string.SavedMessages);
                    avatarDrawable.setAvatarType(AvatarDrawable.AVATAR_TYPE_SAVED);
                    avatarImageView.setImageDrawable(avatarDrawable);
                } else if (UserObject.isReplyUser(user)) {
                    name = getString(R.string.RepliesTitle);
                    avatarDrawable.setAvatarType(AvatarDrawable.AVATAR_TYPE_REPLIES);
                    avatarImageView.setImageDrawable(avatarDrawable);
                } else if (UserObject.isDeleted(user)) {
                    name = getString(R.string.HiddenName);
                    avatarDrawable.setInfo(user);
                    avatarImageView.setForUserOrChat(user, avatarDrawable);
                } else {
                    name = UserObject.getUserName(user);
                    String publicUsername = UserObject.getPublicUsername(user);
                    if (!TextUtils.isEmpty(publicUsername)) {
                        username = "@" + publicUsername;
                    }
                    avatarDrawable.setInfo(user);
                    avatarImageView.setForUserOrChat(user, avatarDrawable);
                }
            } else {
                name = "Unknown";
                avatarDrawable.setInfo(0, "?", "?");
                avatarImageView.setImageDrawable(avatarDrawable);
            }

            nameTextView.setText(name);
            if (!TextUtils.isEmpty(username)) {
                usernameTextView.setText(username);
                usernameTextView.setVisibility(VISIBLE);
            } else {
                usernameTextView.setVisibility(GONE);
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
            super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(200), MeasureSpec.EXACTLY));
        }

        public void setText(String title, String description) {
            titleTextView.setText(title);
            descriptionTextView.setText(description);
        }
    }
}
