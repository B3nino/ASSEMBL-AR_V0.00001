package dev.chyken.assembl_ar.vision;

import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Ball-facing ROB-231 appearance: a reflective ball between two small mounting bores.
 * A circle alone is never identity evidence. The pale mounting ears, relative bore sizes,
 * opposite collinear positions and 40 mm spacing / 16 mm approximate ball diameter must agree.
 * Used only by the guarded, single-part fallback; it does not verify installation or orientation.
 */
final class BallCasterShapeRecognizer {
    static final class Candidate {
        final List<VisionPoint> corners;
        final double confidence;
        Candidate(List<VisionPoint> corners, double confidence) {
            this.corners = corners; this.confidence = confidence;
        }
    }

    private static final Candidate CLUTTER_REJECTED = new Candidate(Collections.emptyList(), 0);

    static Candidate find(Mat rgb, double minimumAreaRatio) {
        Candidate candidate = findThresholdCaster(rgb, minimumAreaRatio);
        if (candidate == CLUTTER_REJECTED) return null; // global baseline clutter/ambiguity veto
        if (candidate == null) candidate = findRelativeCaster(rgb, minimumAreaRatio);
        if (candidate == null) candidate = findRoomCaster(rgb, minimumAreaRatio);
        return candidate != null && hasBluePcb(rgb, candidate.corners) ? null : candidate;
    }

    private static Candidate findThresholdCaster(Mat rgb, double minimumAreaRatio) {
        double scale = Math.min(1.0, 1000.0 / Math.max(rgb.cols(), rgb.rows()));
        Mat working = new Mat(), gray = new Mat(), hsv = new Mat(), blurred = new Mat();
        Mat threshold = new Mat(), hierarchy = new Mat();
        List<MatOfPoint> contours = new ArrayList<>();
        List<Point> checked = new ArrayList<>();
        Candidate best = null; Point bestCenter = null;
        try {
            Imgproc.resize(rgb, working, new Size(), scale, scale, Imgproc.INTER_AREA);
            Imgproc.cvtColor(working, gray, Imgproc.COLOR_RGB2GRAY);
            Imgproc.cvtColor(working, hsv, Imgproc.COLOR_RGB2HSV);
            Imgproc.GaussianBlur(gray, blurred, new Size(9, 9), 2);
            Pixels pixels = new Pixels(gray, hsv);
            for (double level : new double[]{45, 65, 90, 120, 145}) {
                Imgproc.threshold(blurred, threshold, level, 255, Imgproc.THRESH_BINARY_INV);
                Imgproc.findContours(threshold, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE);
                for (MatOfPoint contour : contours) {
                    double area = Imgproc.contourArea(contour);
                    if (area < 300 || area > gray.total() * .09) continue;
                    MatOfPoint2f points = new MatOfPoint2f(contour.toArray());
                    Point center = new Point(); float[] radius = new float[1];
                    try {
                        double perimeter = Imgproc.arcLength(points, true);
                        if (perimeter <= 0 || 4 * Math.PI * area / (perimeter * perimeter) < .72) continue;
                        Imgproc.minEnclosingCircle(points, center, radius);
                    } finally { points.release(); }
                    double r = radius[0];
                    if (r < 12 || area / (Math.PI * r * r) < .68) continue;
                    Stats ball = pixels.stats(center.x, center.y, r);
                    // A dark interior with a highlight must be surrounded by pale neutral plastic.
                    if (ball.ringMean < 80 || ball.ringSaturation > 75
                            || ball.ringMean - ball.mean < 24 || ball.deviation < 12) continue;
                    if (checked.stream().anyMatch(p -> distance(p, center) < r * .5)) continue;
                    checked.add(center);
                    if (checked.size() > 12) return CLUTTER_REJECTED; // clutter is not a clean single-part view
                    Candidate candidate = mountingPair(gray, pixels, center, r, scale, minimumAreaRatio);
                    if (candidate == null) continue;
                    if (bestCenter != null && distance(bestCenter, center) > r * 2) return CLUTTER_REJECTED;
                    if (best == null || candidate.confidence > best.confidence) {
                        best = candidate; bestCenter = center;
                    }
                }
                for (MatOfPoint contour : contours) contour.release();
                contours.clear();
            }
            return best;
        } finally {
            for (MatOfPoint contour : contours) contour.release();
            hierarchy.release(); threshold.release(); blurred.release();
            hsv.release(); gray.release(); working.release();
        }
    }

