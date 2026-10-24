package dev.chyken.assembl_ar.vision;

import java.util.ArrayList;
import java.util.List;
import org.opencv.core.*;
import org.opencv.features2d.ORB;
import org.opencv.imgproc.CLAHE;
import org.opencv.imgproc.Imgproc;

/** Independent local feature searches prevent identical IR modules competing in the KNN ratio. */
final class Phase7TrackerSearch implements AutoCloseable {
    private final ORB orb = ORB.create(1200, 1.2f, 8, 12, 0, 2, ORB.HARRIS_SCORE, 31, 7);
    private final CLAHE clahe;

    Phase7TrackerSearch(VisionConfig config) { clahe = OpenCvVisionUtils.createClahe(config); }

    List<Rect> windows(Mat gray, Mat rgb) {
        int w = gray.cols(), h = gray.rows();
        List<Rect> result = new ArrayList<>();
        // Blue trim pots can merge into one horizontal proposal spanning TWO
        // modules, or disappear in warm shadows. Always search neutral rigid
        // bodies first; color remains a hint and cannot suppress these windows.
        addDarkModuleWindows(gray, result);
        if (rgb != null && !rgb.empty()) {
            Mat scaled = new Mat(), bgr = new Mat();
            try {
                if (rgb.cols() != w || rgb.rows() != h)
                    Imgproc.resize(rgb, scaled, new Size(w, h), 0, 0, Imgproc.INTER_AREA);
                else rgb.copyTo(scaled);
                Imgproc.cvtColor(scaled, bgr, Imgproc.COLOR_RGB2BGR);
                List<List<VisionPoint>> proposals = Phase7TrackerPhotoGeometry.searchRegions(bgr);
                for (int i = 0; i < Math.min(2, proposals.size()); i++) {
                    List<VisionPoint> region = proposals.get(i);
                    double l = region.get(0).getX() * w, t = region.get(0).getY() * h;
                    double r = region.get(2).getX() * w, b = region.get(2).getY() * h;
                    if (r-l > 5 && b-t > 5) add(result, w, h, l-8, t-8, r+8, b+8);
                }
            } finally { scaled.release(); bgr.release(); }
        }
        // Color is an optional search hint. Neutral lighting, monochrome fixtures and different
        // trim-pot colors still receive independent spatial searches, in either camera rotation.
        add(result, w, h, 0, 0, w*.56, h);
        add(result, w, h, w*.44, 0, w, h);
        add(result, w, h, 0, 0, w, h*.56);
        add(result, w, h, 0, h*.44, w, h);
        add(result, w, h, 0, 0, w, h);
        return result;
    }

    Features extract(Mat gray, Rect window, int extent) {
        Mat crop = gray.submat(window), normalized = new Mat(), empty = new Mat();
        MatOfKeyPoint points = new MatOfKeyPoint();
        Mat descriptors = new Mat();
        // Keep a native-pixel pass: enlarging a small module by 3–4x can destroy the
        // ORB sampling scale that actually matches its reference descriptors.
        double scale = extent == 0 ? 1.0 : Math.min(4.0, extent / (double)Math.max(window.width, window.height));
        try {
            Imgproc.resize(crop, normalized, new Size(), scale, scale, Imgproc.INTER_LINEAR);
            DiagnosticHammingMatcher.normalize(clahe,normalized, normalized);
            DiagnosticHammingMatcher.extract(orb,normalized, empty, points, descriptors);
            KeyPoint[] keypoints = points.toArray();
            for (KeyPoint keypoint : keypoints) {
                keypoint.pt.x = window.x + keypoint.pt.x / scale;
                keypoint.pt.y = window.y + keypoint.pt.y / scale;
                keypoint.size /= scale;
            }
            return new Features(keypoints, descriptors);
        } catch (RuntimeException | Error failure) {
            descriptors.release();
            throw failure;
        } finally { crop.release(); normalized.release(); empty.release(); points.release(); }
    }

