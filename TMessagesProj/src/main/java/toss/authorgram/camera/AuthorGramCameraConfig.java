package toss.authorgram.camera;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Range;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.camera.Size;

import xyz.nextalone.nagram.NaConfig;

/**
 * Persistent AuthorGram camera preferences shared by the settings screen and the
 * round-video camera pipeline.
 */
public final class AuthorGramCameraConfig {

    public static final int CAMERA_TELEGRAM = 0;
    public static final int CAMERA_X = 1;
    public static final int CAMERA_2 = 2;

    public static final int ASPECT_16_9 = 0;
    public static final int ASPECT_4_3 = 1;
    public static final int ASPECT_1_1 = 2;
    public static final int ASPECT_DEFAULT = 3;

    public static final int FPS_DEFAULT = 0;
    public static final int FPS_25_30 = 1;
    public static final int FPS_30_30 = 2;
    public static final int FPS_30_60 = 3;
    public static final int FPS_60_60 = 4;

    public static final int EXPOSURE_NONE = 0;
    public static final int EXPOSURE_RIGHT = 1;
    public static final int EXPOSURE_LEFT = 2;

    public static final int ENHANCEMENT_STABILIZATION = 0;
    public static final int ENHANCEMENT_NOISE_REDUCTION = 1;
    public static final int ENHANCEMENT_EDGE = 2;
    public static final int ENHANCEMENT_HOT_PIXEL = 3;
    public static final int ENHANCEMENT_SHADING = 4;
    public static final int ENHANCEMENT_ABERRATION = 5;
    public static final int ENHANCEMENT_COUNT = 6;

    private static final String KEY_CAMERA_TYPE = "AG_CameraType";
    private static final String KEY_ASPECT_RATIO = "AG_CameraAspectRatio";
    private static final String KEY_START_ULTRA_WIDE = "AG_CameraStartUltraWide";
    private static final String KEY_QUALITY = "AG_CameraQuality";
    private static final String KEY_FPS = "AG_CameraFps";
    private static final String KEY_EXPOSURE = "AG_CameraExposure";
    private static final String KEY_CENTER_CONTROLS = "AG_CameraCenterControls";
    private static final String[] ENHANCEMENT_KEYS = {
            "AG_CameraEnhancementStabilization",
            "AG_CameraEnhancementNoiseReduction",
            "AG_CameraEnhancementEdge",
            "AG_CameraEnhancementHotPixel",
            "AG_CameraEnhancementShading",
            "AG_CameraEnhancementAberration"
    };

    private AuthorGramCameraConfig() {
    }

    private static SharedPreferences preferences() {
        Context context = ApplicationLoader.applicationContext;
        return context.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE);
    }

    public static int getCameraType() {
        return preferences().getInt(KEY_CAMERA_TYPE, CAMERA_TELEGRAM);
    }

    public static void setCameraType(int value) {
        preferences().edit().putInt(KEY_CAMERA_TYPE, value).apply();
    }

    public static boolean shouldUseCameraX() {
        return getCameraType() == CAMERA_X;
    }

    public static boolean shouldUseCamera2(int account) {
        int type = getCameraType();
        return type == CAMERA_2 || type == CAMERA_TELEGRAM && SharedConfig.isUsingCamera2(account);
    }

    public static int getAspectRatio() {
        return preferences().getInt(KEY_ASPECT_RATIO, ASPECT_4_3);
    }

    public static void setAspectRatio(int value) {
        preferences().edit().putInt(KEY_ASPECT_RATIO, value).apply();
    }

    public static Size getAspectRatioSize() {
        return switch (getAspectRatio()) {
            case ASPECT_16_9 -> new Size(16, 9);
            case ASPECT_1_1 -> new Size(1, 1);
            case ASPECT_DEFAULT -> SharedConfig.roundCamera16to9 ? new Size(16, 9) : new Size(4, 3);
            default -> new Size(4, 3);
        };
    }

    public static boolean startFromUltraWide() {
        return preferences().getBoolean(KEY_START_ULTRA_WIDE, true);
    }

    public static void setStartFromUltraWide(boolean value) {
        preferences().edit().putBoolean(KEY_START_ULTRA_WIDE, value).apply();
    }

    public static int getQuality() {
        return preferences().getInt(KEY_QUALITY, 1080);
    }

    public static void setQuality(int value) {
        preferences().edit().putInt(KEY_QUALITY, value).apply();
    }

    public static int getFpsMode() {
        return preferences().getInt(KEY_FPS,
                SharedConfig.getDevicePerformanceClass() >= SharedConfig.PERFORMANCE_CLASS_AVERAGE
                        ? FPS_25_30 : FPS_DEFAULT);
    }

    public static void setFpsMode(int value) {
        preferences().edit().putInt(KEY_FPS, value).apply();
    }

    public static Range<Integer> getFpsRange() {
        return switch (getFpsMode()) {
            case FPS_25_30 -> new Range<>(25, 30);
            case FPS_30_30 -> new Range<>(30, 30);
            case FPS_30_60 -> new Range<>(30, 60);
            case FPS_60_60 -> new Range<>(60, 60);
            default -> null;
        };
    }

    public static int getExposurePosition() {
        return preferences().getInt(KEY_EXPOSURE, EXPOSURE_RIGHT);
    }

    public static void setExposurePosition(int value) {
        preferences().edit().putInt(KEY_EXPOSURE, value).apply();
    }

    public static boolean centerControls() {
        return preferences().getBoolean(KEY_CENTER_CONTROLS, true);
    }

    public static void setCenterControls(boolean value) {
        preferences().edit().putBoolean(KEY_CENTER_CONTROLS, value).apply();
    }

    public static boolean isEnhancementEnabled(int index) {
        if (index < 0 || index >= ENHANCEMENT_KEYS.length) {
            return false;
        }
        if (index == ENHANCEMENT_STABILIZATION) {
            return NaConfig.INSTANCE.getCameraStabilization().Bool();
        }
        return preferences().getBoolean(ENHANCEMENT_KEYS[index], false);
    }

    public static void setEnhancementEnabled(int index, boolean value) {
        if (index < 0 || index >= ENHANCEMENT_KEYS.length) {
            return;
        }
        if (index == ENHANCEMENT_STABILIZATION) {
            NaConfig.INSTANCE.getCameraStabilization().setConfigBool(value);
            return;
        }
        preferences().edit().putBoolean(ENHANCEMENT_KEYS[index], value).apply();
    }

    public static int getEnhancementCount() {
        int count = 0;
        for (int i = 0; i < ENHANCEMENT_COUNT; i++) {
            if (isEnhancementEnabled(i)) {
                count++;
            }
        }
        return count;
    }

    public static android.util.Size getRequestedPreviewSize() {
        int longSide = getQuality();
        if (longSide <= 0) {
            longSide = 1080;
        }
        int shortSide = switch (getAspectRatio()) {
            case ASPECT_16_9 -> Math.max(1, longSide * 9 / 16);
            case ASPECT_1_1 -> longSide;
            case ASPECT_DEFAULT -> SharedConfig.roundCamera16to9
                    ? Math.max(1, longSide * 9 / 16)
                    : Math.max(1, longSide * 3 / 4);
            default -> Math.max(1, longSide * 3 / 4);
        };
        return new android.util.Size(longSide, shortSide);
    }
}