    private static Candidate mountingPair(Mat gray, Pixels pixels, Point ball, double radius,
            double scale, double minimumAreaRatio) {
        int left = Math.max(0, (int) (ball.x - radius * 4)), top = Math.max(0, (int) (ball.y - radius * 4));
        int right = Math.min(gray.cols(), (int) Math.ceil(ball.x + radius * 4));
        int bottom = Math.min(gray.rows(), (int) Math.ceil(ball.y + radius * 4));
        Mat region = gray.submat(new Rect(left, top, right-left, bottom-top));
        Mat blurred = new Mat(), circles = new Mat();
        try {
            Imgproc.GaussianBlur(region, blurred, new Size(5,5), 1.2);
            Imgproc.HoughCircles(blurred, circles, Imgproc.HOUGH_GRADIENT, 1.2,
                    Math.max(8, radius*.2), 70, 14, Math.max(4, (int)(radius*.13)),
                    Math.max(6, (int)(radius*.40)));
            List<double[]> holes = new ArrayList<>();
            for (int i=0; i<circles.cols(); i++) {
                double[] c = circles.get(0,i); c[0] += left; c[1] += top;
                double distance = Math.hypot(c[0]-ball.x,c[1]-ball.y);
                if (distance < radius*1.85 || distance > radius*3.55) continue;
                Stats stats = pixels.stats(c[0],c[1],c[2]);
                if (stats.ringMean < 90 || stats.ringSaturation > 60 || stats.deviation < 6) continue;
                holes.add(c);
            }
            Candidate best = null;
            for (int i=0;i<holes.size();i++) for (int j=i+1;j<holes.size();j++) {
                double[] a=holes.get(i), b=holes.get(j);
                if (Math.max(a[2],b[2])/Math.min(a[2],b[2]) > 1.65) continue;
                double dx=b[0]-a[0], dy=b[1]-a[1], spacing=Math.hypot(dx,dy);
                double cx=(a[0]+b[0])/2, cy=(a[1]+b[1])/2;
                double offset=Math.hypot(cx-ball.x,cy-ball.y)/radius;
                if (spacing/radius < 4.1 || spacing/radius > 6.8 || offset > .48) continue;
                double ax=dx/spacing, ay=dy/spacing;
                if (ax < 0) { ax=-ax; ay=-ay; }
                // Both mounting ears must be connected by pale neutral base pixels beside the ball.
                Stats flankA=pixels.stats(cx-ay*radius*1.17,cy+ax*radius*1.17,radius*.28);
                Stats flankB=pixels.stats(cx+ay*radius*1.17,cy-ax*radius*1.17,radius*.28);
                if (Math.min(flankA.mean,flankB.mean)<80
                        || Math.max(flankA.saturation,flankB.saturation)>65) continue;
                double length=spacing*1.25, width=spacing*.625;
                double areaRatio=length*width/gray.total();
                if (areaRatio < minimumAreaRatio || areaRatio > .70) continue;
                List<VisionPoint> corners = Arrays.asList(
                        corner(cx,cy,ax,ay,-length/2,-width/2,scale),
                        corner(cx,cy,ax,ay,length/2,-width/2,scale),
                        corner(cx,cy,ax,ay,length/2,width/2,scale),
                        corner(cx,cy,ax,ay,-length/2,width/2,scale));
                if (corners.stream().anyMatch(p -> p.getX()<0 || p.getY()<0
                        || p.getX()>=gray.cols()/scale || p.getY()>=gray.rows()/scale)) continue;
                double confidence=.78+.10*(1-offset/.48)+.04*Math.min(a[2],b[2])/Math.max(a[2],b[2]);
                if (best==null || confidence>best.confidence) best=new Candidate(corners,confidence);
            }
            return best;
        } finally { circles.release(); blurred.release(); region.release(); }
    }

