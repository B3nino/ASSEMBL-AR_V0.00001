package dev.chyken.assembl_ar.vision;

import android.graphics.Rect;

import androidx.annotation.Nullable;
import androidx.camera.core.ImageProxy;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * Finds the red positive lead/terminal marker directly in a CameraX YUV frame. The complementary
 * negative endpoint is inferred from the authored two-anchor rule; this avoids treating a dark
 * chassis, tire, or shadow as a black wire while still distinguishing correct from swapped leads.
 */
final class YuvPolarityDetector {
    private static final int MINIMUM_RED_SAMPLES = 3;

    private YuvPolarityDetector() {
    }

    @Nullable
    static RecognitionRequest.PolarityObservation detect(
            ImageProxy image,
            Rect crop,
            int rotationDegrees,
            boolean pixelsRotatedToDisplay,
            List<VisionPoint> projectedCorners,
            RecognitionRequest.PolarityRule rule,
            int analyzedWidth,
            int analyzedHeight) {
        if (image == null || crop == null || rule == null
                || projectedCorners == null || projectedCorners.size() != 4
                || image.getPlanes().length < 3) {
            return null;
        }

        VisionPoint expectedPositive = PlanarCoordinateMapper.projectUnitSquare(
                projectedCorners, rule.getPositiveAnchor());
        VisionPoint expectedNegative = PlanarCoordinateMapper.projectUnitSquare(
                projectedCorners, rule.getNegativeAnchor());
        if (expectedPositive == null || expectedNegative == null) {
            return null;
        }

        ImageProxy.PlaneProxy yPlane = image.getPlanes()[0];
        ImageProxy.PlaneProxy uPlane = image.getPlanes()[1];
        ImageProxy.PlaneProxy vPlane = image.getPlanes()[2];
        ByteBuffer yBuffer = yPlane.getBuffer().duplicate();
        ByteBuffer uBuffer = uPlane.getBuffer().duplicate();
        ByteBuffer vBuffer = vPlane.getBuffer().duplicate();
        int yBase = yBuffer.position();
        int uBase = uBuffer.position();
        int vBase = vBuffer.position();

        double componentDiagonal = projectedCorners.get(0)
                .distanceTo(projectedCorners.get(2));
        double anchorSeparation = expectedPositive.distanceTo(expectedNegative);
        if (!(componentDiagonal > 1.0) || !(anchorSeparation >= 6.0)) {
            return null;
        }
        // Keep the search neighborhoods disjoint and component-local. The earlier whole-frame
        // radius could latch onto the board LED or a red reflection far from the actual terminal.
        double searchRadius = Math.max(3.0, Math.min(36.0,
                Math.min(componentDiagonal * 0.08, anchorSeparation * 0.38)));
        double searchRadiusSquared = searchRadius * searchRadius;
        int samplingStep = Math.max(2, Math.min(crop.width(), crop.height()) / 420);
        Accumulator nearPositive = new Accumulator();
        Accumulator nearNegative = new Accumulator();
        EndpointEvidence positiveEndpoint = new EndpointEvidence();
        EndpointEvidence negativeEndpoint = new EndpointEvidence();

        for (int sourceY = crop.top; sourceY < crop.bottom; sourceY += samplingStep) {
            for (int sourceX = crop.left; sourceX < crop.right; sourceX += samplingStep) {
                int luminance = sample(yBuffer, yBase, yPlane, sourceX, sourceY);
                int chromaU = sample(uBuffer, uBase, uPlane, sourceX / 2, sourceY / 2);
                int chromaV = sample(vBuffer, vBase, vPlane, sourceX / 2, sourceY / 2);
                VisionPoint analyzedPoint = toAnalyzedPoint(
                        sourceX - crop.left,
                        sourceY - crop.top,
                        crop.width(),
                        crop.height(),
                        rotationDegrees,
                        pixelsRotatedToDisplay);
                double positiveDistance = squaredDistance(analyzedPoint, expectedPositive);
                double negativeDistance = squaredDistance(analyzedPoint, expectedNegative);
                if (positiveDistance <= searchRadiusSquared) {
                    positiveEndpoint.add(luminance, chromaU, chromaV);
                }
                if (negativeDistance <= searchRadiusSquared) {
                    negativeEndpoint.add(luminance, chromaU, chromaV);
                }
                if (!isRed(luminance, chromaU, chromaV)) {
                    continue;
                }
                double weight = Math.max(1.0, chromaV - chromaU)
                        * Math.max(1.0, luminance + 24.0);
                if (positiveDistance <= searchRadiusSquared) {
                    nearPositive.add(analyzedPoint, weight);
                }
                if (negativeDistance <= searchRadiusSquared) {
                    nearNegative.add(analyzedPoint, weight);
                }
            }
        }

        Accumulator selected = nearPositive.weight >= nearNegative.weight
                ? nearPositive : nearNegative;
        Accumulator competing = selected == nearPositive ? nearNegative : nearPositive;
        if (selected.count < MINIMUM_RED_SAMPLES || !(selected.weight > 0.0)) {
            return null;
        }
        if (competing.count >= MINIMUM_RED_SAMPLES
                && competing.weight > selected.weight * 0.45) {
            // Red evidence at both endpoints is ambiguous rather than silently "correct".
            return null;
        }
        VisionPoint observedPositive = selected.center();
        boolean redAtExpectedPositive = observedPositive.distanceTo(expectedPositive)
                <= observedPositive.distanceTo(expectedNegative);
        EndpointEvidence inferredNegativeEvidence = redAtExpectedPositive
                ? negativeEndpoint : positiveEndpoint;
        if (!inferredNegativeEvidence.hasDarkLeadBoundary()) {
            return null;
        }
        VisionPoint inferredNegative = redAtExpectedPositive
                ? expectedNegative : expectedPositive;
        if (observedPositive.distanceTo(inferredNegative) < 1.0) {
            inferredNegative = new VisionPoint(
                    Math.min(analyzedWidth - 1.0, inferredNegative.getX() + 1.0),
                    inferredNegative.getY());
        }
        return new RecognitionRequest.PolarityObservation(
                observedPositive, inferredNegative);
    }

