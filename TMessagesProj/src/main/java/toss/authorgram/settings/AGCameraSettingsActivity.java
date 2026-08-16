package toss.authorgram.settings;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;

import toss.authorgram.camera.AuthorGramCameraConfig;
import xyz.nextalone.nagram.NaConfig;

public class AGCameraSettingsActivity extends BaseAGSettingsActivity {

    private int cameraTypeHeaderRow;
    private int cameraTypeRow;
    private int cameraTypeInfoRow;
    private int cameraTypeEndRow;
    private int cameraHeaderRow;
    private int aspectRatioRow;
    private int cameraEndRow;
    private int videoHeaderRow;
    private int dualCameraRow;
    private int rearCameraRow;
    private int ultraWideRow;
    private int qualityRow;
    private int fpsRow;
    private int enhancementsRow;
    private final int[] enhancementRows = new int[AuthorGramCameraConfig.ENHANCEMENT_COUNT];
    private int enhancementsInfoRow;
    private int videoEndRow;
    private int controlsHeaderRow;
    private int exposureRow;
    private int centerControlsRow;
    private int controlsEndRow;

    private boolean enhancementsExpanded;

    @Override
    protected void updateRows() {
        super.updateRows();
        cameraTypeHeaderRow = addRow("cameraType");
        cameraTypeRow = addRow("cameraTypeSelector");
        cameraTypeInfoRow = addRow();
        cameraTypeEndRow = addRow();

        cameraHeaderRow = addRow("camera");
        aspectRatioRow = addRow("cameraAspectRatio");
        cameraEndRow = addRow();

        videoHeaderRow = addRow("videoMessages");
        int type = AuthorGramCameraConfig.getCameraType();
        if (type == AuthorGramCameraConfig.CAMERA_X || type == AuthorGramCameraConfig.CAMERA_2) {
            dualCameraRow = addRow("dualCamera");
        } else {
            dualCameraRow = -1;
        }
        if (!isDualEnabled()) {
            rearCameraRow = addRow("rearCamera");
        } else {
            rearCameraRow = -1;
        }
        if (type == AuthorGramCameraConfig.CAMERA_X) {
            ultraWideRow = addRow("startUltraWide");
            qualityRow = addRow("cameraQuality");
            fpsRow = addRow("cameraFps");
            enhancementsRow = addRow("cameraEnhancements");
            if (enhancementsExpanded) {
                for (int i = 0; i < enhancementRows.length; i++) {
                    enhancementRows[i] = addRow("cameraEnhancement" + i);
                }
            } else {
                for (int i = 0; i < enhancementRows.length; i++) {
                    enhancementRows[i] = -1;
                }
            }
            enhancementsInfoRow = addRow();
        } else {
            ultraWideRow = qualityRow = fpsRow = enhancementsRow = enhancementsInfoRow = -1;
            for (int i = 0; i < enhancementRows.length; i++) {
                enhancementRows[i] = -1;
            }
        }
        videoEndRow = addRow();

        controlsHeaderRow = addRow("cameraControls");
        if (type == AuthorGramCameraConfig.CAMERA_X) {
            exposureRow = addRow("cameraExposure");
        } else {
            exposureRow = -1;
        }
        centerControlsRow = addRow("centerCameraControls");
        controlsEndRow = addRow();
    }

    @Override
    protected void onItemClick(View view, int position, float x, float y) {
        if (position == cameraTypeRow) {
            showCameraTypeDialog();
        } else if (position == aspectRatioRow) {
            showAspectRatioDialog();
        } else if (position == dualCameraRow) {
            if (!isDualSupported()) {
                return;
            }
            boolean enabled = !isDualEnabled();
            MessagesController.getGlobalMainSettings().edit().putBoolean("rounddual_available", enabled).apply();
            rebuildRows();
        } else if (position == rearCameraRow) {
            boolean rear = NaConfig.INSTANCE.getCameraInVideoMessages().Int() == 1;
            NaConfig.INSTANCE.getCameraInVideoMessages().setConfigInt(rear ? 0 : 1);
            listAdapter.notifyItemChanged(position);
        } else if (position == ultraWideRow) {
            AuthorGramCameraConfig.setStartFromUltraWide(!AuthorGramCameraConfig.startFromUltraWide());
            listAdapter.notifyItemChanged(position);
        } else if (position == qualityRow) {
            showQualityDialog();
        } else if (position == fpsRow) {
            showFpsDialog();
        } else if (position == enhancementsRow) {
            enhancementsExpanded = !enhancementsExpanded;
            rebuildRows();
        } else if (position == exposureRow) {
            showExposureDialog();
        } else if (position == centerControlsRow) {
            AuthorGramCameraConfig.setCenterControls(!AuthorGramCameraConfig.centerControls());
            listAdapter.notifyItemChanged(position);
        } else {
            for (int i = 0; i < enhancementRows.length; i++) {
                if (position == enhancementRows[i]) {
                    AuthorGramCameraConfig.setEnhancementEnabled(i,
                            !AuthorGramCameraConfig.isEnhancementEnabled(i));
                    listAdapter.notifyItemChanged(position);
                    if (enhancementsRow >= 0) {
                        listAdapter.notifyItemChanged(enhancementsRow);
                    }
                    return;
                }
            }
        }
    }

