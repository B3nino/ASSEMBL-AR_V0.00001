package dev.chyken.assembl_ar.vision;

import dev.chyken.assembl_ar.camera.Phase11AssetCatalog;
import org.opencv.core.*;
import org.opencv.features2d.*;
import org.opencv.imgproc.Imgproc;
import org.opencv.imgproc.CLAHE;
import org.opencv.calib3d.Calib3d;
import java.util.*;

/** OLED and board homographies are independent. Shared background alone cannot verify orientation. */
final class Phase11ReferenceMatcher implements AutoCloseable {
    private final ReferenceDatabase database;
    private final ORB orb;
    private final ORB detailOrb=ORB.create(1600,1.2f,8,15,0,2,ORB.HARRIS_SCORE,31,5);
    private final Map<ReferenceDatabase.PreparedOrbVariant,Split> splits=new IdentityHashMap<>();
    private int boardEvidence;
    private final int[] cursors=new int[4];
    private final ReferenceDatabase.PreparedReference[] preferred=new ReferenceDatabase.PreparedReference[4];
    private final CLAHE clahe;
    private final DiagnosticHammingMatcher matcher=new DiagnosticHammingMatcher();
    private final Phase1011ReferenceSearch search=new Phase1011ReferenceSearch();
    Phase11ReferenceMatcher(ReferenceDatabase database,VisionConfig config){this.database=database;orb=ORB.create(3200,1.2f,8,31,0,2,ORB.HARRIS_SCORE,31,10);clahe=OpenCvVisionUtils.createClahe(config);}
    RecognitionResult inspect(Mat gray,long time,int step){
        long start=android.os.SystemClock.elapsedRealtimeNanos();Mat raw=new Mat(),work=new Mat(),descriptors=new Mat(),empty=new Mat();MatOfKeyPoint keypoints=new MatOfKeyPoint();
        double scale=Math.min(1,1280.0/Math.max(gray.cols(),gray.rows()));
        double front=0,back=0,placed=0,reversed=0,invalid=0;List<VisionPoint> corners=Collections.emptyList();
        boardEvidence=0;database.acquireForRecognition();
        try{
            if(scale<1)Imgproc.resize(gray,work,new Size(),scale,scale,Imgproc.INTER_AREA);else gray.copyTo(work);
            if(step==3)work.copyTo(raw);DiagnosticHammingMatcher.normalize(clahe,work,work);DiagnosticHammingMatcher.extract(orb,work,empty,keypoints,descriptors);KeyPoint[] live=keypoints.toArray();DiagnosticHammingMatcher.captureLive(live,descriptors,scale);
            List<ReferenceDatabase.PreparedReference> positive=new ArrayList<>(),negative=new ArrayList<>();
            for(ReferenceDatabase.PreparedReference ref:database.preparedReferences()){String role=Phase11AssetCatalog.referenceRole(ref.spec.getReferenceId());if(Phase11AssetCatalog.eligible(role,step))(role.equals("back")?negative:positive).add(ref);}
            List<ReferenceDatabase.PreparedReference> candidates=new ArrayList<>();int cursor=cursors[step]++;
            positive=search.order(positive,descriptors,cursor,preferred[step]);negative=search.order(negative,descriptors,cursor,null);
            for(int i=0;i<4;i++){
                if(i<negative.size())candidates.add(negative.get(i));
                if(i<positive.size())candidates.add(positive.get(i));
            }
            Set<String> seen=new HashSet<>();long deadline=System.nanoTime()+650_000_000L;int tried=0;
            if(!descriptors.empty())for(ReferenceDatabase.PreparedReference ref:candidates){
                if(tried>=Math.min(negative.isEmpty()?1:2,candidates.size())&&System.nanoTime()>deadline)break;tried++;
                String role=Phase11AssetCatalog.referenceRole(ref.spec.getReferenceId());
                if(role==null||!Phase11AssetCatalog.eligible(role,step))continue;
                String path=ref.spec.getJpegAssetPath();
                if(!seen.add(role+":"+path))continue;
                for(ReferenceDatabase.PreparedOrbVariant variant:ref.orbVariants){
                    if(!variant.hasOrbData())continue;
                    Match found=role.equals("assembly")?matchInstalled(ref,variant,live,descriptors,raw,scale):match(ref,variant,live,descriptors,scale,false);if(found==null)continue;
                    if((role.equals("front")||role.equals("battery"))&&found.score>front){front=found.score;corners=found.actual;preferred[step]=ref;}
                    if(role.equals("back")&&found.score>back){back=found.score;if(back>front)corners=found.actual;}
                    if(role.equals("assembly")){
                        Phase5Observation.State orientation=placement(found.expected,found.actual);
                        if(orientation==Phase5Observation.State.PLACED&&found.score>placed){placed=found.score;corners=found.actual;preferred[step]=ref;}
                        else if(orientation!=Phase5Observation.State.PLACED&&found.score>invalid){invalid=found.score;if(invalid>placed)corners=found.actual;}
                    }
                    break;
                }
                if(negative.isEmpty()&&(front>=12||placed>=10))break;
            }
            Phase11Observation.State state=Phase11Observation.classify(step,front,back,placed,Math.max(reversed,invalid));
            if(front<12&&placed<10)preferred[step]=null;
            double score=Math.max(Math.max(front,back),Math.max(invalid,Math.max(placed,reversed)));
            if(step==3&&state==Phase11Observation.State.SEARCHING&&boardEvidence>=14)state=Phase11Observation.State.BOARD_LOCATED;
            Phase11Observation result=new Phase11Observation(state,score,boardEvidence);
            return RecognitionResult.builder(time).identity("phase11:photo",Phase11AssetCatalog.target(step),"OLED display and battery")
                    .method(corners.size()==4?RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY:RecognitionResult.DetectionMethod.NONE)
                    .validation(result.accepted()?RecognitionResult.ValidationState.EXPECTED_COMPONENT:result.warning()?RecognitionResult.ValidationState.WRONG_ORIENTATION:RecognitionResult.ValidationState.NO_COMPONENT)
                    .corners(corners).matches(live.length,0,0,0).confidence(result.accepted()?.85:0)
                    .latency(new RecognitionResult.Latency(0,0,0,0,(android.os.SystemClock.elapsedRealtimeNanos()-start)/1e6)).build().withPhase11Observation(result);
        }finally{database.releaseAfterRecognition();raw.release();work.release();descriptors.release();empty.release();keypoints.release();}
    }
    /** The PCB locates a small search window; only independently matched OLED features pass it. */
    private Match matchInstalled(ReferenceDatabase.PreparedReference ref,ReferenceDatabase.PreparedOrbVariant variant,KeyPoint[] live,Mat descriptors,Mat work,double scale){DiagnosticHammingMatcher.context(ref);
        Split split=splits.get(variant);if(split==null){split=new Split(ref,variant);splits.put(variant,split);}
        List<Point> source=new ArrayList<>(),target=new ArrayList<>();collect(split.context,live,descriptors,source,target);
        Mat board=homography(source,target,14,15,15);if(board==null)return null;boardEvidence=Math.max(boardEvidence,inliers(source,target,board));
        Mat detail=new Mat(),detailDescriptors=new Mat(),empty=new Mat();MatOfKeyPoint keys=new MatOfKeyPoint();Mat part=null;Mat crop=null;
        try{
            List<VisionPoint> c=ref.spec.getPlanarCornersNormalized(),expected=project(c,ref.imageWidth,ref.imageHeight,board,scale);
            if(expected.size()!=4)return null;
            double l=Double.MAX_VALUE,t=l,r=-l,b=-l;for(VisionPoint p:expected){l=Math.min(l,p.getX()*scale);t=Math.min(t,p.getY()*scale);r=Math.max(r,p.getX()*scale);b=Math.max(b,p.getY()*scale);}
            if(r-l<16||b-t<16||r-l>work.cols()*1.2||b-t>work.rows()*1.2)return null;
            int x=Math.max(0,(int)Math.floor(l-(r-l)*.30)),y=Math.max(0,(int)Math.floor(t-(b-t)*.30)),right=Math.min(work.cols(),(int)Math.ceil(r+(r-l)*.30)),bottom=Math.min(work.rows(),(int)Math.ceil(b+(b-t)*.30));
            if(right-x<16||bottom-y<16)return null;crop=work.submat(new Rect(x,y,right-x,bottom-y));double enlargement=480.0/Math.max(crop.cols(),crop.rows());
            Imgproc.resize(crop,detail,new Size(Math.round(crop.cols()*enlargement),Math.round(crop.rows()*enlargement)),0,0,Imgproc.INTER_CUBIC);
            // Re-normalize the local window. Global CLAHE alone allocates features to PCB printing.
            DiagnosticHammingMatcher.normalize(clahe,detail,detail);DiagnosticHammingMatcher.extract(detailOrb,detail,empty,keys,detailDescriptors);KeyPoint[] local=keys.toArray();
            double sx=crop.cols()/(double)detail.cols(),sy=crop.rows()/(double)detail.rows();for(KeyPoint k:local){k.pt.x=x+k.pt.x*sx;k.pt.y=y+k.pt.y*sy;}
            source.clear();target.clear();collect(split.local,local,detailDescriptors,source,target);
            double width=(c.get(2).getX()-c.get(0).getX())*ref.imageWidth,height=(c.get(2).getY()-c.get(0).getY())*ref.imageHeight;
            part=homography(source,target,10,Math.max(12,width*.30),Math.max(12,height*.30));if(part==null)return null;
            List<VisionPoint> actual=project(c,ref.imageWidth,ref.imageHeight,part,scale);if(actual.size()!=4)return null;
            return new Match(inliers(source,target,part),actual,expected);
        }finally{board.release();if(part!=null)part.release();if(crop!=null)crop.release();detail.release();detailDescriptors.release();empty.release();keys.release();}
    }
    static Phase5Observation.State placement(List<VisionPoint> expected,List<VisionPoint> actual){
        Phase5Observation.State strict=Phase5Observation.placement(expected,actual);if(strict!=Phase5Observation.State.MISALIGNED)return strict;
        double diagonal=Math.hypot(expected.get(0).getX()-expected.get(2).getX(),expected.get(0).getY()-expected.get(2).getY()),error=0;
        for(int i=0;i<4;i++)error+=Math.hypot(expected.get(i).getX()-actual.get(i).getX(),expected.get(i).getY()-actual.get(i).getY());
        return error/(4*diagonal)<.12?Phase5Observation.State.PLACED:Phase5Observation.State.MISALIGNED;
    }
    private void collect(Features features,KeyPoint[] live,Mat descriptors,List<Point> source,List<Point> target){
        if(features.descriptors.empty()||descriptors.empty()||descriptors.rows()<2)return;
        List<DMatch> good=new ArrayList<>();
        good.addAll(matcher.ratioMatches(features.descriptors,descriptors));
        good.sort(Comparator.comparingDouble(p->p.distance));Set<Integer> used=new HashSet<>();for(DMatch m:good)if(used.add(m.trainIdx)){source.add(features.keys[m.queryIdx].pt);target.add(live[m.trainIdx].pt);}
    }
    private static final class Features{
        final Mat descriptors;final KeyPoint[] keys;
        Features(ReferenceDatabase.PreparedOrbVariant v,List<Integer> indexes){
            keys=new KeyPoint[indexes.size()];descriptors=indexes.isEmpty()?new Mat():new Mat(indexes.size(),v.descriptors.cols(),v.descriptors.type());if(indexes.isEmpty())return;
            try{
            int stride=v.descriptors.cols()*(int)v.descriptors.elemSize();byte[] all=new byte[v.descriptors.rows()*stride],selected=new byte[indexes.size()*stride];v.descriptors.get(0,0,all);
            for(int i=0;i<indexes.size();i++){int index=indexes.get(i);keys[i]=v.keypoints[index];System.arraycopy(all,index*stride,selected,i*stride,stride);}descriptors.put(0,0,selected);
            }catch(RuntimeException|Error failure){descriptors.release();throw failure;}
        }
    }
    private static final class Split{
        final Features local,context;
        Split(ReferenceDatabase.PreparedReference ref,ReferenceDatabase.PreparedOrbVariant v){
            List<VisionPoint> c=ref.spec.getPlanarCornersNormalized();double l=c.get(0).getX()*ref.imageWidth,t=c.get(0).getY()*ref.imageHeight,r=c.get(2).getX()*ref.imageWidth,b=c.get(2).getY()*ref.imageHeight;
            List<Integer> a=new ArrayList<>(),other=new ArrayList<>();for(int i=0;i<v.keypoints.length;i++){Point p=v.keypoints[i].pt;if(p.x>=l&&p.x<=r&&p.y>=t&&p.y<=b)a.add(i);else if(p.x<l-(r-l)*.15||p.x>r+(r-l)*.15||p.y<t-(b-t)*.15||p.y>b+(b-t)*.15)other.add(i);}
            local=new Features(v,a);try{context=new Features(v,other);}catch(RuntimeException|Error failure){local.descriptors.release();throw failure;}
        }
    }
    private Match match(ReferenceDatabase.PreparedReference ref,ReferenceDatabase.PreparedOrbVariant variant,KeyPoint[] live,Mat descriptors,double scale,boolean installed){DiagnosticHammingMatcher.context(ref);
        List<DMatch> candidates=new ArrayList<>();
        candidates.addAll(matcher.ratioMatches(variant.descriptors,descriptors));
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
        try{src.fromList(a);dst.fromList(b);h=DiagnosticHammingMatcher.homography(src,dst,Calib3d.RANSAC,3.5,mask,1800,.995);
            int n=h.empty()?0:Core.countNonZero(mask);if(n<minimum||n<a.size()*.5){return null;}
            double minX=Double.MAX_VALUE,minY=minX,maxX=-minX,maxY=-minX;
            for(int i=0;i<a.size();i++)if(mask.get(i,0)[0]!=0){Point p=a.get(i);minX=Math.min(minX,p.x);maxX=Math.max(maxX,p.x);minY=Math.min(minY,p.y);maxY=Math.max(maxY,p.y);}
            // A terminal row or a small unchanged patch alone cannot certify the whole module.
            if(maxX-minX<minimumWidth||maxY-minY<minimumHeight){return null;}Mat owned=h;h=null;return owned;
        }finally{src.release();dst.release();mask.release();if(h!=null)h.release();}
    }
    private List<VisionPoint> project(List<VisionPoint> corners,int width,int height,Mat h,double scale){
        MatOfPoint2f src=new MatOfPoint2f(),dst=new MatOfPoint2f();
        try{List<Point> p=new ArrayList<>();for(VisionPoint c:corners)p.add(new Point(c.getX()*width,c.getY()*height));src.fromList(p);Core.perspectiveTransform(src,dst,h);
            Point[] q=dst.toArray();double area=Imgproc.contourArea(dst,true);if(!Double.isFinite(area)||area<100)return Collections.emptyList();
            List<VisionPoint> result=new ArrayList<>();for(Point c:q){if(!Double.isFinite(c.x+c.y))return Collections.emptyList();result.add(new VisionPoint(c.x/scale,c.y/scale));}return result;
        }finally{src.release();dst.release();}
    }
    private static final class Match{final double score;final List<VisionPoint> actual,expected;Match(double score,List<VisionPoint> actual,List<VisionPoint> expected){this.score=score;this.actual=actual;this.expected=expected;}}
    public void close(){search.close();for(Split split:splits.values()){split.local.descriptors.release();split.context.descriptors.release();}splits.clear();detailOrb.clear();orb.clear();clahe.clear();}
}
