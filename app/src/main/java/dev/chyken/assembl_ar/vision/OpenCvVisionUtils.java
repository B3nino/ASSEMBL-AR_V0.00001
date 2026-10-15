package dev.chyken.assembl_ar.vision;

import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.features2d.ORB;
import org.opencv.imgproc.CLAHE;
import org.opencv.imgproc.Imgproc;

final class OpenCvVisionUtils {
    private OpenCvVisionUtils() {}

    static ORB createOrb(VisionConfig config) {
        return ORB.create(
                config.getOrbFeatureCount(),
                config.getOrbScaleFactor(),
                config.getOrbPyramidLevels(),
                config.getOrbEdgeThreshold(),
                config.getOrbFirstLevel(),
                config.getOrbWtaK(),
                ORB.HARRIS_SCORE,
                config.getOrbPatchSize(),
                config.getOrbFastThreshold());
    }

    static CLAHE createClahe(VisionConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("VisionConfig is required");
        }
        return Imgproc.createCLAHE(
                config.getClaheClipLimit(),
                new Size(config.getClaheTileGridWidth(), config.getClaheTileGridHeight()));
    }

    static void normalizeForOrb(Mat grayscale, Mat destination, CLAHE clahe) {
        if (grayscale == null || grayscale.empty() || grayscale.type() != CvType.CV_8UC1) {
            throw new IllegalArgumentException("A non-empty CV_8UC1 image is required");
        }
        if (destination == null || clahe == null) {
            throw new IllegalArgumentException("Destination and CLAHE are required");
        }
        clahe.apply(grayscale, destination);
    }
}