    private void rebuildRows() {
        updateRows();
        listAdapter.notifyDataSetChanged();
    }

    private void showCameraTypeDialog() {
        String[] items = {
                getString(R.string.AGCameraTypeTelegram),
                "CameraX",
                getString(R.string.AGCameraTypeCamera2)
        };
        new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                .setTitle(getString(R.string.AGCameraType))
                .setItems(items, (dialog, which) -> {
                    AuthorGramCameraConfig.setCameraType(which);
                    rebuildRows();
                })
                .setNegativeButton(getString(R.string.Cancel), null)
                .show();
    }

    private void showAspectRatioDialog() {
        int[] values = {
                AuthorGramCameraConfig.ASPECT_1_1,
                AuthorGramCameraConfig.ASPECT_4_3,
                AuthorGramCameraConfig.ASPECT_16_9,
                AuthorGramCameraConfig.ASPECT_DEFAULT
        };
        String[] items = {"1:1", "4:3", "16:9", getString(R.string.Default)};
        new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                .setTitle(getString(R.string.AGCameraAspectRatio))
                .setItems(items, (dialog, which) -> {
                    AuthorGramCameraConfig.setAspectRatio(values[which]);
                    listAdapter.notifyItemChanged(aspectRatioRow);
                })
                .setNegativeButton(getString(R.string.Cancel), null)
                .show();
    }

    private void showQualityDialog() {
        int[] values = {720, 1080, 2160};
        String[] items = {"720p", "1080p", "2160p"};
        new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                .setTitle(getString(R.string.AGCameraQuality))
                .setItems(items, (dialog, which) -> {
                    AuthorGramCameraConfig.setQuality(values[which]);
                    listAdapter.notifyItemChanged(qualityRow);
                })
                .setNegativeButton(getString(R.string.Cancel), null)
                .show();
    }

    private void showFpsDialog() {
        int[] values = {
                AuthorGramCameraConfig.FPS_25_30,
                AuthorGramCameraConfig.FPS_30_30,
                AuthorGramCameraConfig.FPS_30_60,
                AuthorGramCameraConfig.FPS_60_60,
                AuthorGramCameraConfig.FPS_DEFAULT
        };
        String[] items = {"25-30", "30-30", "30-60", "60-60", getString(R.string.Default)};
        new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                .setTitle("FPS")
                .setItems(items, (dialog, which) -> {
                    AuthorGramCameraConfig.setFpsMode(values[which]);
                    listAdapter.notifyItemChanged(fpsRow);
                })
                .setNegativeButton(getString(R.string.Cancel), null)
                .show();
    }

    private void showExposureDialog() {
        int[] values = {
                AuthorGramCameraConfig.EXPOSURE_RIGHT,
                AuthorGramCameraConfig.EXPOSURE_LEFT,
                AuthorGramCameraConfig.EXPOSURE_NONE
        };
        String[] items = {
                getString(R.string.AGCameraPositionRight),
                getString(R.string.AGCameraPositionLeft),
                getString(R.string.Disable)
        };
        new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                .setTitle(getString(R.string.AGCameraExposure))
                .setItems(items, (dialog, which) -> {
                    AuthorGramCameraConfig.setExposurePosition(values[which]);
                    listAdapter.notifyItemChanged(exposureRow);
                })
                .setNegativeButton(getString(R.string.Cancel), null)
                .show();
    }

