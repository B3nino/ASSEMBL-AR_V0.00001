package dev.chyken.assembl_ar.vision;

import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import java.io.*;
import java.util.*;

/** Bounds raw board photos before ORB extraction; ambiguous photos never become full-frame poses. */
final class BoardPhotoGeometry {
    private BoardPhotoGeometry() { }

    static ComponentReferenceSpec resolve(ComponentReferenceSpec source, byte[] bytes, File cache)
            throws IOException {
        File geometry = cache == null ? null : new File(cache.getPath() + ".quad1");
        double[] corners = null;
        if (geometry != null && geometry.isFile()) {
            try (DataInputStream input = new DataInputStream(new FileInputStream(geometry))) {
                corners = new double[8];
                for (int i = 0; i < 8; i++) corners[i] = input.readDouble();
                return withCorners(source, corners);
            } catch (IOException | IllegalArgumentException stale) { corners = null; }
        }
        MatOfByte encoded = new MatOfByte(bytes);
        Mat image = Imgcodecs.imdecode(encoded, Imgcodecs.IMREAD_GRAYSCALE);
        try {
            if (image.empty()) throw new IOException("Unreadable raw board photo");
            double scale = Math.min(1.0, 960.0 / Math.max(image.cols(), image.rows()));
            if (scale < 1) Imgproc.resize(image, image, new Size(), scale, scale, Imgproc.INTER_AREA);
            corners = detect(image);
        } finally { image.release(); encoded.release(); }
        if (corners == null) throw new IOException("No unambiguous board perimeter in " + source.getJpegAssetPath());
        ComponentReferenceSpec result = withCorners(source, corners);
        if (geometry != null) {
            try (DataOutputStream output = new DataOutputStream(new FileOutputStream(geometry))) {
                for (double value : corners) output.writeDouble(value);
            } catch (IOException ignored) { /* Geometry can be rebuilt when the cache is unwritable. */ }
        }
        return result;
    }

    private static ComponentReferenceSpec withCorners(ComponentReferenceSpec source, double[] c) {
        VisionPoint[] p = new VisionPoint[4];
        for (int i = 0; i < 4; i++) p[i] = new VisionPoint(c[2*i], c[2*i+1]);
        ComponentReferenceSpec.Builder builder = ComponentReferenceSpec.builder(source.getReferenceId(),
                        source.getComponentId(), source.getDisplayName(), source.getPhysicalWidthMm(),
                        source.getPhysicalHeightMm()).jpegAssetPath(source.getJpegAssetPath())
                .maximumImageDimension(source.getMaximumImageDimension()).assemblyStepNumber(1)
                .automaticBoardGeometry(source.isBottomFace()).planarInlierPose(true)
                .fastAcquisitionView(source.isFastAcquisitionView()).polarityAnchorsVerified(false);
        if (source.isBottomFace()) builder.mirroredPlanarReferenceCornersNormalized(p[1],p[0],p[3],p[2]);
        else builder.planarReferenceCornersNormalized(p[0],p[1],p[2],p[3]);
        return builder.build();
    }

    private static double[] detect(Mat gray) {
        Mat blur = new Mat(), edges = new Mat(), hierarchy = new Mat();
        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(5,5));
        double bestArea = 0;
        Point[] best = null;
        try {
            Imgproc.GaussianBlur(gray, blur, new Size(5,5), 0);
            for (int pass = 0; pass < 2; pass++) {
                if (pass == 0) Imgproc.Canny(blur, edges, 40, 120);
                else Imgproc.threshold(blur, edges, 0, 255, Imgproc.THRESH_BINARY | Imgproc.THRESH_OTSU);
                Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, kernel);
                List<MatOfPoint> contours = new ArrayList<>();
                try {
                    Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE);
                    for (MatOfPoint contour : contours) {
                        double area = Math.abs(Imgproc.contourArea(contour));
                        if (area < gray.total() * .06 || area > gray.total() * .94 || area <= bestArea) continue;
                        MatOfPoint2f curve = new MatOfPoint2f(contour.toArray()), approx = new MatOfPoint2f();
                        try {
                            Imgproc.approxPolyDP(curve, approx, .025 * Imgproc.arcLength(curve, true), true);
                            Point[] points = approx.toArray();
                            if (points.length != 4) continue;
                            MatOfPoint polygon = new MatOfPoint(points);
                            boolean convex;
                            try { convex = Imgproc.isContourConvex(polygon); } finally { polygon.release(); }
                            if (!convex) continue;
                            boolean atBorder = false;
                            for (Point p : points) if (p.x <= 2 || p.y <= 2
                                    || p.x >= gray.cols()-3 || p.y >= gray.rows()-3) atBorder = true;
                            if (atBorder) continue;
                            double shortest = Double.MAX_VALUE, longest = 0;
                            for (int i=0;i<4;i++) {
                                double length = Math.hypot(points[i].x-points[(i+1)%4].x,
                                        points[i].y-points[(i+1)%4].y);
                                shortest=Math.min(shortest,length); longest=Math.max(longest,length);
                            }
                            if (shortest < 20 || longest / shortest > 4) continue;
                            best = points; bestArea = area;
                        } finally { curve.release(); approx.release(); }
                    }
                } finally { for (Mat contour : contours) contour.release(); }
            }
            if (best == null) return null;
            final double cx = Arrays.stream(best).mapToDouble(p -> p.x).average().orElse(0);
            final double cy = Arrays.stream(best).mapToDouble(p -> p.y).average().orElse(0);
            Arrays.sort(best, Comparator.comparingDouble(p -> Math.atan2(p.y-cy,p.x-cx)));
            int start = 0;
            for (int i=1;i<4;i++) if (best[i].x+best[i].y < best[start].x+best[start].y) start=i;
            Point[] ordered = new Point[4];
            for (int i=0;i<4;i++) ordered[i] = best[(start+i)%4];
            // Phase 1 validates face and identity only. Select the shorter board axis as width;
            // raw photos are intentionally not installation anchors with authored FRONT coordinates.
            if (distance(ordered[0],ordered[1]) > distance(ordered[1],ordered[2])) {
                Point first=ordered[0]; ordered[0]=ordered[1]; ordered[1]=ordered[2];
                ordered[2]=ordered[3]; ordered[3]=first;
            }
            double[] result = new double[8];
            for(int i=0;i<4;i++) { result[2*i]=ordered[i].x/(gray.cols()-1.0); result[2*i+1]=ordered[i].y/(gray.rows()-1.0); }
            return result;
        } finally { blur.release(); edges.release(); hierarchy.release(); kernel.release(); }
    }

    private static double distance(Point a, Point b) { return Math.hypot(a.x-b.x,a.y-b.y); }
}
