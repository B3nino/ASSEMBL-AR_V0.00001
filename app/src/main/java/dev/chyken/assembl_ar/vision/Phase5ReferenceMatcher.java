package dev.chyken.assembl_ar.vision;

import dev.chyken.assembl_ar.camera.Phase5AssetCatalog;
import org.opencv.core.*;
import org.opencv.features2d.*;
import org.opencv.imgproc.Imgproc;
import org.opencv.imgproc.CLAHE;
import org.opencv.calib3d.Calib3d;
import java.util.*;

/** Nano and board homographies are independent. Shared background alone cannot verify orientation. */
final class Phase5ReferenceMatcher implements AutoCloseable {
    private final ReferenceDatabase database;
    private final ORB orb;
    private final CLAHE clahe;
    private final BFMatcher matcher=BFMatcher.create(Core.NORM_HAMMING,false);
    Phase5ReferenceMatcher(ReferenceDatabase database,VisionConfig config){this.database=database;orb=ORB.create(3200,1.2f,8,31,0,2,ORB.HARRIS_SCORE,31,10);clahe=OpenCvVisionUtils.createClahe(config);}
    RecognitionResult inspect(Mat gray,long time,int step){
        long start=android.os.SystemClock.elapsedRealtimeNanos();Mat work=new Mat(),descriptors=new Mat(),empty=new Mat();MatOfKeyPoint keypoints=new MatOfKeyPoint();
        double scale=Math.min(1,1280.0/Math.max(gray.cols(),gray.rows()));
        double front=0,back=0,placed=0,reversed=0,invalid=0;List<VisionPoint> corners=Collections.emptyList();
        database.acquireForRecognition();
        try{
            if(scale<1)Imgproc.resize(gray,work,new Size(),scale,scale,Imgproc.INTER_AREA);else gray.copyTo(work);
            clahe.apply(work,work);orb.detectAndCompute(work,empty,keypoints,descriptors);KeyPoint[] live=keypoints.toArray();
            Set<String> seen=new HashSet<>();
            if(!descriptors.empty())for(ReferenceDatabase.PreparedReference ref:database.preparedReferences()){
                String role=Phase5AssetCatalog.referenceRole(ref.spec.getReferenceId());
                if(role==null||step==1&&Phase5AssetCatalog.installed(role)||step!=1&&!Phase5AssetCatalog.installed(role))continue;
                String path=ref.spec.getJpegAssetPath();
                if(!seen.add(role+":"+path))continue;
                for(ReferenceDatabase.PreparedOrbVariant variant:ref.orbVariants){
                    if(!variant.hasOrbData())continue;
                    Match found=match(ref,variant,live,descriptors,scale,Phase5AssetCatalog.installed(role));if(found==null)continue;
                    if(role.equals("front")&&found.score>front){front=found.score;corners=found.actual;}
                    if(role.equals("back")&&found.score>back){back=found.score;if(back>front)corners=found.actual;}
                    if(Phase5AssetCatalog.installed(role)){
                        Phase5Observation.State state=Phase5Observation.placement(found.expected,found.actual);
                        if(role.equals("assembly")){
                            if(state==Phase5Observation.State.PLACED&&found.score>placed){placed=found.score;corners=found.actual;}
                            if(state==Phase5Observation.State.BACKWARD&&found.score>reversed){reversed=found.score;if(reversed>placed)corners=found.actual;}
                            if(state==Phase5Observation.State.MISALIGNED&&found.score>invalid){invalid=found.score;if(invalid>placed)corners=found.actual;}
                        }else if(state==Phase5Observation.State.PLACED&&found.score>invalid){
                            // Reject only a board-relative match to the actual invalid installation.
                            // A rotated match of the same Nano surface is not negative evidence.
                            invalid=found.score;if(invalid>placed)corners=found.actual;
                        }
                    }
                }
            }
            Phase5Observation.State state=Phase5Observation.classify(step,front,back,placed,reversed,invalid);
            double score=Math.max(Math.max(front,back),Math.max(invalid,Math.max(placed,reversed)));
            Phase5Observation result=new Phase5Observation(state,score);
            return RecognitionResult.builder(time).identity("phase5:photo","arduino_nano_microcontroller","Arduino Nano")
                    .method(corners.size()==4?RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY:RecognitionResult.DetectionMethod.NONE)
                    .validation(result.accepted(step)?RecognitionResult.ValidationState.EXPECTED_COMPONENT:state==Phase5Observation.State.FLIPPED||state==Phase5Observation.State.BACKWARD||state==Phase5Observation.State.MISALIGNED?RecognitionResult.ValidationState.WRONG_ORIENTATION:RecognitionResult.ValidationState.NO_COMPONENT)
                    .corners(corners).matches(live.length,(int)score,(int)score,score>0?1:0).confidence(result.accepted(step)?.85:0)
                    .latency(new RecognitionResult.Latency(0,0,0,0,(android.os.SystemClock.elapsedRealtimeNanos()-start)/1e6)).build().withPhase5Observation(result);
        }finally{database.releaseAfterRecognition();work.release();descriptors.release();empty.release();keypoints.release();}
    }
    private Match match(ReferenceDatabase.PreparedReference ref,ReferenceDatabase.PreparedOrbVariant variant,KeyPoint[] live,Mat descriptors,double scale,boolean installed){
        List<MatOfDMatch> pairs=new ArrayList<>();List<DMatch> candidates=new ArrayList<>();
        try{matcher.knnMatch(variant.descriptors,descriptors,pairs,2);for(MatOfDMatch pair:pairs){DMatch[] p=pair.toArray();if(p.length==2&&p[0].distance<65&&p[0].distance<p[1].distance*.76)candidates.add(p[0]);}}finally{for(Mat m:pairs)m.release();}
        candidates.sort(Comparator.comparingDouble(p->p.distance));Set<Integer> used=new HashSet<>();
        List<Point> module=new ArrayList<>(),moduleLive=new ArrayList<>(),board=new ArrayList<>(),boardLive=new ArrayList<>();
        List<VisionPoint> c=ref.spec.getPlanarCornersNormalized();double l=c.get(0).getX()*ref.imageWidth,t=c.get(0).getY()*ref.imageHeight,r=c.get(2).getX()*ref.imageWidth,b=c.get(2).getY()*ref.imageHeight;
        for(DMatch m:candidates){if(!used.add(m.trainIdx))continue;Point p=variant.keypoints[m.queryIdx].pt,q=live[m.trainIdx].pt;
            if(p.x>=l&&p.x<=r&&p.y>=t&&p.y<=b){module.add(p);moduleLive.add(q);}
            else if(p.x<l-(r-l)*.15||p.x>r+(r-l)*.15||p.y<t-(b-t)*.15||p.y>b+(b-t)*.15){board.add(p);boardLive.add(q);}
        }
        Mat mh=homography(module,moduleLive,installed?10:12,Math.max(15,(r-l)*.45),Math.max(15,(b-t)*.45)),bh=installed?homography(board,boardLive,16,15,15):null;
        try{
            if(mh==null||installed&&bh==null)return null;
            List<VisionPoint> actual=project(c,ref.imageWidth,ref.imageHeight,mh,scale);
            if(actual.size()!=4)return null;
            return new Match(inliers(module,moduleLive,mh),actual,installed?project(c,ref.imageWidth,ref.imageHeight,bh,scale):actual);
        }finally{if(mh!=null)mh.release();if(bh!=null)bh.release();}
    }
    private static int inliers(List<Point> reference,List<Point> live,Mat h){
        MatOfPoint2f src=new MatOfPoint2f(),dst=new MatOfPoint2f();
        try{src.fromList(reference);Core.perspectiveTransform(src,dst,h);Point[] projected=dst.toArray();int count=0;
            for(int i=0;i<projected.length;i++)if(Math.hypot(projected[i].x-live.get(i).x,projected[i].y-live.get(i).y)<=3.5)count++;return count;
        }finally{src.release();dst.release();}
    }
    private Mat homography(List<Point> a,List<Point> b,int minimum,double minimumWidth,double minimumHeight){
        if(a.size()<minimum)return null;MatOfPoint2f src=new MatOfPoint2f(),dst=new MatOfPoint2f();Mat mask=new Mat(),h=null;
        try{src.fromList(a);dst.fromList(b);h=Calib3d.findHomography(src,dst,Calib3d.RANSAC,3.5,mask,1800,.995);
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
    public void close(){orb.clear();clahe.clear();matcher.clear();}
}