    /** Dim mounting plastic can be only slightly brighter than the grey mat.
     * Absolute dark thresholds merge the ball with that mat, so circular edge
     * evidence starts this path. A reflective sphere alone is insufficient:
     * two smaller opposite bores and both neutral plastic flanks are required.
     */
    private static Candidate findRelativeCaster(Mat rgb, double minimumAreaRatio) {
        double scale = Math.min(1.0, 1000.0 / Math.max(rgb.cols(), rgb.rows()));
        Mat working = new Mat(), gray = new Mat(), hsv = new Mat(), blurred = new Mat(), circles = new Mat();
        Candidate best = null; Point bestCenter = null; int checked = 0;
        try {
            Imgproc.resize(rgb, working, new Size(), scale, scale, Imgproc.INTER_AREA);
            Imgproc.cvtColor(working, gray, Imgproc.COLOR_RGB2GRAY);
            Imgproc.cvtColor(working, hsv, Imgproc.COLOR_RGB2HSV);
            Imgproc.GaussianBlur(gray, blurred, new Size(5, 5), 1.2);
            Imgproc.HoughCircles(blurred, circles, Imgproc.HOUGH_GRADIENT, 1.2, 15, 70, 15, 12, 70);
            if (circles.cols() > 64) return null; // isolated mount, not dense circular clutter
            Pixels pixels = new Pixels(gray, hsv);
            for (int i = 0; i < circles.cols(); i++) {
                double[] circle = circles.get(0, i); Point ball = new Point(circle[0], circle[1]);
                double radius = circle[2]; Stats stats = pixels.stats(ball.x, ball.y, radius);
                if (stats.ringMean < 80 || stats.ringSaturation > 55
                        || stats.ringMean - stats.mean < 24 || stats.deviation < 22) continue;
                if (++checked > 12) return null;
                Candidate candidate = relativeMountingPair(gray, pixels, ball, radius, scale, minimumAreaRatio);
                if (candidate == null) continue;
                Point center = new Point();
                for (VisionPoint p : candidate.corners) { center.x += p.getX(); center.y += p.getY(); }
                center.x *= scale / 4; center.y *= scale / 4;
                if (bestCenter != null && distance(bestCenter, center) > radius * 2) return null;
                if (best == null || candidate.confidence > best.confidence) {
                    best = candidate; bestCenter = center;
                }
            }
            return best;
        } finally { circles.release(); blurred.release(); hsv.release(); gray.release(); working.release(); }
    }

