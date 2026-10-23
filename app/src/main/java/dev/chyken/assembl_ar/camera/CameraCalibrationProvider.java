package dev.chyken.assembl_ar.camera;

import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.graphics.Matrix;
import android.util.Size;
import android.util.SizeF;

import androidx.annotation.OptIn;
import androidx.camera.camera2.interop.Camera2CameraInfo;
import androidx.camera.camera2.interop.ExperimentalCamera2Interop;
import androidx.camera.core.CameraInfo;

import dev.chyken.assembl_ar.vision.AssemblyVisionAnalyzer;
import dev.chyken.assembl_ar.vision.CameraCalibration;

/** Converts Camera2 intrinsic metadata into the exact cropped/rotated analyzer coordinates. */
public final class CameraCalibrationProvider {
    private final float[] intrinsicCalibration;
    private final Rect activeArray;
    private final Rect preCorrectionActiveArray;
    private final Float focalLengthMillimeters;
    private final SizeF sensorSizeMillimeters;
    private final Size pixelArraySize;
    private volatile CameraCalibration.Source lastSource = CameraCalibration.Source.UNSPECIFIED;

    private CameraCalibrationProvider(float[] intrinsicCalibration, Rect activeArray,
                                      Rect preCorrectionActiveArray,
                                      Float focalLengthMillimeters,
                                      SizeF sensorSizeMillimeters,
                                      Size pixelArraySize) {
        this.intrinsicCalibration = intrinsicCalibration == null
                ? null : intrinsicCalibration.clone();
        this.activeArray = activeArray == null ? null : new Rect(activeArray);
        this.preCorrectionActiveArray = preCorrectionActiveArray == null
                ? null : new Rect(preCorrectionActiveArray);
        this.focalLengthMillimeters = focalLengthMillimeters;
        this.sensorSizeMillimeters = sensorSizeMillimeters;
        this.pixelArraySize = pixelArraySize;
    }