    private boolean isDualSupported() {
        Context context = getContext();
        if (context == null) {
            context = ApplicationLoader.applicationContext;
        }
        return context != null
                && context.getPackageManager().hasSystemFeature("android.hardware.camera.concurrent");
    }

    private boolean isDualEnabled() {
        return isDualSupported() && MessagesController.getGlobalMainSettings()
                .getBoolean("rounddual_available", false);
    }

    private static String cameraTypeValue() {
        return switch (AuthorGramCameraConfig.getCameraType()) {
            case AuthorGramCameraConfig.CAMERA_X -> "CameraX";
            case AuthorGramCameraConfig.CAMERA_2 -> getString(R.string.AGCameraTypeCamera2);
            default -> getString(R.string.AGCameraTypeTelegram);
        };
    }

    private static String aspectRatioValue() {
        return switch (AuthorGramCameraConfig.getAspectRatio()) {
            case AuthorGramCameraConfig.ASPECT_16_9 -> "16:9";
            case AuthorGramCameraConfig.ASPECT_1_1 -> "1:1";
            case AuthorGramCameraConfig.ASPECT_DEFAULT -> getString(R.string.Default);
            default -> "4:3";
        };
    }

    private static String fpsValue() {
        return switch (AuthorGramCameraConfig.getFpsMode()) {
            case AuthorGramCameraConfig.FPS_25_30 -> "25-30";
            case AuthorGramCameraConfig.FPS_30_30 -> "30-30";
            case AuthorGramCameraConfig.FPS_30_60 -> "30-60";
            case AuthorGramCameraConfig.FPS_60_60 -> "60-60";
            default -> getString(R.string.Default);
        };
    }

    private static String exposureValue() {
        return switch (AuthorGramCameraConfig.getExposurePosition()) {
            case AuthorGramCameraConfig.EXPOSURE_RIGHT -> getString(R.string.AGCameraPositionRight);
            case AuthorGramCameraConfig.EXPOSURE_LEFT -> getString(R.string.AGCameraPositionLeft);
            default -> getString(R.string.Disable);
        };
    }

    @Override
    protected BaseListAdapter createAdapter(Context context) {
        return new ListAdapter(context);
    }

    @Override
    protected String getActionBarTitle() {
        return getString(R.string.AGCameraSettings);
    }

    @Override
    protected String getKey() {
        return "camera";
    }

