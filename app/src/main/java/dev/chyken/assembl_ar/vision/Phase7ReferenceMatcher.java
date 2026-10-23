package dev.chyken.assembl_ar.vision;

import dev.chyken.assembl_ar.camera.Phase7AssetCatalog;
import org.opencv.core.*;
import org.opencv.features2d.*;
import org.opencv.calib3d.Calib3d;
import org.opencv.imgproc.*;
import java.util.*;

/** Board context locates mounting positions; each sensor is recognized with its own orientation. */
final class Phase7ReferenceMatcher implements AutoCloseable {
    private final ReferenceDatabase database;private final ORB orb=ORB.create(3200,1.2f,8,31,0,2,ORB.HARRIS_SCORE,31,10);
    private final CLAHE clahe;private final DiagnosticHammingMatcher matcher=new DiagnosticHammingMatcher();
    private final Phase7TrackerSearch trackerSearch;
    private final List<ReferenceDatabase.PreparedReference> trackerReferences = new ArrayList<>();
    private final List<ReferenceDatabase.PreparedReference> installedReferences = new ArrayList<>();
    private final Map<ReferenceDatabase.PreparedOrbVariant,Map<List<VisionPoint>,Crop>> cropQueries = new IdentityHashMap<>();
    private ReferenceDatabase.PreparedReference preferredBody, preferredBoard;
    private final int[][] windowCursors=new int[4][9];
    private int boardCursor, searchAttempt;
    Phase7ReferenceMatcher(ReferenceDatabase db,VisionConfig config){
        database=db;clahe=OpenCvVisionUtils.createClahe(config);trackerSearch=new Phase7TrackerSearch(config);
        for(ReferenceDatabase.PreparedReference ref:db.preparedReferences()) {
            String role=Phase7AssetCatalog.referenceRole(ref.spec.getReferenceId());
            if("trackerwork".equals(role))installedReferences.add(ref);
        }
        trackerReferences.addAll(installedReferences);
        for(ReferenceDatabase.PreparedReference ref:db.preparedReferences())
            if("trackers".equals(Phase7AssetCatalog.referenceRole(ref.spec.getReferenceId())))trackerReferences.add(ref);
    }
    RecognitionResult inspect(Mat gray,long time,int step){return inspect(gray,null,time,step);}
    RecognitionResult inspect(Mat gray,Mat rgb,long time,int step){
        long started=android.os.SystemClock.elapsedRealtimeNanos();Mat work=new Mat(),descriptors=new Mat(),empty=new Mat(),remaining=null,trackerGray=null;MatOfKeyPoint keypoints=new MatOfKeyPoint();
        double scale=Math.min(1,1280.0/Math.max(gray.cols(),gray.rows()));List<List<VisionPoint>> located=new ArrayList<>();double negative=0,score=0;
        database.acquireForRecognition();
        try{
            if(scale<1)Imgproc.resize(gray,work,new Size(),scale,scale,Imgproc.INTER_AREA);else gray.copyTo(work);
            if(step==4)trackerGray=work.clone();
            DiagnosticHammingMatcher.normalize(clahe,work,work);DiagnosticHammingMatcher.extract(orb,work,empty,keypoints,descriptors);KeyPoint[] live=keypoints.toArray();DiagnosticHammingMatcher.captureLive(live,descriptors,scale);
            if(step==4){
                Phase7Observation evidence=inspectTrackers(trackerGray,rgb,live,descriptors,scale,located);
                List<RecognitionResult.InstanceObservation> instances=new ArrayList<>();
                for(int i=0;i<located.size();i++)instances.add(new RecognitionResult.InstanceObservation("phase7:tracker:"+i,"line_tracker_module",RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY,located.get(i),.88));
                return RecognitionResult.builder(time).identity("phase7:trackers","line_tracker_module","Dual tracker installation")
                        .method(located.isEmpty()?RecognitionResult.DetectionMethod.NONE:RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY).instances(instances)
                        .validation(evidence.accepted(4)?RecognitionResult.ValidationState.EXPECTED_COMPONENT:RecognitionResult.ValidationState.NO_COMPONENT)
                        .matches(live.length,(int)evidence.score,(int)evidence.score,evidence.score>0?1:0).confidence(evidence.accepted(4)?.88:0)
                        .latency(new RecognitionResult.Latency(0,0,0,0,(android.os.SystemClock.elapsedRealtimeNanos()-started)/1e6))
                        .build().withPhase7Observation(evidence);
            }
            for(int pass=0;pass<(step==2?2:1)&&!descriptors.empty();pass++){
                Mat active=pass==0?descriptors:remaining;Match best=null;
                for(ReferenceDatabase.PreparedReference ref:database.preparedReferences()){
                    String role=Phase7AssetCatalog.referenceRole(ref.spec.getReferenceId());if(!Phase7AssetCatalog.eligible(role,step))continue;
                    boolean bad=Phase7AssetCatalog.negative(role),installed=Phase7AssetCatalog.installed(role);if(pass>0&&bad)continue;
                    for(ReferenceDatabase.PreparedOrbVariant variant:ref.orbVariants){
                        if(!variant.hasOrbData())continue;
                        List<List<VisionPoint>> regions=ref.spec.getFeatureRegionsNormalized();
                        if(installed){
                            Match found=match(ref,variant,live,active,scale,regions,true,bad,null);if(found==null)continue;
                            if(bad)negative=Math.max(negative,found.score);else if(best==null||found.score>best.score)best=found;
                        }else for(List<VisionPoint> region:regions){
                            Match found=match(ref,variant,live,active,scale,Collections.singletonList(region),false,bad,region);if(found==null)continue;
                            if(bad)negative=Math.max(negative,found.score);else if((best==null||found.score>best.score)&&distinct(found.corners,located))best=found;
                        }
                    }
                }
                if(best==null)break;located.add(best.corners);score+=best.score;
                if(step==2&&pass==0){remaining=descriptors.clone();for(int i=0;i<live.length;i++)if(inside(live[i].pt,best.corners,scale)){Mat row=remaining.row(i);try{row.setTo(new Scalar(0));}finally{row.release();}}}
            }
            int count=located.size();boolean invalid=step>=3?count==0&&negative>=18:negative>=10&&(count==0||negative>score*1.25);
            Phase7Observation observation=new Phase7Observation(count,invalid,Math.max(score,negative));List<VisionPoint> corners=count>0?located.get(0):Collections.emptyList();
            RecognitionResult.Builder result=RecognitionResult.builder(time).identity("phase7:photo",step==1?"caster_wheel":step==2?"line_tracker_module":"main_board",step==1?"Caster wheel":step==2?"Line trackers":"Bottom-view installation")
                .method(count>0?RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY:RecognitionResult.DetectionMethod.NONE).corners(corners)
                .validation(observation.accepted(step)?RecognitionResult.ValidationState.EXPECTED_COMPONENT:invalid?RecognitionResult.ValidationState.WRONG_ORIENTATION:RecognitionResult.ValidationState.NO_COMPONENT)
                .matches(live.length,(int)score,(int)score,score>0?1:0).confidence(observation.accepted(step)?.88:0)
                .latency(new RecognitionResult.Latency(0,0,0,0,(android.os.SystemClock.elapsedRealtimeNanos()-started)/1e6));
            return result.build().withPhase7Observation(observation);
        }finally{database.releaseAfterRecognition();work.release();descriptors.release();empty.release();keypoints.release();if(remaining!=null)remaining.release();if(trackerGray!=null)trackerGray.release();}
    }
    private Phase7Observation inspectTrackers(Mat gray,Mat rgb,KeyPoint[] live,Mat descriptors,double scale,List<List<VisionPoint>> located){
        List<Body> bodies=new ArrayList<>();List<List<VisionPoint>> quads=new ArrayList<>();double score=0;
        List<Rect> windows=trackerSearch.windows(gray,rgb);
        ++searchAttempt;
        // Native pixels must participate as well as enlarged detail passes. Actual tablet
        // captures match at 1x but fail at 3–4x despite having more detected keypoints.
        int sampling=(searchAttempt&1)==1?0:1+((searchAttempt/2-1)%3);
        int featureExtent=new int[]{0,400,600,800}[sampling];
        long deadline=System.nanoTime()+650_000_000L;
        for(int slot=0;slot<windows.size();slot++){
            Rect window=windows.get(slot);
            int[] cursors=windowCursors[sampling];
            boolean hadPreferredBody=preferredBody!=null;
            List<ReferenceDatabase.PreparedReference> candidates=ordered(trackerReferences,cursors[slot],preferredBody);
            Body best=null;
            try(Phase7TrackerSearch.Features local=trackerSearch.extract(gray,window,featureExtent)){
                // Extraction is not template matching time. On the tablet it used to consume
                // the entire 80ms allowance, leaving only one photograph tested per window.
                long windowDeadline=Math.min(deadline,System.nanoTime()+80_000_000L);
                int inspected=0;
                for(ReferenceDatabase.PreparedReference ref:candidates){
                    if(inspected>0&&System.nanoTime()>windowDeadline)break;
                    inspected++;
                    List<List<VisionPoint>> regions=ref.spec.getFeatureRegionsNormalized();
                    boolean installed="trackerwork".equals(Phase7AssetCatalog.referenceRole(ref.spec.getReferenceId()));
                    if(installed&&regions.size()<6)continue;
                    for(ReferenceDatabase.PreparedOrbVariant variant:ref.orbVariants){
                        if(!variant.hasOrbData()||local.descriptors.empty())continue;
                        for(int i=0;i<Math.min(2,regions.size());i++){
                            List<VisionPoint> roi=regions.get(i);
                            Match found=match(ref,variant,local.points,local.descriptors,scale,Collections.singletonList(roi),false,false,roi,
                                    installed?regions.get(4+i):null);
                            if(found!=null&&distinct(found.corners,quads)&&(best==null||found.score>best.match.score)){
                                boolean vertical=(roi.get(2).getY()-roi.get(0).getY())*ref.imageHeight>(roi.get(2).getX()-roi.get(0).getX())*ref.imageWidth;
                                best=new Body(found,vertical,ref);
                            }
                        }
                    }
                    // Every returned match already passed the identity, coverage and RANSAC
                    // gates. Searching for a higher-scoring duplicate only delays the next module.
                    if(best!=null)break;
                }
                cursors[slot]+=Math.max(1,inspected-(hadPreferredBody?1:0));
                if(android.util.Log.isLoggable("AssemblArTracker",android.util.Log.DEBUG))
                    android.util.Log.d("AssemblArTracker","slot="+slot+" roi="+window+" extent="+featureExtent
                            +" keypoints="+local.points.length+" references="+inspected+" score="+(best==null?0:best.match.score));
            }
            if(best!=null){
                bodies.add(best);quads.add(best.match.corners);located.add(best.match.corners);score+=best.match.score;
                preferredBody=best.reference;
                // Try this verified template first for the other identical module, with a fresh
                // local descriptor pool so Lowe's ratio does not compare one tracker to the other.
            }
            if(bodies.size()==2||System.nanoTime()>deadline)break;
        }
        if(bodies.size()<2)return Phase7Observation.installedTrackers(bodies.size(),0,false,score);
        int mounts=0;boolean context=false;boolean[] visibleNuts=new boolean[2];
        long contextDeadline=System.nanoTime()+300_000_000L;
        ReferenceDatabase.PreparedReference preferred=preferredBoard!=null?preferredBoard:
                installedReferences.contains(bodies.get(0).reference)?bodies.get(0).reference:null;
        int inspectedContext=0;
        for(ReferenceDatabase.PreparedReference ref:ordered(installedReferences,boardCursor,preferred)){
            if(inspectedContext>0&&System.nanoTime()>contextDeadline)break;
            inspectedContext++;
            List<List<VisionPoint>> regions=ref.spec.getFeatureRegionsNormalized();if(regions.size()<6)continue;
            for(ReferenceDatabase.PreparedOrbVariant variant:ref.orbVariants){
                if(!variant.hasOrbData())continue;
                List<Point>[] matched=correspondences(variant,live,descriptors);List<Point> allA=matched[0],allB=matched[1];
                List<Point> a=new ArrayList<>(),b=new ArrayList<>();
                for(int i=0;i<allA.size();i++)if(!inAny(allA.get(i),regions,ref)){a.add(allA.get(i));b.add(allB.get(i));}
                if(a.size()<18)continue;
                MatOfPoint2f src=new MatOfPoint2f(),dst=new MatOfPoint2f();Mat mask=new Mat(),h=null;
                try{
                    src.fromList(a);dst.fromList(b);h=DiagnosticHammingMatcher.homography(src,dst,Calib3d.RANSAC,3.5,mask,1600,.995);
                    int n=h.empty()?0:Core.countNonZero(mask);if(n<18||n<a.size()*.45)continue;
                    context=true;
                    boolean[][] slots=new boolean[2][2];
                    for(int slot=0;slot<2;slot++){
                        List<VisionPoint> roi=regions.get(2+slot);
                        boolean nutVisible=nutProof(ref,variant,live,descriptors,roi,h);
                        src.fromArray(new Point((roi.get(0).getX()+roi.get(2).getX())*.5*ref.imageWidth,(roi.get(0).getY()+roi.get(2).getY())*.5*ref.imageHeight));
                        Core.perspectiveTransform(src,dst,h);Point point=dst.toArray()[0];
                        if(!Double.isFinite(point.x+point.y))continue;
                        VisionPoint mount=new VisionPoint(point.x/scale,point.y/scale);
                        for(int body=0;body<2;body++){slots[slot][body]=Phase7MountGeometry.fits(bodies.get(body).match.corners,mount,bodies.get(body).vertical);if(slots[slot][body]&&nutVisible)visibleNuts[body]=true;}
                    }
                    // The two sensor bodies must meet two DIFFERENT board mounts under
                    // the same board pose; two sensors beside one mount cannot satisfy it.
                    mounts=Math.max(mounts,Phase7MountGeometry.distinctMounts(slots));
                    // The scan certifies two sensor locations. Reflective fasteners remain
                    // explicit hardware/stability answers, even when their image evidence is weak.
                    if(mounts==2){preferredBoard=ref;return Phase7Observation.installedTrackers(2,2,true,score+n,(visibleNuts[0]?1:0)+(visibleNuts[1]?1:0));}
                }finally{src.release();dst.release();mask.release();if(h!=null)h.release();}
            }
        }
        boardCursor+=Math.max(1,inspectedContext-(preferred!=null?1:0));
        return Phase7Observation.installedTrackers(2,mounts,context,score,(visibleNuts[0]?1:0)+(visibleNuts[1]?1:0));
    }
    private static List<ReferenceDatabase.PreparedReference> ordered(List<ReferenceDatabase.PreparedReference> refs,int cursor,ReferenceDatabase.PreparedReference preferred){DiagnosticHammingMatcher.context(preferred==null?null:preferred.spec);
        List<ReferenceDatabase.PreparedReference> result=new ArrayList<>();
        if(preferred!=null&&refs.contains(preferred))result.add(preferred);
        for(int i=0;i<refs.size();i++){
            ReferenceDatabase.PreparedReference ref=refs.get(Math.floorMod(cursor+i,refs.size()));
            if(ref!=preferred)result.add(ref);
        }
        return result;
    }
    private boolean nutProof(ReferenceDatabase.PreparedReference ref,ReferenceDatabase.PreparedOrbVariant variant,KeyPoint[] live,Mat descriptors,List<VisionPoint> roi,Mat h){
        List<Integer> sourceIds=new ArrayList<>(),liveIds=new ArrayList<>();
        for(int i=0;i<variant.keypoints.length;i++)if(in(variant.keypoints[i].pt,roi,ref))sourceIds.add(i);
        if(sourceIds.size()<3)return false;
        Mat inverse=h.inv();MatOfPoint2f pixels=new MatOfPoint2f(),canonical=new MatOfPoint2f();
        Mat query=null,train=null;
        try{
            Point[] positions=new Point[live.length];for(int i=0;i<live.length;i++)positions[i]=live[i].pt;
            pixels.fromArray(positions);Core.perspectiveTransform(pixels,canonical,inverse);Point[] mapped=canonical.toArray();
            double l=roi.get(0).getX()*ref.imageWidth,t=roi.get(0).getY()*ref.imageHeight,r=roi.get(2).getX()*ref.imageWidth,b=roi.get(2).getY()*ref.imageHeight;
            for(int i=0;i<mapped.length;i++){Point p=mapped[i];if(p.x>=l-(r-l)*.15&&p.x<=r+(r-l)*.15&&p.y>=t-(b-t)*.15&&p.y<=b+(b-t)*.15)liveIds.add(i);}
            if(liveIds.size()<3)return false;
            query=rows(variant.descriptors,sourceIds);train=rows(descriptors,liveIds);
            // Nuts repeat throughout the board. Restrict matching to the projected mount BEFORE
            // the ratio test, so another nut elsewhere cannot steal the correct correspondence.
            List<DMatch> good=matcher.ratioMatches(query,train,.85,74);
            good.sort(Comparator.comparingDouble(m->m.distance));Set<Integer> used=new HashSet<>();List<Point> a=new ArrayList<>(),c=new ArrayList<>();
            for(DMatch m:good)if(used.add(m.trainIdx)){a.add(variant.keypoints[sourceIds.get(m.queryIdx)].pt);c.add(live[liveIds.get(m.trainIdx)].pt);}
            if(a.size()<3)return false;pixels.fromList(a);Core.perspectiveTransform(pixels,canonical,h);Point[] projected=canonical.toArray();
            int count=0;double lx=Double.MAX_VALUE,ly=lx,rx=-lx,ry=-lx;
            for(int i=0;i<a.size();i++)if(Math.hypot(projected[i].x-c.get(i).x,projected[i].y-c.get(i).y)<=4){Point p=a.get(i);count++;lx=Math.min(lx,p.x);rx=Math.max(rx,p.x);ly=Math.min(ly,p.y);ry=Math.max(ry,p.y);}
            return count>=3&&rx-lx>=(r-l)*.20&&ry-ly>=(b-t)*.20;
        }finally{inverse.release();pixels.release();canonical.release();if(query!=null)query.release();if(train!=null)train.release();}
    }
    private static Mat rows(Mat descriptors,List<Integer> ids){
        Mat result=new Mat(ids.size(),descriptors.cols(),descriptors.type());
        for(int i=0;i<ids.size();i++){Mat source=descriptors.row(ids.get(i)),destination=result.row(i);try{source.copyTo(destination);}finally{source.release();destination.release();}}
        return result;
    }
    @SuppressWarnings("unchecked")
    private List<Point>[] correspondences(ReferenceDatabase.PreparedOrbVariant variant,KeyPoint[] live,Mat descriptors){
        List<DMatch> good=new ArrayList<>();
        good.addAll(matcher.ratioMatches(variant.descriptors,descriptors,.76,64));
        good.sort(Comparator.comparingDouble(m->m.distance));Set<Integer> used=new HashSet<>();List<Point> a=new ArrayList<>(),b=new ArrayList<>();
        for(DMatch m:good)if(used.add(m.trainIdx)){a.add(variant.keypoints[m.queryIdx].pt);b.add(live[m.trainIdx].pt);}
        return new List[]{a,b};
    }
    private static final class Body{
        final Match match;final boolean vertical;final ReferenceDatabase.PreparedReference reference;
        Body(Match match,boolean vertical,ReferenceDatabase.PreparedReference reference){this.match=match;this.vertical=vertical;this.reference=reference;}
    }
    private static final class Crop{
        final List<Integer> ids;final Mat descriptors;
        Crop(List<Integer> ids,Mat descriptors){this.ids=ids;this.descriptors=descriptors;}
    }
    private Crop crop(ReferenceDatabase.PreparedReference ref,ReferenceDatabase.PreparedOrbVariant variant,List<VisionPoint> region){
        Map<List<VisionPoint>,Crop> crops=cropQueries.computeIfAbsent(variant,key->new HashMap<>());
        Crop cached=crops.get(region);if(cached!=null)return cached;
        List<Integer> ids=new ArrayList<>();
        for(int i=0;i<variant.keypoints.length;i++)if(in(variant.keypoints[i].pt,region,ref))ids.add(i);
        cached=new Crop(ids,ids.size()<10?null:rows(variant.descriptors,ids));crops.put(region,cached);return cached;
    }
    private Match match(ReferenceDatabase.PreparedReference ref,ReferenceDatabase.PreparedOrbVariant variant,KeyPoint[] live,Mat descriptors,double scale,List<List<VisionPoint>> regions,boolean installed,boolean bad,List<VisionPoint> looseRegion){DiagnosticHammingMatcher.context(ref.spec);
        return match(ref,variant,live,descriptors,scale,regions,installed,bad,looseRegion,null);
    }
    private Match match(ReferenceDatabase.PreparedReference ref,ReferenceDatabase.PreparedOrbVariant variant,KeyPoint[] live,Mat descriptors,double scale,List<List<VisionPoint>> regions,boolean installed,boolean bad,List<VisionPoint> looseRegion,List<VisionPoint> identity){DiagnosticHammingMatcher.context(ref.spec);
        List<DMatch> good=new ArrayList<>();
        List<Integer> queryIds=new ArrayList<>();Mat localQuery=null;
        if(!installed){
            Crop cached=crop(ref,variant,looseRegion);queryIds=cached.ids;
            if(queryIds.size()<10)return null;localQuery=cached.descriptors;
        }
        {
            // Match only the component crop. Board features outside it must neither consume
            // matching time nor steal a live keypoint during train-index deduplication.
            for(DMatch m:matcher.ratioMatches(localQuery!=null?localQuery:variant.descriptors,descriptors,.76,64)){if(localQuery!=null)m.queryIdx=queryIds.get(m.queryIdx);good.add(m);}
        }
        good.sort(Comparator.comparingDouble(m->m.distance));List<Point> a=new ArrayList<>(),b=new ArrayList<>();List<Point> allA=new ArrayList<>(),allB=new ArrayList<>();Set<Integer> used=new HashSet<>();
        for(DMatch m:good){if(!used.add(m.trainIdx))continue;Point p=variant.keypoints[m.queryIdx].pt,q=live[m.trainIdx].pt;allA.add(p);allB.add(q);
            if(installed?bad||!inAny(p,regions,ref):in(p,looseRegion,ref)){a.add(p);b.add(q);}
        }
        int minimum=installed?18:10;if(a.size()<minimum)return null;
        // Necessary preconditions for the identical post-RANSAC gates below. Do not spend
        // a full homography search on a tiny fragment that could never satisfy those gates.
        if(!installed&&!spread(a,looseRegion,ref,.35,10))return null;
        if(identity!=null){List<Point> detail=new ArrayList<>();for(Point p:allA)if(in(p,identity,ref))detail.add(p);if(!spread(detail,identity,ref,.25,4))return null;}
        MatOfPoint2f src=new MatOfPoint2f(),dst=new MatOfPoint2f();Mat mask=new Mat(),h=null;
        try{
            src.fromList(a);dst.fromList(b);h=DiagnosticHammingMatcher.homography(src,dst,Calib3d.RANSAC,3.5,mask,1600,.995);int inliers=h.empty()?0:Core.countNonZero(mask);
            if(inliers<minimum||inliers<a.size()*.45)return null;
            double score=inliers;
            if(identity!=null){
                MatOfPoint2f all=new MatOfPoint2f(),projected=new MatOfPoint2f();
                try{
                    all.fromList(allA);Core.perspectiveTransform(all,projected,h);Point[] points=projected.toArray();
                    int count=0;double lx=Double.MAX_VALUE,ly=lx,rx=-lx,ry=-lx;
                    for(int i=0;i<allA.size();i++)if(in(allA.get(i),identity,ref)&&Math.hypot(points[i].x-allB.get(i).x,points[i].y-allB.get(i).y)<=4){Point p=allA.get(i);count++;lx=Math.min(lx,p.x);rx=Math.max(rx,p.x);ly=Math.min(ly,p.y);ry=Math.max(ry,p.y);}
                    if(count<4||rx-lx<(identity.get(2).getX()-identity.get(0).getX())*ref.imageWidth*.25||ry-ly<(identity.get(2).getY()-identity.get(0).getY())*ref.imageHeight*.25)return null;
                    score+=count;
                }finally{all.release();projected.release();}
            }
            if(installed&&!bad){
                if(regions.size()<(Phase7AssetCatalog.referenceRole(ref.spec.getReferenceId()).equals("trackerwork")?3:1))return null;
                MatOfPoint2f all=new MatOfPoint2f(),projected=new MatOfPoint2f();
                try{all.fromList(allA);Core.perspectiveTransform(all,projected,h);Point[] projectedPoints=projected.toArray();
                    for(List<VisionPoint> region:regions){int n=0;double lx=Double.MAX_VALUE,ly=lx,rx=-lx,ry=-lx;
                        for(int i=0;i<allA.size();i++)if(in(allA.get(i),region,ref)&&Math.hypot(projectedPoints[i].x-allB.get(i).x,projectedPoints[i].y-allB.get(i).y)<=4){Point p=allA.get(i);n++;lx=Math.min(lx,p.x);rx=Math.max(rx,p.x);ly=Math.min(ly,p.y);ry=Math.max(ry,p.y);}
                        double rw=(region.get(2).getX()-region.get(0).getX())*ref.imageWidth,rh=(region.get(2).getY()-region.get(0).getY())*ref.imageHeight;
                        if(n<6||rx-lx<rw*.30||ry-ly<rh*.30)return null;score+=n;
                    }
                }finally{all.release();projected.release();}
            }else if(!installed){
                double lx=Double.MAX_VALUE,ly=lx,rx=-lx,ry=-lx;for(int i=0;i<a.size();i++)if(mask.get(i,0)[0]!=0){lx=Math.min(lx,a.get(i).x);rx=Math.max(rx,a.get(i).x);ly=Math.min(ly,a.get(i).y);ry=Math.max(ry,a.get(i).y);}
                if(rx-lx<(looseRegion.get(2).getX()-looseRegion.get(0).getX())*ref.imageWidth*.35||ry-ly<(looseRegion.get(2).getY()-looseRegion.get(0).getY())*ref.imageHeight*.35)return null;
            }
            List<VisionPoint> c=installed?ref.spec.getPlanarCornersNormalized():looseRegion;Point[] points=new Point[4];for(int i=0;i<4;i++)points[i]=new Point(c.get(i).getX()*ref.imageWidth,c.get(i).getY()*ref.imageHeight);
            src.fromArray(points);Core.perspectiveTransform(src,dst,h);Point[] q=dst.toArray();MatOfPoint2f polygon=new MatOfPoint2f(q);
            try{if(Imgproc.contourArea(polygon,true)<120||!Double.isFinite(Imgproc.contourArea(polygon)))return null;}finally{polygon.release();}
            List<VisionPoint> corners=new ArrayList<>();for(Point p:q){if(!Double.isFinite(p.x+p.y)||Math.abs(p.x)>20000||Math.abs(p.y)>20000)return null;corners.add(new VisionPoint(p.x/scale,p.y/scale));}
            return new Match(score,corners);
        }finally{src.release();dst.release();mask.release();if(h!=null)h.release();}
    }
    private static boolean spread(List<Point> points,List<VisionPoint> region,ReferenceDatabase.PreparedReference ref,double fraction,int minimum){
        if(points.size()<minimum)return false;double l=Double.MAX_VALUE,t=l,r=-l,b=-l;
        for(Point p:points){l=Math.min(l,p.x);r=Math.max(r,p.x);t=Math.min(t,p.y);b=Math.max(b,p.y);}
        return r-l>=(region.get(2).getX()-region.get(0).getX())*ref.imageWidth*fraction&&b-t>=(region.get(2).getY()-region.get(0).getY())*ref.imageHeight*fraction;
    }
    private static boolean in(Point p,List<VisionPoint> r,ReferenceDatabase.PreparedReference ref){return r!=null&&p.x>=r.get(0).getX()*ref.imageWidth&&p.y>=r.get(0).getY()*ref.imageHeight&&p.x<=r.get(2).getX()*ref.imageWidth&&p.y<=r.get(2).getY()*ref.imageHeight;}
    private static boolean inAny(Point p,List<List<VisionPoint>> regions,ReferenceDatabase.PreparedReference ref){for(List<VisionPoint> r:regions)if(in(p,r,ref))return true;return false;}
    private static boolean inside(Point p,List<VisionPoint> corners,double scale){double sign=0;for(int i=0;i<4;i++){VisionPoint a=corners.get(i),b=corners.get((i+1)%4);double cross=(b.getX()-a.getX())*(p.y/scale-a.getY())-(b.getY()-a.getY())*(p.x/scale-a.getX());if(Math.abs(cross)<1e-8)continue;if(sign==0)sign=Math.signum(cross);else if(sign*cross<0)return false;}return true;}
    private static boolean distinct(List<VisionPoint> corners,List<List<VisionPoint>> found){
        MatOfPoint2f candidate=polygon(corners),intersection=new MatOfPoint2f();
        try{for(List<VisionPoint> previous:found){
            MatOfPoint2f other=polygon(previous);
            try{double shared=Imgproc.intersectConvexConvex(candidate,other,intersection,true);
                if(shared>Math.min(Imgproc.contourArea(candidate),Imgproc.contourArea(other))*.30)return false;
            }finally{other.release();}
        }return true;}finally{candidate.release();intersection.release();}
    }
    private static MatOfPoint2f polygon(List<VisionPoint> corners){Point[] points=new Point[corners.size()];for(int i=0;i<points.length;i++)points[i]=new Point(corners.get(i).getX(),corners.get(i).getY());return new MatOfPoint2f(points);}
    private static final class Match{final double score;final List<VisionPoint> corners;Match(double score,List<VisionPoint> corners){this.score=score;this.corners=corners;}}
    public void close(){
        for(Map<List<VisionPoint>,Crop> crops:cropQueries.values())for(Crop crop:crops.values())if(crop.descriptors!=null)crop.descriptors.release();
        cropQueries.clear();trackerSearch.close();orb.clear();clahe.clear();matcher.clear();
    }
}

