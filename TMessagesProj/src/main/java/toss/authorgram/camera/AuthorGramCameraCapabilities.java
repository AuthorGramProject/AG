package toss.authorgram.camera;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.util.Range;
import android.util.Size;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;

import java.util.Arrays;
import java.util.Comparator;

import xyz.nextalone.nagram.NaConfig;

/**
 * Resolves CameraX profile intentions against both Camera2 capture capabilities and
 * the device AVC encoder. Runtime CameraX/MediaCodec negotiation remains the final
 * safety layer because vendors may advertise combinations they cannot sustain.
 */
public final class AuthorGramCameraCapabilities {

    public static final class ResolvedProfile {
        public final int quality;
        public final int fps;

        private ResolvedProfile(int quality, int fps) {
            this.quality = quality;
            this.fps = fps;
        }

        public String summary() {
            return quality + "p · " + fps + " FPS";
        }
    }

    private static final int[] HIGH_QUALITY = {2160, 1440, 1080, 720, 480, 360};
    private static final int[] MEDIUM_QUALITY = {1080, 720, 480, 360};
    private static final int[] LOW_QUALITY = {720, 480, 360};
    private static final String VIDEO_MIME = "video/avc";

    private AuthorGramCameraCapabilities() {
    }

    public static ResolvedProfile resolve(int profile) {
        boolean highFps = AuthorGramCameraConfig.isHighFpsProfile(profile);
        int[] qualities = switch (AuthorGramCameraConfig.getQualityTier(profile)) {
            case AuthorGramCameraConfig.QUALITY_TIER_HIGH -> HIGH_QUALITY;
            case AuthorGramCameraConfig.QUALITY_TIER_LOW -> LOW_QUALITY;
            default -> MEDIUM_QUALITY;
        };
        int[] rates = highFps ? new int[]{60, 30} : new int[]{30};

        try {
            CameraInfoSnapshot camera = readPreferredCamera();
            MediaCodecInfo.VideoCapabilities encoder = findAvcEncoder();
            if (camera != null && encoder != null) {
                for (int quality : qualities) {
                    if (!camera.supportsSquareCrop(quality)) {
                        continue;
                    }
                    for (int fps : rates) {
                        if (camera.supportsFps(fps)
                                && encoder.areSizeAndRateSupported(quality, quality, fps)) {
                            log(profile, quality, fps);
                            return new ResolvedProfile(quality, fps);
                        }
                    }
                }
            }
        } catch (Throwable error) {
            FileLog.e(error);
        }

        int fallbackQuality = qualities[qualities.length - 1];
        int fallbackFps = 30;
        log(profile, fallbackQuality, fallbackFps);
        return new ResolvedProfile(fallbackQuality, fallbackFps);
    }

    private static CameraInfoSnapshot readPreferredCamera() throws Exception {
        Context context = ApplicationLoader.applicationContext;
        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) {
            return null;
        }
        boolean wantsRear = NaConfig.INSTANCE.getCameraInVideoMessages().Int() == 1;
        int requestedFacing = wantsRear
                ? CameraCharacteristics.LENS_FACING_BACK
                : CameraCharacteristics.LENS_FACING_FRONT;
        CameraInfoSnapshot fallback = null;
        for (String id : manager.getCameraIdList()) {
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
            CameraInfoSnapshot snapshot = CameraInfoSnapshot.from(characteristics);
            if (snapshot == null) {
                continue;
            }
            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == requestedFacing) {
                return snapshot;
            }
            if (fallback == null) {
                fallback = snapshot;
            }
        }
        return fallback;
    }

    private static MediaCodecInfo.VideoCapabilities findAvcEncoder() {
        MediaCodecInfo[] codecs = new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos();
        for (MediaCodecInfo codec : codecs) {
            if (!codec.isEncoder()) {
                continue;
            }
            for (String type : codec.getSupportedTypes()) {
                if (VIDEO_MIME.equalsIgnoreCase(type)) {
                    return codec.getCapabilitiesForType(type).getVideoCapabilities();
                }
            }
        }
        return null;
    }

    private static void log(int profile, int quality, int fps) {
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("AuthorGram CameraX profile=" + profile + " resolved=" + quality + "p@" + fps);
        }
    }

    private static final class CameraInfoSnapshot {
        private final Size[] sizes;
        private final Range<Integer>[] fpsRanges;

        private CameraInfoSnapshot(Size[] sizes, Range<Integer>[] fpsRanges) {
            this.sizes = sizes;
            this.fpsRanges = fpsRanges;
        }

        private static CameraInfoSnapshot from(CameraCharacteristics characteristics) {
            StreamConfigurationMap map = characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Range<Integer>[] ranges = characteristics.get(
                    CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            if (map == null || ranges == null || ranges.length == 0) {
                return null;
            }
            Size[] output = map.getOutputSizes(SurfaceTexture.class);
            if (output == null || output.length == 0) {
                return null;
            }
            Arrays.sort(output, Comparator.comparingLong(
                    size -> -((long) size.getWidth() * size.getHeight())));
            return new CameraInfoSnapshot(output, ranges);
        }

        private boolean supportsSquareCrop(int side) {
            for (Size size : sizes) {
                if (Math.min(size.getWidth(), size.getHeight()) >= side) {
                    return true;
                }
            }
            return false;
        }

        private boolean supportsFps(int fps) {
            for (Range<Integer> range : fpsRanges) {
                if (range.contains(fps)) {
                    return true;
                }
            }
            return false;
        }
    }
}
