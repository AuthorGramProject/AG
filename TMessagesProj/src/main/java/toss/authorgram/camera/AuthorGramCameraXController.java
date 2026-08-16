package toss.authorgram.camera;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.util.Range;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.camera.camera2.interop.Camera2CameraInfo;
import androidx.camera.camera2.interop.Camera2Interop;
import androidx.camera.camera2.interop.ExperimentalCamera2Interop;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ConcurrentCamera;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.MeteringPointFactory;
import androidx.camera.core.Preview;
import androidx.camera.core.UseCaseGroup;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.google.common.util.concurrent.ListenableFuture;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.camera.Size;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

/** CameraX preview controller used by Telegram's existing round-video GL encoder. */
@androidx.annotation.OptIn(markerClass = ExperimentalCamera2Interop.class)
public final class AuthorGramCameraXController {

    public interface Listener {
        void onReady(Size[] sizes, boolean concurrent);

        void onError(Throwable error);
    }

    private final CameraLifecycle lifecycle = new CameraLifecycle();
    private final Executor mainExecutor;
    private final Context context;
    private final MeteringPointFactory meteringPointFactory;
    private final SurfaceTexture[] textures;
    private final Listener listener;
    private final ArrayList<Surface> suppliedSurfaces = new ArrayList<>();
    private final Size[] previewSizes = new Size[2];

    private ProcessCameraProvider provider;
    private Camera[] cameras = new Camera[2];
    private boolean frontFace;
    private final boolean surface0Front;
    private boolean concurrentRequested;
    private boolean concurrent;
    private boolean initiated;
    private boolean closed;
    private int readySurfaces;

    public AuthorGramCameraXController(
            Context context,
            MeteringPointFactory meteringPointFactory,
            SurfaceTexture[] textures,
            boolean frontFace,
            boolean concurrentRequested,
            Listener listener
    ) {
        this.context = context.getApplicationContext();
        this.mainExecutor = ContextCompat.getMainExecutor(context);
        this.meteringPointFactory = meteringPointFactory;
        this.textures = textures;
        this.frontFace = frontFace;
        this.surface0Front = frontFace;
        this.concurrentRequested = concurrentRequested;
        this.listener = listener;
    }