    @OptIn(markerClass = ExperimentalCamera2Interop.class)
    public static CameraCalibrationProvider from(CameraInfo cameraInfo) {
        if (cameraInfo == null) {
            return approximate();
        }
        try {
            Camera2CameraInfo info = Camera2CameraInfo.from(cameraInfo);
            float[] intrinsic = info.getCameraCharacteristic(
                    CameraCharacteristics.LENS_INTRINSIC_CALIBRATION);
            Rect active = info.getCameraCharacteristic(
                    CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            Rect preCorrection = info.getCameraCharacteristic(
                    CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE);
            float[] focalLengths = info.getCameraCharacteristic(
                    CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            SizeF sensorSize = info.getCameraCharacteristic(
                    CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
            Size pixelArray = info.getCameraCharacteristic(
                    CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
            Float focal = focalLengths == null || focalLengths.length == 0
                    ? null : focalLengths[0];
            return new CameraCalibrationProvider(
                    intrinsic, active, preCorrection, focal, sensorSize, pixelArray);
        } catch (RuntimeException unavailableMetadata) {
            return approximate();
        }
    }

    public static CameraCalibrationProvider approximate() {
        return new CameraCalibrationProvider(null, null, null, null, null, null);
    }

    /** Package-visible injection point for deterministic calibration instrumentation tests. */
    static CameraCalibrationProvider fromCharacteristics(
            float[] intrinsicCalibration,
            Rect activeArray,
            Rect preCorrectionActiveArray,
            Float focalLengthMillimeters,
            SizeF sensorSizeMillimeters,
            Size pixelArraySize) {
        return new CameraCalibrationProvider(
                intrinsicCalibration, activeArray, preCorrectionActiveArray,
                focalLengthMillimeters, sensorSizeMillimeters, pixelArraySize);
    }

    /**
     * Source of the most recently completed {@link #forFrame} call. Use the returned
     * calibration's {@link CameraCalibration#getSource()} for an exact frame association.
     */
    public CameraCalibration.Source getLastSource() { return lastSource; }

    public CameraCalibration forFrame(AssemblyVisionAnalyzer.FrameMetadata frame) {
        CameraCalibration.Source metadataSource;
        double fx;
        double fy;
        double skew;
        double cx;
        double cy;
        if (hasIntrinsicCalibration()) {
            metadataSource = CameraCalibration.Source.CAMERA2_INTRINSICS;
            double sensorOriginX = preCorrectionActiveArray.left;
            double sensorOriginY = preCorrectionActiveArray.top;
            double[] transformed = transformSensorCalibration(
                    frame,
                    intrinsicCalibration[0],
                    intrinsicCalibration[1],
                    intrinsicCalibration.length >= 5 ? intrinsicCalibration[4] : 0.0,
                    sensorOriginX + intrinsicCalibration[2],
                    sensorOriginY + intrinsicCalibration[3]);
            fx = transformed[0];
            fy = transformed[1];
            skew = transformed[2];
            cx = transformed[3];
            cy = transformed[4];
        } else if (focalLengthMillimeters != null && sensorSizeMillimeters != null
                && sensorSizeMillimeters.getWidth() > 0f
                && sensorSizeMillimeters.getHeight() > 0f
                && pixelArraySize != null && activeArray != null) {
            metadataSource = CameraCalibration.Source.FOCAL_SENSOR_METADATA;
            double sensorFx = focalLengthMillimeters / sensorSizeMillimeters.getWidth()
                    * pixelArraySize.getWidth();
            double sensorFy = focalLengthMillimeters / sensorSizeMillimeters.getHeight()
                    * pixelArraySize.getHeight();
            double[] transformed = transformSensorCalibration(
                    frame, sensorFx, sensorFy, 0.0, activeArray.exactCenterX(),
                    activeArray.exactCenterY());
            fx = transformed[0];
            fy = transformed[1];
            skew = transformed[2];
            cx = transformed[3];
            cy = transformed[4];
        } else {
            return approximateForFrame(frame,
                    CameraCalibration.Source.APPROXIMATE_MISSING_METADATA);
        }

        cx -= frame.getCropLeft();
        cy -= frame.getCropTop();
        if (frame.arePixelsRotatedToDisplay()) {
            double[] rotated = rotateCalibration(
                    fx, fy, skew, cx, cy,
                    frame.getCropWidth(), frame.getCropHeight(),
                    frame.getRotationDegrees());
            fx = rotated[0];
            fy = rotated[1];
            skew = rotated[2];
            cx = rotated[3];
            cy = rotated[4];
        }
        if (!valid(fx, fy, skew, cx, cy,
                frame.getAnalyzedWidth(), frame.getAnalyzedHeight())) {
            return approximateForFrame(frame,
                    CameraCalibration.Source.APPROXIMATE_INVALID_TRANSFORM);
        }
        // CameraX supplies a processed YUV buffer in the post-distortion active-array system.
        // Camera2's raw Brown-Conrady coefficients therefore must not be applied a second time.
        return publish(CameraCalibration.withSkew(fx, fy, skew, cx, cy), metadataSource);
    }

    private CameraCalibration approximateForFrame(AssemblyVisionAnalyzer.FrameMetadata frame,
            CameraCalibration.Source source) {
        double focal = Math.max(frame.getAnalyzedWidth(), frame.getAnalyzedHeight()) * 1.18;
        return publish(CameraCalibration.pinhole(focal, focal,
                frame.getAnalyzedWidth() * 0.5,
                frame.getAnalyzedHeight() * 0.5), source);
    }

    private CameraCalibration publish(CameraCalibration calibration,
                                      CameraCalibration.Source source) {
        CameraCalibration tagged = calibration.withSource(source);
        lastSource = source;
        return tagged;
    }

    private boolean hasIntrinsicCalibration() {
        // Some tablet HALs publish positive focal values with a zero/invalid
        // optical center. Those placeholders are not a calibrated camera. Prefer
        // focal/sensor metadata (or the tagged approximation) over a K centered
        // on the corner of the image, which destabilizes every solvePnP result.
        return intrinsicCalibration != null && intrinsicCalibration.length >= 4
                && activeArray != null && activeArray.width() > 0 && activeArray.height() > 0
                && preCorrectionActiveArray != null
                && preCorrectionActiveArray.width() > 0
                && preCorrectionActiveArray.height() > 0
                && Float.isFinite(intrinsicCalibration[0]) && Float.isFinite(intrinsicCalibration[1])
                && intrinsicCalibration[0] > 0f && intrinsicCalibration[1] > 0f
                && Float.isFinite(intrinsicCalibration[2]) && Float.isFinite(intrinsicCalibration[3])
                && intrinsicCalibration[2] > 0f && intrinsicCalibration[2] < preCorrectionActiveArray.width()
                && intrinsicCalibration[3] > 0f && intrinsicCalibration[3] < preCorrectionActiveArray.height()
                && (intrinsicCalibration.length < 5 || Float.isFinite(intrinsicCalibration[4]));
    }

    /** Applies CameraX's exact active-sensor crop/scale transform to K before analyzer cropping. */
    private static double[] transformSensorCalibration(
            AssemblyVisionAnalyzer.FrameMetadata frame,
            double sensorFx, double sensorFy, double sensorSkew,
            double sensorCx, double sensorCy) {
        Matrix transform = new Matrix();
        transform.setValues(frame.getSensorToBufferTransform());
        float[] points = new float[]{
                (float) sensorCx, (float) sensorCy,
                (float) (sensorCx + sensorFx), (float) sensorCy,
                (float) (sensorCx + sensorSkew), (float) (sensorCy + sensorFy)
        };
        transform.mapPoints(points);
        return canonicalize(
                points[2] - points[0], points[4] - points[0],
                points[3] - points[1], points[5] - points[1],
                points[0], points[1]);
    }

    /** RQ-decomposes an affine-transformed 2x2 K block into K' and an optical-axis roll. */
    private static double[] canonicalize(double a, double b, double c, double d,
                                         double cx, double cy) {
        double fy = Math.hypot(c, d);
        double determinant = a * d - b * c;
        if (!(fy > 0.0) || !(determinant > 0.0)) {
            return new double[]{Double.NaN, Double.NaN, Double.NaN,
                    Double.NaN, Double.NaN};
        }
        double sin = c / fy;
        double cos = d / fy;
        double fx = determinant / fy;
        double skew = a * sin + b * cos;
        return new double[]{fx, fy, skew, cx, cy};
    }

    private static double[] rotateCalibration(
            double fx, double fy, double skew, double cx, double cy,
            int width, int height, int rotationDegrees) {
        switch (rotationDegrees) {
            case 90:
                return canonicalize(0.0, -fy, fx, skew,
                        height - 1.0 - cy, cx);
            case 180:
                return canonicalize(-fx, -skew, 0.0, -fy,
                        width - 1.0 - cx, height - 1.0 - cy);
            case 270:
                return canonicalize(0.0, fy, -fx, -skew,
                        cy, width - 1.0 - cx);
            case 0:
                return new double[]{fx, fy, skew, cx, cy};
            default:
                return new double[]{Double.NaN, Double.NaN, Double.NaN,
                        Double.NaN, Double.NaN};
        }
    }

    private static boolean valid(double fx, double fy, double skew, double cx, double cy,
                                 int width, int height) {
        return Double.isFinite(fx) && Double.isFinite(fy)
                && Double.isFinite(skew)
                && Double.isFinite(cx) && Double.isFinite(cy)
                && fx > 0.0 && fy > 0.0
                && cx > -width && cx < width * 2.0
                && cy > -height && cy < height * 2.0;
    }
}
