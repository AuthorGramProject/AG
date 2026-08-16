package toss.authorgram.settings;

import static android.view.View.OVER_SCROLL_NEVER;
import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.net.Uri;
import android.os.Build;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.BasePermissionsActivity;
import org.telegram.ui.Cells.SettingsSearchCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.DocumentSelectActivity;
import org.telegram.ui.FiltersSetupActivity;
import org.telegram.ui.LaunchActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.UUID;

import tw.nekomimi.nekogram.NekoConfig;
import tw.nekomimi.nekogram.helpers.AppRestartHelper;
import tw.nekomimi.nekogram.helpers.CloudSettingsHelper;
import tw.nekomimi.nekogram.helpers.PasscodeHelper;
import tw.nekomimi.nekogram.helpers.SettingsBackupHelper;
import tw.nekomimi.nekogram.utils.AlertUtil;

public class AGSettingsActivity extends BaseAGSettingsActivity {

    private static final int MENU_SEARCH = 1;
    private static final int MENU_OVERFLOW = 2;
    private static final int MENU_IMPORT = 3;
    private static final int MENU_EXPORT = 4;
    private static final int MENU_RESET = 5;
    private static final String PLAY_PACKAGE = "toss.authorgram.apk";

    private ActionBarMenuItem overflowItem;

    private int generalRow;
    private int appearanceRow;
    private int spyRow;
    private int translatorRow;
    private int chatRow;
    private int cameraRow;
    private int localFoldersRow; // AUTHORGRAM_LOCAL_FOLDERS_ROW
    private int passcodeRow;
    private int experimentRow;
    private int categoriesEndRow;

    private int importSettingsRow;
    private int exportSettingsRow;
    private int resetSettingsRow;
    private int appRestartRow;
    private int agSettingsEndRow;


    private int aboutRow;

    @Override
    protected void updateRows() {
        super.updateRows();

        generalRow = addRow();
        appearanceRow = addRow();
        if (isPrivateMainBuild()) {
            spyRow = addRow();
        } else {
            spyRow = -1;
        }
        translatorRow = addRow();
        chatRow = addRow();
        cameraRow = addRow();
        localFoldersRow = addRow();
        if (!PasscodeHelper.isSettingsHidden()) {
            passcodeRow = addRow();
        } else {
            passcodeRow = -1;
        }
        experimentRow = addRow();
        categoriesEndRow = addRow();

        importSettingsRow = addRow();
        exportSettingsRow = addRow();
        resetSettingsRow = addRow();
        appRestartRow = addRow();
        agSettingsEndRow = addRow();

        aboutRow = addRow();
    }