    private class ListAdapter extends BaseListAdapter {
        private ListAdapter(Context context) {
            super(context);
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            if (holder.getAdapterPosition() == dualCameraRow && !isDualSupported()) {
                return false;
            }
            return super.isEnabled(holder);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position, boolean partial) {
            if (holder.itemView instanceof tw.nekomimi.nekogram.ui.cells.HeaderCell cell) {
                if (position == cameraTypeHeaderRow) {
                    cell.setText(getString(R.string.AGCameraType));
                } else if (position == cameraHeaderRow) {
                    cell.setText(getString(R.string.AGCameraSettings));
                } else if (position == videoHeaderRow) {
                    cell.setText(getString(R.string.AGCameraVideoMessages));
                } else if (position == controlsHeaderRow) {
                    cell.setText(getString(R.string.AGCameraControls));
                }
            } else if (holder.itemView instanceof TextSettingsCell cell) {
                if (position == cameraTypeRow) {
                    cell.setTextAndValue(getString(R.string.AGCameraType), cameraTypeValue(), true);
                } else if (position == aspectRatioRow) {
                    cell.setTextAndValue(getString(R.string.AGCameraAspectRatio), aspectRatioValue(), true);
                } else if (position == qualityRow) {
                    cell.setTextAndValue(getString(R.string.AGCameraQuality), AuthorGramCameraConfig.getQuality() + "p", true);
                } else if (position == fpsRow) {
                    cell.setTextAndValue("FPS", fpsValue(), true);
                } else if (position == enhancementsRow) {
                    String value = AuthorGramCameraConfig.getEnhancementCount() + "/" + AuthorGramCameraConfig.ENHANCEMENT_COUNT
                            + (enhancementsExpanded ? "  ▲" : "  ▼");
                    cell.setTextAndValue(getString(R.string.AGCameraEnhancements), value, true);
                } else if (position == exposureRow) {
                    cell.setTextAndValue(getString(R.string.AGCameraExposure), exposureValue(), true);
                }
            } else if (holder.itemView instanceof TextCheckCell cell) {
                if (position == dualCameraRow) {
                    cell.setTextAndValueAndCheck(
                            getString(R.string.AGCameraDual),
                            isDualSupported() ? getString(R.string.AGCameraDualInfo) : getString(R.string.AGCameraDualUnsupported),
                            isDualEnabled(), true, true);
                    cell.setEnabled(isDualSupported());
                } else if (position == rearCameraRow) {
                    cell.setEnabled(true);
                    cell.setTextAndValueAndCheck(
                            getString(R.string.AGCameraRear),
                            getString(R.string.AGCameraRearInfo),
                            NaConfig.INSTANCE.getCameraInVideoMessages().Int() == 1, true, true);
                } else if (position == ultraWideRow) {
                    cell.setEnabled(true);
                    cell.setTextAndValueAndCheck(
                            getString(R.string.AGCameraUltraWide),
                            getString(R.string.AGCameraUltraWideInfo),
                            AuthorGramCameraConfig.startFromUltraWide(), true, true);
                } else if (position == centerControlsRow) {
                    cell.setEnabled(true);
                    cell.setTextAndValueAndCheck(
                            getString(R.string.AGCameraCenterControls),
                            getString(R.string.AGCameraCenterControlsInfo),
                            AuthorGramCameraConfig.centerControls(), true, false);
                } else {
                    int enhancement = enhancementIndex(position);
                    if (enhancement >= 0) {
                        cell.setEnabled(true);
                        cell.setTextAndCheck(enhancementName(enhancement),
                                AuthorGramCameraConfig.isEnhancementEnabled(enhancement),
                                enhancement < AuthorGramCameraConfig.ENHANCEMENT_COUNT - 1);
                    }
                }
            } else if (holder.itemView instanceof TextInfoPrivacyCell cell) {
                if (position == cameraTypeInfoRow) {
                    int type = AuthorGramCameraConfig.getCameraType();
                    cell.setText(getString(type == AuthorGramCameraConfig.CAMERA_X
                            ? R.string.AGCameraTypeCameraXInfo
                            : type == AuthorGramCameraConfig.CAMERA_2
                            ? R.string.AGCameraTypeCamera2Info
                            : R.string.AGCameraTypeTelegramInfo));
                } else if (position == enhancementsInfoRow) {
                    cell.setText(getString(R.string.AGCameraEnhancementsInfo));
                }
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == cameraTypeHeaderRow || position == cameraHeaderRow
                    || position == videoHeaderRow || position == controlsHeaderRow) {
                return TYPE_HEADER;
            }
            if (position == cameraTypeRow || position == aspectRatioRow || position == qualityRow
                    || position == fpsRow || position == enhancementsRow || position == exposureRow) {
                return TYPE_SETTINGS;
            }
            if (position == dualCameraRow || position == rearCameraRow || position == ultraWideRow
                    || position == centerControlsRow || enhancementIndex(position) >= 0) {
                return TYPE_CHECK;
            }
            if (position == cameraTypeInfoRow || position == enhancementsInfoRow) {
                return TYPE_INFO_PRIVACY;
            }
            return TYPE_SHADOW;
        }
    }

    private int enhancementIndex(int position) {
        for (int i = 0; i < enhancementRows.length; i++) {
            if (position == enhancementRows[i]) {
                return i;
            }
        }
        return -1;
    }

    private String enhancementName(int index) {
        return getString(switch (index) {
            case AuthorGramCameraConfig.ENHANCEMENT_STABILIZATION -> R.string.AGCameraEnhancementStabilization;
            case AuthorGramCameraConfig.ENHANCEMENT_NOISE_REDUCTION -> R.string.AGCameraEnhancementNoise;
            case AuthorGramCameraConfig.ENHANCEMENT_EDGE -> R.string.AGCameraEnhancementEdge;
            case AuthorGramCameraConfig.ENHANCEMENT_HOT_PIXEL -> R.string.AGCameraEnhancementHotPixel;
            case AuthorGramCameraConfig.ENHANCEMENT_SHADING -> R.string.AGCameraEnhancementShading;
            default -> R.string.AGCameraEnhancementAberration;
        });
    }
}
