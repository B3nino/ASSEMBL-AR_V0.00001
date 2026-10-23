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
    private final Map<ReferenceDatabase.PreparedReference,Mat> shortlist=new IdentityHashMap<>();
    private final Map<String,ReferenceDatabase.PreparedReference> preferred=new HashMap<>();
    private int cursor;
    private final CLAHE clahe;
    private final DiagnosticHammingMatcher matcher=new DiagnosticHammingMatcher();
    Phase2ReferenceMatcher(ReferenceDatabase database,VisionConfig config) {
        this.database=database;orb=OpenCvVisionUtils.createOrb(config);clahe=OpenCvVisionUtils.createClahe(config);
        for(ReferenceDatabase.PreparedReference ref:database.preparedReferences())if(Phase2AssetCatalog.referenceRole(ref.spec.getReferenceId())!=null)
            for(ReferenceDatabase.PreparedOrbVariant variant:ref.orbVariants)if(variant.hasOrbData()){
                int n=Math.min(96,variant.descriptors.rows()),stride=variant.descriptors.cols()*(int)variant.descriptors.elemSize();
                byte[] source=new byte[variant.descriptors.rows()*stride],rows=new byte[n*stride];variant.descriptors.get(0,0,source);
                for(int i=0;i<n;i++)System.arraycopy(source,(i*variant.descriptors.rows()/n)*stride,rows,i*stride,stride);
                Mat sparse=new Mat(n,variant.descriptors.cols(),variant.descriptors.type());sparse.put(0,0,rows);shortlist.put(ref,sparse);break;
            }
    }
    RecognitionResult inspect(Mat gray,long timestamp,int stage,RecognitionResult primary) {return inspect(gray,null,timestamp,stage,primary);}
    RecognitionResult inspect(Mat gray,Mat rgb,long timestamp,int stage,RecognitionResult primary) {return inspect(gray,rgb,timestamp,stage,primary,null);}
    RecognitionResult inspect(Mat gray,Mat rgb,long timestamp,int stage,RecognitionResult primary,String requestedView) {
        long started=android.os.SystemClock.elapsedRealtimeNanos();
        Mat work=new Mat(),descriptors=new Mat(),empty=new Mat();MatOfKeyPoint points=new MatOfKeyPoint();
        double scale=Math.min(1,960.0/Math.max(gray.cols(),gray.rows()));
        database.acquireForRecognition();
        try {
            if(scale<1) Imgproc.resize(gray,work,new Size(),scale,scale,Imgproc.INTER_AREA); else gray.copyTo(work);
            DiagnosticHammingMatcher.normalize(clahe,work,work);DiagnosticHammingMatcher.extract(orb,work,empty,points,descriptors);
            KeyPoint[] live=points.toArray();DiagnosticHammingMatcher.captureLive(live,descriptors,scale);
            long featuresDone=android.os.SystemClock.elapsedRealtimeNanos();
            double positive=0,negative=0;String view=null;List<VisionPoint> corners=Collections.emptyList();
            Map<String,Double> viewScores=new HashMap<>();
            boolean housingPossible=stage!=5||rgb==null||housingPossible(rgb);
            List<ReferenceDatabase.PreparedReference> candidates=requestedView==null?database.preparedReferences():ranked(descriptors,stage,requestedView,housingPossible);
            long deadline=android.os.SystemClock.elapsedRealtimeNanos()+250_000_000L;int visited=0;
            referenceLoop: if(!descriptors.empty()) for(ReferenceDatabase.PreparedReference reference:candidates) {
                String role=Phase2AssetCatalog.referenceRole(reference.spec.getReferenceId());
                if(role==null || stage!=5&&role.equals("invalid") || stage==1&&!role.equals("brackets") || stage==2&&!role.equals("motors")
                        || stage==5&&(role.equals("brackets")||role.equals("motors"))) continue;
                if(stage==5&&!housingPossible&&!role.equals("invalid"))continue;
                if(requestedView!=null&&visited>=(stage==5&&housingPossible&&!"any".equals(requestedView)?3:2)&&android.os.SystemClock.elapsedRealtimeNanos()>deadline)break;visited++;
                for(ReferenceDatabase.PreparedOrbVariant variant:reference.orbVariants) {
                    if(!variant.hasOrbData())continue;
                    Evidence evidence=match(reference,variant,live,descriptors,scale,role,work.cols(),work.rows(),work,rgb);
                    if(evidence==null)continue;
                    if(role.equals("invalid")){negative=Math.max(negative,evidence.score);if(!housingPossible&&negative>=16)break referenceLoop;continue;}
                    viewScores.merge(role,evidence.score,Math::max);
                    if(evidence.score>positive) {positive=evidence.score;view=role;corners=evidence.corners;preferred.put(stage+":"+requestedView,reference);}
                    if(requestedView!=null)break;
                }
            }
            if(positive<16)preferred.remove(stage+":"+requestedView);
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
    /** Sparse rows schedule full checks only. They never supply acceptance/count evidence. */
    private List<ReferenceDatabase.PreparedReference> ranked(Mat live,int stage,String view,boolean housingPossible){
        List<ReferenceDatabase.PreparedReference> positive=new ArrayList<>(),negative=new ArrayList<>();
        Map<ReferenceDatabase.PreparedReference,Integer> score=new IdentityHashMap<>();DiagnosticHammingMatcher.context(null);
        for(ReferenceDatabase.PreparedReference ref:database.preparedReferences()){
            String role=Phase2AssetCatalog.referenceRole(ref.spec.getReferenceId());
            if(role==null||stage==1&&!role.equals("brackets")||stage==2&&!role.equals("motors")||stage==5&&(role.equals("brackets")||role.equals("motors")))continue;
            if(stage==5&&!housingPossible&&!role.equals("invalid"))continue;
            Mat sparse=shortlist.get(ref);score.put(ref,sparse==null?0:matcher.ratioMatches(sparse,live,.80,72).size());
            (role.equals("invalid")?negative:positive).add(ref);
        }
        int start=cursor++;if(!positive.isEmpty())Collections.rotate(positive,-Math.floorMod(start,positive.size()));
        if(!negative.isEmpty())Collections.rotate(negative,-Math.floorMod(start,negative.size()));
        Comparator<ReferenceDatabase.PreparedReference> rank=(a,b)->Integer.compare(score.get(b),score.get(a));positive.sort(rank);negative.sort(rank);
        List<ReferenceDatabase.PreparedReference> primary=new ArrayList<>(),competing=new ArrayList<>();
        for(ReferenceDatabase.PreparedReference ref:positive)(stage!=5||"any".equals(view)||view.equals(Phase2AssetCatalog.referenceRole(ref.spec.getReferenceId()))?primary:competing).add(ref);
        // Two fresh primary candidates per frame guarantee coverage; a permanently
        // high sparse score must not starve a lower-ranked, geometrically valid photo.
        if(!primary.isEmpty())Collections.rotate(primary,-Math.floorMod(start*2,primary.size()));
        ReferenceDatabase.PreparedReference last=preferred.get(stage+":"+view);if(primary.remove(last))primary.add(0,last);
        List<ReferenceDatabase.PreparedReference> result=new ArrayList<>();
        if(!primary.isEmpty())result.add(primary.get(0));
        if(primary.size()>1)result.add(primary.get(1));
        if(!negative.isEmpty())result.add(negative.get(0));
        if(!competing.isEmpty())result.add(competing.get(0));
        for(int i=2;i<4;i++){if(i<primary.size())result.add(primary.get(i));if(i-1<negative.size())result.add(negative.get(i-1));if(i-1<competing.size()&&i<3)result.add(competing.get(i-1));}
        return result;
    }
    private Evidence match(ReferenceDatabase.PreparedReference ref,ReferenceDatabase.PreparedOrbVariant variant,
                           KeyPoint[] live,Mat descriptors,double scale,String role,int width,int height,Mat frame,Mat rgb) {DiagnosticHammingMatcher.context(ref.spec);
         List<Point> source=new ArrayList<>(),target=new ArrayList<>();
        {
            List<DMatch> good=matcher.ratioMatches(variant.descriptors,descriptors,.76,64);
            // Sampling alternatives are not additional objects. Keep the best descriptor
            // for each physical live keypoint, independently of extraction-level order.
            for(DMatch m:ComponentRecognizer.uniqueFrameMatches(good)){
                source.add(variant.keypoints[m.queryIdx].pt);target.add(live[m.trainIdx].pt);
            }
        }
        if(source.size()<16)return null;
        MatOfPoint2f src=new MatOfPoint2f(),dst=new MatOfPoint2f();Mat mask=new Mat(),homography=null;
        try {
            src.fromList(source);dst.fromList(target);
            homography=DiagnosticHammingMatcher.homography(src,dst,Calib3d.RANSAC,3.5,mask,1500,.995);
            if(homography.empty())return null;
            int inliers=Core.countNonZero(mask);byte[] accepted=new byte[(int)mask.total()];mask.get(0,0,accepted);
            if(inliers<16||inliers/(double)source.size()<.5)return null;
            List<List<VisionPoint>> regions=ref.spec.getFeatureRegionsNormalized();
            // Confirm the photographed board/view; object counts are separate inventory checkpoints.
            if("top".equals(role)&&regions.size()!=6)return null;
            int roiIndex=0;int[] roiCounts=new int[6];
            for(List<VisionPoint> r:regions) {
                int hits=0;double minX=1,minY=1,maxX=0,maxY=0;
                for(int i=0;i<source.size();i++) if(accepted[i]!=0) {
                    double x=source.get(i).x/ref.imageWidth,y=source.get(i).y/ref.imageHeight;
                    if(x>=r.get(0).getX()&&x<=r.get(2).getX()&&y>=r.get(0).getY()&&y<=r.get(2).getY()){
                        hits++;minX=Math.min(minX,x);maxX=Math.max(maxX,x);minY=Math.min(minY,y);maxY=Math.max(maxY,y);
                    }
                }
                int index=roiIndex;
                if("top".equals(role))roiCounts[roiIndex++]=hits;
                if(rgb!=null&&("top".equals(role)&&index%3==0||"left".equals(role)||"right".equals(role))&&!motorMaterial(rgb,homography,r,ref.imageWidth,ref.imageHeight,scale))return null;
                int minimumHits="top".equals(role)?index%3==0?6:0:("left".equals(role)||"right".equals(role))?8:6;
                double minimumSpread="top".equals(role)&&index%3!=0?.05:("left".equals(role)||"right".equals(role))?.20:.12;
                if("top".equals(role)&&index%3!=0){
                    if(!appearance(frame,homography,r,ref.imageWidth,ref.imageHeight,ref.spec.getReferenceId(),index))return null;
                    continue;
                }
                if(hits<minimumHits||maxX-minX<(r.get(2).getX()-r.get(0).getX())*minimumSpread
                        ||maxY-minY<(r.get(2).getY()-r.get(0).getY())*minimumSpread)return null;
            }
            if("top".equals(role)&&!dev.chyken.assembl_ar.workflow.phase.Phase2MotorBracketFlow.acceptsTop(roiCounts[0],roiCounts[3],roiCounts[1]+roiCounts[2],roiCounts[4]+roiCounts[5]))return null;
            double left=1,top=1,right=0,bottom=0;
            for(List<VisionPoint> region:regions)for(VisionPoint p:region){left=Math.min(left,p.getX());top=Math.min(top,p.getY());right=Math.max(right,p.getX());bottom=Math.max(bottom,p.getY());}
            if(regions.isEmpty()){left=0;top=0;right=1;bottom=1;}
            // Display/track the hardware extent, never the reference photo's tabletop.
            MatOfPoint2f rectangle=new MatOfPoint2f(new Point(left*ref.imageWidth,top*ref.imageHeight),new Point(right*ref.imageWidth,top*ref.imageHeight),
                    new Point(right*ref.imageWidth,bottom*ref.imageHeight),new Point(left*ref.imageWidth,bottom*ref.imageHeight)),projected=new MatOfPoint2f();
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
    private static boolean appearance(Mat gray,Mat homography,List<VisionPoint> roi,int width,int height,String referenceId,int index){
        MatOfPoint2f source=new MatOfPoint2f(),image=new MatOfPoint2f(),square=new MatOfPoint2f(new Point(0,0),new Point(31,0),new Point(31,63),new Point(0,63));Mat transform=null,patch=new Mat();
        try{Point[] p=new Point[4];for(int i=0;i<4;i++)p[i]=new Point(roi.get(i).getX()*width,roi.get(i).getY()*height);source.fromArray(p);Core.perspectiveTransform(source,image,homography);
            for(Point point:image.toArray())if(point.x<0||point.y<0||point.x>=gray.cols()||point.y>=gray.rows())return false;
            transform=Imgproc.getPerspectiveTransform(image,square);Imgproc.warpPerspective(gray,patch,transform,new Size(32,64));return Phase2AppearanceTemplates.supports(referenceId,index,patch);
        }finally{source.release();image.release();square.release();patch.release();if(transform!=null)transform.release();}
    }
    /** A cheap necessary condition only: no yellow TT housing anywhere means no
     * positive motor ROI can pass the stricter projected material test below. */
    private static boolean housingPossible(Mat rgb){
        Mat small=new Mat();try{Imgproc.resize(rgb,small,new Size(160,100),0,0,Imgproc.INTER_AREA);byte[] data=new byte[160*100*3];small.get(0,0,data);int count=0;
            for(int i=0;i<data.length;i+=3){int r=data[i]&255,g=data[i+1]&255,b=data[i+2]&255,max=Math.max(r,Math.max(g,b)),min=Math.min(r,Math.min(g,b));
                if(max>35&&max-min>max*.3&&g>=r*.57&&g<=r*1.35&&b<r*.65&&++count>=16)return true;
            }return false;
        }finally{small.release();}
    }
    /** Supplemental local TT housing material support. A white PCB/shadow is not a motor. */
    private static boolean motorMaterial(Mat rgb,Mat h,List<VisionPoint> roi,int width,int height,double scale){
        MatOfPoint2f src=new MatOfPoint2f(),mapped=new MatOfPoint2f(),square=new MatOfPoint2f(new Point(0,0),new Point(31,0),new Point(31,63),new Point(0,63));Mat transform=null,patch=new Mat();
        try{
            Point[] points=new Point[4];for(int i=0;i<4;i++)points[i]=new Point(roi.get(i).getX()*width,roi.get(i).getY()*height);src.fromArray(points);Core.perspectiveTransform(src,mapped,h);
            Point[] p=mapped.toArray();for(Point point:p){point.x/=scale;point.y/=scale;if(!Double.isFinite(point.x+point.y)||point.x<0||point.y<0||point.x>=rgb.cols()||point.y>=rgb.rows())return false;}
            mapped.fromArray(p);transform=Imgproc.getPerspectiveTransform(mapped,square);Imgproc.warpPerspective(rgb,patch,transform,new Size(32,64));byte[] pixels=new byte[32*64*3];patch.get(0,0,pixels);int yellow=0;
            for(int i=0;i<pixels.length;i+=3){int red=pixels[i]&255,green=pixels[i+1]&255,blue=pixels[i+2]&255;int max=Math.max(red,Math.max(green,blue)),min=Math.min(red,Math.min(green,blue));if(max>35&&max-min>max*.3&&green>=red*.57&&green<=red*1.35&&blue<red*.65)yellow++;}
            return yellow>=32*64*.18;
        }finally{src.release();mapped.release();square.release();patch.release();if(transform!=null)transform.release();}
    }
    private static final class Evidence {
        final double score;final List<VisionPoint> corners;
        Evidence(double score,List<VisionPoint> corners) {this.score=score;this.corners=corners;}
    }
    @Override public void close(){for(Mat rows:shortlist.values())rows.release();shortlist.clear();preferred.clear();orb.clear();clahe.clear();matcher.clear();}
}
