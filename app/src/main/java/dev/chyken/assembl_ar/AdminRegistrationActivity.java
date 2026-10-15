package dev.chyken.assembl_ar;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.core.UseCaseGroup;
import androidx.camera.core.ViewPort;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.android.material.textfield.TextInputEditText;
import com.google.common.util.concurrent.ListenableFuture;

import org.opencv.android.OpenCVLoader;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.chyken.assembl_ar.data.AssemblArRepository;
import dev.chyken.assembl_ar.model.Component;
import dev.chyken.assembl_ar.model.ComponentRegistration;
import dev.chyken.assembl_ar.model.OrbDescriptorData;
import dev.chyken.assembl_ar.model.ReferenceImageMetadata;
import dev.chyken.assembl_ar.model.SpatialMetadata;
import dev.chyken.assembl_ar.ui.AdminReferenceCaptureFrame;
import dev.chyken.assembl_ar.ui.UiUtils;
import dev.chyken.assembl_ar.vision.AdminDescriptorBlobCodec;
import dev.chyken.assembl_ar.vision.AdminReferenceGeometry;
import dev.chyken.assembl_ar.vision.VisionConfig;
import dev.chyken.assembl_ar.vision.VisionPoint;

public final class AdminRegistrationActivity extends AppCompatActivity {
    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    private final List<File> capturedFiles = new ArrayList<>();
    private final List<List<VisionPoint>> capturedCorners = new ArrayList<>();
    private final List<Double> capturedAzimuths = new ArrayList<>();
    private final String sessionId = UUID.randomUUID().toString();
    private final ActivityResultLauncher<String> cameraPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    startCamera();
                } else {
                    Toast.makeText(this, R.string.camera_permission_title, Toast.LENGTH_LONG).show();
                }
            });

    private AssemblArRepository repository;
    private AdminReferenceCaptureFrame captureFrame;
    private PreviewView previewView;
    private ImageCapture imageCapture;
    private TextView captureCount;
    private LinearLayout thumbnails;
    private TextInputEditText nameInput;
    private TextInputEditText descriptionInput;
    private TextInputEditText purposeInput;
    private TextInputEditText usageInput;
    private TextInputEditText manualPageInput;
    private TextInputEditText widthInput;
    private TextInputEditText heightInput;
    private TextInputEditText depthInput;
    private Component selectedComponent;
    private boolean captureInFlight;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiUtils.configureStandardWindow(this);
        setContentView(R.layout.activity_admin_registration);
        UiUtils.applyStandardInsets(this);
        UiUtils.setupToolbar(this, getString(R.string.administrator_tools));
        repository = AssemblArRepository.getInstance(this);
        bindViews();
        configureTabletCaptureFrame();
        captureFrame.setCaptureAzimuthDegrees(
                AdminReferenceGeometry.azimuthForCaptureIndex(0));
        updateCaptureCount();
        nameInput.setFocusable(false);
        nameInput.setOnClickListener(view -> chooseComponent());
        findViewById(R.id.captureAngleButton).setOnClickListener(view -> captureAngle());
        findViewById(R.id.saveComponentButton).setOnClickListener(view -> compileAndSave());

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA);
        }
    }

    private void bindViews() {
        captureFrame = findViewById(R.id.adminCaptureFrame);
        previewView = findViewById(R.id.adminPreviewView);
        captureCount = findViewById(R.id.captureCountLabel);
        thumbnails = findViewById(R.id.captureThumbnails);
        nameInput = findViewById(R.id.componentNameInput);
        descriptionInput = findViewById(R.id.componentDescriptionInput);
        purposeInput = findViewById(R.id.componentPurposeInput);
        usageInput = findViewById(R.id.componentUsageInput);
        manualPageInput = findViewById(R.id.componentManualPageInput);
        widthInput = findViewById(R.id.componentWidthInput);
        heightInput = findViewById(R.id.componentHeightInput);
        depthInput = findViewById(R.id.componentDepthInput);
        TextWatcher dimensionWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                updateCaptureGeometry();
            }
            @Override public void afterTextChanged(Editable value) {}
        };
        widthInput.addTextChangedListener(dimensionWatcher);
        heightInput.addTextChangedListener(dimensionWatcher);
    }

    private void configureTabletCaptureFrame() {
        if (!UiUtils.isTablet(this)) {
            return;
        }
        LinearLayout.LayoutParams params =
                (LinearLayout.LayoutParams) captureFrame.getLayoutParams();
        params.width = getResources().getDimensionPixelSize(
                R.dimen.tablet_admin_capture_width);
        params.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        captureFrame.setLayoutParams(params);
    }

    private void chooseComponent() {
        if (!capturedFiles.isEmpty()) {
            Toast.makeText(this,
                    "The component and dimensions are locked after capture. Reopen this screen to change them.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        List<Component> components = repository.getComponents();
        String[] names = new String[components.size()];
        for (int index = 0; index < components.size(); index++) {
            names[index] = components.get(index).getName();
        }
        new AlertDialog.Builder(this)
                .setTitle("Select a registered NEOBOT component")
                .setItems(names, (dialog, index) -> selectComponent(components.get(index)))
                .show();
    }

    private void selectComponent(Component component) {
        selectedComponent = component;
        nameInput.setText(component.getName());
        descriptionInput.setText(component.getDescription());
        purposeInput.setText(component.getPurpose());
        usageInput.setText(component.getAssemblyUsage());
        manualPageInput.setText(String.valueOf(component.getManualReference().getPageStart()));
        SpatialMetadata spatial = repository.getSpatialMetadata(component.getId());
        if (spatial != null && spatial.getWidthMillimeters() > 0) {
            widthInput.setText(formatNumber(spatial.getWidthMillimeters()));
            heightInput.setText(formatNumber(spatial.getHeightMillimeters()));
            depthInput.setText(formatNumber(spatial.getDepthMillimeters()));
        } else {
            widthInput.setText("");
            heightInput.setText("");
            depthInput.setText("");
        }
        updateCaptureGeometry();
        updateSaveEnabled();
    }

    private void startCamera() {
        if (previewView.getWidth() <= 0 || previewView.getHeight() <= 0) {
            previewView.post(this::startCamera);
            return;
        }
        ListenableFuture<ProcessCameraProvider> providerFuture =
                ProcessCameraProvider.getInstance(this);
        providerFuture.addListener(() -> {
            try {
                ProcessCameraProvider provider = providerFuture.get();
                ResolutionSelector selector = new ResolutionSelector.Builder()
                        .setAspectRatioStrategy(
                                AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .build();
                Preview preview = new Preview.Builder()
                        .setResolutionSelector(selector)
                        .build();
                imageCapture = new ImageCapture.Builder()
                        .setResolutionSelector(selector)
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                provider.unbindAll();
                UseCaseGroup.Builder group = new UseCaseGroup.Builder()
                        .addUseCase(preview)
                        .addUseCase(imageCapture);
                ViewPort viewPort = previewView.getViewPort();
                if (viewPort != null) {
                    group.setViewPort(viewPort);
                }
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, group.build());
            } catch (Exception failure) {
                Toast.makeText(this, "Unable to open the rear camera.", Toast.LENGTH_LONG).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void captureAngle() {
        ImageCapture capture = imageCapture;
        if (capture == null || selectedComponent == null || captureInFlight) {
            return;
        }
        List<VisionPoint> guideCorners = captureFrame.getNormalizedGuideCorners();
        if (guideCorners.size() != 4) {
            Toast.makeText(this, "Wait for the camera guide, then try again.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        View captureButton = findViewById(R.id.captureAngleButton);
        captureInFlight = true;
        captureButton.setEnabled(false);
        updateSaveEnabled();
        int captureIndex = capturedFiles.size();
        double captureAzimuth = AdminReferenceGeometry.azimuthForCaptureIndex(captureIndex);
        File directory = new File(getFilesDir(), "component_references/" + sessionId);
        if (!directory.exists() && !directory.mkdirs()) {
            captureInFlight = false;
            updateCaptureGeometry();
            Toast.makeText(this, "Unable to create local capture storage.", Toast.LENGTH_LONG).show();
            return;
        }
        File output = new File(directory,
                String.format(Locale.US, "angle_%02d.jpg", captureIndex + 1));
        ImageCapture.OutputFileOptions options = new ImageCapture.OutputFileOptions.Builder(output).build();
        capture.takePicture(options, cameraExecutor, new ImageCapture.OnImageSavedCallback() {
            @Override
            public void onImageSaved(@NonNull ImageCapture.OutputFileResults results) {
                capturedFiles.add(output);
                capturedCorners.add(guideCorners);
                capturedAzimuths.add(captureAzimuth);
                Bitmap thumbnail = decodeSampledThumbnail(
                        output, UiUtils.dp(AdminRegistrationActivity.this, 68));
                runOnUiThread(() -> {
                    if (thumbnail != null) {
                        addThumbnail(thumbnail);
                    }
                    captureFrame.setCaptureAzimuthDegrees(
                            AdminReferenceGeometry.azimuthForCaptureIndex(capturedFiles.size()));
                    captureInFlight = false;
                    widthInput.setEnabled(false);
                    heightInput.setEnabled(false);
                    updateCaptureCount();
                });
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                runOnUiThread(() -> {
                    captureInFlight = false;
                    updateCaptureGeometry();
                    Toast.makeText(AdminRegistrationActivity.this,
                            "Capture failed. Hold steady and try again.", Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private static Bitmap decodeSampledThumbnail(File file, int targetSize) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        int sampleSize = 1;
        while (bounds.outWidth / (sampleSize * 2) >= targetSize
                && bounds.outHeight / (sampleSize * 2) >= targetSize) {
            sampleSize *= 2;
        }
        BitmapFactory.Options sampled = new BitmapFactory.Options();
        sampled.inSampleSize = sampleSize;
        sampled.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), sampled);
    }

    private void addThumbnail(Bitmap bitmap) {
        ImageView image = new ImageView(this);
        int size = UiUtils.dp(this, 68);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
        params.setMarginEnd(UiUtils.dp(this, 8));
        image.setLayoutParams(params);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setImageBitmap(bitmap);
        image.setContentDescription("Captured component angle " + capturedFiles.size());
        thumbnails.addView(image);
    }

    private void updateCaptureCount() {
        captureCount.setText(getString(
                R.string.captures_format,
                capturedFiles.size(),
                captureLabel(capturedFiles.size())));
        updateCaptureGeometry();
        updateSaveEnabled();
    }

    private void updateCaptureGeometry() {
        double width = positiveNumber(widthInput);
        double height = positiveNumber(heightInput);
        boolean ready = selectedComponent != null && width > 0.0 && height > 0.0;
        if (ready) {
            captureFrame.setComponentAspectRatio(width / height);
        }
        View captureButton = findViewById(R.id.captureAngleButton);
        if (captureButton != null) {
            captureButton.setEnabled(ready && !captureInFlight);
        }
    }

    private void updateSaveEnabled() {
        findViewById(R.id.saveComponentButton).setEnabled(
                selectedComponent != null && capturedFiles.size() >= 3 && !captureInFlight);
    }

    private void compileAndSave() {
        if (selectedComponent == null || capturedFiles.size() < 3 || captureInFlight) {
            return;
        }
        String description = UiUtils.nonBlank(descriptionInput.getText());
        String purpose = UiUtils.nonBlank(purposeInput.getText());
        String usage = UiUtils.nonBlank(usageInput.getText());
        double width = positiveNumber(widthInput);
        double height = positiveNumber(heightInput);
        double depth = nonNegativeNumber(depthInput);
        if (description.isEmpty() || purpose.isEmpty() || usage.isEmpty()
                || width <= 0 || height <= 0 || depth < 0) {
            Toast.makeText(this, "Complete the metadata and measured dimensions.", Toast.LENGTH_LONG).show();
            return;
        }
        findViewById(R.id.saveComponentButton).setEnabled(false);
        cameraExecutor.execute(() -> {
            try {
                if (!OpenCVLoader.initLocal()) {
                    throw new IllegalStateException("OpenCV native runtime unavailable");
                }
                saveRegistration(description, purpose, usage, width, height, depth);
                runOnUiThread(() -> {
                    Toast.makeText(this, R.string.reference_compile_success,
                            Toast.LENGTH_LONG).show();
                    finish();
                });
            } catch (Throwable failure) {
                runOnUiThread(() -> {
                    findViewById(R.id.saveComponentButton).setEnabled(true);
                    Toast.makeText(this, "Could not compile references: " + failure.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void saveRegistration(String description, String purpose, String usage,
                                  double width, double height, double depth) throws IOException {
        VisionConfig config = VisionConfig.defaults();
        List<byte[]> descriptorBlobs = new ArrayList<>();
        List<int[]> sizes = new ArrayList<>();
        List<Long> viewHashes = new ArrayList<>();
        for (int index = 0; index < capturedFiles.size(); index++) {
            File file = capturedFiles.get(index);
            Mat grayscale = Imgcodecs.imread(file.getAbsolutePath(), Imgcodecs.IMREAD_GRAYSCALE);
            Mat mask = new Mat();
            try {
                if (grayscale.empty()) {
                    throw new IOException("Unable to decode " + file.getName());
                }
                mask.release();
                mask = AdminReferenceGeometry.createDescriptorMask(
                        grayscale.cols(), grayscale.rows(), capturedCorners.get(index));
                try {
                    descriptorBlobs.add(AdminDescriptorBlobCodec.computeAndEncode(
                            grayscale, mask, config));
                } catch (IllegalArgumentException weakCapture) {
                    throw new IllegalArgumentException(
                            captureLabel(index) + " needs to be sharper, closer, or better lit: "
                                    + weakCapture.getMessage(), weakCapture);
                }
                sizes.add(new int[]{grayscale.cols(), grayscale.rows()});
                long viewHash = perceptualHash(grayscale);
                for (long previousHash : viewHashes) {
                    if (Long.bitCount(previousHash ^ viewHash) < 5) {
                        throw new IllegalArgumentException(
                                "Two captures are nearly identical. Capture distinct front, left-oblique, "
                                        + "and right-oblique views.");
                    }
                }
                viewHashes.add(viewHash);
            } finally {
                mask.release();
                grayscale.release();
            }
        }

        long now = System.currentTimeMillis();
        String registrationId = "registration_" + sessionId;
        String manualReference = "Personal Robot Kit Manual, page "
                + UiUtils.nonBlank(manualPageInput.getText());
        repository.saveComponentRegistration(new ComponentRegistration(
                registrationId, selectedComponent.getId(), "local_authorized_admin",
                selectedComponent.getName(), description, purpose, usage, manualReference,
                true, now, now));

        for (int index = 0; index < capturedFiles.size(); index++) {
            File file = capturedFiles.get(index);
            String imageId = registrationId + "_angle_" + (index + 1);
            int[] size = sizes.get(index);
            repository.saveReferenceImage(new ReferenceImageMetadata(
                    imageId, registrationId, selectedComponent.getId(), file.getAbsolutePath(),
                    captureLabel(index), capturedAzimuths.get(index), 0.0,
                    0.0, "indoor-controlled", false, size[0], size[1], sha256(file), now));
            byte[] blob = descriptorBlobs.get(index);
            repository.saveOrbDescriptor(new OrbDescriptorData(
                    imageId + "_orb", imageId,
                    "ORB/OpenCV 4.12.0; ASOR-3; CLAHE-1; ROI-2",
                    "{\"features\":1200,\"scaleFactor\":1.2,\"levels\":8,"
                            + "\"claheClipLimit\":2.0,\"claheTileGrid\":[8,8],"
                            + "\"photometricVariants\":[\"normalized\",\"inverted\"],"
                            + "\"canonicalGuide\":\"v2\","
                            + "\"canonicalCorners\":"
                            + cornersJson(capturedCorners.get(index)) + "}",
                    0, 0, 0, 0, new byte[0], blob));
        }
        repository.saveSpatialMetadata(new SpatialMetadata(
                selectedComponent.getId(), width, height, depth,
                "component_planar_top_left_origin",
                "[[0,0,0],[1,0,0],[1,1,0],[0,1,0]]",
                "{\"expectedOrientationDegrees\":0,\"toleranceDegrees\":25}",
                new byte[0], modelFor(selectedComponent.getId()), now));
    }

    private static long perceptualHash(Mat grayscale) {
        Mat reduced = new Mat();
        try {
            Imgproc.resize(grayscale, reduced, new Size(9, 8), 0.0, 0.0, Imgproc.INTER_AREA);
            byte[] pixels = new byte[9 * 8];
            reduced.get(0, 0, pixels);
            long hash = 0L;
            int bit = 0;
            for (int row = 0; row < 8; row++) {
                for (int column = 0; column < 8; column++) {
                    int left = pixels[row * 9 + column] & 0xff;
                    int right = pixels[row * 9 + column + 1] & 0xff;
                    if (left > right) {
                        hash |= 1L << bit;
                    }
                    bit++;
                }
            }
            return hash;
        } finally {
            reduced.release();
        }
    }

    private static String captureLabel(int index) {
        switch (index) {
            case 0: return "Front view";
            case 1: return "Left-oblique view";
            case 2: return "Right-oblique view";
            default: return "Additional view " + (index + 1);
        }
    }

    private static String cornersJson(List<VisionPoint> corners) {
        StringBuilder json = new StringBuilder("[");
        for (int index = 0; index < corners.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            VisionPoint point = corners.get(index);
            json.append(String.format(Locale.US, "[%.8f,%.8f]",
                    point.getX(), point.getY()));
        }
        return json.append(']').toString();
    }

    private static String sha256(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileInputStream stream = new FileInputStream(file)) {
                byte[] buffer = new byte[16 * 1024];
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    digest.update(buffer, 0, count);
                }
            }
            return Base64.encodeToString(digest.digest(), Base64.NO_WRAP);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String modelFor(String componentId) {
        if (componentId.contains("motor")) return "models/motor_ghost.obj";
        if (componentId.contains("screw") || componentId.contains("nut")
                || componentId.contains("standoff")) return "models/screw_ghost.obj";
        return "models/board_ghost.obj";
    }

    private static String formatNumber(double value) {
        return String.format(Locale.getDefault(), "%.1f", value);
    }

    private static double positiveNumber(TextInputEditText input) {
        double result = nonNegativeNumber(input);
        return result > 0 ? result : -1;
    }

    private static double nonNegativeNumber(TextInputEditText input) {
        try {
            double value = Double.parseDouble(UiUtils.nonBlank(input.getText()));
            return Double.isFinite(value) && value >= 0 ? value : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    @Override
    protected void onDestroy() {
        cameraExecutor.shutdownNow();
        super.onDestroy();
    }
}
