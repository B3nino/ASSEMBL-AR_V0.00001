package dev.chyken.assembl_ar.render;

import java.util.Objects;

/**
 * Immutable pinhole-camera projection for the exact pixel coordinate system analyzed by vision.
 *
 * <p>The supplied intrinsics must describe the image after any crop and rotation performed before
 * recognition. {@link PreviewScale#FILL_CENTER} matches CameraX PreviewView's {@code fillCenter}
 * behavior by applying the same centered aspect-ratio crop to the principal point and focal
 * lengths. Non-linear lens distortion is intentionally not modeled by the OpenGL projection; use
 * rectified intrinsics or negligible distortion for the closest registration.</p>
 */
public final class ArCameraProjection {
    public enum PreviewScale {
        /** Scale uniformly until the preview fills the view, cropping equally at opposite edges. */
        FILL_CENTER,
        /** Scale uniformly until the complete frame fits, letterboxing opposite edges. */
        FIT_CENTER,
        /** Scale each axis independently to exactly fill the view. */
        STRETCH
    }

    private static final float DEFAULT_NEAR = 0.05f;
    private static final float DEFAULT_FAR = 100f;

    private final int analyzedWidth;
    private final int analyzedHeight;
    private final double focalX;
    private final double focalY;
    private final double skew;
    private final double principalX;
    private final double principalY;
    private final PreviewScale previewScale;
    private final boolean mirroredHorizontally;
    private final float nearPlane;
    private final float farPlane;

    private ArCameraProjection(int analyzedWidth, int analyzedHeight,
                               double focalX, double focalY, double skew,
                               double principalX, double principalY,
                               PreviewScale previewScale, boolean mirroredHorizontally,
                               float nearPlane, float farPlane) {
        if (analyzedWidth <= 0 || analyzedHeight <= 0) {
            throw new IllegalArgumentException("Analyzed frame dimensions must be positive");
        }
        if (!(focalX > 0.0) || !(focalY > 0.0)
                || !Double.isFinite(focalX) || !Double.isFinite(focalY)
                || !Double.isFinite(skew)
                || !Double.isFinite(principalX) || !Double.isFinite(principalY)) {
            throw new IllegalArgumentException("Camera intrinsics must be finite with positive focal lengths");
        }
        if (!(nearPlane > 0f) || !(farPlane > nearPlane)
                || !Float.isFinite(nearPlane) || !Float.isFinite(farPlane)) {
            throw new IllegalArgumentException("Clipping planes must be finite and satisfy 0 < near < far");
        }
        this.analyzedWidth = analyzedWidth;
        this.analyzedHeight = analyzedHeight;
        this.focalX = focalX;
        this.focalY = focalY;
        this.skew = skew;
        this.principalX = principalX;
        this.principalY = principalY;
        this.previewScale = Objects.requireNonNull(previewScale, "previewScale");
        this.mirroredHorizontally = mirroredHorizontally;
        this.nearPlane = nearPlane;
        this.farPlane = farPlane;
    }

    public static ArCameraProjection pinhole(int analyzedWidth, int analyzedHeight,
                                             double focalX, double focalY,
                                             double principalX, double principalY) {
        return new ArCameraProjection(analyzedWidth, analyzedHeight,
                focalX, focalY, 0.0, principalX, principalY,
                PreviewScale.FILL_CENTER, false, DEFAULT_NEAR, DEFAULT_FAR);
    }

