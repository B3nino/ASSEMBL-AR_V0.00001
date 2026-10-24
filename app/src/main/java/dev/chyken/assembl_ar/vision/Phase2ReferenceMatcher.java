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
    private final ORB hardwareOrb=ORB.create(2400,1.2f,8,31,0,2,ORB.HARRIS_SCORE,31,10);
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
        Mat work=new Mat(),rawWork=new Mat(),descriptors=new Mat(),empty=new Mat();MatOfKeyPoint points=new MatOfKeyPoint();
        double scale=Math.min(1,960.0/Math.max(gray.cols(),gray.rows()));
        database.acquireForRecognition();
        try {
            if(scale<1) Imgproc.resize(gray,rawWork,new Size(),scale,scale,Imgproc.INTER_AREA); else gray.copyTo(rawWork);
            DiagnosticHammingMatcher.State extracted=DiagnosticHammingMatcher.snapshot();
            boolean reuse=stage!=5&&extracted.live!=null&&scale==1;
            if(reuse){rawWork.copyTo(work);extracted.live.copyTo(descriptors);points.fromArray(extracted.liveKeys);}
            else {
            DiagnosticHammingMatcher.normalize(clahe,rawWork,work);
            // Allocate the existing feature budget to the motor housings and nearby
            // brackets/shafts instead of the tabletop or a hard cast shadow. Color
            // only proposes windows; all identity, bracket and RANSAC gates remain.
            if(stage==5&&rgb!=null)hardwareFeatureMask(rgb,work.size(),empty);
            DiagnosticHammingMatcher.extract(stage==5?hardwareOrb:orb,work,empty,points,descriptors);
            }
            KeyPoint[] live=points.toArray();DiagnosticHammingMatcher.captureLive(live,descriptors,scale);
            long featuresDone=android.os.SystemClock.elapsedRealtimeNanos();
            double positive=0,negative=0;String view=null;List<VisionPoint> corners=Collections.emptyList();Evidence located=null,verifiedEvidence=null;String locatedId=null,verifiedId=null;
            Map<String,Double> viewScores=new HashMap<>();
            boolean housingPossible=stage!=5||rgb==null||housingPossible(rgb);
            List<ReferenceDatabase.PreparedReference> candidates=requestedView==null?database.preparedReferences():ranked(descriptors,stage,requestedView,housingPossible);
            referenceLoop: if(!descriptors.empty()) for(ReferenceDatabase.PreparedReference reference:candidates) {
                String role=Phase2AssetCatalog.referenceRole(reference.spec.getReferenceId());
                if(role==null || stage!=5&&role.equals("invalid") || stage==1&&!role.equals("brackets") || stage==2&&!role.equals("motors")
                        || stage==5&&(role.equals("brackets")||role.equals("motors"))) continue;
                if(stage==5&&!housingPossible&&!role.equals("invalid"))continue;
                // A bare-board correspondence cannot establish absence while a
                // yellow housing is visibly present (see absenceConfirmed below).
                // Do not spend another full BF pass on evidence that cannot affect
                // this verdict. Missing sides still fail their independent ROIs.
                if(stage==5&&housingPossible&&role.equals("invalid"))continue;
                for(ReferenceDatabase.PreparedOrbVariant variant:reference.orbVariants) {
                    if(!variant.hasOrbData())continue;
                    // Room lighting changes contrast, not image polarity. Live
                    // installed-hardware checks use the normalized photo bank with
                    // automatic illumination recovery, rather than doubling every
                    // failed candidate with an artificial negative of the photo.
                    // Exhaustive diagnostic inspection retains both variants.
                    if(requestedView!=null&&variant.photometricVariant!=ReferenceDatabase.PhotometricVariant.NORMALIZED)continue;
                    Evidence evidence=match(reference,variant,live,descriptors,scale,role,work.cols(),work.rows(),work,rawWork,rgb);
                    if(evidence==null)continue;
                    if(!role.equals("invalid")&&!evidence.bodies.isEmpty()&&(located==null||evidence.bodies.size()>located.bodies.size()||evidence.bodies.size()==located.bodies.size()&&evidence.score>located.score)){located=evidence;locatedId=reference.spec.getReferenceId();}
                    if(!evidence.complete)continue;
                    if(role.equals("invalid")){negative=Math.max(negative,evidence.score);if(!housingPossible&&negative>=16)break referenceLoop;continue;}
                    viewScores.merge(role,evidence.score,Math::max);
                    if(evidence.score>positive) {positive=evidence.score;view=role;corners=evidence.corners;verifiedEvidence=evidence;verifiedId=reference.spec.getReferenceId();preferred.put(stage+":"+requestedView,reference);}
                    if(requestedView!=null)break;
                }
            }
            if(positive<16)preferred.remove(stage+":"+requestedView);
            double competing=0;
            if(stage==5)for(Map.Entry<String,Double> score:viewScores.entrySet())
                if(!score.getKey().equals(view))competing=Math.max(competing,score.getValue());
            if(verifiedEvidence!=null){located=verifiedEvidence;locatedId=verifiedId;}
            // A bare-PCB correspondence also exists on a correctly assembled board.
            // Without independently established absence it is not a missing-part alert.
            Phase2Observation observation=new Phase2Observation(view,positive,negative,located==null?-1:located.bodies.size(),competing,rgb==null||!housingPossible);
            List<RecognitionResult.InstanceObservation> instances=new ArrayList<>();
            String component=stage==1?"motor_bracket":stage==2?"gear_motor_6v":"main_board";
            if(located!=null){for(List<VisionPoint> body:located.bodies)instances.add(new RecognitionResult.InstanceObservation(locatedId,stage==1?"motor_bracket":"gear_motor_6v",RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY,body,located.complete?.8:.65));if(corners.isEmpty())corners=located.bodies.get(0);}
            if(primary==null) primary=RecognitionResult.builder(timestamp)
                    .identity(locatedId==null?"phase2:scene":locatedId,component,stage==1?"Motor brackets":stage==2?"TT motors":"Motor assembly")
                    .method(corners.isEmpty()?RecognitionResult.DetectionMethod.NONE:RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY)
                    .matches(live.length,(int)positive,(int)positive,positive>0?1:0)
                    .corners(corners).instances(instances).confidence(observation.referenceSupported?.8:0)
                    .validation(observation.referenceSupported?RecognitionResult.ValidationState.EXPECTED_COMPONENT:RecognitionResult.ValidationState.NO_COMPONENT)
                    .build();
            RecognitionResult.Latency base=primary.getLatency();
            double featureMs=(featuresDone-started)/1_000_000.0;
            double matchingMs=(android.os.SystemClock.elapsedRealtimeNanos()-featuresDone)/1_000_000.0;
            return primary.withPhase2Observation(observation).withLatency(new RecognitionResult.Latency(
                    base.getFeatureExtractionMs()+featureMs,base.getMatchingMs()+matchingMs,
                    base.getSpatialValidationMs(),base.getCircularFallbackMs(),base.getTotalMs()+featureMs+matchingMs));
        } finally {database.releaseAfterRecognition();work.release();rawWork.release();descriptors.release();empty.release();points.release();}
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
        // One requested view, one strongest negative and one competing view per
        // camera attempt. The old four-full-reference minimum could take seconds
        // and consume the deadline before reaching its competing-view check.
        // Alternate the top two proposals, with a bounded exploration slot on
        // every fourth miss; a successful reference stays preferred.
        LinkedHashSet<ReferenceDatabase.PreparedReference> result=new LinkedHashSet<>();
        ReferenceDatabase.PreparedReference last=preferred.get(stage+":"+view);
        if(primary.contains(last))result.add(last);
        else if(!primary.isEmpty())result.add(primary.get(Math.floorMod(start,4)==3?Math.floorMod(start/4+2,primary.size()):Math.floorMod(start,Math.min(2,primary.size()))));
        if(!negative.isEmpty()&&!housingPossible)result.add(negative.get(0));
        if(!competing.isEmpty())result.add(competing.get(0));
        return new ArrayList<>(result);
    }
    private Evidence match(ReferenceDatabase.PreparedReference ref,ReferenceDatabase.PreparedOrbVariant variant,
                           KeyPoint[] live,Mat descriptors,double scale,String role,int width,int height,Mat frame,Mat raw,Mat rgb) {DiagnosticHammingMatcher.context(ref.spec);
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
            int roiIndex=0;int[] roiCounts=new int[6];boolean complete=!"top".equals(role)||topViewTransformSupported(homography,regions,ref.imageWidth,ref.imageHeight);List<List<VisionPoint>> bodies=new ArrayList<>();
            for(List<VisionPoint> r:regions) {
                int hits=0;double minX=1,minY=1,maxX=0,maxY=0;
                for(int i=0;i<source.size();i++) if(accepted[i]!=0) {
                    double x=source.get(i).x/ref.imageWidth,y=source.get(i).y/ref.imageHeight;
                    if(x>=r.get(0).getX()&&x<=r.get(2).getX()&&y>=r.get(0).getY()&&y<=r.get(2).getY()){
                        hits++;minX=Math.min(minX,x);maxX=Math.max(maxX,x);minY=Math.min(minY,y);maxY=Math.max(maxY,y);
                    }
                }
                int index=roiIndex++;
                if("top".equals(role))roiCounts[index]=hits;
                boolean motor="top".equals(role)&&index%3==0||"left".equals(role)||"right".equals(role);
                boolean material=rgb==null||!motor||motorMaterial(rgb,homography,r,ref.imageWidth,ref.imageHeight,scale);
                int minimumHits="top".equals(role)?index%3==0?6:0:("left".equals(role)||"right".equals(role))?8:6;
                double minimumSpread="top".equals(role)&&index%3!=0?.05:("left".equals(role)||"right".equals(role))?.20:.12;
                if("top".equals(role)&&index%3!=0){
                    if(!appearance(frame,homography,r,ref.imageWidth,ref.imageHeight,ref.spec.getReferenceId(),index)
                            ||!darkBracket(raw,homography,r,regions.get(index/3*3),ref.imageWidth,ref.imageHeight))complete=false;
                    continue;
                }
                boolean supported=material&&hits>=minimumHits&&maxX-minX>=(r.get(2).getX()-r.get(0).getX())*minimumSpread
                        &&maxY-minY>=(r.get(2).getY()-r.get(0).getY())*minimumSpread;
                if(android.util.Log.isLoggable("AssemblArPhase2Roi",android.util.Log.DEBUG))android.util.Log.d("AssemblArPhase2Roi",ref.spec.getReferenceId()+" roi="+index+" hits="+hits+" min="+minimumHits+" spread="+((maxX-minX)/(r.get(2).getX()-r.get(0).getX()))+","+((maxY-minY)/(r.get(2).getY()-r.get(0).getY()))+" material="+material+" supported="+supported);
                if(!supported)complete=false;
                if(supported&&(motor||"brackets".equals(role)||"motors".equals(role))){
                    // Track the rigid motor plus its mounting bands, so textureless
                    // yellow plastic is not the only available LK seed surface.
                    List<VisionPoint> outline="top".equals(role)?hardwareExtent(regions,index):r;
                    List<VisionPoint> body=projectRegion(ref,homography,outline,scale,width,height);if(body.size()==4)bodies.add(body);else complete=false;
                }
            }
            if("top".equals(role)&&!dev.chyken.assembl_ar.workflow.phase.Phase2MotorBracketFlow.acceptsTop(roiCounts[0],roiCounts[3],roiCounts[1]+roiCounts[2],roiCounts[4]+roiCounts[5]))complete=false;
            if(!complete&&bodies.isEmpty())return null;
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
                return new Evidence(inliers*(inliers/(double)source.size()),corners,complete,bodies);
            } finally {rectangle.release();projected.release();}
        } finally {src.release();dst.release();mask.release();if(homography!=null)homography.release();}
    }
    private static List<VisionPoint> projectRegion(ReferenceDatabase.PreparedReference ref,Mat h,List<VisionPoint> roi,double scale,int width,int height){
        MatOfPoint2f source=new MatOfPoint2f(),mapped=new MatOfPoint2f();
        try{Point[] p=new Point[4];for(int i=0;i<4;i++)p[i]=new Point(roi.get(i).getX()*ref.imageWidth,roi.get(i).getY()*ref.imageHeight);source.fromArray(p);Core.perspectiveTransform(source,mapped,h);
            double area=Imgproc.contourArea(mapped,true);if(!Double.isFinite(area)||area<36||area>width*(double)height)return Collections.emptyList();List<VisionPoint> result=new ArrayList<>();
            for(Point point:mapped.toArray()){if(!Double.isFinite(point.x+point.y)||point.x<0||point.y<0||point.x>=width||point.y>=height)return Collections.emptyList();result.add(new VisionPoint(point.x/scale,point.y/scale));}return result;
        }finally{source.release();mapped.release();}
    }
    /** A top photograph may rotate/scale and tilt modestly, but may not collapse
     * its plane into an almost edge-on side view. Evaluate the local Jacobian at
     * BOTH motor sites; a single global inlier count cannot distinguish repeats. */
    static boolean topViewTransformSupported(Mat h,List<List<VisionPoint>> regions,int width,int height){
        if(regions.size()!=6||h.empty())return false;double[] m=new double[9];h.get(0,0,m);
        for(int index:new int[]{0,3}){
            List<VisionPoint> r=regions.get(index);double x=(r.get(0).getX()+r.get(2).getX())*.5*width,y=(r.get(0).getY()+r.get(2).getY())*.5*height;
            double z=m[6]*x+m[7]*y+m[8];if(!Double.isFinite(z)||Math.abs(z)<1e-8)return false;
            double u=(m[0]*x+m[1]*y+m[2])/z,v=(m[3]*x+m[4]*y+m[5])/z;
            double a=(m[0]-u*m[6])/z,b=(m[1]-u*m[7])/z,c=(m[3]-v*m[6])/z,d=(m[4]-v*m[7])/z;
            double det=a*d-b*c,trace=a*a+b*b+c*c+d*d,disc=Math.sqrt(Math.max(0,trace*trace-4*det*det));
            double low=(trace-disc)/2,high=(trace+disc)/2;
            if(det<=0||low<=1e-8||!Double.isFinite(high)||high/low>2.8*2.8)return false;
        }
        return true;
    }
    private static List<VisionPoint> hardwareExtent(List<List<VisionPoint>> regions,int first){
        double l=1,t=1,r=0,b=0;for(int i=first;i<first+3;i++)for(VisionPoint p:regions.get(i)){l=Math.min(l,p.getX());t=Math.min(t,p.getY());r=Math.max(r,p.getX());b=Math.max(b,p.getY());}
        return Arrays.asList(new VisionPoint(l,t),new VisionPoint(r,t),new VisionPoint(r,b),new VisionPoint(l,b));
    }
    private static boolean appearance(Mat gray,Mat homography,List<VisionPoint> roi,int width,int height,String referenceId,int index){
        MatOfPoint2f source=new MatOfPoint2f(),image=new MatOfPoint2f(),square=new MatOfPoint2f(new Point(0,0),new Point(31,0),new Point(31,63),new Point(0,63));Mat transform=null,patch=new Mat();
        try{Point[] p=new Point[4];for(int i=0;i<4;i++)p[i]=new Point(roi.get(i).getX()*width,roi.get(i).getY()*height);source.fromArray(p);Core.perspectiveTransform(source,image,homography);
            for(Point point:image.toArray())if(point.x<0||point.y<0||point.x>=gray.cols()||point.y>=gray.rows())return false;
            transform=Imgproc.getPerspectiveTransform(image,square);Imgproc.warpPerspective(gray,patch,transform,new Size(32,64));return Phase2AppearanceTemplates.supports(referenceId,index,patch);
        }finally{source.release();image.release();square.release();patch.release();if(transform!=null)transform.release();}
    }
    /** Photometric normalization can make an erased/empty band resemble a uniform
     * plastic template. Require raw local dark material relative to its own adjacent
     * housing as well; room exposure or CLAHE cannot supply missing hardware. */
    private static boolean darkBracket(Mat raw,Mat h,List<VisionPoint> bracket,List<VisionPoint> housing,int width,int height){
        MatOfPoint2f source=new MatOfPoint2f(),mapped=new MatOfPoint2f(),square=new MatOfPoint2f(new Point(0,0),new Point(15,0),new Point(15,31),new Point(0,31));
        Mat transform=null,patch=new Mat();double motorMean=0;byte[] band=new byte[16*32];
        try{
            for(int part=0;part<2;part++){
                List<VisionPoint> roi=part==0?housing:bracket;Point[] p=new Point[4];for(int i=0;i<4;i++)p[i]=new Point(roi.get(i).getX()*width,roi.get(i).getY()*height);source.fromArray(p);Core.perspectiveTransform(source,mapped,h);
                for(Point v:mapped.toArray())if(!Double.isFinite(v.x+v.y)||v.x<0||v.y<0||v.x>=raw.cols()||v.y>=raw.rows())return false;
                if(transform!=null)transform.release();transform=Imgproc.getPerspectiveTransform(mapped,square);Imgproc.warpPerspective(raw,patch,transform,new Size(16,32));
                if(part==0)motorMean=Core.mean(patch).val[0];else patch.get(0,0,band);
            }
            double threshold=Math.max(12,motorMean*.70);int dark=0;
            for(byte value:band)if((value&255)<threshold)dark++;
            // Tight projected bands still include shiny screw heads and light PCB
            // gaps. Require real dark support without treating their mean brightness
            // as plastic color; valid held-out bands contain 28–40% dark material.
            return dark>=band.length*.20;
        }finally{source.release();mapped.release();square.release();patch.release();if(transform!=null)transform.release();}
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
    private static void hardwareFeatureMask(Mat rgb,Size size,Mat output){
        Mat small=new Mat(),yellow=new Mat(),hierarchy=new Mat();List<MatOfPoint> contours=new ArrayList<>();
        Mat kernel=Imgproc.getStructuringElement(Imgproc.MORPH_RECT,new Size(3,3));
        try{
            Imgproc.resize(rgb,small,new Size(160,100),0,0,Imgproc.INTER_AREA);
            byte[] pixels=new byte[160*100*3],bits=new byte[160*100];small.get(0,0,pixels);
            for(int i=0,j=0;i<pixels.length;i+=3,j++){
                int r=pixels[i]&255,g=pixels[i+1]&255,b=pixels[i+2]&255,max=Math.max(r,Math.max(g,b)),min=Math.min(r,Math.min(g,b));
                if(max>35&&max-min>max*.3&&g>=r*.57&&g<=r*1.35&&b<r*.65)bits[j]=(byte)255;
            }
            yellow.create(100,160,CvType.CV_8UC1);yellow.put(0,0,bits);Imgproc.morphologyEx(yellow,yellow,Imgproc.MORPH_CLOSE,kernel);
            Imgproc.findContours(yellow,contours,hierarchy,Imgproc.RETR_EXTERNAL,Imgproc.CHAIN_APPROX_SIMPLE);
            Mat windows=Mat.zeros(100,160,CvType.CV_8UC1);try{
                for(MatOfPoint contour:contours)if(Imgproc.contourArea(contour)>=8){
                    Rect box=Imgproc.boundingRect(contour);int pad=(int)Math.ceil(Math.max(box.width,box.height)*.5);
                    Imgproc.rectangle(windows,new Point(Math.max(0,box.x-pad),Math.max(0,box.y-pad)),new Point(Math.min(159,box.x+box.width+pad),Math.min(99,box.y+box.height+pad)),new Scalar(255),-1);
                }
                // An empty proposal must retain full-frame negative/wrong-item checks.
                if(Core.countNonZero(windows)>0)Imgproc.resize(windows,output,size,0,0,Imgproc.INTER_NEAREST);
            }finally{windows.release();}
        }finally{small.release();yellow.release();hierarchy.release();kernel.release();for(Mat c:contours)c.release();}
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
        final double score;final List<VisionPoint> corners;final boolean complete;final List<List<VisionPoint>> bodies;
        Evidence(double score,List<VisionPoint> corners,boolean complete,List<List<VisionPoint>> bodies) {this.score=score;this.corners=corners;this.complete=complete;this.bodies=bodies;}
    }
    @Override public void close(){for(Mat rows:shortlist.values())rows.release();shortlist.clear();preferred.clear();orb.clear();hardwareOrb.clear();clahe.clear();matcher.clear();}
}