    private static Candidate relativeMountingPair(Mat gray, Pixels pixels, Point ball, double radius,
            double scale, double minimumAreaRatio) {
        int left = Math.max(0, (int) (ball.x - radius * 4)), top = Math.max(0, (int) (ball.y - radius * 4));
        int right = Math.min(gray.cols(), (int) Math.ceil(ball.x + radius * 4));
        int bottom = Math.min(gray.rows(), (int) Math.ceil(ball.y + radius * 4));
        Mat region = gray.submat(new Rect(left, top, right-left, bottom-top));
        Mat blurred = new Mat(), circles = new Mat();
        try {
            Imgproc.GaussianBlur(region, blurred, new Size(3, 3), .8);
            Imgproc.HoughCircles(blurred, circles, Imgproc.HOUGH_GRADIENT, 1.2,
                    Math.max(5, radius * .15), 50, 7, Math.max(3, (int) (radius * .10)),
                    Math.max(5, (int) (radius * .32)));
            List<double[]> holes = new ArrayList<>();
            for (int i = 0; i < circles.cols(); i++) {
                double[] c = circles.get(0, i); c[0] += left; c[1] += top;
                double distance = Math.hypot(c[0] - ball.x, c[1] - ball.y);
                if (distance < radius * 1.4 || distance > radius * 3.4) continue;
                Stats s = pixels.stats(c[0], c[1], c[2]);
                if (s.ringMean < 80 || s.ringSaturation > 60 || s.ringMean - s.mean < 18) continue;
                holes.add(c);
                if (holes.size() > 24) return null; // repeated bore-like clutter is not a clean mounting pair
            }
            Candidate best = null;
            for (int i = 0; i < holes.size(); i++) for (int j = i + 1; j < holes.size(); j++) {
                double[] a = holes.get(i), b = holes.get(j);
                if (Math.max(a[2], b[2]) / Math.min(a[2], b[2]) > 1.8) continue;
                double dx = b[0] - a[0], dy = b[1] - a[1], spacing = Math.hypot(dx, dy);
                if (spacing / radius < 4.0 || spacing / radius > 6.8) continue;
                double ax = dx / spacing, ay = dy / spacing, cx = (a[0] + b[0]) / 2, cy = (a[1] + b[1]) / 2;
                // The ball rises above the mounting plane and projects along its long axis when tilted.
                double along = Math.abs((ball.x - cx) * ax + (ball.y - cy) * ay) / radius;
                double across = Math.abs((ball.x - cx) * ay - (ball.y - cy) * ax) / radius;
                if (along > .9 || across > .55) continue;
                Stats flankA = pixels.stats(cx-ay*spacing*.225, cy+ax*spacing*.225, spacing*.045);
                Stats flankB = pixels.stats(cx+ay*spacing*.225, cy-ax*spacing*.225, spacing*.045);
                Stats outerA = pixels.stats(cx-ay*spacing*.40, cy+ax*spacing*.40, spacing*.05);
                Stats outerB = pixels.stats(cx+ay*spacing*.40, cy-ax*spacing*.40, spacing*.05);
                double contrastA = flankA.mean - outerA.mean, contrastB = flankB.mean - outerB.mean;
                if (Math.min(flankA.mean, flankB.mean) < 80
                        || Math.max(flankA.saturation, flankB.saturation) > 60
                        || Math.min(contrastA, contrastB) < 2 || (contrastA + contrastB) / 2 < 14) continue;
                double length = spacing * 1.25, width = spacing * .625, areaRatio = length * width / gray.total();
                if (areaRatio < minimumAreaRatio || areaRatio > .70) continue;
                if (ax < 0) { ax = -ax; ay = -ay; }
                List<VisionPoint> corners = Arrays.asList(
                        corner(cx,cy,ax,ay,-length/2,-width/2,scale),
                        corner(cx,cy,ax,ay,length/2,-width/2,scale),
                        corner(cx,cy,ax,ay,length/2,width/2,scale),
                        corner(cx,cy,ax,ay,-length/2,width/2,scale));
                if (corners.stream().anyMatch(p -> p.getX()<0 || p.getY()<0
                        || p.getX()>=gray.cols()/scale || p.getY()>=gray.rows()/scale)) continue;
                double confidence = .84+.04*(1-across/.55)+.02*Math.min(a[2],b[2])/Math.max(a[2],b[2]);
                if (best == null || confidence > best.confidence) best = new Candidate(corners, confidence);
            }
            return best;
        } finally { circles.release(); blurred.release(); region.release(); }
    }

    /** Guide lines on the room mat can produce many weak circular edges. This
     * separate path requires stronger sphere votes, two smaller opposite bores,
     * and four smooth pale plastic samples on the ears. Two lit samples must
     * locate a boundary; the sphere's cast shadow may darken the other ear.
     */
    private static Candidate findRoomCaster(Mat rgb, double minimumAreaRatio) {
        double scale = Math.min(1.0, 1000.0 / Math.max(rgb.cols(), rgb.rows()));
        Mat working = new Mat(), gray = new Mat(), hsv = new Mat(), blurred = new Mat(), circles = new Mat();
        Candidate best = null; Point bestCenter = null; int checked = 0;
        try {
            Imgproc.resize(rgb, working, new Size(), scale, scale, Imgproc.INTER_AREA);
            Imgproc.cvtColor(working, gray, Imgproc.COLOR_RGB2GRAY);
            Imgproc.cvtColor(working, hsv, Imgproc.COLOR_RGB2HSV);
            Imgproc.GaussianBlur(gray, blurred, new Size(5, 5), 1.2);
            Imgproc.HoughCircles(blurred, circles, Imgproc.HOUGH_GRADIENT, 1.2, 25, 90, 30, 12, 70);
            if (circles.cols() > 24) return null;
            Pixels pixels = new Pixels(gray, hsv);
            for (int i = 0; i < circles.cols(); i++) {
                double[] c = circles.get(0, i); Point ball = new Point(c[0], c[1]);
                double radius = c[2]; Stats s = pixels.stats(ball.x, ball.y, radius);
                if (s.ringMean < 90 || s.ringSaturation > 55
                        || s.ringMean - s.mean < 24 || s.deviation < 22) continue;
                if (++checked > 12) return null;
                Candidate candidate = roomMountingPair(gray, pixels, ball, radius, scale, minimumAreaRatio);
                if (candidate == null) continue;
                Point center = new Point();
                for (VisionPoint p : candidate.corners) { center.x += p.getX(); center.y += p.getY(); }
                center.x *= scale / 4; center.y *= scale / 4;
                if (bestCenter != null && distance(bestCenter, center) > radius * 2) return null;
                if (best == null || candidate.confidence > best.confidence) {
                    best = candidate; bestCenter = center;
                }
            }
            return best;
        } finally { circles.release(); blurred.release(); hsv.release(); gray.release(); working.release(); }
    }