    @Override
    public View createView(Context context) {
        View view = super.createView(context);

        ActionBarMenu menu = actionBar.createMenu();
        overflowItem = menu.addItem(MENU_OVERFLOW, R.drawable.ic_ab_other, resourcesProvider);
        overflowItem.setContentDescription(getString(R.string.AccDescrMoreOptions));
        overflowItem.addSubItem(MENU_SEARCH, R.drawable.ic_ab_search, getString(R.string.Search));
        overflowItem.addColoredGap();
        overflowItem.addSubItem(MENU_IMPORT, R.drawable.import_solar, getString(R.string.ImportSettings));
        overflowItem.addSubItem(MENU_EXPORT, R.drawable.export_solar, getString(R.string.BackupSettings));
        overflowItem.addColoredGap();
        ActionBarMenuSubItem resetSub = overflowItem.addSubItem(MENU_RESET, R.drawable.msg_reset, getString(R.string.ResetSettings));
        int red = Theme.getColor(Theme.key_text_RedRegular, resourcesProvider);
        resetSub.setColors(red, red);
        resetSub.setSelectorColor(Theme.multAlpha(red, .12f));
        overflowItem.setOnClickListener(v -> showOverflowMenu());
        overflowItem.setLongClickEnabled(false);

        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_SEARCH) {
                    showSettingsSearchDialog();
                } else if (id == MENU_IMPORT) {
                    if (Build.VERSION.SDK_INT >= 33) {
                        openFilePicker();
                    } else {
                        DocumentSelectActivity activity = getDocumentSelectActivity(getParentActivity());
                        if (activity != null) {
                            presentFragment(activity);
                        }
                    }
                } else if (id == MENU_EXPORT) {
                    SettingsBackupHelper.backupSettings(getParentActivity(), resourcesProvider);
                } else if (id == MENU_RESET) {
                    showResetConfirmation();
                }
            }
        });

        return view;
    }

    /**
     * @noinspection SizeReplaceableByIsEmpty
     */
    private void showSettingsSearchDialog() {
        try {
            Activity parent = getParentActivity();
            if (parent == null) return;

            ArrayList<AGSettingsSearchResult> results = AGSettingsRouter.onCreateSearchArray(fragment -> AndroidUtilities.runOnUIThread(() -> {
                try {
                    presentFragment(fragment);
                } catch (Exception ignore) {
                }
            }));

            final ArrayList<AGSettingsSearchResult> filtered = new ArrayList<>(results);
            final String[] currentQuery = new String[]{""};
            final int searchHeight = dp(36);
            final int clearSize = dp(36);
            final int pad = dp(12);

            LinearLayout containerLayout = new LinearLayout(parent);
            containerLayout.setOrientation(LinearLayout.VERTICAL);
            containerLayout.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));

            FrameLayout searchFrame = new FrameLayout(parent);
            LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, searchHeight + dp(12));
            layoutParams.leftMargin = dp(10);
            layoutParams.rightMargin = dp(10);
            layoutParams.topMargin = dp(6);
            layoutParams.bottomMargin = dp(2);
            searchFrame.setLayoutParams(layoutParams);
            searchFrame.setClipToPadding(true);
            searchFrame.setClipChildren(true);

            ImageView searchIcon = new ImageView(parent);
            searchIcon.setScaleType(ImageView.ScaleType.CENTER);
            searchIcon.setImageResource(R.drawable.ic_ab_search);
            searchIcon.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_windowBackgroundWhiteGrayIcon), PorterDuff.Mode.MULTIPLY));
            searchFrame.addView(searchIcon, LayoutHelper.createFrame(48, 48, Gravity.LEFT | Gravity.CENTER_VERTICAL));

            EditTextBoldCursor searchField = new EditTextBoldCursor(parent);
            searchField.setHint(getString(R.string.Search));
            searchField.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
            searchField.setHintTextColor(getThemedColor(Theme.key_windowBackgroundWhiteHintText));
            searchField.setSingleLine(true);
            searchField.setBackground(null);
            searchField.setInputType(InputType.TYPE_CLASS_TEXT);
            searchField.setLineColors(getThemedColor(Theme.key_windowBackgroundWhiteInputField), getThemedColor(Theme.key_windowBackgroundWhiteInputFieldActivated), getThemedColor(Theme.key_text_RedRegular));
            searchField.setPadding(dp(61), pad / 2, dp(48), pad / 2);
            searchField.setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER_VERTICAL));
            searchFrame.addView(searchField);

            ImageView clearButton = new ImageView(parent);
            clearButton.setScaleType(ImageView.ScaleType.CENTER);
            clearButton.setImageResource(R.drawable.ic_close_white);
            clearButton.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_actionBarWhiteSelector), Theme.RIPPLE_MASK_CIRCLE_20DP));
            clearButton.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_windowBackgroundWhiteGrayIcon), PorterDuff.Mode.MULTIPLY));
            clearButton.setLayoutParams(new FrameLayout.LayoutParams(clearSize, clearSize, Gravity.END | Gravity.CENTER_VERTICAL));
            searchFrame.addView(clearButton);
            containerLayout.addView(searchFrame);

            AlertDialog.Builder builder = new AlertDialog.Builder(parent, resourceProvider);
            builder.setView(containerLayout);
            builder.setNegativeButton(getString(R.string.Close), null);
            final AlertDialog dialog = builder.create();
            dialog.setOnShowListener(d -> {
                try {
                    searchField.requestFocus();
                    AndroidUtilities.showKeyboard(searchField);
                } catch (Exception ignore) {
                }
            });

            RecyclerListView searchListView = new RecyclerListView(parent);
            searchListView.setOverScrollMode(OVER_SCROLL_NEVER);
            searchListView.setLayoutManager(new LinearLayoutManager(parent, LinearLayoutManager.VERTICAL, false));

            var adapter = new RecyclerListView.SelectionAdapter() {
                @Override
                public boolean isEnabled(RecyclerView.ViewHolder holder) {
                    return true;
                }

                @NonNull
                @Override
                public RecyclerListView.Holder onCreateViewHolder(@NonNull ViewGroup parent1, int viewType) {
                    View view = new SettingsSearchCell(parent);
                    view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
                    return new RecyclerListView.Holder(view);
                }

                @Override
                public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
                    SettingsSearchCell cell = (SettingsSearchCell) holder.itemView;
                    AGSettingsSearchResult r = filtered.get(position);
                    String[] path = r.path2 != null ? new String[]{r.path1, r.path2} : new String[]{r.path1};
                    CharSequence titleToSet = r.searchTitle == null ? "" : r.searchTitle;
                    String q = currentQuery[0];
                    if (q != null && !q.isEmpty() && titleToSet.length() > 0) {
                        SpannableStringBuilder ss = new SpannableStringBuilder(titleToSet);
                        String lower = titleToSet.toString().toLowerCase();
                        String[] parts = q.split("\\s+");
                        int highlightColor = getThemedColor(Theme.key_windowBackgroundWhiteBlueText4);
                        for (String p : parts) {
                            if (p.isEmpty()) continue;
                            int idx = 0;
                            while (true) {
                                int found = lower.indexOf(p, idx);
                                if (found == -1) break;
                                try {
                                    ss.setSpan(new ForegroundColorSpan(highlightColor), found, found + p.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                                } catch (Exception ignore) {
                                }
                                idx = found + p.length();
                            }
                        }
                        titleToSet = ss;
                    }
                    cell.setTextAndValueAndIcon(titleToSet, path, r.iconResId, position < filtered.size() - 1);
                }

                @Override
                public int getItemCount() {
                    return filtered.size();
                }
            };

            searchListView.setAdapter(adapter);
            searchListView.setOnItemClickListener((v, position) -> {
                if (position < 0 || position >= filtered.size()) return;
                AGSettingsSearchResult r = filtered.get(position);
                try {
                    if (r.openRunnable != null) r.openRunnable.run();
                } catch (Exception ignore) {
                }
                dialog.dismiss();
            });

            containerLayout.addView(searchListView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));

            searchField.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                }

                @SuppressLint("NotifyDataSetChanged")
                @Override
                public void afterTextChanged(Editable s) {
                    String q = s.toString().toLowerCase().trim();
                    currentQuery[0] = q;
                    filtered.clear();
                    if (q.isEmpty()) {
                        filtered.addAll(results);
                    } else {
                        String[] parts = q.split("\\s+");
                        for (AGSettingsSearchResult item : results) {
                            String title = item.searchTitle == null ? "" : item.searchTitle.toLowerCase();
                            boolean ok = true;
                            for (String p : parts) {
                                if (!title.contains(p)) {
                                    ok = false;
                                    break;
                                }
                            }
                            if (ok) filtered.add(item);
                        }
                    }
                    adapter.notifyDataSetChanged();
                    searchIcon.setVisibility(q.length() > 20 ? View.GONE : View.VISIBLE);
                    clearButton.setVisibility(q.isEmpty() ? View.GONE : View.VISIBLE);
                }
            });

            clearButton.setOnClickListener(v -> {
                searchField.setText("");
                searchField.requestFocus();
                AndroidUtilities.showKeyboard(searchField);
            });
            clearButton.setVisibility(View.GONE);

            showDialog(dialog);
        } catch (Exception ignore) {
        }
    }

    private boolean isPrivateMainBuild() {
        return ApplicationLoader.applicationContext == null
                || !PLAY_PACKAGE.equals(ApplicationLoader.applicationContext.getPackageName());
    }

    private void showOverflowMenu() {
        if (overflowItem == null || getContext() == null) {
            return;
        }
        ActionBarMenuSubItem restartItem = new ActionBarMenuSubItem(getContext(), false, true, true, resourcesProvider);
        restartItem.setTextAndIcon(getString(R.string.RestartApp), R.drawable.msg_retry);
        restartItem.setMinimumWidth(AndroidUtilities.dp(196));
        restartItem.setOnClickListener(view -> {
            overflowItem.closeSubMenu();
            AppRestartHelper.triggerRebirth(getParentActivity(), new Intent(getParentActivity(), LaunchActivity.class));
        });
        overflowItem.toggleSubMenu(restartItem, overflowItem);
    }

    private void showResetConfirmation() {
        AlertUtil.showConfirm(getParentActivity(),
                getString(R.string.ResetSettingsAlert),
                R.drawable.msg_reset,
                getString(R.string.Reset),
                true,
                () -> {
                    ApplicationLoader.applicationContext.getSharedPreferences("nekocloud", Activity.MODE_PRIVATE).edit().clear().commit();
                    ApplicationLoader.applicationContext.getSharedPreferences("nekox_config", Activity.MODE_PRIVATE).edit().clear().commit();
                    ApplicationLoader.applicationContext.getSharedPreferences("aichatconfig", Activity.MODE_PRIVATE).edit().clear().commit();
                    ApplicationLoader.applicationContext.getSharedPreferences("pillstackconfig", Activity.MODE_PRIVATE).edit().clear().commit();
                    NekoConfig.getPreferences().edit().clear().commit();
                    AppRestartHelper.triggerRebirth(getParentActivity(), new Intent(getParentActivity(), LaunchActivity.class));
                });
    }

    @Override
    protected String getActionBarTitle() {
        return getString(R.string.AGSettings);
    }

    @SuppressLint("ApplySharedPref")
    @Override
    protected void onItemClick(View view, int position, float x, float y) {
        if (position == chatRow) {
            presentFragment(new AGChatSettingsActivity());
        } else if (position == cameraRow) {
            presentFragment(new AGCameraSettingsActivity());
        } else if (position == localFoldersRow) {
            presentFragment(new FiltersSetupActivity());
        } else if (position == generalRow) {
            presentFragment(new AGGeneralSettingsActivity());
        } else if (position == appearanceRow) {
            presentFragment(new AGAppearanceSettingsActivity());
        } else if (position == spyRow) {
            presentFragment(new AGSpySettingsActivity());
        } else if (position == passcodeRow) {
            presentFragment(new AGPasscodeSettingsActivity());
        } else if (position == experimentRow) {
            presentFragment(new AGExperimentalSettingsActivity());
        } else if (position == translatorRow) {
            presentFragment(new AGTranslatorSettingsActivity());
        } else if (position == aboutRow) {
            presentFragment(new AGAboutActivity());
        } else if (position == importSettingsRow) {
            if (Build.VERSION.SDK_INT >= 33) {
                openFilePicker();
            } else {
                DocumentSelectActivity activity = getDocumentSelectActivity(getParentActivity());
                if (activity != null) {
                    presentFragment(activity);
                }
            }
        } else if (position == resetSettingsRow) {
            showResetConfirmation();
        } else if (position == exportSettingsRow) {
            SettingsBackupHelper.backupSettings(getParentActivity(), resourceProvider);
        } else if (position == appRestartRow) {
            AppRestartHelper.triggerRebirth(getParentActivity(), new Intent(getParentActivity(), LaunchActivity.class));
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
            switch (viewType) {
                case TYPE_SHADOW: {
                    holder.itemView.setBackground(Theme.getThemedDrawable(mContext, R.drawable.greydivider, Theme.key_windowBackgroundGrayShadow));
                    break;
                }
                case TYPE_TEXT: {
                    TextCell textCell = (TextCell) holder.itemView;
                    if (position == chatRow) {
                        textCell.setTextAndIcon(getString(R.string.Chat), R.drawable.msg_discussion, true);
                    } else if (position == cameraRow) {
                        textCell.setTextAndIcon(getString(R.string.AGCameraSettings), R.drawable.msg_camera, true);
                    } else if (position == localFoldersRow) {
                        textCell.setTextAndIcon(getString(R.string.BuiltInFolders), R.drawable.msg_folders, true);
                    } else if (position == generalRow) {
                        textCell.setTextAndIcon(getString(R.string.General), R.drawable.msg_media, true);
                    } else if (position == appearanceRow) {
                        textCell.setTextAndIcon(getString(R.string.Appearance), R.drawable.msg_theme, true);
                    } else if (position == spyRow) {
                        textCell.setTextAndIcon(getString(R.string.AuthorGramSpy), R.drawable.msg_secret, true);
                    } else if (position == translatorRow) {
                        textCell.setTextAndIcon(getString(R.string.TranslatorSettings), R.drawable.ic_translate, true);
                    } else if (position == passcodeRow) {
                        textCell.setTextAndIcon(getString(R.string.AGPasscode), R.drawable.msg_permissions, true);
                    } else if (position == experimentRow) {
                        textCell.setTextAndIcon(getString(R.string.Experimental), R.drawable.msg_fave, true);
                    } else if (position == importSettingsRow) {
                        textCell.setTextAndIcon(getString(R.string.ImportSettings), R.drawable.import_solar, true);
                    } else if (position == exportSettingsRow) {
                        textCell.setTextAndIcon(getString(R.string.BackupSettings), R.drawable.export_solar, true);
                    } else if (position == resetSettingsRow) {
                        textCell.setTextAndIcon(getString(R.string.ResetSettings), R.drawable.msg_reset, true);
                    } else if (position == appRestartRow) {
                        textCell.setTextAndIcon(getString(R.string.RestartApp), R.drawable.msg_retry, true);
                    } else if (position == aboutRow) {
                        textCell.setTextAndIcon(getString(R.string.About), R.drawable.msg_info, true);
                    }
                    break;
                }

            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == categoriesEndRow || position == agSettingsEndRow) {
                return TYPE_SHADOW;
            } else if (position == chatRow || position == cameraRow || position == localFoldersRow || position == generalRow || position == appearanceRow || position == spyRow || position == passcodeRow || position == experimentRow || position == translatorRow ||
                    position == importSettingsRow || position == exportSettingsRow || position == resetSettingsRow || position == appRestartRow ||
                    position == aboutRow) {
                return TYPE_TEXT;
            }
            return TYPE_SHADOW;
        }
    }

    private DocumentSelectActivity getDocumentSelectActivity(Activity parent) {
        try {
            if (parent.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                parent.requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, BasePermissionsActivity.REQUEST_CODE_EXTERNAL_STORAGE);
                return null;
            }
        } catch (Throwable ignore) {
        }
        DocumentSelectActivity fragment = new DocumentSelectActivity(false);
        fragment.setMaxSelectedFiles(1);
        fragment.setAllowPhoto(false);
        fragment.setDelegate(new DocumentSelectActivity.DocumentSelectActivityDelegate() {
            @Override
            public void didSelectFiles(DocumentSelectActivity activity, ArrayList<String> files, String caption, boolean notify, int scheduleDate) {
                activity.finishFragment();
                SettingsBackupHelper.importSettings(parent, new File(files.get(0)));
            }

            @Override
            public void didSelectPhotos(ArrayList<SendMessagesHelper.SendingMediaInfo> photos, boolean notify, int scheduleDate) {
            }

            @Override
            public void startDocumentSelectActivity() {
            }
        });
        return fragment;
    }

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("application/json");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(intent, 21);
        } catch (android.content.ActivityNotFoundException ex) {
            AlertUtil.showSimpleAlert(getParentActivity(), ex);
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode == 21 && resultCode == Activity.RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                File cacheDir = AndroidUtilities.getCacheDir();
                String tempFile = UUID.randomUUID().toString().replace("-", "") + ".authorgram-settings.json";
                File file = new File(cacheDir.getPath(), tempFile);
                try {
                    final InputStream inputStream = ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri);
                    if (inputStream != null) {
                        OutputStream outputStream = new FileOutputStream(file);
                        final byte[] buffer = new byte[4 * 1024];
                        int read;
                        while ((read = inputStream.read(buffer)) != -1) {
                            outputStream.write(buffer, 0, read);
                        }
                        inputStream.close();
                        outputStream.flush();
                        outputStream.close();
                        SettingsBackupHelper.importSettings(getParentActivity(), file);
                    }
                } catch (Exception ignore) {
                }
            }
            super.onActivityResultFragment(requestCode, resultCode, data);
        }
    }
}