    /** Search hints for dark IR emitters, which need not look blue in tablet lighting. */
    private static void addDarkModuleWindows(Mat gray, List<Rect> windows) {
        Mat mask=new Mat(),hierarchy=new Mat();
        Mat kernel=Imgproc.getStructuringElement(Imgproc.MORPH_RECT,new Size(3,3));
        List<MatOfPoint> contours=new ArrayList<>();
        List<Rect> candidates=new ArrayList<>();
        try {
            for(int threshold:new int[]{45,65,85}) {
                Imgproc.threshold(gray,mask,threshold,255,Imgproc.THRESH_BINARY_INV);
                Imgproc.morphologyEx(mask,mask,Imgproc.MORPH_CLOSE,kernel);
                for(Mat contour:contours)contour.release();contours.clear();
                Imgproc.findContours(mask,contours,hierarchy,Imgproc.RETR_EXTERNAL,Imgproc.CHAIN_APPROX_SIMPLE);
                List<Rect> thresholdCandidates=new ArrayList<>();
                for(MatOfPoint contour:contours) {
                    Rect r=Imgproc.boundingRect(contour);
                    double area=Imgproc.contourArea(contour),aspect=Math.max(r.width,r.height)/(double)Math.max(1,Math.min(r.width,r.height));
                    if(area<gray.total()*.001||area>gray.total()*.20||aspect<1.8||aspect>8||area/r.area()<.25)continue;
                    // These are proposals, never acceptance evidence. Dark sensors in warm
                    // indoor light can have no blue pixels; ORB identity decides below.
                    thresholdCandidates.add(r);
                }
                // Suppress nested contours only after sorting. Otherwise a narrow shadow
                // returned first by findContours can suppress the entire real module.
                thresholdCandidates.sort((a,b)->Double.compare(b.area(),a.area()));
                for(Rect r:thresholdCandidates) {
                    boolean duplicate=false;
                    for(Rect previous:candidates)if(overlap(r,previous)>.35){duplicate=true;break;}
                    if(!duplicate)candidates.add(r);
                }
            }
            candidates.sort((a,b)->Double.compare(b.area(),a.area()));
            for(Rect r:candidates) {
                boolean duplicate=false;
                for(Rect previous:windows)if(overlap(r,previous)>.35){duplicate=true;break;}
                if(!duplicate)add(windows,gray.cols(),gray.rows(),r.x-8,r.y-8,r.x+r.width+8,r.y+r.height+8);
                // Without color, shadows and printed logos also propose rectangles. Retain
                // alternatives rather than letting either false hint displace a real sensor.
                if(windows.size()>=4)break;
            }
        } finally {
            for(Mat contour:contours)contour.release();
            mask.release();hierarchy.release();kernel.release();
        }
    }

    private static double overlap(Rect a,Rect b) {
        double area=Math.max(0,Math.min(a.x+a.width,b.x+b.width)-Math.max(a.x,b.x))
                *Math.max(0,Math.min(a.y+a.height,b.y+b.height)-Math.max(a.y,b.y));
        return area/Math.max(1,Math.min(a.area(),b.area()));
    }

    private static void add(List<Rect> windows, int width, int height,
                            double left, double top, double right, double bottom) {
        int l = Math.max(0, (int)Math.floor(left)), t = Math.max(0, (int)Math.floor(top));
        int r = Math.min(width, (int)Math.ceil(right)), b = Math.min(height, (int)Math.ceil(bottom));
        if (r-l >= 12 && b-t >= 12) windows.add(new Rect(l, t, r-l, b-t));
    }

    static final class Features implements AutoCloseable {
        final KeyPoint[] points;
        final Mat descriptors;
        Features(KeyPoint[] points, Mat descriptors) { this.points=points; this.descriptors=descriptors; }
        public void close() { descriptors.release(); }
    }

    public void close() { orb.clear(); clahe.clear(); }
}