    private static Candidate roomMountingPair(Mat gray, Pixels pixels, Point ball, double radius,
            double scale, double minimumAreaRatio) {
        int left = Math.max(0, (int) (ball.x - radius * 4)), top = Math.max(0, (int) (ball.y - radius * 4));
        int right = Math.min(gray.cols(), (int) Math.ceil(ball.x + radius * 4));
        int bottom = Math.min(gray.rows(), (int) Math.ceil(ball.y + radius * 4));
        Mat region = gray.submat(new Rect(left, top, right-left, bottom-top));
        Mat blurred = new Mat(), circles = new Mat();
        try {
            Imgproc.GaussianBlur(region, blurred, new Size(3, 3), .8);
            Imgproc.HoughCircles(blurred, circles, Imgproc.HOUGH_GRADIENT, 1.2,
                    Math.max(5, radius * .15), 50, 7, Math.max(3, (int) (radius * .10)),
                    Math.max(5, (int) (radius * .23)));
            List<double[]> holes = new ArrayList<>();
            for (int i = 0; i < circles.cols(); i++) {
                double[] c = circles.get(0, i); c[0] += left; c[1] += top;
                double d = Math.hypot(c[0] - ball.x, c[1] - ball.y);
                Stats s = pixels.stats(c[0], c[1], c[2]);
                if (d >= radius * 1.8 && d <= radius * 3.4 && s.ringMean >= 90
                        && s.ringSaturation <= 60 && s.ringMean - s.mean >= 18) holes.add(c);
            }
            if (holes.size() > 24) return null;
            Candidate best = null;
            for (int i = 0; i < holes.size(); i++) for (int j = i + 1; j < holes.size(); j++) {
                double[] a = holes.get(i), b = holes.get(j);
                if (Math.max(a[2], b[2]) / Math.min(a[2], b[2]) > 1.8) continue;
                double dx = b[0] - a[0], dy = b[1] - a[1], spacing = Math.hypot(dx, dy);
                if (spacing / radius < 4 || spacing / radius > 6.8) continue;
                double ax = dx / spacing, ay = dy / spacing, cx = (a[0] + b[0]) / 2, cy = (a[1] + b[1]) / 2;
                double along = Math.abs((ball.x - cx) * ax + (ball.y - cy) * ay) / radius;
                double across = Math.abs((ball.x - cx) * ay - (ball.y - cy) * ax) / radius;
                if (along > .55 || across > .55) continue;
                boolean plastic = true; int litBoundaries = 0;
                for (int ear : new int[]{-1, 1}) for (int side : new int[]{-1, 1}) {
                    Stats inner = pixels.stats(cx + ear * spacing * .34 * ax - side * spacing * .08 * ay,
                            cy + ear * spacing * .34 * ay + side * spacing * .08 * ax, spacing * .035);
                    Stats outer = pixels.stats(cx + ear * spacing * .34 * ax - side * spacing * .34 * ay,
                            cy + ear * spacing * .34 * ay + side * spacing * .34 * ax, spacing * .035);
                    if (inner.mean < 110 || inner.saturation > 60 || inner.deviation > 20) plastic = false;
                    if (inner.mean - outer.mean >= 3) litBoundaries++;
                }
                if (!plastic || litBoundaries < 2) continue;
                double length = spacing * 1.25, width = spacing * .625;
                double areaRatio = length * width / gray.total();
                if (areaRatio < minimumAreaRatio || areaRatio > .70) continue;
                if (ax < 0) { ax = -ax; ay = -ay; }
                List<VisionPoint> corners = Arrays.asList(
                        corner(cx,cy,ax,ay,-length/2,-width/2,scale),
                        corner(cx,cy,ax,ay,length/2,-width/2,scale),
                        corner(cx,cy,ax,ay,length/2,width/2,scale),
                        corner(cx,cy,ax,ay,-length/2,width/2,scale));
                if (corners.stream().anyMatch(p -> p.getX() < 0 || p.getY() < 0
                        || p.getX() >= gray.cols()/scale || p.getY() >= gray.rows()/scale)) continue;
                double confidence = .84 + .04 * (1 - across/.55) + .02 * Math.min(a[2],b[2])/Math.max(a[2],b[2]);
                if (best == null || confidence > best.confidence) best = new Candidate(corners, confidence);
            }
            return best;
        } finally { circles.release(); blurred.release(); region.release(); }
    }