    public void start() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(context);
        future.addListener(() -> {
            try {
                if (closed) {
                    return;
                }
                provider = future.get();
                lifecycle.start();
                bindUseCases();
            } catch (Throwable error) {
                fail(error);
            }
        }, mainExecutor);
    }

    public boolean isInitiated() {
        return initiated;
    }

    public boolean isFrontFace() {
        return frontFace;
    }

    public boolean isConcurrent() {
        return concurrent;
    }

    public void switchCamera() {
        frontFace = !frontFace;
        if (!concurrent) {
            bindUseCases();
        }
    }

    public void close() {
        closed = true;
        initiated = false;
        if (provider != null) {
            try {
                provider.unbindAll();
            } catch (Throwable error) {
                FileLog.e(error);
            }
        }
        releaseSurfaces();
        lifecycle.stop();
    }

    public void setZoom(float linearZoom) {
        Camera camera = currentCamera();
        if (camera != null) {
            camera.getCameraControl().setLinearZoom(Math.max(0f, Math.min(1f, linearZoom)));
        }
    }

    public float getZoom() {
        Camera camera = currentCamera();
        if (camera == null || camera.getCameraInfo().getZoomState().getValue() == null) {
            return 0f;
        }
        return camera.getCameraInfo().getZoomState().getValue().getLinearZoom();
    }

    public void setTorch(boolean enabled) {
        Camera camera = currentCamera();
        if (camera != null && camera.getCameraInfo().hasFlashUnit()) {
            camera.getCameraControl().enableTorch(enabled);
        }
    }

    public boolean isExposureSupported() {
        Camera camera = currentCamera();
        return camera != null && camera.getCameraInfo().getExposureState().isExposureCompensationSupported();
    }

    public void setExposure(float value) {
        Camera camera = currentCamera();
        if (camera == null || !camera.getCameraInfo().getExposureState().isExposureCompensationSupported()) {
            return;
        }
        Range<Integer> range = camera.getCameraInfo().getExposureState().getExposureCompensationRange();
        int index = Math.round(range.getLower() + (range.getUpper() - range.getLower()) * Math.max(0f, Math.min(1f, value)));
        camera.getCameraControl().setExposureCompensationIndex(index);
    }

    public void focusToPoint(float x, float y) {
        Camera camera = currentCamera();
        if (camera == null || meteringPointFactory == null) {
            return;
        }
        MeteringPoint point = meteringPointFactory.createPoint(x, y);
        FocusMeteringAction action = new FocusMeteringAction.Builder(
                point,
                FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE | FocusMeteringAction.FLAG_AWB
        ).build();
        camera.getCameraControl().startFocusAndMetering(action);
    }

    private Camera currentCamera() {
        if (!concurrent) {
            return cameras[0];
        }
        return frontFace == surface0Front ? cameras[0] : cameras[1];
    }

    private void bindUseCases() {
        if (closed || provider == null || lifecycle.getLifecycle().getCurrentState() == Lifecycle.State.DESTROYED) {
            return;
        }
        try {
            initiated = false;
            readySurfaces = 0;
            previewSizes[0] = null;
            previewSizes[1] = null;
            releaseSurfaces();
            provider.unbindAll();

            boolean canUseConcurrent = concurrentRequested && !provider.getAvailableConcurrentCameraInfos().isEmpty();
            if (canUseConcurrent) {
                bindConcurrent();
            } else {
                bindSingle();
            }
        } catch (Throwable error) {
            if (concurrentRequested) {
                concurrentRequested = false;
                try {
                    provider.unbindAll();
                    bindSingle();
                    return;
                } catch (Throwable fallbackError) {
                    error.addSuppressed(fallbackError);
                }
            }
            fail(error);
        }
    }

    private void bindSingle() {
        concurrent = false;
        CameraSelector selector = selector(frontFace);
        Preview preview = buildPreview(0, selector);
        cameras[0] = provider.bindToLifecycle(lifecycle, selector, preview);
        cameras[1] = null;
    }

    private void bindConcurrent() {
        concurrent = true;
        CameraSelector firstSelector = selector(frontFace);
        CameraSelector secondSelector = selector(!frontFace);
        Preview firstPreview = buildPreview(0, firstSelector);
        Preview secondPreview = buildPreview(1, secondSelector);

        ConcurrentCamera.SingleCameraConfig first = new ConcurrentCamera.SingleCameraConfig(
                firstSelector,
                new UseCaseGroup.Builder().addUseCase(firstPreview).build(),
                lifecycle
        );
        ConcurrentCamera.SingleCameraConfig second = new ConcurrentCamera.SingleCameraConfig(
                secondSelector,
                new UseCaseGroup.Builder().addUseCase(secondPreview).build(),
                lifecycle
        );
        ConcurrentCamera bound = provider.bindToLifecycle(Arrays.asList(first, second));
        cameras[0] = bound.getCameras().get(0);
        cameras[1] = bound.getCameras().get(1);
    }

    private Preview buildPreview(int index, CameraSelector selector) {
        Preview.Builder builder = new Preview.Builder();
        builder.setTargetResolution(AuthorGramCameraConfig.getRequestedPreviewSize());
        Range<Integer> fps = AuthorGramCameraConfig.getFpsRange();
        if (fps != null) {
            builder.setTargetFrameRate(fps);
        }
        applyEnhancements(builder, selector);
        Preview preview = builder.build();
        preview.setSurfaceProvider(request -> {
            android.util.Size resolution = request.getResolution();
            textures[index].setDefaultBufferSize(resolution.getWidth(), resolution.getHeight());
            previewSizes[index] = new Size(resolution.getWidth(), resolution.getHeight());
            Surface surface = new Surface(textures[index]);
            suppliedSurfaces.add(surface);
            request.provideSurface(surface, mainExecutor, result -> {
                suppliedSurfaces.remove(surface);
                surface.release();
            });
            readySurfaces++;
            int expected = concurrent ? 2 : 1;
            if (readySurfaces >= expected) {
                initiated = true;
                listener.onReady(previewSizes.clone(), concurrent);
            }
        });
        return preview;
    }

    private CameraSelector selector(boolean front) {
        if (front) {
            return CameraSelector.DEFAULT_FRONT_CAMERA;
        }
        if (!AuthorGramCameraConfig.startFromUltraWide()) {
            return CameraSelector.DEFAULT_BACK_CAMERA;
        }
        return new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .addCameraFilter(cameraInfos -> {
                    if (cameraInfos.size() < 2) {
                        return cameraInfos;
                    }
                    CameraInfo best = null;
                    float bestFocal = Float.MAX_VALUE;
                    for (CameraInfo info : cameraInfos) {
                        try {
                            float[] focalLengths = Camera2CameraInfo.from(info).getCameraCharacteristic(
                                    CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                            if (focalLengths != null) {
                                for (float focal : focalLengths) {
                                    if (focal > 0f && focal < bestFocal) {
                                        bestFocal = focal;
                                        best = info;
                                    }
                                }
                            }
                        } catch (Throwable ignore) {
                        }
                    }
                    return best == null ? cameraInfos : Collections.singletonList(best);
                })
                .build();
    }

    private void applyEnhancements(Preview.Builder builder, CameraSelector selector) {
        CameraInfo info = firstInfo(selector);
        if (info == null) {
            return;
        }
        Camera2CameraInfo camera2Info = Camera2CameraInfo.from(info);
        Camera2Interop.Extender<Preview> extender = new Camera2Interop.Extender<>(builder);

        if (AuthorGramCameraConfig.isEnhancementEnabled(AuthorGramCameraConfig.ENHANCEMENT_STABILIZATION)
                && supports(camera2Info, CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES,
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)) {
            extender.setCaptureRequestOption(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON);
        }
        if (AuthorGramCameraConfig.isEnhancementEnabled(AuthorGramCameraConfig.ENHANCEMENT_NOISE_REDUCTION)
                && supports(camera2Info, CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES,
                CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)) {
            extender.setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE,
                    CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY);
        }
        if (AuthorGramCameraConfig.isEnhancementEnabled(AuthorGramCameraConfig.ENHANCEMENT_EDGE)
                && supports(camera2Info, CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES,
                CaptureRequest.EDGE_MODE_HIGH_QUALITY)) {
            extender.setCaptureRequestOption(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY);
        }
        if (AuthorGramCameraConfig.isEnhancementEnabled(AuthorGramCameraConfig.ENHANCEMENT_HOT_PIXEL)
                && supports(camera2Info, CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES,
                CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY)) {
            extender.setCaptureRequestOption(CaptureRequest.HOT_PIXEL_MODE,
                    CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY);
        }
        if (AuthorGramCameraConfig.isEnhancementEnabled(AuthorGramCameraConfig.ENHANCEMENT_SHADING)
                && supports(camera2Info, CameraCharacteristics.SHADING_AVAILABLE_MODES,
                CaptureRequest.SHADING_MODE_HIGH_QUALITY)) {
            extender.setCaptureRequestOption(CaptureRequest.SHADING_MODE, CaptureRequest.SHADING_MODE_HIGH_QUALITY);
        }
        if (AuthorGramCameraConfig.isEnhancementEnabled(AuthorGramCameraConfig.ENHANCEMENT_ABERRATION)
                && supports(camera2Info, CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES,
                CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY)) {
            extender.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
                    CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY);
        }
    }

    private CameraInfo firstInfo(CameraSelector selector) {
        try {
            List<CameraInfo> filtered = selector.filter(provider.getAvailableCameraInfos());
            return filtered.isEmpty() ? null : filtered.get(0);
        } catch (Throwable ignore) {
            return null;
        }
    }

    private static boolean supports(Camera2CameraInfo info, CameraCharacteristics.Key<int[]> key, int value) {
        try {
            int[] values = info.getCameraCharacteristic(key);
            if (values != null) {
                for (int candidate : values) {
                    if (candidate == value) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignore) {
        }
        return false;
    }

    private void fail(Throwable error) {
        initiated = false;
        FileLog.e(error);
        listener.onError(error);
    }

    private void releaseSurfaces() {
        // CameraX owns every supplied surface until its result callback fires.
        // Unbinding triggers those callbacks; releasing earlier can race the camera service.
        suppliedSurfaces.clear();
    }

    private static final class CameraLifecycle implements LifecycleOwner {
        private final LifecycleRegistry registry = new LifecycleRegistry(this);

        private CameraLifecycle() {
            registry.setCurrentState(Lifecycle.State.CREATED);
        }

        private void start() {
            registry.setCurrentState(Lifecycle.State.RESUMED);
        }

        private void stop() {
            registry.setCurrentState(Lifecycle.State.DESTROYED);
        }

        @NonNull
        @Override
        public Lifecycle getLifecycle() {
            return registry;
        }
    }
}