    private static int sample(ByteBuffer buffer, int base,
                              ImageProxy.PlaneProxy plane, int x, int y) {
        long index = (long) base + (long) y * plane.getRowStride()
                + (long) x * plane.getPixelStride();
        if (index < base || index >= buffer.limit()) {
            return 128;
        }
        return buffer.get((int) index) & 0xff;
    }

    private static boolean isRed(int y, int u, int v) {
        return y >= 28 && v >= 145 && u <= 148
                && v - u >= 42 && v - y >= 22;
    }

    private static VisionPoint toAnalyzedPoint(
            int cropX, int cropY, int cropWidth, int cropHeight,
            int rotationDegrees, boolean rotate) {
        if (!rotate || rotationDegrees == 0) {
            return new VisionPoint(cropX, cropY);
        }
        switch (rotationDegrees) {
            case 90:
                return new VisionPoint(cropHeight - 1.0 - cropY, cropX);
            case 180:
                return new VisionPoint(
                        cropWidth - 1.0 - cropX, cropHeight - 1.0 - cropY);
            case 270:
                return new VisionPoint(cropY, cropWidth - 1.0 - cropX);
            default:
                return new VisionPoint(cropX, cropY);
        }
    }

    private static double squaredDistance(VisionPoint first, VisionPoint second) {
        double x = first.getX() - second.getX();
        double y = first.getY() - second.getY();
        return x * x + y * y;
    }

    private static final class Accumulator {
        double weightedX;
        double weightedY;
        double weight;
        int count;

        void add(VisionPoint point, double sampleWeight) {
            weightedX += point.getX() * sampleWeight;
            weightedY += point.getY() * sampleWeight;
            weight += sampleWeight;
            count++;
        }

        VisionPoint center() {
            return new VisionPoint(weightedX / weight, weightedY / weight);
        }
    }

    /** Requires a locally dark, roughly neutral lead adjoining a brighter terminal/background. */
    private static final class EndpointEvidence {
        int darkNeutralSamples;
        int contrastingSamples;

        void add(int luminance, int chromaU, int chromaV) {
            if (luminance <= 78 && Math.abs(chromaU - 128) <= 34
                    && Math.abs(chromaV - 128) <= 34) {
                darkNeutralSamples++;
            }
            if (luminance >= 105) {
                contrastingSamples++;
            }
        }

        boolean hasDarkLeadBoundary() {
            return darkNeutralSamples >= 2 && contrastingSamples >= 2;
        }
    }
}
