package dev.chyken.assembl_ar.vision;

import dev.chyken.assembl_ar.camera.Phase8AssetCatalog;
import org.opencv.core.*;
import org.opencv.features2d.*;
import org.opencv.imgproc.Imgproc;
import org.opencv.imgproc.CLAHE;
import org.opencv.calib3d.Calib3d;
import java.util.*;

/** Sensor and board inliers are independent. Only the strongest local candidates need a context match. */
final class Phase8ReferenceMatcher implements AutoCloseable {
    private final Map<ReferenceDatabase.PreparedOrbVariant,Split> splits=new IdentityHashMap<>();
    private final ReferenceDatabase database;
    private final ORB orb;
    private final CLAHE clahe;
    private final DiagnosticHammingMatcher matcher=new DiagnosticHammingMatcher();
    Phase8ReferenceMatcher(ReferenceDatabase database,VisionConfig config){this.database=database;orb=ORB.create(3200,1.2f,8,31,0,2,ORB.HARRIS_SCORE,31,10);clahe=OpenCvVisionUtils.createClahe(config);}
    RecognitionResult inspect(Mat gray,long time,int step){
        long start=android.os.SystemClock.elapsedRealtimeNanos();Mat work=new Mat(),descriptors=new Mat(),empty=new Mat();MatOfKeyPoint keypoints=new MatOfKeyPoint();
        double scale=Math.min(1,1280.0/Math.max(gray.cols(),gray.rows()));
        double front=0,back=0,placed=0,reversed=0,invalid=0,inward=0,missing=0;List<VisionPoint> corners=Collections.emptyList();
        database.acquireForRecognition();
        try{
            if(scale<1)Imgproc.resize(gray,work,new Size(),scale,scale,Imgproc.INTER_AREA);else gray.copyTo(work);
            DiagnosticHammingMatcher.normalize(clahe,work,work);DiagnosticHammingMatcher.extract(orb,work,empty,keypoints,descriptors);KeyPoint[] live=keypoints.toArray();DiagnosticHammingMatcher.captureLive(live,descriptors,scale);
            List<Candidate> candidates=new ArrayList<>();
            if(!descriptors.empty())for(ReferenceDatabase.PreparedReference ref:database.preparedReferences()){
                String role=Phase8AssetCatalog.referenceRole(ref.spec.getReferenceId());
                if(role==null||(step==1)==Phase8AssetCatalog.installed(role))continue;
                for(ReferenceDatabase.PreparedOrbVariant variant:ref.orbVariants){
                    if(!variant.hasOrbData())continue;
                    Match local=match(ref,variant,live,descriptors,scale,false);if(local!=null)candidates.add(new Candidate(ref,variant,role,local));
                }
            }
            candidates.sort((a,b)->Double.compare(b.local.score,a.local.score));
            Map<String,Integer> tried=new HashMap<>();
            for(Candidate c:candidates){
                int n=tried.getOrDefault(c.role,0);if(n>=4)continue;tried.put(c.role,n+1);
                Match found=step==1?c.local:match(c.ref,c.variant,live,descriptors,scale,true);if(found==null)continue;
                if(step==1){if(c.role.equals("front")&&found.score>front){front=found.score;corners=found.actual;}if(c.role.equals("back")&&found.score>back){back=found.score;if(back>front)corners=found.actual;}}
                else{
                    Phase8Observation.State placement=Phase8Observation.placement(found.expected,found.actual);
                    if(c.role.equals("assembly")&&placement==Phase8Observation.State.PLACED&&found.score>placed){placed=found.score;corners=found.actual;}
                    // A positive reference whose sensor moved relative to its board is never accepted.
                    if(c.role.equals("assembly")&&placement==Phase8Observation.State.INVALID&&found.score>reversed)reversed=found.score;
                    if(c.role.equals("invalid")&&placement==Phase8Observation.State.PLACED){
                        if(Phase8AssetCatalog.inwardReference(c.ref.spec.getJpegAssetPath()))inward=Math.max(inward,found.score);else missing=Math.max(missing,found.score);
                        if(found.score>invalid){invalid=found.score;if(invalid>placed)corners=found.actual;}
                    }
                }
            }
            Phase8Observation.State state=Phase8Observation.classify(step,front,back,placed,reversed,invalid);
            if(state==Phase8Observation.State.INVALID&&inward>0&&inward>missing)state=Phase8Observation.State.INWARD;
            double score=Math.max(Math.max(front,back),Math.max(invalid,Math.max(placed,reversed)));
            Phase8Observation result=new Phase8Observation(state,score);
            return RecognitionResult.builder(time).identity("phase8:photo","ultrasonic_distance_sensor","Distance sensor")
                    .method(corners.size()==4?RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY:RecognitionResult.DetectionMethod.NONE)
                    .validation(result.accepted(step)?RecognitionResult.ValidationState.EXPECTED_COMPONENT:state==Phase8Observation.State.FLIPPED||state==Phase8Observation.State.INVALID||state==Phase8Observation.State.INWARD?RecognitionResult.ValidationState.WRONG_ORIENTATION:RecognitionResult.ValidationState.NO_COMPONENT)
                    .corners(corners).matches(live.length,(int)score,(int)score,score>0?1:0).confidence(result.accepted(step)?.85:0)
                    .latency(new RecognitionResult.Latency(0,0,0,0,(android.os.SystemClock.elapsedRealtimeNanos()-start)/1e6)).build().withPhase8Observation(result);
        }finally{database.releaseAfterRecognition();work.release();descriptors.release();empty.release();keypoints.release();}
    }
    private Match match(ReferenceDatabase.PreparedReference ref,ReferenceDatabase.PreparedOrbVariant variant,KeyPoint[] live,Mat descriptors,double scale,boolean installed){DiagnosticHammingMatcher.context(ref.spec);
        Split split=splits.get(variant);if(split==null){split=new Split(ref,variant);splits.put(variant,split);}
        List<VisionPoint> c=ref.spec.getPlanarCornersNormalized();double l=c.get(0).getX()*ref.imageWidth,t=c.get(0).getY()*ref.imageHeight,r=c.get(2).getX()*ref.imageWidth,b=c.get(2).getY()*ref.imageHeight;
        List<Point> module=new ArrayList<>(),moduleLive=new ArrayList<>(),board=new ArrayList<>(),boardLive=new ArrayList<>();
        collect(split.local,live,descriptors,module,moduleLive);
        if(installed)collect(split.context,live,descriptors,board,boardLive);
        Mat mh=homography(module,moduleLive,10,Math.max(15,(r-l)*.45),Math.max(12,(b-t)*(sensorCaps(ref)?.08:Phase8AssetCatalog.installed(Phase8AssetCatalog.referenceRole(ref.spec.getReferenceId()))?.20:.45))),bh=installed?homography(board,boardLive,14,15,15):null;
        try{
            if(mh==null||installed&&bh==null)return null;
            List<VisionPoint> actual=project(c,ref.imageWidth,ref.imageHeight,mh,scale);
            if(actual.size()!=4)return null;
            return new Match(inliers(module,moduleLive,mh),actual,installed?project(c,ref.imageWidth,ref.imageHeight,bh,scale):actual);
        }finally{if(mh!=null)mh.release();if(bh!=null)bh.release();}
    }
    private void collect(Features source,KeyPoint[] live,Mat descriptors,List<Point> reference,List<Point> target){
        if(source.descriptors.empty())return;List<DMatch> good=new ArrayList<>();
        good.addAll(matcher.ratioMatches(source.descriptors,descriptors,.76,64));
        good.sort(Comparator.comparingDouble(p->p.distance));Set<Integer> used=new HashSet<>();for(DMatch m:good)if(used.add(m.trainIdx)){reference.add(source.keys[m.queryIdx].pt);target.add(live[m.trainIdx].pt);}
    }
    private static final class Features{
        final Mat descriptors;final KeyPoint[] keys;
        Features(ReferenceDatabase.PreparedOrbVariant v,List<Integer> indexes){
            keys=new KeyPoint[indexes.size()];descriptors=indexes.isEmpty()?new Mat():new Mat(indexes.size(),v.descriptors.cols(),v.descriptors.type());if(indexes.isEmpty())return;
            int stride=v.descriptors.cols()*(int)v.descriptors.elemSize();byte[] source=new byte[v.descriptors.rows()*stride],selected=new byte[indexes.size()*stride];v.descriptors.get(0,0,source);
            for(int i=0;i<indexes.size();i++){int index=indexes.get(i);keys[i]=v.keypoints[index];System.arraycopy(source,index*stride,selected,i*stride,stride);}descriptors.put(0,0,selected);
        }
    }
    private static boolean sensorCaps(ReferenceDatabase.PreparedReference ref){
        return "assembly".equals(Phase8AssetCatalog.referenceRole(ref.spec.getReferenceId()))
                ||Phase8AssetCatalog.inwardReference(ref.spec.getJpegAssetPath());
    }
    private static final class Split{
        final Features local,context;
        Split(ReferenceDatabase.PreparedReference ref,ReferenceDatabase.PreparedOrbVariant v){
            List<VisionPoint> c=ref.spec.getPlanarCornersNormalized();
            double l=c.get(0).getX()*ref.imageWidth,t=c.get(0).getY()*ref.imageHeight,r=c.get(2).getX()*ref.imageWidth,b=c.get(2).getY()*ref.imageHeight;
            boolean cylinders=sensorCaps(ref),inward=Phase8AssetCatalog.inwardReference(ref.spec.getJpegAssetPath());
            List<Integer> a=new ArrayList<>(),other=new ArrayList<>();
            for(int i=0;i<v.keypoints.length;i++){
                Point p=v.keypoints[i].pt;double u=(p.x-l)/(r-l),vertical=(p.y-t)/(b-t);
                if(p.x>=l&&p.x<=r&&p.y>=t&&p.y<=b){
                    if(!cylinders||vertical>.08&&vertical<(inward?.90:.72)&&(u>.08&&u<.45||u>.55&&u<.92))a.add(i);
                }else if(p.x<l-(r-l)*.15||p.x>r+(r-l)*.15||p.y<t-(b-t)*.15||p.y>b+(b-t)*.15)other.add(i);
            }
            local=new Features(v,a);context=new Features(v,other);
        }
    }
    private static final class Candidate{final ReferenceDatabase.PreparedReference ref;final ReferenceDatabase.PreparedOrbVariant variant;final String role;final Match local;Candidate(ReferenceDatabase.PreparedReference ref,ReferenceDatabase.PreparedOrbVariant variant,String role,Match local){this.ref=ref;this.variant=variant;this.role=role;this.local=local;}}
    private static int inliers(List<Point> reference,List<Point> live,Mat h){
        MatOfPoint2f src=new MatOfPoint2f(),dst=new MatOfPoint2f();
        try{src.fromList(reference);Core.perspectiveTransform(src,dst,h);Point[] projected=dst.toArray();int count=0;
            for(int i=0;i<projected.length;i++)if(Math.hypot(projected[i].x-live.get(i).x,projected[i].y-live.get(i).y)<=3.5)count++;return count;
        }finally{src.release();dst.release();}
    }
    private Mat homography(List<Point> a,List<Point> b,int minimum,double minimumWidth,double minimumHeight){
        if(a.size()<minimum)return null;MatOfPoint2f src=new MatOfPoint2f(),dst=new MatOfPoint2f();Mat mask=new Mat(),h=null;
        try{src.fromList(a);dst.fromList(b);h=DiagnosticHammingMatcher.homography(src,dst,Calib3d.RANSAC,3.5,mask,1800,.995);
            int n=h.empty()?0:Core.countNonZero(mask);if(n<minimum||n<a.size()*.5){h.release();return null;}
            double minX=Double.MAX_VALUE,minY=minX,maxX=-minX,maxY=-minX;
            for(int i=0;i<a.size();i++)if(mask.get(i,0)[0]!=0){Point p=a.get(i);minX=Math.min(minX,p.x);maxX=Math.max(maxX,p.x);minY=Math.min(minY,p.y);maxY=Math.max(maxY,p.y);}
            // A terminal row or a small unchanged patch alone cannot certify the whole module.
            if(maxX-minX<minimumWidth||maxY-minY<minimumHeight){h.release();return null;}return h;
        }finally{src.release();dst.release();mask.release();}
    }
    private List<VisionPoint> project(List<VisionPoint> corners,int width,int height,Mat h,double scale){
        MatOfPoint2f src=new MatOfPoint2f(),dst=new MatOfPoint2f();
        try{List<Point> p=new ArrayList<>();for(VisionPoint c:corners)p.add(new Point(c.getX()*width,c.getY()*height));src.fromList(p);Core.perspectiveTransform(src,dst,h);
            Point[] q=dst.toArray();double area=Imgproc.contourArea(dst,true);if(!Double.isFinite(area)||area<100)return Collections.emptyList();
            List<VisionPoint> result=new ArrayList<>();for(Point c:q){if(!Double.isFinite(c.x+c.y))return Collections.emptyList();result.add(new VisionPoint(c.x/scale,c.y/scale));}return result;
        }finally{src.release();dst.release();}
    }
    private static final class Match{final double score;final List<VisionPoint> actual,expected;Match(double score,List<VisionPoint> actual,List<VisionPoint> expected){this.score=score;this.actual=actual;this.expected=expected;}}
    public void close(){for(Split split:splits.values()){split.local.descriptors.release();split.context.descriptors.release();}splits.clear();orb.clear();clahe.clear();matcher.clear();}
}
