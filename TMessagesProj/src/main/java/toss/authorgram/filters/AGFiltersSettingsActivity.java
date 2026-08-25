package toss.authorgram.filters;

import static org.telegram.messenger.LocaleController.getString;

import android.annotation.SuppressLint;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.gson.Gson;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MultipartBody;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.DialogsActivity;

import java.util.ArrayList;
import java.util.HashMap;

import tw.nekomimi.nekogram.NekoConfig;
import tw.nekomimi.nekogram.config.CellGroup;
import tw.nekomimi.nekogram.config.cell.AbstractConfigCell;
import tw.nekomimi.nekogram.config.cell.ConfigCellCheckBox;
import tw.nekomimi.nekogram.config.cell.ConfigCellCustom;
import tw.nekomimi.nekogram.config.cell.ConfigCellDivider;
import tw.nekomimi.nekogram.config.cell.ConfigCellHeader;
import tw.nekomimi.nekogram.config.cell.ConfigCellTextCheck;
import toss.authorgram.settings.BaseAGXSettingsActivity;
import tw.nekomimi.nekogram.ui.cells.FiltersChatCell;
import tw.nekomimi.nekogram.utils.HttpClient;
import xyz.nextalone.nagram.NaConfig;

public class AGFiltersSettingsActivity extends BaseAGXSettingsActivity {

    private static final int VIEW_TYPE_CHAT_FILTER = 100;

    private static class DialogFilterItem {
        long dialogId;
        int chatFiltersCount;
        int excludedSharedCount;
    }

    private static class DialogFilterCell extends ConfigCellCustom {
        final DialogFilterItem item;

        DialogFilterCell(DialogFilterItem item) {
            super("RegexFiltersChat_" + item.dialogId, VIEW_TYPE_CHAT_FILTER, true);
            this.item = item;
        }
    }

    private ListAdapter listAdapter;
    private final CellGroup cellGroup = new CellGroup(this);
    private final AbstractConfigCell filtersOptionHeaderRow = new ConfigCellHeader(getString(R.string.General));
    private final AbstractConfigCell regexFiltersEnabledRow = new ConfigCellTextCheck(NaConfig.INSTANCE.getRegexFiltersEnabled(), null, getString(R.string.RegexFiltersEnabled));
    private final AbstractConfigCell regexFiltersEnableInChatsRow = new ConfigCellTextCheck(NaConfig.INSTANCE.getRegexFiltersEnableInChats(), null, getString(R.string.RegexFiltersEnableInChats));
    private final AbstractConfigCell ignoreBlockedRow = new ConfigCellTextCheck(NekoConfig.ignoreBlocked, null, getString(R.string.IgnoreBlocked));
    private final AbstractConfigCell filtersOptionDividerRow = new ConfigCellDivider();
    private final AbstractConfigCell regexFiltersMaskMessagesRow = new ConfigCellCustom("RegexFiltersMaskMessagesShort", CellGroup.ITEM_TYPE_TEXT_CHECK, true);
    private final AbstractConfigCell regexFiltersMaskMessagesInfoRow = new ConfigCellCustom("RegexFiltersMaskMessagesAbout", CellGroup.ITEM_TYPE_TEXT, false);
    private final AbstractConfigCell filtersHeaderRow = new ConfigCellHeader(getString(R.string.RegexFiltersGlobalHeader));
    private final AbstractConfigCell sharedFiltersPageRow = new ConfigCellCustom("RegexFiltersSharedHeader", CellGroup.ITEM_TYPE_TEXT_SETTINGS_CELL, true);
    private final AbstractConfigCell userFiltersPageRow = new ConfigCellCustom("ShadowBan", CellGroup.ITEM_TYPE_TEXT_SETTINGS_CELL, true);
    private final AbstractConfigCell dividerRow = new ConfigCellDivider();
    private final AbstractConfigCell chatFiltersHeaderRow = new ConfigCellHeader(getString(R.string.RegexFiltersChatHeader));

    public AGFiltersSettingsActivity() {
        rebuildRows();
    }

    private void addCell(AbstractConfigCell cell) {
        cell.bindCellGroup(cellGroup);
        cellGroup.rows.add(cell);
    }

    private void rebuildRows() {
        cellGroup.rows.clear();
        addCell(filtersOptionHeaderRow);
        addCell(regexFiltersEnabledRow);
        addCell(regexFiltersEnableInChatsRow);
        addCell(ignoreBlockedRow);
        addCell(filtersOptionDividerRow);
        addCell(regexFiltersMaskMessagesRow);
        addCell(regexFiltersMaskMessagesInfoRow);
        addCell(filtersHeaderRow);
        addCell(sharedFiltersPageRow);
        addCell(userFiltersPageRow);
        addCell(dividerRow);
        var chatEntries = getDialogFilterItems();
        if (!chatEntries.isEmpty()) {
            addCell(chatFiltersHeaderRow);
            for (DialogFilterItem item : chatEntries) {
                addCell(new DialogFilterCell(item));
            }
        }
        addRowsToMap(cellGroup);
    }

    @SuppressLint("NotifyDataSetChanged")
    @Override
    public void onResume() {
        rebuildRows();
        super.onResume();
    }

    @Override
    protected RecyclerListView.SelectionAdapter getListAdapter() {
        return listAdapter;
    }

    @Override
    protected CellGroup getCellGroup() {
        return cellGroup;
    }

