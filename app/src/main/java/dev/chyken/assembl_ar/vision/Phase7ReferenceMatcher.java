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
    private final ComponentDetailExtractor casterDetail;
    private final DetailSamplingSchedule samplingSchedule=new DetailSamplingSchedule();
    private final List<ReferenceDatabase.PreparedReference> trackerReferences = new ArrayList<>();
    private final List<ReferenceDatabase.PreparedReference> installedReferences = new ArrayList<>();
    private final Map<ReferenceDatabase.PreparedOrbVariant,Map<List<VisionPoint>,Crop>> cropQueries = new IdentityHashMap<>();
    private final Map<ReferenceDatabase.PreparedOrbVariant,Crop> contextQueries=new IdentityHashMap<>();
    private ReferenceDatabase.PreparedReference preferredBody, preferredBoard;
    // A board-only window proposal must not overwrite the last template that
    // independently verified BOTH trackers, mounts and caster on one frame.
    private ReferenceDatabase.PreparedReference preferredAssembly;private int preferredAssemblyMisses;
    private final List<Rect> confirmedWindows=new ArrayList<>();
    private final List<ReferenceDatabase.PreparedReference> confirmedBodies=new ArrayList<>();
    private int confirmedWidth,confirmedHeight;
    private final List<List<VisionPoint>> searchHints=new ArrayList<>();
    private long hintTime;
    private final int[][] windowCursors=new int[5][16];
    private int boardCursor,frameWidth,frameHeight,lastTrackerStage;
    Phase7ReferenceMatcher(ReferenceDatabase db,VisionConfig config){
        database=db;clahe=OpenCvVisionUtils.createClahe(config);trackerSearch=new Phase7TrackerSearch(config);casterDetail=new ComponentDetailExtractor(config);
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
        if(step!=lastTrackerStage){searchHints.clear();hintTime=0;preferredBody=null;preferredAssembly=null;preferredAssemblyMisses=0;confirmedWindows.clear();confirmedBodies.clear();lastTrackerStage=step;samplingSchedule.reset();}
        long started=android.os.SystemClock.elapsedRealtimeNanos();Mat work=new Mat(),descriptors=new Mat(),empty=new Mat(),remaining=null,trackerGray=null;MatOfKeyPoint keypoints=new MatOfKeyPoint();
        double scale=Math.min(1,1280.0/Math.max(gray.cols(),gray.rows()));List<List<VisionPoint>> located=new ArrayList<>();double negative=0,score=0;
        boolean requestedRecovery=DiagnosticHammingMatcher.snapshot().recovery;
        database.acquireForRecognition();
        try{
            if(step==2||step==4)DiagnosticHammingMatcher.recovery(samplingSchedule.recoveryFor(requestedRecovery));
            if(scale<1)Imgproc.resize(gray,work,new Size(),scale,scale,Imgproc.INTER_AREA);else gray.copyTo(work);
            frameWidth=work.cols();frameHeight=work.rows();
            if(step==1||step==2||step==4)trackerGray=work.clone();
            DiagnosticHammingMatcher.normalize(clahe,work,work);DiagnosticHammingMatcher.extract(orb,work,empty,keypoints,descriptors);KeyPoint[] live=keypoints.toArray();DiagnosticHammingMatcher.captureLive(live,descriptors,scale);
            if(step==2||step==4){
                Phase7Observation evidence=inspectTrackers(trackerGray,rgb,live,descriptors,scale,located,step==4);
                samplingSchedule.observe(evidence.accepted(step),DiagnosticHammingMatcher.snapshot().recovery);
                List<RecognitionResult.InstanceObservation> instances=new ArrayList<>();
                for(int i=0;i<located.size();i++)instances.add(new RecognitionResult.InstanceObservation("phase7:part:"+i,i<2?"line_tracker_module":"caster_wheel",RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY,located.get(i),.88));
                return RecognitionResult.builder(time).identity("phase7:trackers","line_tracker_module",step==4?"Caster and dual tracker installation":"Line trackers")
                        .method(located.isEmpty()?RecognitionResult.DetectionMethod.NONE:RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY).instances(instances)
                        .validation(evidence.accepted(step)?RecognitionResult.ValidationState.EXPECTED_COMPONENT:RecognitionResult.ValidationState.NO_COMPONENT)
                        .matches(live.length,0,0,0).confidence(evidence.accepted(step)?.88:0)
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
                if(best==null&&step==1){
                    // A small texture-poor caster needs local sampling, just like
                    // the narrow sensor PCBs. This is one bounded fallback, not
                    // another full-library sweep or a silhouette-only approval.
                    List<VisionPoint> frame=Arrays.asList(new VisionPoint(0,0),new VisionPoint(frameWidth,0),new VisionPoint(frameWidth,frameHeight),new VisionPoint(0,frameHeight));
                    for(int extent:new int[]{0,960}){
                    try(ComponentDetailExtractor.Sample detail=casterDetail.extract(trackerGray,frame,extent)){
                        if(detail!=null)for(ReferenceDatabase.PreparedReference ref:database.preparedReferences()){
                            if(!"caster".equals(Phase7AssetCatalog.referenceRole(ref.spec.getReferenceId())))continue;
                            for(ReferenceDatabase.PreparedOrbVariant variant:ref.orbVariants)if(variant.hasOrbData())for(List<VisionPoint> region:ref.spec.getFeatureRegionsNormalized()){
                                Match found=match(ref,variant,detail.keys,detail.descriptors,scale,Collections.singletonList(region),false,false,region);
                                if(found!=null&&(best==null||found.score>best.score))best=found;
                            }
                        }
                    }
                    if(best!=null)break;
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
                .matches(live.length,0,0,0).confidence(observation.accepted(step)?.88:0)
                .latency(new RecognitionResult.Latency(0,0,0,0,(android.os.SystemClock.elapsedRealtimeNanos()-started)/1e6));
            return result.build().withPhase7Observation(observation);
        }finally{DiagnosticHammingMatcher.recovery(requestedRecovery);database.releaseAfterRecognition();work.release();descriptors.release();empty.release();keypoints.release();if(remaining!=null)remaining.release();if(trackerGray!=null)trackerGray.release();}
    }
    private Phase7Observation inspectTrackers(Mat gray,Mat rgb,KeyPoint[] live,Mat descriptors,double scale,List<List<VisionPoint>> located,boolean installation){
        List<Body> bodies=new ArrayList<>();List<List<VisionPoint>> quads=new ArrayList<>();double score=0;
        List<Rect> windows=trackerSearch.windows(gray,rgb);
        if(installation)windows.addAll(0,boardProposals(live,descriptors,gray));
        long clock=System.nanoTime();
        if(clock-hintTime<1_500_000_000L)for(List<VisionPoint> hint:searchHints){
            double l=Double.MAX_VALUE,t=l,r=-l,b=-l;for(VisionPoint p:hint){l=Math.min(l,p.getX()*scale);r=Math.max(r,p.getX()*scale);t=Math.min(t,p.getY()*scale);b=Math.max(b,p.getY()*scale);}
            int x=Math.max(0,(int)(l-(r-l)*.35)),y=Math.max(0,(int)(t-(b-t)*.35));
            int right=Math.min(gray.cols(),(int)Math.ceil(r+(r-l)*.35)),bottom=Math.min(gray.rows(),(int)Math.ceil(b+(b-t)*.35));
            if(right-x>=16&&bottom-y>=16)windows.add(0,new Rect(x,y,right-x,bottom-y));
        }
        boolean confirmed=installation&&preferredAssembly!=null&&confirmedWindows.size()==2&&confirmedWidth==gray.cols()&&confirmedHeight==gray.rows();
        // Hold the actual winning crops and source templates for confirmation.
        // New pixels are still extracted/matched; a tighter inferred crop changes
        // CLAHE tiling and scale and must not replace the just-verified sampling.
        if(confirmed)for(int i=confirmedWindows.size()-1;i>=0;i--){Rect r=confirmedWindows.get(i);windows.add(0,new Rect(r.x,r.y,r.width,r.height));}
        // Native pixels must participate as well as enlarged detail passes. Actual tablet
        // captures match at 1x but fail at 3–4x despite having more detected keypoints.
        int sampling=samplingSchedule.next();
        int featureExtent=new int[]{0,192,400,600,800}[sampling];
        long deadline=System.nanoTime()+650_000_000L;boolean confirmationGraceUsed=false;
        for(int slot=0;slot<Math.min(windows.size(),windowCursors[0].length);slot++){
            Rect window=windows.get(slot);
            if(coveredWindow(window,quads,scale))continue;
            if(System.nanoTime()>deadline&&bodies.size()==1&&installation){if(confirmationGraceUsed)break;confirmationGraceUsed=true;}
            int[] cursors=windowCursors[sampling];
            boolean hadPreferredBody=preferredBody!=null;
            List<ReferenceDatabase.PreparedReference> candidates;
            Body best=null;
            try(Phase7TrackerSearch.Features local=trackerSearch.extract(gray,window,featureExtent)){
                // Remove the already located instance BEFORE the ratio test. In overlapping
                // windows, two identical sensors otherwise suppress each other's descriptors.
                for(int k=0;k<local.points.length;k++)for(List<VisionPoint> found:quads)if(inside(local.points[k].pt,found,scale)){
                    Mat row=local.descriptors.row(k);try{row.setTo(new Scalar(0));}finally{row.release();}break;
                }
                int usable=0;for(KeyPoint k:local.points){boolean used=false;for(List<VisionPoint> found:quads)used|=inside(k.pt,found,scale);if(!used)usable++;}
                if(usable<10)continue;
                candidates=rankedBodies(local.descriptors,cursors[slot],preferredBody);
                if(confirmed&&slot<2){ReferenceDatabase.PreparedReference source=confirmedBodies.get(slot);candidates.remove(source);candidates.add(0,source);}
                // Extraction is not template matching time. On the tablet it used to consume
                // the entire 80ms allowance, leaving only one photograph tested per window.
                long windowDeadline=Math.min(deadline,System.nanoTime()+80_000_000L);
                int inspected=0;
                for(ReferenceDatabase.PreparedReference ref:candidates){
                    if(inspected>=2&&System.nanoTime()>windowDeadline)break;
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
                                best=new Body(found,vertical,ref,new Rect(window.x,window.y,window.width,window.height));
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
            if(bodies.size()==2)break;
            // One bounded extra distinct window lets an installation view
            // verify its second sensor when CPU scheduling consumed the budget.
            if(System.nanoTime()>deadline&&!(bodies.size()==1&&installation&&!confirmationGraceUsed))break;
        }
        if(!quads.isEmpty()){
            // A stale proposal for a previous camera position must not be refreshed
            // forever just because the OTHER module remains visible.
            searchHints.clear();searchHints.addAll(quads);
            hintTime=System.nanoTime();
        }
        if(bodies.isEmpty())preferredBody=null;
        if(!installation)return new Phase7Observation(bodies.size(),false,score);
        if(bodies.size()<2){assemblyMiss();return Phase7Observation.installedTrackers(bodies.size(),0,false,score);}
        int mounts=0;boolean context=false,caster=false;boolean[] visibleNuts=new boolean[2];
        long contextDeadline=System.nanoTime()+300_000_000L;
        // A measured sensor viewpoint is a better local-caster candidate than
        // a board-only proposal. Keep other reviewed angles ahead of the rotating
        // legacy fallback so a repetitive PCB cannot starve local hardware proof.
        LinkedHashSet<ReferenceDatabase.PreparedReference> contextOrder=new LinkedHashSet<>();
        if(preferredAssembly!=null)contextOrder.add(preferredAssembly);
        for(Body body:bodies)if(installedReferences.contains(body.reference))contextOrder.add(body.reference);
        if(preferredBoard!=null)contextOrder.add(preferredBoard);
        List<ReferenceDatabase.PreparedReference> reviewed=new ArrayList<>();for(ReferenceDatabase.PreparedReference ref:installedReferences)if(dev.chyken.assembl_ar.camera.Phase789VideoReferences.annotation(ref.spec.getJpegAssetPath())!=null)reviewed.add(ref);
        for(int i=0;i<reviewed.size();i++)contextOrder.add(reviewed.get(Math.floorMod(boardCursor+i,reviewed.size())));
        ReferenceDatabase.PreparedReference preferred=contextOrder.isEmpty()?null:contextOrder.iterator().next();
        contextOrder.addAll(ordered(installedReferences,boardCursor,preferred));
        int inspectedContext=0;
        for(ReferenceDatabase.PreparedReference ref:contextOrder){
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
                    boolean candidateCaster=regions.size()>6&&localProof(ref,variant,live,descriptors,regions.get(6),h,6);
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
                    int candidateMounts=Phase7MountGeometry.distinctMounts(slots);
                    if(candidateMounts==2&&!candidateCaster&&regions.size()>6){
                        List<Point> plate=new ArrayList<>();for(VisionPoint p:regions.get(6))plate.add(new Point(p.getX()*ref.imageWidth,p.getY()*ref.imageHeight));
                        src.fromList(plate);Core.perspectiveTransform(src,dst,h);List<VisionPoint> search=new ArrayList<>();for(Point p:dst.toArray())search.add(new VisionPoint(p.x,p.y));
                        // The ball's highlight and prongs are too small in the
                        // whole-frame ORB pyramid in some rooms. Sample just this
                        // measured mount; fresh local identity is still required.
                        try(ComponentDetailExtractor.Sample detail=casterDetail.extract(gray,search,360)){
                            candidateCaster=detail!=null&&localProof(ref,variant,detail.keys,detail.descriptors,regions.get(6),h,6);
                        }
                    }
                    if(candidateMounts>mounts||candidateMounts==mounts&&candidateCaster){mounts=candidateMounts;caster=candidateCaster;}
                    // The scan certifies two sensor locations. Reflective fasteners remain
                    // explicit hardware/stability answers, even when their image evidence is weak.
                    if(candidateMounts==2&&candidateCaster){
                        List<Point> plate=new ArrayList<>();for(VisionPoint p:regions.get(6))plate.add(new Point(p.getX()*ref.imageWidth,p.getY()*ref.imageHeight));
                        src.fromList(plate);Core.perspectiveTransform(src,dst,h);List<VisionPoint> outline=new ArrayList<>();for(Point p:dst.toArray())outline.add(new VisionPoint(p.x/scale,p.y/scale));located.add(outline);
                        preferredBoard=ref;preferredAssembly=ref;preferredAssemblyMisses=0;hintTime=System.nanoTime();
                        confirmedWindows.clear();confirmedBodies.clear();for(Body body:bodies){confirmedWindows.add(body.window);confirmedBodies.add(body.reference);}confirmedWidth=gray.cols();confirmedHeight=gray.rows();
                        return Phase7Observation.installedTrackers(2,2,true,score+n,(visibleNuts[0]?1:0)+(visibleNuts[1]?1:0)).withCaster(true);
                    }
                }finally{src.release();dst.release();mask.release();if(h!=null)h.release();}
            }
        }
        boardCursor+=Math.max(1,inspectedContext-(preferred!=null?1:0));
        assemblyMiss();
        return Phase7Observation.installedTrackers(2,mounts,context,score,(visibleNuts[0]?1:0)+(visibleNuts[1]?1:0)).withCaster(caster);
    }
    private void assemblyMiss(){if(preferredAssembly!=null&&++preferredAssemblyMisses>=2){preferredAssembly=null;preferredAssemblyMisses=0;confirmedWindows.clear();confirmedBodies.clear();}}
    /** Fixed PCB features propose search windows only. Every module and the caster
     * still need their OWN current-frame geometric proof below. No whole-board
     * match, predicted window or old one-module frame can complete this scan. */
    private List<Rect> boardProposals(KeyPoint[] live,Mat descriptors,Mat gray){
        List<ReferenceDatabase.PreparedReference> refs=new ArrayList<>();if(preferredBoard!=null)refs.add(preferredBoard);
        for(ReferenceDatabase.PreparedReference ref:installedReferences)if(ref!=preferredBoard&&dev.chyken.assembl_ar.camera.Phase789VideoReferences.annotation(ref.spec.getJpegAssetPath())!=null)refs.add(ref);
        Map<ReferenceDatabase.PreparedReference,Integer> scores=new IdentityHashMap<>();
        for(ReferenceDatabase.PreparedReference ref:refs){int score=0;for(ReferenceDatabase.PreparedOrbVariant v:ref.orbVariants){Crop c=contextCrop(ref,v);if(!c.sparse.empty()&&descriptors.rows()>1){NearestDescriptorPairs pairs=NearestDescriptorPairs.compute(c.sparse,descriptors);for(int i=0;i<pairs.size();i++)if(pairs.hasTwo(i)&&OrbMatchPolicy.accepts(pairs.bestDistance(i),pairs.secondDistance(i)))score++;}break;}scores.put(ref,score);}
        refs.sort((a,b)->Integer.compare(scores.get(b),scores.get(a)));
        int tried=0;for(ReferenceDatabase.PreparedReference ref:refs){if(tried++==3)break;List<List<VisionPoint>> regions=ref.spec.getFeatureRegionsNormalized();
            for(ReferenceDatabase.PreparedOrbVariant v:ref.orbVariants){Crop c=contextCrop(ref,v);if(c.descriptors==null)continue;
                List<DMatch> good=ComponentRecognizer.uniqueFrameMatches(matcher.ratioMatches(c.descriptors,descriptors));if(good.size()<18)continue;
                MatOfPoint2f a=new MatOfPoint2f(),b=new MatOfPoint2f();Mat mask=new Mat(),h=null;
                try{List<Point> src=new ArrayList<>(),dst=new ArrayList<>();for(DMatch m:good){src.add(v.keypoints[c.ids.get(m.queryIdx)].pt);dst.add(live[m.trainIdx].pt);}a.fromList(src);b.fromList(dst);h=DiagnosticHammingMatcher.homography(a,b,Calib3d.RANSAC,3.5,mask,1200,.995);if(h.empty()||Core.countNonZero(mask)<18||Core.countNonZero(mask)<good.size()*.5)continue;
                    List<Rect> found=new ArrayList<>();for(int part=0;part<2;part++){
                        List<Point> corners=new ArrayList<>();for(VisionPoint p:regions.get(part))corners.add(new Point(p.getX()*ref.imageWidth,p.getY()*ref.imageHeight));a.fromList(corners);Core.perspectiveTransform(a,b,h);
                        double l=gray.cols(),t=gray.rows(),r=0,bt=0;for(Point p:b.toArray()){l=Math.min(l,p.x);r=Math.max(r,p.x);t=Math.min(t,p.y);bt=Math.max(bt,p.y);}double dx=(r-l)*.25,dy=(bt-t)*.20;
                        if(!Double.isFinite(l+t+r+bt)||r-l<12||bt-t<12||r-l>gray.cols()||bt-t>gray.rows())continue;
                        found.add(Phase9PhotoGeometry.bounded(l-dx,t-dy,r+dx,bt+dy,gray));
                    }
                    if(found.size()==2){preferredBoard=ref;return found;}
                }finally{a.release();b.release();mask.release();if(h!=null)h.release();}
            }
        }return Collections.emptyList();
    }
    private Crop contextCrop(ReferenceDatabase.PreparedReference ref,ReferenceDatabase.PreparedOrbVariant v){
        Crop c=contextQueries.get(v);if(c!=null)return c;List<Integer> ids=new ArrayList<>();for(int i=0;i<v.keypoints.length;i++)if(!inAny(v.keypoints[i].pt,ref.spec.getFeatureRegionsNormalized(),ref))ids.add(i);
        c=new Crop(ids,ids.size()<18?null:rows(v.descriptors,ids));contextQueries.put(v,c);return c;
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
        return localProof(ref,variant,live,descriptors,roi,h,3);
    }
    private boolean localProof(ReferenceDatabase.PreparedReference ref,ReferenceDatabase.PreparedOrbVariant variant,KeyPoint[] live,Mat descriptors,List<VisionPoint> roi,Mat h,int minimum){
        List<Integer> sourceIds=new ArrayList<>(),liveIds=new ArrayList<>();
        for(int i=0;i<variant.keypoints.length;i++)if(in(variant.keypoints[i].pt,roi,ref))sourceIds.add(i);
        if(sourceIds.size()<minimum)return false;
        Mat inverse=h.inv();MatOfPoint2f pixels=new MatOfPoint2f(),canonical=new MatOfPoint2f();
        Mat query=null,train=null;
        try{
            Point[] positions=new Point[live.length];for(int i=0;i<live.length;i++)positions[i]=live[i].pt;
            pixels.fromArray(positions);Core.perspectiveTransform(pixels,canonical,inverse);Point[] mapped=canonical.toArray();
            double l=roi.get(0).getX()*ref.imageWidth,t=roi.get(0).getY()*ref.imageHeight,r=roi.get(2).getX()*ref.imageWidth,b=roi.get(2).getY()*ref.imageHeight;
            for(int i=0;i<mapped.length;i++){Point p=mapped[i];if(p.x>=l-(r-l)*.15&&p.x<=r+(r-l)*.15&&p.y>=t-(b-t)*.15&&p.y<=b+(b-t)*.15)liveIds.add(i);}
            if(liveIds.size()<minimum)return false;
            query=rows(variant.descriptors,sourceIds);train=rows(descriptors,liveIds);
            // Nuts repeat throughout the board. Restrict matching to the projected mount BEFORE
            // the ratio test, so another nut elsewhere cannot steal the correct correspondence.
            List<DMatch> good=matcher.ratioMatches(query,train);
            good.sort(Comparator.comparingDouble(m->m.distance));Set<Integer> used=new HashSet<>();List<Point> a=new ArrayList<>(),c=new ArrayList<>();
            for(DMatch m:good)if(used.add(m.trainIdx)){a.add(variant.keypoints[sourceIds.get(m.queryIdx)].pt);c.add(live[liveIds.get(m.trainIdx)].pt);}
            if(a.size()<minimum)return false;pixels.fromList(a);Core.perspectiveTransform(pixels,canonical,h);Point[] projected=canonical.toArray();
            if(minimum>=6){
                // The caster ball protrudes from the PCB. Its own homography may differ
                // from the flat-board transform; require local identity and coherent placement.
                Mat localMask=new Mat(),local=null;MatOfPoint2f observed=new MatOfPoint2f();
                try{
                    observed.fromList(c);local=DiagnosticHammingMatcher.homography(pixels,observed,Calib3d.RANSAC,3.5,localMask,800,.995);
                    if(local.empty()||Core.countNonZero(localMask)<minimum||Core.countNonZero(localMask)<a.size()*.5)return false;
                    double lx=Double.MAX_VALUE,ly=lx,rx=-lx,ry=-lx;
                    for(int i=0;i<a.size();i++)if(localMask.get(i,0)[0]!=0){Point p=a.get(i);lx=Math.min(lx,p.x);rx=Math.max(rx,p.x);ly=Math.min(ly,p.y);ry=Math.max(ry,p.y);}
                    if(rx-lx<(r-l)*.20||ry-ly<(b-t)*.20)return false;
                    pixels.fromArray(new Point(l,t),new Point(r,t),new Point(r,b),new Point(l,b));
                    Core.perspectiveTransform(pixels,canonical,h);Core.perspectiveTransform(pixels,observed,local);
                    Point[] expected=canonical.toArray(),actual=observed.toArray();double diagonal=Math.hypot(expected[0].x-expected[2].x,expected[0].y-expected[2].y);
                    for(int i=0;i<4;i++)if(!Double.isFinite(actual[i].x+actual[i].y)||Math.hypot(actual[i].x-expected[i].x,actual[i].y-expected[i].y)>Math.max(8,diagonal*.35))return false;
                    return true;
                }finally{localMask.release();observed.release();if(local!=null)local.release();}
            }
            int count=0;double lx=Double.MAX_VALUE,ly=lx,rx=-lx,ry=-lx;
            for(int i=0;i<a.size();i++)if(Math.hypot(projected[i].x-c.get(i).x,projected[i].y-c.get(i).y)<=4){Point p=a.get(i);count++;lx=Math.min(lx,p.x);rx=Math.max(rx,p.x);ly=Math.min(ly,p.y);ry=Math.max(ry,p.y);}
            return count>=minimum&&rx-lx>=(r-l)*.20&&ry-ly>=(b-t)*.20;
        }finally{inverse.release();pixels.release();canonical.release();if(query!=null)query.release();if(train!=null)train.release();}
    }
    private static Mat rows(Mat descriptors,List<Integer> ids){
        Mat result=new Mat(ids.size(),descriptors.cols(),descriptors.type());
        try{for(int i=0;i<ids.size();i++){Mat source=descriptors.row(ids.get(i)),destination=result.row(i);try{source.copyTo(destination);}finally{source.release();destination.release();}}
        return result;
        }catch(RuntimeException|Error failure){result.release();throw failure;}
    }
    @SuppressWarnings("unchecked")
    private List<Point>[] correspondences(ReferenceDatabase.PreparedOrbVariant variant,KeyPoint[] live,Mat descriptors){
        List<DMatch> good=new ArrayList<>();
        good.addAll(matcher.ratioMatches(variant.descriptors,descriptors));
        good.sort(Comparator.comparingDouble(m->m.distance));Set<Integer> used=new HashSet<>();List<Point> a=new ArrayList<>(),b=new ArrayList<>();
        for(DMatch m:good)if(used.add(m.trainIdx)){a.add(variant.keypoints[m.queryIdx].pt);b.add(live[m.trainIdx].pt);}
        return new List[]{a,b};
    }
    private static final class Body{
        final Match match;final boolean vertical;final ReferenceDatabase.PreparedReference reference;final Rect window;
        Body(Match match,boolean vertical,ReferenceDatabase.PreparedReference reference,Rect window){this.match=match;this.vertical=vertical;this.reference=reference;this.window=window;}
    }
    private static final class Crop{
        final List<Integer> ids;final Mat descriptors,sparse;
        Crop(List<Integer> ids,Mat descriptors){this.ids=ids;this.descriptors=descriptors;
            int count=descriptors==null?0:Math.min(32,descriptors.rows());sparse=count==0?new Mat():new Mat(count,descriptors.cols(),descriptors.type());
            try{
            if(count>0){int stride=descriptors.cols();byte[] all=new byte[descriptors.rows()*stride],selected=new byte[count*stride];descriptors.get(0,0,all);for(int i=0;i<count;i++)System.arraycopy(all,(i*descriptors.rows()/count)*stride,selected,i*stride,stride);sparse.put(0,0,selected);}
            }catch(RuntimeException|Error failure){if(descriptors!=null)descriptors.release();sparse.release();throw failure;}
        }
    }
    /** Sparse ranking schedules a viewpoint; only the full geometric check accepts it. */
    private List<ReferenceDatabase.PreparedReference> rankedBodies(Mat live,int cursor,ReferenceDatabase.PreparedReference preferred){
        LinkedHashSet<ReferenceDatabase.PreparedReference> window=new LinkedHashSet<>();if(preferred!=null)window.add(preferred);
        for(ReferenceDatabase.PreparedReference ref:trackerReferences)if(dev.chyken.assembl_ar.camera.Phase789VideoReferences.annotation(ref.spec.getJpegAssetPath())!=null)window.add(ref);
        for(int i=0;i<Math.min(8,trackerReferences.size());i++)window.add(trackerReferences.get(Math.floorMod(cursor+i,trackerReferences.size())));
        List<ReferenceDatabase.PreparedReference> ordered=new ArrayList<>(window);
        Map<ReferenceDatabase.PreparedReference,Integer> scores=new IdentityHashMap<>();
        long started=android.os.SystemClock.elapsedRealtimeNanos();
        try{
            for(ReferenceDatabase.PreparedReference ref:ordered){int best=0;
                for(ReferenceDatabase.PreparedOrbVariant v:ref.orbVariants){if(!v.hasOrbData())continue;
                    List<List<VisionPoint>> regions=ref.spec.getFeatureRegionsNormalized();
                    for(int i=0;i<Math.min(2,regions.size());i++){
                        Crop body=crop(ref,v,regions.get(i));if(body.sparse.empty()||live.rows()<2)continue;
                        NearestDescriptorPairs pairs=NearestDescriptorPairs.compute(body.sparse,live);int n=0;
                        for(int k=0;k<pairs.size();k++)if(pairs.hasTwo(k)&&OrbMatchPolicy.accepts(pairs.bestDistance(k),pairs.secondDistance(k)))n++;
                        best=Math.max(best,n);
                    }
                    break;
                }
                scores.put(ref,best);
            }
        }finally{DiagnosticHammingMatcher.snapshot().matchingNs+=android.os.SystemClock.elapsedRealtimeNanos()-started;}
        // A photograph that identified the first module need not fit the second:
        // its trim-pot angle/reflection may differ. Rank the CURRENT local body,
        // and reserve a rotating alternative so two strong but unsuitable probes
        // cannot monopolize the short window budget forever.
        ordered.sort((a,b)->Integer.compare(scores.get(b),scores.get(a)));
        if(ordered.size()>2){ReferenceDatabase.PreparedReference fallback=ordered.get(Math.floorMod(cursor,ordered.size()));ordered.remove(fallback);ordered.add(1,fallback);}return ordered;
    }
    private static boolean coveredWindow(Rect window,List<List<VisionPoint>> known,double scale){
        for(List<VisionPoint> quad:known){double x=0,y=0;for(VisionPoint p:quad){x+=p.getX()*scale/4;y+=p.getY()*scale/4;}
            double diagonal=Math.hypot(quad.get(0).getX()-quad.get(2).getX(),quad.get(0).getY()-quad.get(2).getY())*scale;
            if(Math.abs(x-(window.x+window.width*.5))<window.width*.25&&Math.abs(y-(window.y+window.height*.5))<window.height*.25&&Math.hypot(window.width,window.height)<diagonal*2.3)return true;
        }return false;
    }
    private Crop crop(ReferenceDatabase.PreparedReference ref,ReferenceDatabase.PreparedOrbVariant variant,List<VisionPoint> region){
        Map<List<VisionPoint>,Crop> crops=cropQueries.computeIfAbsent(variant,key->new HashMap<>());
        Crop cached=crops.get(region);if(cached!=null)return cached;
        List<Integer> ids=new ArrayList<>();
        for(int i=0;i<variant.keypoints.length;i++)if(in(variant.keypoints[i].pt,region,ref))ids.add(i);
        if(ids.size()>600&&dev.chyken.assembl_ar.camera.Phase789VideoReferences.annotation(ref.spec.getJpegAssetPath())!=null){List<Integer> sampled=new ArrayList<>();for(int i=0;i<600;i++)sampled.add(ids.get(i*ids.size()/600));ids=sampled;}
        cached=new Crop(ids,ids.size()<10?null:rows(variant.descriptors,ids));crops.put(region,cached);return cached;
    }
    private Match match(ReferenceDatabase.PreparedReference ref,ReferenceDatabase.PreparedOrbVariant variant,KeyPoint[] live,Mat descriptors,double scale,List<List<VisionPoint>> regions,boolean installed,boolean bad,List<VisionPoint> looseRegion){DiagnosticHammingMatcher.context(ref);
        return match(ref,variant,live,descriptors,scale,regions,installed,bad,looseRegion,null);
    }
    private Match match(ReferenceDatabase.PreparedReference ref,ReferenceDatabase.PreparedOrbVariant variant,KeyPoint[] live,Mat descriptors,double scale,List<List<VisionPoint>> regions,boolean installed,boolean bad,List<VisionPoint> looseRegion,List<VisionPoint> identity){DiagnosticHammingMatcher.context(ref);
        List<DMatch> good=new ArrayList<>();
        List<Integer> queryIds=new ArrayList<>();Mat localQuery=null;
        if(!installed){
            Crop cached=crop(ref,variant,looseRegion);queryIds=cached.ids;
            if(queryIds.size()<10)return null;localQuery=cached.descriptors;
        }
        {
            // Match only the component crop. Board features outside it must neither consume
            // matching time nor steal a live keypoint during train-index deduplication.
            for(DMatch m:matcher.ratioMatches(localQuery!=null?localQuery:variant.descriptors,descriptors)){if(localQuery!=null)m.queryIdx=queryIds.get(m.queryIdx);good.add(m);}
        }
        good.sort(Comparator.comparingDouble(m->m.distance));List<Point> a=new ArrayList<>(),b=new ArrayList<>();List<Point> allA=new ArrayList<>(),allB=new ArrayList<>();Set<Integer> used=new HashSet<>();
        for(DMatch m:good){if(!used.add(m.trainIdx))continue;Point p=variant.keypoints[m.queryIdx].pt,q=live[m.trainIdx].pt;allA.add(p);allB.add(q);
            if(installed?bad||!inAny(p,regions,ref):in(p,looseRegion,ref)){a.add(p);b.add(q);}
        }
        int minimum=installed?18:10;
        if(a.size()<minimum)return null;
        // Necessary preconditions for the identical post-RANSAC gates below. Do not spend
        // a full homography search on a tiny fragment that could never satisfy those gates.
        if(!installed&&!spread(a,looseRegion,ref,.35,10))return null;
        if(identity!=null){List<Point> detail=new ArrayList<>();for(Point p:allA)if(in(p,identity,ref))detail.add(p);if(!spread(detail,identity,ref,.25,4))return null;}
        MatOfPoint2f src=new MatOfPoint2f(),dst=new MatOfPoint2f();Mat mask=new Mat(),h=null;
        try{
            src.fromList(a);dst.fromList(b);
            if(!installed&&"caster".equals(Phase7AssetCatalog.referenceRole(ref.spec.getReferenceId()))){
                // A ball and its prongs are not one flat PCB. A constrained affine
                // fit avoids projective horizons from this shallow 3D body.
                long fit=android.os.SystemClock.elapsedRealtimeNanos();Mat affine=Calib3d.estimateAffinePartial2D(src,dst,mask,Calib3d.RANSAC,3.5,1600,.995,10);
                try{if(affine.empty())return null;h=Mat.eye(3,3,CvType.CV_64F);Mat rows=h.rowRange(0,2);try{affine.copyTo(rows);DiagnosticHammingMatcher.recordFit(src,dst,h,mask);}finally{rows.release();}}finally{affine.release();DiagnosticHammingMatcher.snapshot().geometryNs+=android.os.SystemClock.elapsedRealtimeNanos()-fit;}
            }else h=DiagnosticHammingMatcher.homography(src,dst,Calib3d.RANSAC,3.5,mask,1600,.995);
            int inliers=h.empty()?0:Core.countNonZero(mask);
            if(android.util.Log.isLoggable("AssemblArHardware",android.util.Log.DEBUG)&&dev.chyken.assembl_ar.camera.Phase789VideoReferences.annotation(ref.spec.getJpegAssetPath())!=null)
                android.util.Log.d("AssemblArHardware",ref.spec.getJpegAssetPath()+" good="+a.size()+" inliers="+inliers+" minimum="+minimum+" identity="+(identity!=null));
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
                List<Point> confirmed=new ArrayList<>();for(int i=0;i<a.size();i++)if(mask.get(i,0)[0]!=0)confirmed.add(a.get(i));
                if(!spread(confirmed,looseRegion,ref,.35,10))return null;
            }
            List<VisionPoint> c=installed?ref.spec.getPlanarCornersNormalized():looseRegion;Point[] points=new Point[4];for(int i=0;i<4;i++)points[i]=new Point(c.get(i).getX()*ref.imageWidth,c.get(i).getY()*ref.imageHeight);
            src.fromArray(points);Core.perspectiveTransform(src,dst,h);Point[] q=dst.toArray();MatOfPoint2f polygon=new MatOfPoint2f(q);
            MatOfPoint convex=new MatOfPoint(q);
            try{double area=Imgproc.contourArea(polygon,true);if(area<120||!Double.isFinite(area)||area>frameWidth*(double)frameHeight*.90||!Imgproc.isContourConvex(convex))return null;
                for(Point p:q)if(p.x<-frameWidth*.25||p.x>frameWidth*1.25||p.y<-frameHeight*.25||p.y>frameHeight*1.25)return null;
                String role=Phase7AssetCatalog.referenceRole(ref.spec.getReferenceId());
                if(!installed&&("trackers".equals(role)||"trackerwork".equals(role))){double width=Math.hypot(q[1].x-q[0].x,q[1].y-q[0].y)+Math.hypot(q[2].x-q[3].x,q[2].y-q[3].y),height=Math.hypot(q[3].x-q[0].x,q[3].y-q[0].y)+Math.hypot(q[2].x-q[1].x,q[2].y-q[1].y),aspect=Math.max(width,height)/Math.max(1,Math.min(width,height));if(aspect<1.65||aspect>10)return null;}
            }finally{polygon.release();convex.release();}
            List<VisionPoint> corners=new ArrayList<>();for(Point p:q){if(!Double.isFinite(p.x+p.y)||Math.abs(p.x)>20000||Math.abs(p.y)>20000)return null;corners.add(new VisionPoint(p.x/scale,p.y/scale));}
            return new Match(score,corners);
        }finally{src.release();dst.release();mask.release();if(h!=null)h.release();}
    }
    private static boolean spread(List<Point> points,List<VisionPoint> region,ReferenceDatabase.PreparedReference ref,double fraction,int minimum){
        if(points.size()<minimum)return false;double l=Double.MAX_VALUE,t=l,r=-l,b=-l;
        // Coverage is measured along the actual PCB axes, not the axis-aligned
        // screen box. Tilted trackers otherwise need impossible cross-body spread.
        for(Point p:points){VisionPoint v=PlanarCoordinateMapper.unprojectUnitSquare(region,new VisionPoint(p.x/ref.imageWidth,p.y/ref.imageHeight));if(v==null)continue;l=Math.min(l,v.getX());r=Math.max(r,v.getX());t=Math.min(t,v.getY());b=Math.max(b,v.getY());}
        return r-l>=fraction&&b-t>=fraction;
    }
    private static boolean in(Point p,List<VisionPoint> r,ReferenceDatabase.PreparedReference ref){
        if(r==null)return false;double sign=0,x=p.x/ref.imageWidth,y=p.y/ref.imageHeight;
        for(int i=0;i<r.size();i++){VisionPoint a=r.get(i),b=r.get((i+1)%r.size());double cross=(b.getX()-a.getX())*(y-a.getY())-(b.getY()-a.getY())*(x-a.getX());if(Math.abs(cross)<1e-10)continue;if(sign==0)sign=Math.signum(cross);else if(sign*cross<0)return false;}return true;
    }
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
        casterDetail.close();
        for(Crop c:contextQueries.values()){if(c.descriptors!=null)c.descriptors.release();c.sparse.release();}contextQueries.clear();
        for(Map<List<VisionPoint>,Crop> crops:cropQueries.values())for(Crop crop:crops.values()){if(crop.descriptors!=null)crop.descriptors.release();crop.sparse.release();}
        cropQueries.clear();trackerSearch.close();orb.clear();clahe.clear();
    }
}