    /** Convenience factory for CameraCalibration#getCameraMatrixRowMajor() output. */
    public static ArCameraProjection fromCameraMatrix(int analyzedWidth, int analyzedHeight,
                                                       double[] rowMajorCameraMatrix) {
        if (rowMajorCameraMatrix == null || rowMajorCameraMatrix.length != 9) {
            throw new IllegalArgumentException("Camera matrix must contain nine row-major values");
        }
        for (double value : rowMajorCameraMatrix) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("Camera matrix values must be finite");
            }
        }
        if (Math.abs(rowMajorCameraMatrix[3]) > 1e-9
                || Math.abs(rowMajorCameraMatrix[6]) > 1e-9
                || Math.abs(rowMajorCameraMatrix[7]) > 1e-9
                || Math.abs(rowMajorCameraMatrix[8] - 1.0) > 1e-9) {
            throw new IllegalArgumentException(
                    "Camera matrix must use pinhole form [[fx,s,cx],[0,fy,cy],[0,0,1]]");
        }
        return new ArCameraProjection(analyzedWidth, analyzedHeight,
                rowMajorCameraMatrix[0], rowMajorCameraMatrix[4], rowMajorCameraMatrix[1],
                rowMajorCameraMatrix[2], rowMajorCameraMatrix[5],
                PreviewScale.FILL_CENTER, false, DEFAULT_NEAR, DEFAULT_FAR);
    }

    public ArCameraProjection withPreviewScale(PreviewScale scale) {
        return new ArCameraProjection(analyzedWidth, analyzedHeight,
                focalX, focalY, skew, principalX, principalY,
                scale, mirroredHorizontally, nearPlane, farPlane);
    }

    /** Use for a front-camera preview whose displayed pixels are mirrored. */
    public ArCameraProjection withHorizontalMirror(boolean mirrored) {
        return new ArCameraProjection(analyzedWidth, analyzedHeight,
                focalX, focalY, skew, principalX, principalY,
                previewScale, mirrored, nearPlane, farPlane);
    }

    public ArCameraProjection withClippingPlanes(float near, float far) {
        return new ArCameraProjection(analyzedWidth, analyzedHeight,
                focalX, focalY, skew, principalX, principalY,
                previewScale, mirroredHorizontally, near, far);
    }

    public int getAnalyzedWidth() {
        return analyzedWidth;
    }

    public int getAnalyzedHeight() {
        return analyzedHeight;
    }

    public PreviewScale getPreviewScale() {
        return previewScale;
    }

    /**
     * Creates a column-major OpenGL projection registered to a preview surface of the given size.
     * Exposed publicly to make calibration diagnostics and projection tests straightforward.
     */
    public float[] createOpenGlMatrix(int surfaceWidth, int surfaceHeight) {
        if (surfaceWidth <= 0 || surfaceHeight <= 0) {
            throw new IllegalArgumentException("Surface dimensions must be positive");
        }

        double scaleX = surfaceWidth / (double) analyzedWidth;
        double scaleY = surfaceHeight / (double) analyzedHeight;
        double offsetX = 0.0;
        double offsetY = 0.0;
        if (previewScale != PreviewScale.STRETCH) {
            double uniformScale = previewScale == PreviewScale.FILL_CENTER
                    ? Math.max(scaleX, scaleY)
                    : Math.min(scaleX, scaleY);
            scaleX = uniformScale;
            scaleY = uniformScale;
            offsetX = (surfaceWidth - analyzedWidth * uniformScale) * 0.5;
            offsetY = (surfaceHeight - analyzedHeight * uniformScale) * 0.5;
        }

        double viewFocalX = focalX * scaleX;
        double viewFocalY = focalY * scaleY;
        double viewSkew = skew * scaleX;
        double viewPrincipalX = principalX * scaleX + offsetX;
        double viewPrincipalY = principalY * scaleY + offsetY;
        if (mirroredHorizontally) {
            viewFocalX = -viewFocalX;
            viewSkew = -viewSkew;
            viewPrincipalX = surfaceWidth - viewPrincipalX;
        }

        float[] result = new float[16];
        result[0] = (float) (2.0 * viewFocalX / surfaceWidth);
        // Camera2 K permits u = fx*x + s*y + cx. Model-view conversion flips camera Y,
        // hence the negative row-0/column-1 OpenGL term.
        result[4] = (float) (-2.0 * viewSkew / surfaceWidth);
        result[5] = (float) (2.0 * viewFocalY / surfaceHeight);
        // OpenCV camera coordinates are converted to OpenGL by diag(1,-1,-1,1).
        result[8] = (float) (1.0 - 2.0 * viewPrincipalX / surfaceWidth);
        result[9] = (float) (2.0 * viewPrincipalY / surfaceHeight - 1.0);
        result[10] = -(farPlane + nearPlane) / (farPlane - nearPlane);
        result[11] = -1f;
        result[14] = -(2f * farPlane * nearPlane) / (farPlane - nearPlane);
        return result;
    }
}