    @SuppressWarnings("NewApi")
    @Override
    public View createView(Context context) {
        View v = super.createView(context);
        listAdapter = new ListAdapter(context);
        listView.setAdapter(listAdapter);
        setupDefaultListeners();
        setupLongClickListener();

        cellGroup.callBackSettingsChanged = (key, newValue) -> {
            if (key.equals(NekoConfig.ignoreBlocked.getKey())) {
                if ((boolean) newValue && !NaConfig.INSTANCE.getRegexFiltersEnabled().Bool()) {
                    NaConfig.INSTANCE.getRegexFiltersEnabled().setConfigBool(true);
                    if (listAdapter != null) {
                        listAdapter.notifyDataSetChanged();
                    }
                }
            }
            AGFilter.invalidateFilteredCache();
        };

        ActionBarMenu menu = actionBar.createMenu();
        ActionBarMenuItem menuItem = menu.addItem(0, R.drawable.ic_ab_other);
        menuItem.addSubItem(1, R.drawable.msg_user_search, getString(R.string.SelectChat));
        menuItem.addColoredGap();
        menuItem.addSubItem(2, R.drawable.msg_archive, getString(R.string.RegexFiltersImport));
        menuItem.addSubItem(3, R.drawable.msg_unarchive, getString(R.string.RegexFiltersExport));
        menuItem.addColoredGap();
        ActionBarMenuSubItem clearSub = menuItem.addSubItem(4, R.drawable.msg_clear, getString(R.string.ClearRegexFilters));
        int red = Theme.getColor(Theme.key_text_RedRegular);
        clearSub.setColors(red, red);
        clearSub.setSelectorColor(Theme.multAlpha(red, .12f));

        return v;
    }

    @Override
    protected void onActionBarItemClick(int id) {
        if (id == 1) {
            presentFragment(createSelectChatActivity());
        } else if (id == 2) {
            Context context = getContext();
            if (context != null) {
                showImportSourceChooser(context);
            }
        } else if (id == 3) {
            Context context = getContext();
            if (context != null) {
                showExportSourceChooser(context);
            }
        } else if (id == 4) {
            new AlertDialog.Builder(getContext(), getResourceProvider())
                    .setTitle(getString(R.string.ClearRegexFilters))
                    .setMessage(getString(R.string.ClearRegexFiltersAlertMessage))
                    .setNegativeButton(getString(R.string.Cancel), null)
                    .setPositiveButton(getString(R.string.Clear), (dialog, which) -> {
                        AGFilter.clearAllFilters();
                        refreshRows();
                    })
                    .makeRed(AlertDialog.BUTTON_POSITIVE)
                    .show();
        }
    }

    @Override
    protected void onCustomCellClick(View view, int position, float x, float y) {
        AbstractConfigCell row = cellGroup.rows.get(position);
        if (row == regexFiltersMaskMessagesRow) {
            TextCheckCell cell = (TextCheckCell) view;
            boolean enabled = !cell.isChecked();
            cell.setChecked(enabled);
            NaConfig.INSTANCE.getRegexFiltersMaskMessages().setConfigBool(enabled);
            AGFilter.invalidateFilteredCache();
        } else if (row == sharedFiltersPageRow) {
            presentFragment(new RegexSharedFiltersListActivity());
        } else if (row == userFiltersPageRow) {
            presentFragment(new ShadowBanListActivity());
        } else if (row instanceof DialogFilterCell dialogFilterCell) {
            presentFragment(new RegexChatFiltersListActivity(dialogFilterCell.item.dialogId));
        }
    }

