package dev.chyken.assembl_ar.vision;

import dev.chyken.assembl_ar.camera.Phase3AssetCatalog;
import org.opencv.core.*;
import org.opencv.features2d.*;
import org.opencv.imgproc.Imgproc;
import org.opencv.imgproc.CLAHE;
import org.opencv.calib3d.Calib3d;
import java.util.*;

/** Module and board homographies are independent: a shared PCB background cannot verify placement. */
final class Phase3ReferenceMatcher implements AutoCloseable {
    private final Map<ReferenceDatabase.PreparedOrbVariant,Split> splits=new IdentityHashMap<>();
    private final List<Candidate> refinements=new ArrayList<>();
    private long extractionNanos;
    private int extractedPoints;
    private String preferredReference;
    private final ReferenceDatabase database;
    private final ORB orb;
    private final CLAHE clahe;
    private final DiagnosticHammingMatcher matcher=new DiagnosticHammingMatcher();
    Phase3ReferenceMatcher(ReferenceDatabase database,VisionConfig config){this.database=database;orb=ORB.create(3200,1.2f,8,31,0,2,ORB.HARRIS_SCORE,31,10);clahe=OpenCvVisionUtils.createClahe(config);}
    RecognitionResult inspect(Mat gray,long time,int step){
        long start=android.os.SystemClock.elapsedRealtimeNanos();Mat raw=new Mat(),work=new Mat(),descriptors=new Mat(),empty=new Mat();MatOfKeyPoint keypoints=new MatOfKeyPoint();
        double scale=Math.min(1,1280.0/Math.max(gray.cols(),gray.rows()));
        double front=0,back=0,placed=0,reversed=0;List<VisionPoint> corners=Collections.emptyList();
        database.acquireForRecognition();
        try{
            refinements.clear();extractionNanos=0;extractedPoints=0;
            long extractStart=android.os.SystemClock.elapsedRealtimeNanos();
            if(scale<1)Imgproc.resize(gray,raw,new Size(),scale,scale,Imgproc.INTER_AREA);else gray.copyTo(raw);
            DiagnosticHammingMatcher.normalize(clahe,raw,work);DiagnosticHammingMatcher.extract(orb,work,empty,keypoints,descriptors);KeyPoint[] live=keypoints.toArray();DiagnosticHammingMatcher.captureLive(live,descriptors,scale);
            extractionNanos+=android.os.SystemClock.elapsedRealtimeNanos()-extractStart;extractedPoints+=live.length;
            Set<String> seen=new HashSet<>();
            List<ReferenceDatabase.PreparedReference> ordered=new ArrayList<>(database.preparedReferences());
            if(preferredReference!=null)for(int i=0;i<ordered.size();i++)if(preferredReference.equals(ordered.get(i).spec.getReferenceId())){ordered.add(0,ordered.remove(i));break;}
            referenceLoop: if(!descriptors.empty())for(ReferenceDatabase.PreparedReference ref:ordered){
                String role=Phase3AssetCatalog.referenceRole(ref.spec.getReferenceId());
                if(role==null||step==1&&role.equals("assembly")||step!=1&&!role.equals("assembly"))continue;
                String path=ref.spec.getJpegAssetPath(),name=path.substring(path.lastIndexOf('/')+1);
                if(!seen.add(role+":"+name))continue;
                for(ReferenceDatabase.PreparedOrbVariant variant:ref.orbVariants){
                    if(!variant.hasOrbData())continue;
                    Match found=match(ref,variant,live,descriptors,raw,scale,role.equals("assembly"),false);if(found==null)continue;
                    if(role.equals("front")&&found.score>front){front=found.score;corners=found.actual;}
                    if(role.equals("back")&&found.score>back){back=found.score;if(back>front)corners=found.actual;}
                    if(role.equals("assembly")){
                        Phase3Observation.State state=Phase3Observation.placement(found.expected,found.actual);
                        if(state==Phase3Observation.State.PLACED&&found.score>placed){placed=found.score;corners=found.actual;}
                        if(state==Phase3Observation.State.BACKWARD&&found.score>reversed){reversed=found.score;if(reversed>placed)corners=found.actual;}
                        // Distributed local evidence plus independent PCB geometry already
                        // establishes orientation. Extra near-duplicate photos add latency.
                        if(placed>=16||reversed>=16){preferredReference=ref.spec.getReferenceId();break referenceLoop;}
                    }
                }
            }
            // At 640×400 a small driver can lose its ORB feature budget to board printing.
            // Use the independently verified board only to locate a bounded search window.
            // Fresh driver inliers must STILL prove its identity and orientation; a board
            // homography, wire bundle, or tracking prediction can never pass this gate.
            if(step!=1&&placed<8&&reversed<8){
                refinements.sort((a,b)->Double.compare(b.contextScore,a.contextScore));
                Set<String> refined=new HashSet<>();int attempts=0;
                for(Candidate c:refinements){
                    if(!refined.add(c.ref.spec.getReferenceId()))continue;if(attempts++>=4)break;
                    Match found=match(c.ref,c.variant,live,descriptors,raw,scale,true,true);if(found==null)continue;
                    Phase3Observation.State placement=Phase3Observation.placement(found.expected,found.actual);
                    if(placement==Phase3Observation.State.PLACED&&found.score>placed){placed=found.score;corners=found.actual;}
                    if(placement==Phase3Observation.State.BACKWARD&&found.score>reversed){reversed=found.score;if(reversed>placed)corners=found.actual;}
                }
            }
            Phase3Observation.State state=Phase3Observation.State.SEARCHING;
            if(step==1){if(back>=12&&back>front*1.15)state=Phase3Observation.State.FLIPPED;else if(front>=12&&front>back*1.15)state=Phase3Observation.State.FRONT;}
            else {if(reversed>=8&&reversed>placed*1.15)state=Phase3Observation.State.BACKWARD;else if(placed>=8&&placed>reversed*1.15)state=Phase3Observation.State.PLACED;}
            double score=Math.max(Math.max(front,back),Math.max(placed,reversed));
            Phase3Observation result=new Phase3Observation(state,score);
            return RecognitionResult.builder(time).identity("phase3:photo","dual_motor_driver","Motor Driver")
                    .method(corners.size()==4?RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY:RecognitionResult.DetectionMethod.NONE)
                    .validation(result.accepted(step)?RecognitionResult.ValidationState.EXPECTED_COMPONENT:state==Phase3Observation.State.FLIPPED||state==Phase3Observation.State.BACKWARD?RecognitionResult.ValidationState.WRONG_ORIENTATION:RecognitionResult.ValidationState.NO_COMPONENT)
                    .corners(corners).matches(extractedPoints,(int)score,(int)score,score>0?1:0).confidence(result.accepted(step)?.85:0)
                    .latency(new RecognitionResult.Latency(extractionNanos/1e6,Math.max(0,android.os.SystemClock.elapsedRealtimeNanos()-start-extractionNanos)/1e6,0,0,(android.os.SystemClock.elapsedRealtimeNanos()-start)/1e6)).build().withPhase3Observation(result);
        }finally{database.releaseAfterRecognition();raw.release();work.release();descriptors.release();empty.release();keypoints.release();}
    }
    private Match match(ReferenceDatabase.PreparedReference ref,ReferenceDatabase.PreparedOrbVariant variant,KeyPoint[] live,Mat descriptors,Mat raw,double scale,boolean installed,boolean refine){DiagnosticHammingMatcher.context(ref.spec);
        Split split=splits.get(variant);if(split==null){split=new Split(ref,variant);splits.put(variant,split);}
        List<Point> module=new ArrayList<>(),moduleLive=new ArrayList<>(),board=new ArrayList<>(),boardLive=new ArrayList<>();
        List<VisionPoint> c=ref.spec.getPlanarCornersNormalized();double l=c.get(0).getX()*ref.imageWidth,t=c.get(0).getY()*ref.imageHeight,r=c.get(2).getX()*ref.imageWidth,b=c.get(2).getY()*ref.imageHeight;
        collect(split.local,live,descriptors,module,moduleLive);
        Mat mh=homography(module,moduleLive,installed?8:12,Math.max(15,(r-l)*.45),Math.max(15,(b-t)*.45));
        // Rank cheap local candidates first. Do not BF-match the entire board for every
        // reference on every frame, especially with the new wire/perspective dataset.
        if(installed&&mh==null&&!refine){refinements.add(new Candidate(ref,variant,module.size()));return null;}
        if(installed)collect(split.context,live,descriptors,board,boardLive);
        Mat bh=installed?homography(board,boardLive,16,15,15):null;
        try{
            if(installed&&mh==null&&bh!=null){
                if(refine){
                    List<VisionPoint> search=project(c,ref.imageWidth,ref.imageHeight,bh,1);
                    module.clear();moduleLive.clear();collectLocal(split.local,raw,search,module,moduleLive);
                    mh=homography(module,moduleLive,8,Math.max(15,(r-l)*.45),Math.max(15,(b-t)*.45));
                }
            }
            if(mh==null||installed&&bh==null)return null;
            List<VisionPoint> actual=project(c,ref.imageWidth,ref.imageHeight,mh,scale);
            if(actual.size()!=4)return null;
            return new Match(inliers(module,moduleLive,mh),actual,installed?project(c,ref.imageWidth,ref.imageHeight,bh,scale):actual);
        }finally{if(mh!=null)mh.release();if(bh!=null)bh.release();}
    }
    private void collect(Features source,KeyPoint[] live,Mat descriptors,List<Point> reference,List<Point> target){
        if(source.descriptors.empty()||descriptors.rows()<2)return;
        List<DMatch> good=new ArrayList<>();
        good.addAll(matcher.ratioMatches(source.descriptors,descriptors,.76,64));
        good.sort(Comparator.comparingDouble(p->p.distance));Set<Integer> used=new HashSet<>();
        for(DMatch m:good)if(used.add(m.trainIdx)){reference.add(source.keys[m.queryIdx].pt);target.add(live[m.trainIdx].pt);}
    }
    private void collectLocal(Features source,Mat gray,List<VisionPoint> corners,List<Point> reference,List<Point> target){
        if(corners.size()!=4)return;double l=Double.MAX_VALUE,t=l,r=-l,b=-l;
        for(VisionPoint p:corners){l=Math.min(l,p.getX());t=Math.min(t,p.getY());r=Math.max(r,p.getX());b=Math.max(b,p.getY());}
        double padX=(r-l)*.3,padY=(b-t)*.3;
        int x=Math.max(0,(int)Math.floor(l-padX)),y=Math.max(0,(int)Math.floor(t-padY));
        int right=Math.min(gray.cols(),(int)Math.ceil(r+padX)),bottom=Math.min(gray.rows(),(int)Math.ceil(b+padY));
        if(right-x<24||bottom-y<24)return;
        Rect rect=new Rect(x,y,right-x,bottom-y);Mat crop=gray.submat(rect),resized=new Mat(),normalized=new Mat(),descriptors=new Mat(),mask=new Mat();MatOfKeyPoint points=new MatOfKeyPoint();
        try{
            long start=android.os.SystemClock.elapsedRealtimeNanos();double boost=Math.min(4,Math.max(1,360.0/Math.max(rect.width,rect.height)));
            Imgproc.resize(crop,resized,new Size(Math.round(rect.width*boost),Math.round(rect.height*boost)),0,0,Imgproc.INTER_LINEAR);
            DiagnosticHammingMatcher.normalize(clahe,resized,normalized);DiagnosticHammingMatcher.extract(orb,normalized,mask,points,descriptors);
            KeyPoint[] keys=points.toArray();extractedPoints+=keys.length;
            double sx=rect.width/(double)resized.cols(),sy=rect.height/(double)resized.rows();
            for(KeyPoint k:keys){k.pt.x=x+k.pt.x*sx;k.pt.y=y+k.pt.y*sy;}
            extractionNanos+=android.os.SystemClock.elapsedRealtimeNanos()-start;
            collect(source,keys,descriptors,reference,target);
        }finally{crop.release();resized.release();normalized.release();descriptors.release();mask.release();points.release();}
    }
    private static int inliers(List<Point> reference,List<Point> live,Mat h){
        MatOfPoint2f src=new MatOfPoint2f(),dst=new MatOfPoint2f();try{src.fromList(reference);Core.perspectiveTransform(src,dst,h);Point[] q=dst.toArray();int count=0;for(int i=0;i<q.length;i++)if(Math.hypot(q[i].x-live.get(i).x,q[i].y-live.get(i).y)<=3.5)count++;return count;}finally{src.release();dst.release();}
    }
    private static final class Features{
        final Mat descriptors;final KeyPoint[] keys;
        Features(ReferenceDatabase.PreparedOrbVariant v,List<Integer> indexes){
            keys=new KeyPoint[indexes.size()];descriptors=indexes.isEmpty()?new Mat():new Mat(indexes.size(),v.descriptors.cols(),v.descriptors.type());if(indexes.isEmpty())return;
            int stride=v.descriptors.cols()*(int)v.descriptors.elemSize();byte[] original=new byte[v.descriptors.rows()*stride],selected=new byte[indexes.size()*stride];v.descriptors.get(0,0,original);
            for(int i=0;i<indexes.size();i++){int n=indexes.get(i);keys[i]=v.keypoints[n];System.arraycopy(original,n*stride,selected,i*stride,stride);}descriptors.put(0,0,selected);
        }
    }
    private static final class Split{
        final Features local,context;
        Split(ReferenceDatabase.PreparedReference ref,ReferenceDatabase.PreparedOrbVariant v){
            List<VisionPoint> c=ref.spec.getPlanarCornersNormalized();double l=c.get(0).getX()*ref.imageWidth,t=c.get(0).getY()*ref.imageHeight,r=c.get(2).getX()*ref.imageWidth,b=c.get(2).getY()*ref.imageHeight;
            List<Integer> a=new ArrayList<>(),other=new ArrayList<>();for(int i=0;i<v.keypoints.length;i++){
                Point p=v.keypoints[i].pt;
                if(p.x>=l&&p.x<=r&&p.y>=t&&p.y<=b)a.add(i);
                else if(p.x<l-(r-l)*.15||p.x>r+(r-l)*.15||p.y<t-(b-t)*.15||p.y>b+(b-t)*.15)other.add(i);
            }
            local=new Features(v,a);context=new Features(v,other);
        }
    }
    private static final class Candidate{final ReferenceDatabase.PreparedReference ref;final ReferenceDatabase.PreparedOrbVariant variant;final int contextScore;Candidate(ReferenceDatabase.PreparedReference r,ReferenceDatabase.PreparedOrbVariant v,int score){ref=r;variant=v;contextScore=score;}}
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
    public void close(){for(Split s:splits.values()){s.local.descriptors.release();s.context.descriptors.release();}splits.clear();refinements.clear();orb.clear();clahe.clear();matcher.clear();}
}
