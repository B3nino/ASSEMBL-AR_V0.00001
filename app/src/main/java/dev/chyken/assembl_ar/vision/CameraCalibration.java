package dev.chyken.assembl_ar.vision;

import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDouble;

import java.util.Arrays;

/**
 * Immutable camera intrinsics for the exact pixel coordinate system passed to recognition.
 * Distances returned by solvePnP use the same unit as component dimensions (normally mm).
 */
public final class CameraCalibration {
    /** Provenance of the intrinsics actually used for this frame's pose estimate. */
    public enum Source {
        UNSPECIFIED,
        CAMERA2_INTRINSICS,
        FOCAL_SENSOR_METADATA,
        APPROXIMATE_MISSING_METADATA,
        APPROXIMATE_INVALID_TRANSFORM
    }

    private final double[] cameraMatrix;
    private final double[] distortionCoefficients;
    private final Source source;

    public CameraCalibration(double[] rowMajorCameraMatrix, double[] distortionCoefficients) {
        this(rowMajorCameraMatrix, distortionCoefficients, Source.UNSPECIFIED);
    }

    private CameraCalibration(double[] rowMajorCameraMatrix, double[] distortionCoefficients,
                              Source source) {
        if (rowMajorCameraMatrix == null || rowMajorCameraMatrix.length != 9) {
            throw new IllegalArgumentException("Camera matrix must contain nine row-major values");
        }
        for (double value : rowMajorCameraMatrix) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("Camera matrix values must be finite");
            }
        }
        if (rowMajorCameraMatrix[0] <= 0.0 || rowMajorCameraMatrix[4] <= 0.0
                || Math.abs(rowMajorCameraMatrix[3]) > 1e-9
                || Math.abs(rowMajorCameraMatrix[6]) > 1e-9
                || Math.abs(rowMajorCameraMatrix[7]) > 1e-9
                || Math.abs(rowMajorCameraMatrix[8] - 1.0) > 1e-9) {
            throw new IllegalArgumentException(
                    "Camera matrix must use pinhole form [[fx,s,cx],[0,fy,cy],[0,0,1]]");
        }
        this.cameraMatrix = rowMajorCameraMatrix.clone();
        this.distortionCoefficients = distortionCoefficients == null
                ? new double[0]
                : distortionCoefficients.clone();
        int distortionCount = this.distortionCoefficients.length;
        if (distortionCount != 0 && distortionCount != 4 && distortionCount != 5
                && distortionCount != 8 && distortionCount != 12 && distortionCount != 14) {
            throw new IllegalArgumentException(
                    "OpenCV distortion vectors must contain 0, 4, 5, 8, 12, or 14 values");
        }
        for (double value : this.distortionCoefficients) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("Distortion coefficients must be finite");
            }
        }
        if (source == null) throw new IllegalArgumentException("Calibration source is required");
        this.source = source;
    }

    public static CameraCalibration pinhole(double fx, double fy, double cx, double cy,
                                            double... distortionCoefficients) {
        return withSkew(fx, fy, 0.0, cx, cy, distortionCoefficients);
    }

    /** Camera2-compatible pinhole calibration including the optional sensor-plane skew. */
    public static CameraCalibration withSkew(double fx, double fy, double skew,
                                             double cx, double cy,
                                             double... distortionCoefficients) {
        return new CameraCalibration(new double[] {
                fx, skew, cx,
                0.0, fy, cy,
                0.0, 0.0, 1.0
        }, distortionCoefficients);
    }

    public double[] getCameraMatrixRowMajor() {
        return cameraMatrix.clone();
    }

    public double[] getDistortionCoefficients() {
        return distortionCoefficients.clone();
    }

    public Source getSource() { return source; }

    /** Returns the same intrinsics with per-frame provenance attached by the camera provider. */
    public CameraCalibration withSource(Source source) {
        return new CameraCalibration(cameraMatrix, distortionCoefficients, source);
    }

    public CameraCalibration scaled(double scaleX, double scaleY) {
        if (!(scaleX > 0.0) || !(scaleY > 0.0)) {
            throw new IllegalArgumentException("Calibration scales must be positive");
        }
        double[] scaled = cameraMatrix.clone();
        scaled[0] *= scaleX;
        scaled[1] *= scaleX;
        scaled[2] *= scaleX;
        scaled[3] *= scaleY;
        scaled[4] *= scaleY;
        scaled[5] *= scaleY;
        return new CameraCalibration(scaled, distortionCoefficients, source);
    }

    /** Returns intrinsics for an unscaled crop whose origin moved by (leftPx, topPx). */
    public CameraCalibration cropped(int leftPx, int topPx) {
        if (leftPx < 0 || topPx < 0) {
            throw new IllegalArgumentException("Crop offsets cannot be negative");
        }
        double[] cropped = cameraMatrix.clone();
        cropped[2] -= leftPx;
        cropped[5] -= topPx;
        return new CameraCalibration(cropped, distortionCoefficients, source);
    }

    /**
     * OpenCV's standard calib3d pinhole model requires zero skew. Callers pair this matrix with
     * {@link #deskew(VisionPoint)} so Camera2's optional skew is retained exactly rather than
     * silently discarded.
     */
    Mat toOpenCvCameraMatrix() {
        Mat matrix = new Mat(3, 3, CvType.CV_64FC1);
        double[] zeroSkew = cameraMatrix.clone();
        zeroSkew[1] = 0.0;
        matrix.put(0, 0, zeroSkew);
        return matrix;
    }

    VisionPoint deskew(VisionPoint point) {
        double normalizedY = (point.getY() - cameraMatrix[5]) / cameraMatrix[4];
        return new VisionPoint(
                point.getX() - cameraMatrix[1] * normalizedY,
                point.getY());
    }

    MatOfDouble toDistortionMatrix() {
        return new MatOfDouble(distortionCoefficients);
    }

    @Override
    public String toString() {
        return "CameraCalibration{cameraMatrix=" + Arrays.toString(cameraMatrix)
                + ", distortion=" + Arrays.toString(distortionCoefficients)
                + ", source=" + source + '}';
    }
}
