package dev.chyken.assembl_ar.vision;

import dev.chyken.assembl_ar.camera.Phase2AssetCatalog;
import org.opencv.core.*;
import org.opencv.features2d.*;
import org.opencv.calib3d.Calib3d;
import org.opencv.imgproc.*;
import java.util.*;

/** Independent scene evidence. A photo match never supplies a physical pose or object count. */
final class Phase2ReferenceMatcher implements AutoCloseable {
    private final ReferenceDatabase database;
    private final ORB orb;
    private final CLAHE clahe;
    private final BFMatcher matcher=BFMatcher.create(Core.NORM_HAMMING,false);
    Phase2ReferenceMatcher(ReferenceDatabase database,VisionConfig config) {
        this.database=database;orb=OpenCvVisionUtils.createOrb(config);clahe=OpenCvVisionUtils.createClahe(config);
    }
    RecognitionResult inspect(Mat gray,long timestamp,int stage,RecognitionResult primary) {
        long started=android.os.SystemClock.elapsedRealtimeNanos();
        Mat work=new Mat(),descriptors=new Mat(),empty=new Mat();MatOfKeyPoint points=new MatOfKeyPoint();
        double scale=Math.min(1,960.0/Math.max(gray.cols(),gray.rows()));
        database.acquireForRecognition();
        try {
            if(scale<1) Imgproc.resize(gray,work,new Size(),scale,scale,Imgproc.INTER_AREA); else gray.copyTo(work);
            clahe.apply(work,work);orb.detectAndCompute(work,empty,points,descriptors);
            KeyPoint[] live=points.toArray();
            long featuresDone=android.os.SystemClock.elapsedRealtimeNanos();
            double positive=0,negative=0;String view=null;List<VisionPoint> corners=Collections.emptyList();
            Map<String,Double> viewScores=new HashMap<>();
            if(!descriptors.empty()) for(ReferenceDatabase.PreparedReference reference:database.preparedReferences()) {
                String role=Phase2AssetCatalog.referenceRole(reference.spec.getReferenceId());
                if(role==null || role.equals("invalid") || stage==1&&!role.equals("brackets") || stage==2&&!role.equals("motors")
                        || stage==5&&(role.equals("brackets")||role.equals("motors"))) continue;
                for(ReferenceDatabase.PreparedOrbVariant variant:reference.orbVariants) {
                    if(!variant.hasOrbData())continue;
                    Evidence evidence=match(reference,variant,live,descriptors,scale,role,work.cols(),work.rows());
                    if(evidence==null)continue;
                    viewScores.merge(role,evidence.score,Math::max);
                    if(evidence.score>positive) {positive=evidence.score;view=role;corners=evidence.corners;}
                }
            }
            double competing=0;
            if(stage==5)for(Map.Entry<String,Double> score:viewScores.entrySet())
                if(!score.getKey().equals(view))competing=Math.max(competing,score.getValue());
            Phase2Observation observation=new Phase2Observation(view,positive,negative,-1,competing);
            if(primary==null) primary=RecognitionResult.builder(timestamp)
                    .identity("phase2:scene","main_board","Motor assembly")
                    .method(observation.referenceSupported?RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY:RecognitionResult.DetectionMethod.NONE)
                    .matches(live.length,(int)positive,(int)positive,positive>0?1:0)
                    .corners(corners).confidence(observation.referenceSupported?.8:0)
                    .validation(observation.referenceSupported?RecognitionResult.ValidationState.EXPECTED_COMPONENT:RecognitionResult.ValidationState.NO_COMPONENT)
                    .build();
            RecognitionResult.Latency base=primary.getLatency();
            double featureMs=(featuresDone-started)/1_000_000.0;
            double matchingMs=(android.os.SystemClock.elapsedRealtimeNanos()-featuresDone)/1_000_000.0;
            return primary.withPhase2Observation(observation).withLatency(new RecognitionResult.Latency(
                    base.getFeatureExtractionMs()+featureMs,base.getMatchingMs()+matchingMs,
                    base.getSpatialValidationMs(),base.getCircularFallbackMs(),base.getTotalMs()+featureMs+matchingMs));
        } finally {database.releaseAfterRecognition();work.release();descriptors.release();empty.release();points.release();}
    }
    private Evidence match(ReferenceDatabase.PreparedReference ref,ReferenceDatabase.PreparedOrbVariant variant,
                           KeyPoint[] live,Mat descriptors,double scale,String role,int width,int height) {
        List<MatOfDMatch> pairs=new ArrayList<>(); List<Point> source=new ArrayList<>(),target=new ArrayList<>();
        try {
            matcher.knnMatch(variant.descriptors,descriptors,pairs,2);
            Set<Integer> used=new HashSet<>();
            for(MatOfDMatch pair:pairs) {
                DMatch[] p=pair.toArray();
                if(p.length<2||p[0].distance>64||p[0].distance>=p[1].distance*.76||!used.add(p[0].trainIdx)) continue;
                source.add(variant.keypoints[p[0].queryIdx].pt); target.add(live[p[0].trainIdx].pt);
            }
        } finally {for(Mat p:pairs)p.release();}
        if(source.size()<16)return null;
        MatOfPoint2f src=new MatOfPoint2f(),dst=new MatOfPoint2f();Mat mask=new Mat(),homography=null;
        try {
            src.fromList(source);dst.fromList(target);
            homography=Calib3d.findHomography(src,dst,Calib3d.RANSAC,3.5,mask,1500,.995);
            if(homography.empty())return null;
            int inliers=Core.countNonZero(mask);
            if(inliers<16||inliers/(double)source.size()<.5)return null;
            List<List<VisionPoint>> regions=ref.spec.getFeatureRegionsNormalized();
            // Confirm the photographed board/view; object counts are separate inventory checkpoints.
            for(List<VisionPoint> r:regions) {
                int hits=0;double minX=1,minY=1,maxX=0,maxY=0;
                for(int i=0;i<source.size();i++) if(mask.get(i,0)[0]!=0) {
                    double x=source.get(i).x/ref.imageWidth,y=source.get(i).y/ref.imageHeight;
                    if(x>=r.get(0).getX()&&x<=r.get(2).getX()&&y>=r.get(0).getY()&&y<=r.get(2).getY()){
                        hits++;minX=Math.min(minX,x);maxX=Math.max(maxX,x);minY=Math.min(minY,y);maxY=Math.max(maxY,y);
                    }
                }
                int minimumHits=("left".equals(role)||"right".equals(role))?8:6;
                double minimumSpread=("left".equals(role)||"right".equals(role))?.20:.12;
                if(hits<minimumHits||maxX-minX<(r.get(2).getX()-r.get(0).getX())*minimumSpread
                        ||maxY-minY<(r.get(2).getY()-r.get(0).getY())*minimumSpread)return null;
            }
            MatOfPoint2f rectangle=new MatOfPoint2f(new Point(0,0),new Point(ref.imageWidth,0),
                    new Point(ref.imageWidth,ref.imageHeight),new Point(0,ref.imageHeight)),projected=new MatOfPoint2f();
            try {
                Core.perspectiveTransform(rectangle,projected,homography);
                List<VisionPoint> corners=new ArrayList<>();double area=Imgproc.contourArea(projected,true);
                // An ordinary camera rotation preserves winding; a reflected left/right mapping does not.
                if(!Double.isFinite(area)||area<width*(double)height*.005||area>width*(double)height*4)return null;
                Point[] polygon=projected.toArray();
                for(Point p:polygon) {if(!Double.isFinite(p.x+p.y)||p.x< -width||p.x>width*2||p.y< -height||p.y>height*2)return null;corners.add(new VisionPoint(p.x/scale,p.y/scale));}
                MatOfPoint convex=new MatOfPoint(polygon);
                try{if(!Imgproc.isContourConvex(convex))return null;}finally{convex.release();}
                return new Evidence(inliers*(inliers/(double)source.size()),corners);
            } finally {rectangle.release();projected.release();}
        } finally {src.release();dst.release();mask.release();if(homography!=null)homography.release();}
    }
    private static final class Evidence {
        final double score;final List<VisionPoint> corners;
        Evidence(double score,List<VisionPoint> corners) {this.score=score;this.corners=corners;}
    }
    @Override public void close(){orb.clear();clahe.clear();matcher.clear();}
}