    /** Saturated blue PCB within an otherwise plausible mount belongs to an
     * electronic part. This veto applies to every caster appearance route.
     */
    private static boolean hasBluePcb(Mat rgb, List<VisionPoint> corners) {
        Mat hsv = new Mat(), outline = Mat.zeros(rgb.rows(), rgb.cols(), CvType.CV_8UC1);
        Mat blue = new Mat(); MatOfPoint polygon = new MatOfPoint();
        try {
            Point[] points = new Point[corners.size()];
            for (int i = 0; i < points.length; i++) {
                VisionPoint p = corners.get(i); points[i] = new Point((int) p.getX(), (int) p.getY());
            }
            polygon.fromArray(points); Imgproc.fillConvexPoly(outline, polygon, Scalar.all(255));
            Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV);
            Core.inRange(hsv, new Scalar(85, 60, 35), new Scalar(140, 255, 255), blue);
            Core.bitwise_and(blue, outline, blue);
            return Core.countNonZero(blue) / (double) Math.max(1, Core.countNonZero(outline)) >= .02;
        } finally { polygon.release(); blue.release(); outline.release(); hsv.release(); }
    }

    private static VisionPoint corner(double cx,double cy,double ax,double ay,double x,double y,double scale) {
        return new VisionPoint((cx+ax*x-ay*y)/scale,(cy+ay*x+ax*y)/scale);
    }
    private static double distance(Point a,Point b) { return Math.hypot(a.x-b.x,a.y-b.y); }

    private static final class Stats {
        double mean, deviation, saturation, ringMean, ringSaturation;
    }
    private static final class Pixels {
        final int width,height; final byte[] gray,hsv;
        Pixels(Mat g,Mat h) { width=g.cols(); height=g.rows(); gray=new byte[width*height];
            hsv=new byte[width*height*3]; g.get(0,0,gray); h.get(0,0,hsv); }
        Stats stats(double cx,double cy,double r) {
            Stats out=new Stats(); double sum=0,squared=0,sat=0,ring=0,ringSat=0; int n=0,nr=0;
            for(int y=Math.max(0,(int)(cy-r*1.8));y<Math.min(height,cy+r*1.8);y++)
                for(int x=Math.max(0,(int)(cx-r*1.8));x<Math.min(width,cx+r*1.8);x++) {
                    double d=((x-cx)*(x-cx)+(y-cy)*(y-cy))/(r*r);
                    int i=y*width+x, value=gray[i]&255, saturation=hsv[i*3+1]&255;
                    if(d<.5625) {sum+=value;squared+=value*value;sat+=saturation;n++;}
                    else if(d>1.5625 && d<3.24) {ring+=value;ringSat+=saturation;nr++;}
                }
            out.mean=sum/Math.max(1,n);out.deviation=Math.sqrt(Math.max(0,squared/Math.max(1,n)-out.mean*out.mean));
            out.saturation=sat/Math.max(1,n);out.ringMean=ring/Math.max(1,nr);out.ringSaturation=ringSat/Math.max(1,nr);
            return out;
        }
    }
}