    @NonNull
    private DialogsActivity createSelectChatActivity() {
        Bundle b = new Bundle();
        b.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_REGEX_FILTER);
        b.putBoolean("onlySelect", true);
        b.putBoolean("canSelectTopics", false);
        b.putBoolean("allowSwitchAccount", true);
        b.putBoolean("checkCanWrite", false);
        DialogsActivity dialogsActivity = new DialogsActivity(b);
        dialogsActivity.setDelegate((fragment, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
            if (dids != null && !dids.isEmpty()) {
                long dialogId = ((MessagesStorage.TopicKey) dids.get(0)).dialogId;
                presentFragment(new RegexChatFiltersListActivity(dialogId), true);
            }
            return true;
        });
        return dialogsActivity;
    }

    private int getShadowBanCount() {
        return AGFilter.getBlockedChannelsList().size() + AGFilter.getCustomFilteredUsersList().size();
    }

    private String buildExportJson() {
        TransferData data = new TransferData();
        data.version = 2;
        data.filters = new ArrayList<>();
        data.peers = new HashMap<>();
        MessagesController messagesController = getMessagesController();
        for (AGFilter.FilterModel model : AGFilter.getRegexFilters()) {
            if (model == null || model.regex == null) {
                continue;
            }
            data.filters.add(buildBackupFilter(model, null));
        }
        for (AGFilter.ChatFilterEntry entry : checkChatFilters(AGFilter.getChatFilterEntries())) {
            if (entry == null || entry.filters == null) {
                continue;
            }
            for (AGFilter.FilterModel model : entry.filters) {
                if (model == null || model.regex == null) {
                    continue;
                }
                data.filters.add(buildBackupFilter(model, entry.dialogId));
            }
            addPeerUsername(data.peers, entry.dialogId, messagesController);
        }
        data.exclusions = new ArrayList<>();
        for (AGFilter.ExcludedFilterEntry entry : AGFilter.getExcludedFilterEntries()) {
            if (entry == null || TextUtils.isEmpty(entry.filterId) || entry.dialogId == 0L) {
                continue;
            }
            BackupExclusion exclusion = new BackupExclusion();
            exclusion.dialogId = entry.dialogId;
            exclusion.filterId = entry.filterId;
            data.exclusions.add(exclusion);
            addPeerUsername(data.peers, entry.dialogId, messagesController);
        }
        data.customFilteredUsersData = AGFilter.getCustomFilteredUsersDataList();
        return new Gson().toJson(data);
    }

    private void showExportSourceChooser(Context context) {
        new AlertDialog.Builder(context, getResourceProvider())
                .setTitle(getString(R.string.FiltersExportTitle))
                .setItems(new CharSequence[]{
                        getString(R.string.FiltersExportClipboard),
                        getString(R.string.FiltersExportURL)
                }, (dialog, which) -> {
                    if (which == 0) {
                        exportToClipboard();
                    } else {
                        exportToUrl();
                    }
                })
                .show();
    }

    private void exportToClipboard() {
        try {
            AndroidUtilities.addToClipboard(buildExportJson());
            BulletinFactory.of(this).createCopyBulletin(getString(R.string.TextCopied)).show();
        } catch (Exception e) {
            FileLog.e(e);
            BulletinFactory.of(this).createSimpleBulletin(R.raw.error, getString(R.string.RegexFiltersExportError)).show();
        }
    }

    private void exportToUrl() {
        final String json;
        try {
            json = buildExportJson();
        } catch (Exception e) {
            FileLog.e(e);
            BulletinFactory.of(this).createSimpleBulletin(R.raw.error, getString(R.string.RegexFiltersExportError)).show();
            return;
        }
        Request request = new Request.Builder()
                .url("https://dpaste.com/api/v2/")
                .post(new MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("content", json)
                        .addFormDataPart("syntax", "json")
                        .addFormDataPart("title", "AuthorGram Filters")
                        .build())
                .build();
        HttpClient.INSTANCE.getInstance().newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                AndroidUtilities.runOnUIThread(() ->
                        BulletinFactory.of(AGFiltersSettingsActivity.this)
                                .createSimpleBulletin(R.raw.error, getString(R.string.FiltersToastFailPublish))
                                .show());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                String location;
                try (ResponseBody ignored = response.body()) {
                    location = response.header("Location");
                }
                final String exportUrl = TextUtils.isEmpty(location) ? null : (location.endsWith(".txt") ? location : location + ".txt");
                AndroidUtilities.runOnUIThread(() -> {
                    if (TextUtils.isEmpty(exportUrl)) {
                        BulletinFactory.of(AGFiltersSettingsActivity.this)
                                .createSimpleBulletin(R.raw.error, getString(R.string.FiltersToastFailPublish))
                                .show();
                        return;
                    }
                    AndroidUtilities.addToClipboard(exportUrl);
                    BulletinFactory.of(AGFiltersSettingsActivity.this).createCopyLinkBulletin().show();
                });
            }
        });
    }

    private ArrayList<AGFilter.ChatFilterEntry> checkChatFilters(ArrayList<AGFilter.ChatFilterEntry> chatEntries) {
        if (chatEntries == null || chatEntries.isEmpty()) return chatEntries;
        ArrayList<AGFilter.ChatFilterEntry> newEntries = new ArrayList<>();
        for (AGFilter.ChatFilterEntry entry : chatEntries) {
            if (entry == null) continue;
            if (entry.dialogId > 0) {
                TLRPC.User user = MessagesController.getInstance(UserConfig.selectedAccount).getUser(entry.dialogId);
                if (user != null) {
                    newEntries.add(entry);
                }
            } else {
                TLRPC.Chat chat = MessagesController.getInstance(UserConfig.selectedAccount).getChat(-entry.dialogId);
                if (chat != null) {
                    newEntries.add(entry);
                }
            }
        }
        return newEntries;
    }

    private boolean isKnownDialog(long dialogId) {
        if (dialogId > 0) {
            return MessagesController.getInstance(UserConfig.selectedAccount).getUser(dialogId) != null;
        }
        return MessagesController.getInstance(UserConfig.selectedAccount).getChat(-dialogId) != null;
    }

    private void resolveUnknownPeers(HashMap<Long, String> peers) {
        MessagesController messagesController = getMessagesController();
        for (var entry : peers.entrySet()) {
            Long dialogId = entry.getKey();
            String rawUsername = entry.getValue();
            if (dialogId == null || dialogId == 0L || TextUtils.isEmpty(rawUsername)) {
                continue;
            }
            if (isKnownDialog(dialogId)) {
                continue;
            }
            String username = rawUsername.startsWith("@") ? rawUsername.substring(1) : rawUsername;
            if (TextUtils.isEmpty(username)) {
                continue;
            }
            messagesController.getUserNameResolver().resolve(username, peerId -> {
                if (peerId != null && peerId != 0L) {
                    AndroidUtilities.runOnUIThread(this::refreshRows);
                }
            });
        }
    }

    // region Import pipeline

    /**
     * Parsed-but-not-yet-applied import payload. Holds only raw incoming data; merging
     * with current state happens later in {@link #applyImport(ParsedImport)}.
     */
    private static class ParsedImport {
        ArrayList<AGFilter.FilterModel> sharedIncoming;
        ArrayList<AGFilter.ChatFilterEntry> chatsIncoming;
        ArrayList<AGFilter.CustomFilteredUser> customFilteredUsersIncoming;
        ArrayList<AGFilter.ExcludedFilterEntry> exclusionsIncoming;
        HashMap<Long, String> peersIncoming;

        boolean hasAnyData() {
            return (sharedIncoming != null && !sharedIncoming.isEmpty())
                    || (chatsIncoming != null && !chatsIncoming.isEmpty())
                    || (customFilteredUsersIncoming != null && !customFilteredUsersIncoming.isEmpty())
                    || (exclusionsIncoming != null && !exclusionsIncoming.isEmpty());
        }
    }

    /** Parse a filter backup JSON (either legacy `[filters]` array or full {@link TransferData}). */
    private ParsedImport parseImportJson(String rawJson) {
        if (TextUtils.isEmpty(rawJson)) return null;
        String json = rawJson.trim();
        long selfUserId = getUserConfig().getClientUserId();
        ParsedImport out = new ParsedImport();
        try {
            if (json.startsWith("[")) {
                AGFilter.FilterModel[] arr = new Gson().fromJson(json, AGFilter.FilterModel[].class);
                if (arr != null) {
                    out.sharedIncoming = new ArrayList<>();
                    for (AGFilter.FilterModel m : arr) {
                        if (m == null || m.regex == null) continue;
                        m.migrateFromLegacy(0L);
                        out.sharedIncoming.add(m);
                    }
                }
            } else {
                TransferData data = new Gson().fromJson(json, TransferData.class);
                if (data != null) {
                    if (data.filters != null) {
                        out.sharedIncoming = new ArrayList<>();
                        out.chatsIncoming = new ArrayList<>();
                        HashMap<Long, AGFilter.ChatFilterEntry> chatMap = new HashMap<>();
                        for (BackupFilter bf : data.filters) {
                            if (bf == null || TextUtils.isEmpty(bf.text)) continue;
                            AGFilter.FilterModel m = new AGFilter.FilterModel();
                            m.id = bf.id;
                            m.regex = bf.text;
                            m.enabled = bf.enabled;
                            m.caseInsensitive = bf.caseInsensitive;
                            m.reversed = bf.reversed;
                            m.ensureId();
                            if (bf.dialogId == null) {
                                out.sharedIncoming.add(m);
                            } else {
                                long did = bf.dialogId;
                                AGFilter.ChatFilterEntry entry = chatMap.get(did);
                                if (entry == null) {
                                    entry = new AGFilter.ChatFilterEntry();
                                    entry.dialogId = did;
                                    entry.filters = new ArrayList<>();
                                    chatMap.put(did, entry);
                                    out.chatsIncoming.add(entry);
                                }
                                entry.filters.add(m);
                            }
                        }
                    }
                    if (data.exclusions != null) {
                        out.exclusionsIncoming = new ArrayList<>();
                        for (BackupExclusion be : data.exclusions) {
                            if (be == null || TextUtils.isEmpty(be.filterId)) continue;
                            AGFilter.ExcludedFilterEntry entry = new AGFilter.ExcludedFilterEntry();
                            entry.dialogId = be.dialogId;
                            entry.filterId = be.filterId;
                            out.exclusionsIncoming.add(entry);
                        }
                    }
                    if (data.peers != null && !data.peers.isEmpty()) {
                        out.peersIncoming = new HashMap<>(data.peers);
                    }
                    if (data.customFilteredUsersData != null) {
                        out.customFilteredUsersIncoming = new ArrayList<>();
                        for (AGFilter.CustomFilteredUser user : data.customFilteredUsersData) {
                            if (user == null || user.id <= 0L || user.id == selfUserId) {
                                continue;
                            }
                            AGFilter.CustomFilteredUser normalized = new AGFilter.CustomFilteredUser();
                            normalized.id = user.id;
                            normalized.accessHash = user.accessHash;
                            normalized.username = user.username;
                            if (!TextUtils.isEmpty(user.displayName)) {
                                String displayName = user.displayName.trim();
                                if (!TextUtils.isEmpty(displayName)) {
                                    normalized.displayName = displayName;
                                }
                            }
                            out.customFilteredUsersIncoming.add(normalized);
                        }
                    } else if (data.customFilteredUsers != null) {
                        out.customFilteredUsersIncoming = new ArrayList<>();
                        for (Long userId : data.customFilteredUsers) {
                            if (userId == null || userId <= 0L || userId == selfUserId) {
                                continue;
                            }
                            AGFilter.CustomFilteredUser normalized = new AGFilter.CustomFilteredUser();
                            normalized.id = userId;
                            out.customFilteredUsersIncoming.add(normalized);
                        }
                    }
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
        return out.hasAnyData() ? out : null;
    }

    /** Categorize parsed data into counts for the preview sheet.
     *  Truly identical filters (same regex, flags, and enabled state) are ignored. */
    private FiltersImportBottomSheet.Summary buildImportSummary(ParsedImport parsed) {
        FiltersImportBottomSheet.Summary s = new FiltersImportBottomSheet.Summary();
        if (parsed == null) return s;
        if (parsed.sharedIncoming != null && !parsed.sharedIncoming.isEmpty()) {
            java.util.concurrent.CopyOnWriteArrayList<AGFilter.FilterModel> currentShared = AGFilter.getRegexFilters();
            for (AGFilter.FilterModel in : parsed.sharedIncoming) {
                boolean exactDuplicate = false;
                boolean needsUpdate = false;
                for (AGFilter.FilterModel ex : currentShared) {
                    if (ex != null && ex.regex != null
                            && ex.regex.equals(in.regex)
                            && ex.caseInsensitive == in.caseInsensitive
                            && ex.reversed == in.reversed) {
                        if (ex.enabled == in.enabled) {
                            exactDuplicate = true;
                        } else {
                            needsUpdate = true;
                        }
                        break;
                    }
                }
                if (exactDuplicate) {
                    // skip — nothing to do
                } else if (needsUpdate) {
                    s.updatedFilters++;
                } else {
                    s.newFilters++;
                }
            }
        }
        if (parsed.chatsIncoming != null) {
            ArrayList<AGFilter.ChatFilterEntry> currentChats = checkChatFilters(AGFilter.getChatFilterEntries());
            for (AGFilter.ChatFilterEntry inEntry : parsed.chatsIncoming) {
                if (inEntry == null || inEntry.filters == null) continue;
                AGFilter.ChatFilterEntry existingEntry = null;
                for (AGFilter.ChatFilterEntry exEntry : currentChats) {
                    if (exEntry != null && exEntry.dialogId == inEntry.dialogId) {
                        existingEntry = exEntry;
                        break;
                    }
                }
                for (AGFilter.FilterModel in : inEntry.filters) {
                    boolean exactDuplicate = false;
                    boolean needsUpdate = false;
                    if (existingEntry != null && existingEntry.filters != null) {
                        for (AGFilter.FilterModel ex : existingEntry.filters) {
                            if (ex != null && ex.regex != null
                                    && ex.regex.equals(in.regex)
                                    && ex.caseInsensitive == in.caseInsensitive
                                    && ex.reversed == in.reversed) {
                                if (ex.enabled == in.enabled) {
                                    exactDuplicate = true;
                                } else {
                                    needsUpdate = true;
                                }
                                break;
                            }
                        }
                    }
                    if (exactDuplicate) {
                        // skip
                    } else if (needsUpdate) {
                        s.updatedFilters++;
                    } else {
                        s.newChatFilters++;
                    }
                }
            }
        }
        if (parsed.exclusionsIncoming != null) {
            ArrayList<AGFilter.ExcludedFilterEntry> currentExclusions = AGFilter.getExcludedFilterEntries();
            for (AGFilter.ExcludedFilterEntry in : parsed.exclusionsIncoming) {
                boolean exists = false;
                for (AGFilter.ExcludedFilterEntry ex : currentExclusions) {
                    if (ex != null && ex.dialogId == in.dialogId
                            && ex.filterId != null && ex.filterId.equals(in.filterId)) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) s.newExclusions++;
            }
        }
        if (parsed.customFilteredUsersIncoming != null) {
            long selfUserId = getUserConfig().getClientUserId();
            HashMap<Long, AGFilter.CustomFilteredUser> existing = new HashMap<>();
            for (AGFilter.CustomFilteredUser u : AGFilter.getCustomFilteredUsersDataList()) {
                if (u != null && u.id > 0L) existing.put(u.id, u);
            }
            for (AGFilter.CustomFilteredUser in : parsed.customFilteredUsersIncoming) {
                if (in == null || in.id <= 0L || in.id == selfUserId) continue;
                AGFilter.CustomFilteredUser ex = existing.get(in.id);
                if (ex == null) {
                    s.newShadowBans++;
                } else if (!TextUtils.equals(ex.username, in.username)
                        || !TextUtils.equals(ex.displayName, in.displayName)
                        || ex.accessHash != in.accessHash) {
                    s.updatedFilters++; // treat as an update for display
                }
            }
        }
        if (parsed.peersIncoming != null) {
            for (var entry : parsed.peersIncoming.entrySet()) {
                Long did = entry.getKey();
                if (did == null || did == 0L) continue;
                if (!isKnownDialog(did)) s.peersToResolve++;
            }
        }
        return s;
    }

    /** Merge parsed data into local filter state and persist. Mirrors the legacy inline flow. */
    private void applyImport(ParsedImport parsed) {
        if (parsed == null) return;
        long selfUserId = getUserConfig().getClientUserId();
        if (parsed.sharedIncoming != null && !parsed.sharedIncoming.isEmpty()) {
            java.util.concurrent.CopyOnWriteArrayList<AGFilter.FilterModel> currentShared = AGFilter.getRegexFilters();
            for (AGFilter.FilterModel in : parsed.sharedIncoming) {
                boolean found = false;
                for (int i = 0; i < currentShared.size(); i++) {
                    AGFilter.FilterModel ex = currentShared.get(i);
                    if (ex != null && ex.regex != null && ex.regex.equals(in.regex) && ex.caseInsensitive == in.caseInsensitive && ex.reversed == in.reversed) {
                        ex.enabled = in.enabled;
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    currentShared.add(in);
                }
            }
            AGFilter.saveFilter(currentShared);
        }
        if (parsed.chatsIncoming != null && !parsed.chatsIncoming.isEmpty()) {
            ArrayList<AGFilter.ChatFilterEntry> currentChats = checkChatFilters(AGFilter.getChatFilterEntries());
            for (AGFilter.ChatFilterEntry inEntry : parsed.chatsIncoming) {
                if (inEntry == null) continue;
                AGFilter.ChatFilterEntry target = null;
                for (AGFilter.ChatFilterEntry exEntry : currentChats) {
                    if (exEntry != null && exEntry.dialogId == inEntry.dialogId) {
                        target = exEntry;
                        break;
                    }
                }
                if (target == null) {
                    AGFilter.ChatFilterEntry newEntry = new AGFilter.ChatFilterEntry();
                    newEntry.dialogId = inEntry.dialogId;
                    newEntry.filters = new ArrayList<>();
                    currentChats.add(newEntry);
                    target = newEntry;
                }
                if (inEntry.filters != null) {
                    for (AGFilter.FilterModel in : inEntry.filters) {
                        boolean found = false;
                        if (target.filters == null) target.filters = new ArrayList<>();
                        for (int i = 0; i < target.filters.size(); i++) {
                            AGFilter.FilterModel ex = target.filters.get(i);
                            if (ex != null && ex.regex != null && ex.regex.equals(in.regex) && ex.caseInsensitive == in.caseInsensitive && ex.reversed == in.reversed) {
                                ex.enabled = in.enabled;
                                found = true;
                                break;
                            }
                        }
                        if (!found) {
                            target.filters.add(in);
                        }
                    }
                }
            }
            AGFilter.saveChatFilterEntries(currentChats);
        }
        if (parsed.customFilteredUsersIncoming != null && !parsed.customFilteredUsersIncoming.isEmpty()) {
            HashMap<Long, AGFilter.CustomFilteredUser> merged = new HashMap<>();
            for (AGFilter.CustomFilteredUser existing : AGFilter.getCustomFilteredUsersDataList()) {
                if (existing == null || existing.id <= 0L || existing.id == selfUserId) {
                    continue;
                }
                merged.put(existing.id, existing);
            }
            for (AGFilter.CustomFilteredUser incoming : parsed.customFilteredUsersIncoming) {
                if (incoming == null || incoming.id <= 0L || incoming.id == selfUserId) {
                    continue;
                }
                AGFilter.CustomFilteredUser target = merged.get(incoming.id);
                if (target == null) {
                    target = new AGFilter.CustomFilteredUser();
                    target.id = incoming.id;
                }
                if (incoming.accessHash != 0L) {
                    target.accessHash = incoming.accessHash;
                }
                String username = incoming.username;
                if (!TextUtils.isEmpty(username)) {
                    target.username = username;
                }
                if (!TextUtils.isEmpty(incoming.displayName)) {
                    String displayName = incoming.displayName.trim();
                    if (!TextUtils.isEmpty(displayName)) {
                        target.displayName = displayName;
                    }
                }
                merged.put(target.id, target);
            }
            AGFilter.setCustomFilteredUsersData(new ArrayList<>(merged.values()));
        }
        if (parsed.exclusionsIncoming != null && !parsed.exclusionsIncoming.isEmpty()) {
            for (AGFilter.ExcludedFilterEntry entry : parsed.exclusionsIncoming) {
                if (entry == null || TextUtils.isEmpty(entry.filterId) || entry.dialogId == 0L) {
                    continue;
                }
                AGFilter.setSharedFilterExcluded(entry.dialogId, entry.filterId, true);
            }
        }
        if (parsed.peersIncoming != null && !parsed.peersIncoming.isEmpty()) {
            resolveUnknownPeers(parsed.peersIncoming);
        }
    }

    /** Show preview sheet with counts; on confirm, apply the import and surface a success bulletin. */
    private void showImportPreview(ParsedImport parsed) {
        if (parsed == null || !parsed.hasAnyData()) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.error, getString(R.string.RegexFiltersImportNoChanges)).show();
            return;
        }
        FiltersImportBottomSheet.Summary summary = buildImportSummary(parsed);
        if (summary.isEmpty()) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.error, getString(R.string.RegexFiltersImportNoChanges)).show();
            return;
        }
        new FiltersImportBottomSheet(this, summary, () -> {
            try {
                applyImport(parsed);
                refreshRows();
                BulletinFactory.of(this).createSimpleBulletin(R.raw.done, getString(R.string.RegexFiltersImportSuccess)).show();
            } catch (Exception e) {
                FileLog.e(e);
                BulletinFactory.of(this).createSimpleBulletin(R.raw.error, getString(R.string.RegexFiltersImportError)).show();
            }
        }).show();
    }

    /** Entry point for the Import menu action: lets the user pick clipboard or URL as the source. */
    private void showImportSourceChooser(Context context) {
        new AlertDialog.Builder(context, getResourceProvider())
                .setTitle(getString(R.string.RegexFiltersImportSourceTitle))
                .setItems(new CharSequence[]{
                        getString(R.string.RegexFiltersImportFromClipboard),
                        getString(R.string.RegexFiltersImportFromURL)
                }, (dialog, which) -> {
                    if (which == 0) {
                        importFromClipboard(context);
                    } else {
                        importFromUrl(context);
                    }
                })
                .show();
    }

    private void importFromClipboard(Context context) {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        CharSequence text = null;
        if (clipboard != null && clipboard.hasPrimaryClip() && clipboard.getPrimaryClip() != null && clipboard.getPrimaryClip().getItemCount() > 0) {
            text = clipboard.getPrimaryClip().getItemAt(0).coerceToText(context);
        }
        if (text == null || TextUtils.isEmpty(text.toString().trim())) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.error, getString(R.string.RegexFiltersImportError)).show();
            return;
        }
        ParsedImport parsed = parseImportJson(text.toString());
        if (parsed == null) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.error, getString(R.string.RegexFiltersImportError)).show();
            return;
        }
        showImportPreview(parsed);
    }

    private void importFromUrl(Context context) {
        FrameLayout container = new FrameLayout(context);
        EditText editText = new EditText(context);
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        editText.setTextSize(16);
        editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, getResourceProvider()));
        editText.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint, getResourceProvider()));
        editText.setHint(getString(R.string.RegexFiltersImportUrlHint));
        editText.setSingleLine(true);
        editText.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        CharSequence clipboardText = null;
        if (clipboard != null && clipboard.hasPrimaryClip() && clipboard.getPrimaryClip() != null && clipboard.getPrimaryClip().getItemCount() > 0) {
            clipboardText = clipboard.getPrimaryClip().getItemAt(0).coerceToText(context);
        }
        String initialLink = clipboardText != null ? clipboardText.toString().trim() : "";
        String lastLink = NaConfig.INSTANCE.getRegexFiltersLastImportLink().String();
        if (!initialLink.contains(".txt") && !initialLink.contains("github") && !initialLink.contains("bin") && !initialLink.contains("paste")) {
            initialLink = lastLink;
        }
        if (!TextUtils.isEmpty(initialLink)) {
            editText.setText(initialLink);
            editText.setSelection(editText.getText().length());
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = AndroidUtilities.dp(24);
        lp.rightMargin = AndroidUtilities.dp(24);
        lp.topMargin = AndroidUtilities.dp(6);
        container.addView(editText, lp);

        AlertDialog alert = new AlertDialog.Builder(context, getResourceProvider())
                .setTitle(getString(R.string.RegexFiltersImportUrlTitle))
                .setView(container)
                .setNegativeButton(getString(R.string.Cancel), null)
                .setPositiveButton(getString(R.string.RegexFiltersImportUrlAction), (dialog, which) -> {
                    String url = editText.getText() != null ? editText.getText().toString().trim() : "";
                    if (TextUtils.isEmpty(url)) {
                        BulletinFactory.of(this).createSimpleBulletin(R.raw.error, getString(R.string.RegexFiltersImportUrlFetchFailed)).show();
                        return;
                    }
                    fetchImportFromUrl(url);
                })
                .create();
        alert.show();
        editText.requestFocus();
        AndroidUtilities.showKeyboard(editText);
    }

    private void fetchImportFromUrl(String url) {
        final AlertDialog progress = new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER, getResourceProvider());
        progress.setCanCancel(false);
        progress.show();
        Request request;
        try {
            request = new Request.Builder().url(url).get().build();
        } catch (IllegalArgumentException e) {
            progress.dismiss();
            BulletinFactory.of(this).createSimpleBulletin(R.raw.error, getString(R.string.RegexFiltersImportUrlFetchFailed)).show();
            return;
        }
        HttpClient.INSTANCE.getInstance().newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                AndroidUtilities.runOnUIThread(() -> {
                    progress.dismiss();
                    BulletinFactory.of(AGFiltersSettingsActivity.this)
                            .createSimpleBulletin(R.raw.error, getString(R.string.RegexFiltersImportUrlFetchFailed))
                            .show();
                });
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                String body = null;
                try (ResponseBody rb = response.body()) {
                    if (response.isSuccessful() && rb != null) {
                        body = rb.string();
                    }
                } catch (IOException ignored) {
                }
                final String finalBody = body;
                AndroidUtilities.runOnUIThread(() -> {
                    progress.dismiss();
                    if (TextUtils.isEmpty(finalBody)) {
                        BulletinFactory.of(AGFiltersSettingsActivity.this)
                                .createSimpleBulletin(R.raw.error, getString(R.string.RegexFiltersImportUrlFetchFailed))
                                .show();
                        return;
                    }
                    ParsedImport parsed = parseImportJson(finalBody);
                    if (parsed == null) {
                        BulletinFactory.of(AGFiltersSettingsActivity.this)
                                .createSimpleBulletin(R.raw.error, getString(R.string.RegexFiltersImportError))
                                .show();
                        return;
                    }
                    NaConfig.INSTANCE.getRegexFiltersLastImportLink().setConfigString(url);
                    showImportPreview(parsed);
                });
            }
        });
    }

    // endregion Import pipeline

    private ArrayList<DialogFilterItem> getDialogFilterItems() {
        HashMap<Long, DialogFilterItem> map = new HashMap<>();
        ArrayList<AGFilter.ChatFilterEntry> chatEntries = checkChatFilters(AGFilter.getChatFilterEntries());
        if (chatEntries != null) {
            for (AGFilter.ChatFilterEntry entry : chatEntries) {
                if (entry == null || !isKnownDialog(entry.dialogId)) {
                    continue;
                }
                DialogFilterItem item = map.computeIfAbsent(entry.dialogId, did -> {
                    DialogFilterItem newItem = new DialogFilterItem();
                    newItem.dialogId = did;
                    return newItem;
                });
                item.chatFiltersCount = entry.filters != null ? entry.filters.size() : 0;
            }
        }
        for (AGFilter.ExcludedFilterEntry entry : AGFilter.getExcludedFilterEntries()) {
            if (entry == null || !isKnownDialog(entry.dialogId)) {
                continue;
            }
            DialogFilterItem item = map.computeIfAbsent(entry.dialogId, did -> {
                DialogFilterItem newItem = new DialogFilterItem();
                newItem.dialogId = did;
                return newItem;
            });
            item.excludedSharedCount++;
        }
        ArrayList<DialogFilterItem> items = new ArrayList<>(map.values());
        items.sort((o1, o2) -> Long.compare(o1.dialogId, o2.dialogId));
        return items;
    }

    @SuppressLint("NotifyDataSetChanged")
    private void refreshRows() {
        rebuildRows();
        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
    }

    @Override
    protected boolean onItemLongClick(View view, int position, float x, float y) {
        return super.onItemLongClick(view, position, x, y);
    }

    private void setupLongClickListener() {
        listView.setOnItemLongClickListener((view, position, x, y) -> {
            if (isDialogFilterRow(position)) {
                return false;
            }
            if (onItemLongClick(view, position, x, y)) {
                return true;
            }
            if (position < 0 || position >= cellGroup.rows.size()) {
                return false;
            }
            if (cellGroup.rows.get(position) instanceof ConfigCellCheckBox) {
                return true;
            }
            String prefix = getSettingsPrefix();
            if (prefix == null || listAdapter == null) {
                return false;
            }
            RecyclerView.ViewHolder holder = listView.findViewHolderForAdapterPosition(position);
            if (holder != null && listAdapter.isEnabled(holder)) {
                showDefaultLongClickOptions(view, prefix, position);
                return true;
            }
            return false;
        });
    }

    private boolean isDialogFilterRow(int position) {
        return position >= 0 && position < cellGroup.rows.size() && cellGroup.rows.get(position) instanceof DialogFilterCell;
    }

    @Override
    public String getTitle() {
        return getString(R.string.RegexFilters);
    }

    @Override
    protected String getSettingsPrefix() {
        return "regexfilters";
    }

    @Override
    protected void styleTextInfoPrivacyCell(TextInfoPrivacyCell cell) {
        cell.setBackground(Theme.getThemedDrawable(cell.getContext(), R.drawable.greydivider, Theme.key_windowBackgroundGrayShadow));
    }

    private static BackupFilter buildBackupFilter(AGFilter.FilterModel model, Long dialogId) {
        BackupFilter bf = new BackupFilter();
        bf.id = model.id;
        bf.text = model.regex;
        bf.dialogId = dialogId;
        bf.enabled = model.enabled;
        bf.caseInsensitive = model.caseInsensitive;
        bf.reversed = model.reversed;
        return bf;
    }

    private static void addPeerUsername(HashMap<Long, String> peers, long dialogId, MessagesController messagesController) {
        if (peers == null || dialogId == 0L || peers.containsKey(dialogId)) return;
        TLObject peer = dialogId > 0
                ? messagesController.getUser(dialogId)
                : messagesController.getChat(-dialogId);
        if (peer == null) return;
        String username = DialogObject.getPublicUsername(peer);
        if (!TextUtils.isEmpty(username)) {
            peers.put(dialogId, "@" + username);
        }
    }

    private static class TransferData {
        public Integer version;
        public ArrayList<BackupFilter> filters;
        public ArrayList<BackupExclusion> exclusions;
        public HashMap<Long, String> peers;
        public ArrayList<AGFilter.CustomFilteredUser> customFilteredUsersData;
        // Kept for backward compatibility when importing older NagramXF exports
        public ArrayList<Long> customFilteredUsers;
    }

    private static class BackupFilter {
        public String id;
        public String text;
        public Long dialogId;
        public boolean enabled = true;
        public boolean caseInsensitive;
        public boolean reversed;
    }

    private static class BackupExclusion {
        public long dialogId;
        public String filterId;
    }

    private class ListAdapter extends BaseListAdapter {

        public ListAdapter(Context context) {
            super(context);
        }

        @Override
        protected View onCreateCustomViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == VIEW_TYPE_CHAT_FILTER) {
                FiltersChatCell chatCell = new FiltersChatCell(mContext);
                chatCell.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
                chatCell.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                return chatCell;
            }
            return null;
        }

        @Override
        protected void onBindCustomViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            AbstractConfigCell row = cellGroup.rows.get(position);
            if (row == regexFiltersMaskMessagesRow) {
                TextCheckCell textCheckCell = (TextCheckCell) holder.itemView;
                textCheckCell.setTextAndCheck(getString(R.string.RegexFiltersMaskMessagesShort), NaConfig.INSTANCE.getRegexFiltersMaskMessages().Bool(), false);
            } else if (row == regexFiltersMaskMessagesInfoRow) {
                TextInfoPrivacyCell infoCell = (TextInfoPrivacyCell) holder.itemView;
                infoCell.setBackground(Theme.getThemedDrawable(mContext, R.drawable.greydivider, Theme.key_windowBackgroundGrayShadow));
                infoCell.setText(getString(R.string.RegexFiltersMaskMessagesAbout));
            } else if (row == sharedFiltersPageRow) {
                TextSettingsCell settingsCell = (TextSettingsCell) holder.itemView;
                settingsCell.setTextAndValue(getString(R.string.RegexFiltersSharedHeader), String.valueOf(AGFilter.getRegexFilters().size()), true);
            } else if (row == userFiltersPageRow) {
                TextSettingsCell settingsCell = (TextSettingsCell) holder.itemView;
                settingsCell.setTextAndValue(getString(R.string.ShadowBan), String.valueOf(getShadowBanCount()), false);
            } else if (row instanceof DialogFilterCell dialogFilterCell) {
                DialogFilterItem entry = dialogFilterCell.item;
                FiltersChatCell chatCell = (FiltersChatCell) holder.itemView;
                chatCell.setDialog(entry.dialogId, entry.chatFiltersCount, entry.excludedSharedCount);
            }
        }
    }
}
