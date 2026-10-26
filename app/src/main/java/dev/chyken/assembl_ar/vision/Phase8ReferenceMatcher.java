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
    private final ComponentDetailExtractor details;
    private final DetailSamplingSchedule recoverySchedule=new DetailSamplingSchedule();
    private int lastStage;
    private int refinementCursor;
    private int searchCursor;
    private final Map<String,ReferenceDatabase.PreparedReference> preferred=new HashMap<>();
    private final DiagnosticHammingMatcher matcher=new DiagnosticHammingMatcher();
    Phase8ReferenceMatcher(ReferenceDatabase database,VisionConfig config){this.database=database;details=new ComponentDetailExtractor(config);orb=ORB.create(3200,1.2f,8,31,0,2,ORB.HARRIS_SCORE,31,10);clahe=OpenCvVisionUtils.createClahe(config);}
    RecognitionResult inspect(Mat gray,long time,int step){return inspect(gray,null,time,step);}
    RecognitionResult inspect(Mat gray,Mat rgb,long time,int step){
        if(lastStage!=step){lastStage=step;recoverySchedule.reset();}
        boolean requestedRecovery=DiagnosticHammingMatcher.snapshot().recovery;
        long start=android.os.SystemClock.elapsedRealtimeNanos(),deadline=System.nanoTime()+900_000_000L;Mat raw=new Mat(),work=new Mat(),descriptors=new Mat(),empty=new Mat();MatOfKeyPoint keypoints=new MatOfKeyPoint();
        double scale=Math.min(1,1280.0/Math.max(gray.cols(),gray.rows()));
        double front=0,back=0,placed=0,reversed=0,invalid=0,inward=0,missing=0;List<VisionPoint> corners=Collections.emptyList();
        database.acquireForRecognition();
        try{
            DiagnosticHammingMatcher.recovery(recoverySchedule.recoveryFor(requestedRecovery));
            if(scale<1)Imgproc.resize(gray,work,new Size(),scale,scale,Imgproc.INTER_AREA);else gray.copyTo(work);
            work.copyTo(raw);DiagnosticHammingMatcher.normalize(clahe,work,work);DiagnosticHammingMatcher.extract(orb,work,empty,keypoints,descriptors);KeyPoint[] live=keypoints.toArray();DiagnosticHammingMatcher.captureLive(live,descriptors,scale);
            List<Candidate> candidates=new ArrayList<>();
            List<ReferenceDatabase.PreparedReference> scheduled=ranked(live,descriptors,step);
            int visited=0;
            if(!descriptors.empty())for(ReferenceDatabase.PreparedReference ref:scheduled){
                if(visited++>=2&&System.nanoTime()>deadline)break;
                String role=Phase8AssetCatalog.referenceRole(ref.spec.getReferenceId());
                if(role==null||(step==1)==Phase8AssetCatalog.installed(role))continue;
                for(ReferenceDatabase.PreparedOrbVariant variant:ref.recoveryVariants()){
                    if(!variant.hasOrbData())continue;
                    boolean reviewed=step==2&&dev.chyken.assembl_ar.camera.Phase789VideoReferences.annotation(ref.spec.getJpegAssetPath())!=null;
                    Match local=reviewed?refine(ref,variant,live,descriptors,raw,scale):match(ref,variant,live,descriptors,scale,false);if(local!=null)candidates.add(new Candidate(ref,variant,role,local,reviewed));
                }
            }
            // A reflective cap may contribute too few whole-frame features. Independent
            // PCB geometry may propose a detail crop, but cannot itself verify the sensor.
            for(int pass=0;pass<2;pass++){
            if(pass==1){
                if(step==1||placed>=10||reversed>=10||invalid>=10||System.nanoTime()>deadline)break;
                candidates.clear();
                List<ReferenceDatabase.PreparedReference> refs=scheduled;
                int count=Math.min(4,refs.size()),offset=refinementCursor++*2;
                for(int i=0;i<count;i++){
                    if(i>0&&System.nanoTime()>deadline)break;
                    ReferenceDatabase.PreparedReference ref=refs.get(Math.floorMod(offset+i,refs.size()));
                    for(ReferenceDatabase.PreparedOrbVariant variant:ref.recoveryVariants()){
                        if(!variant.hasOrbData())continue;
                        Match refined=refine(ref,variant,live,descriptors,raw,scale);
                        if(refined!=null)candidates.add(new Candidate(ref,variant,Phase8AssetCatalog.referenceRole(ref.spec.getReferenceId()),refined,true));
                    }
                }
            }
            candidates.sort((a,b)->Double.compare(b.local.score,a.local.score));
            Map<String,Integer> tried=new HashMap<>();
            for(Candidate c:candidates){
                int n=tried.getOrDefault(c.role,0);if(n>=4)continue;tried.put(c.role,n+1);
                Match found=step==1||c.refined?c.local:match(c.ref,c.variant,live,descriptors,scale,true);if(found==null)continue;
                preferred.put(c.role,c.ref);
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
            }
            if(step==1&&front==0&&back==0){
                // A small loose sensor can be only ~100 pixels wide at 640x400.
                // Sample its rigid body locally, rather than rewarding background
                // texture or requiring the user to fill the entire camera view.
                // Dark regions propose windows ONLY: the same 10-inlier, coverage,
                // ratio/distance and front/back geometric gates still decide.
                int windows=0;long proposalStart=android.os.SystemClock.elapsedRealtimeNanos();
                List<List<VisionPoint>> proposals=looseSensorWindows(raw);
                if(rgb!=null&&!rgb.empty()){
                    Mat compatible=new Mat();try{if(rgb.cols()!=raw.cols()||rgb.rows()!=raw.rows())Imgproc.resize(rgb,compatible,raw.size(),0,0,Imgproc.INTER_AREA);else rgb.copyTo(compatible);
                        LooseSensorShapeRecognizer.Candidate body=LooseSensorShapeRecognizer.findUltrasonic(compatible,.002);
                        // Appearance only proposes an ROI. Fresh reference-to-live
                        // ORB/RANSAC evidence below remains mandatory for Phase 8.
                        if(body!=null)proposals.add(0,body.corners);
                    }finally{compatible.release();}
                }
                DiagnosticHammingMatcher.snapshot().featureNs+=android.os.SystemClock.elapsedRealtimeNanos()-proposalStart;
                for(List<VisionPoint> window:proposals){
                    if(windows++>=2)break;
                    try(ComponentDetailExtractor.Sample detail=details.extract(raw,window,256)){
                        if(detail==null)continue;
                        long localDeadline=System.nanoTime()+400_000_000L;int tried=0;
                        for(ReferenceDatabase.PreparedReference ref:ranked(detail.keys,detail.descriptors,1)){
                            if(tried++>=2&&System.nanoTime()>localDeadline)break;
                            String role=Phase8AssetCatalog.referenceRole(ref.spec.getReferenceId());
                            for(ReferenceDatabase.PreparedOrbVariant v:ref.recoveryVariants()){
                                if(!v.hasOrbData())continue;Match found=match(ref,v,detail.keys,detail.descriptors,scale,false);if(found==null)continue;
                                preferred.put(role,ref);
                                if("front".equals(role)&&found.score>front){front=found.score;corners=found.actual;}
                                if("back".equals(role)&&found.score>back){back=found.score;if(back>front)corners=found.actual;}
                            }
                        }
                    }
                    if(front>0||back>0)break;
                }
            }
            Phase8Observation.State state=Phase8Observation.classify(step,front,back,placed,reversed,invalid);
            if(state==Phase8Observation.State.INVALID&&inward>0&&inward>missing)state=Phase8Observation.State.INWARD;
            double score=Math.max(Math.max(front,back),Math.max(invalid,Math.max(placed,reversed)));
            Phase8Observation result=new Phase8Observation(state,score);
            recoverySchedule.observe(result.accepted(step),DiagnosticHammingMatcher.snapshot().recovery);
            return RecognitionResult.builder(time).identity("phase8:photo","ultrasonic_distance_sensor","Distance sensor")
                    .method(corners.size()==4?RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY:RecognitionResult.DetectionMethod.NONE)
                    .validation(result.accepted(step)?RecognitionResult.ValidationState.EXPECTED_COMPONENT:state==Phase8Observation.State.FLIPPED||state==Phase8Observation.State.INVALID||state==Phase8Observation.State.INWARD?RecognitionResult.ValidationState.WRONG_ORIENTATION:RecognitionResult.ValidationState.NO_COMPONENT)
                    .corners(corners).matches(live.length,0,0,0).confidence(result.accepted(step)?.85:0)
                    .latency(new RecognitionResult.Latency(0,0,0,0,(android.os.SystemClock.elapsedRealtimeNanos()-start)/1e6)).build().withPhase8Observation(result);
        }finally{DiagnosticHammingMatcher.recovery(requestedRecovery);database.releaseAfterRecognition();raw.release();work.release();descriptors.release();empty.release();keypoints.release();}
    }
    private static List<List<VisionPoint>> looseSensorWindows(Mat gray){
        Mat mask=new Mat(),hierarchy=new Mat(),kernel=Imgproc.getStructuringElement(Imgproc.MORPH_RECT,new Size(3,3));
        List<MatOfPoint> contours=new ArrayList<>();List<Rect> boxes=new ArrayList<>();
        try{
            for(int threshold:new int[]{35,50,80,110}){
                for(Mat c:contours)c.release();contours.clear();Imgproc.threshold(gray,mask,threshold,255,Imgproc.THRESH_BINARY_INV);
                Imgproc.morphologyEx(mask,mask,Imgproc.MORPH_CLOSE,kernel);Imgproc.findContours(mask,contours,hierarchy,Imgproc.RETR_EXTERNAL,Imgproc.CHAIN_APPROX_SIMPLE);
                List<Rect> candidates=new ArrayList<>();for(MatOfPoint contour:contours)candidates.add(Imgproc.boundingRect(contour));
                for(Rect r:candidates){double aspect=Math.max(r.width,r.height)/(double)Math.max(1,Math.min(r.width,r.height));
                    if(Math.max(r.width,r.height)<25||Math.min(r.width,r.height)<12||r.area()>gray.total()*.20||aspect<1.35||aspect>4.5)continue;
                    boolean duplicate=false;for(Rect b:boxes){int w=Math.max(0,Math.min(r.x+r.width,b.x+b.width)-Math.max(r.x,b.x)),h=Math.max(0,Math.min(r.y+r.height,b.y+b.height)-Math.max(r.y,b.y));if(w*h>Math.min(r.area(),b.area())*.65){duplicate=true;break;}}
                    if(!duplicate)boxes.add(r);
                }
            }
            boxes.sort(Comparator.comparingDouble(r->Math.abs(Math.log(Math.max(r.width,r.height)/(double)Math.min(r.width,r.height)/2.2))+.4*Math.hypot(r.x+r.width*.5-gray.cols()*.5,r.y+r.height*.5-gray.rows()*.5)/Math.max(gray.cols(),gray.rows())));
            List<List<VisionPoint>> result=new ArrayList<>();for(int i=0;i<Math.min(3,boxes.size());i++){Rect r=boxes.get(i);result.add(Arrays.asList(new VisionPoint(r.x,r.y),new VisionPoint(r.x+r.width,r.y),new VisionPoint(r.x+r.width,r.y+r.height),new VisionPoint(r.x,r.y+r.height)));}return result;
        }finally{for(Mat c:contours)c.release();mask.release();hierarchy.release();kernel.release();}
    }
    /** Sparse probes only schedule work; full distributed RANSAC still proves
     * identity. Each frame gets BOTH orientation families and a rotating fallback,
     * so a large library cannot starve the negative family or stall the camera. */
    private List<ReferenceDatabase.PreparedReference> ranked(KeyPoint[] live,Mat descriptors,int step){
        Map<String,List<ReferenceDatabase.PreparedReference>> families=new LinkedHashMap<>();
        Map<ReferenceDatabase.PreparedReference,Integer> scores=new IdentityHashMap<>();
        long start=android.os.SystemClock.elapsedRealtimeNanos();
        Map<String,List<ReferenceDatabase.PreparedReference>> full=new LinkedHashMap<>();
        for(ReferenceDatabase.PreparedReference ref:database.preparedReferences()){
            String role=Phase8AssetCatalog.referenceRole(ref.spec.getReferenceId());if(role!=null&&(step==1)!=Phase8AssetCatalog.installed(role))full.computeIfAbsent(role,k->new ArrayList<>()).add(ref);
        }
        List<ReferenceDatabase.PreparedReference> window=new ArrayList<>();
        for(Map.Entry<String,List<ReferenceDatabase.PreparedReference>> family:full.entrySet()){
            LinkedHashSet<ReferenceDatabase.PreparedReference> selected=new LinkedHashSet<>();ReferenceDatabase.PreparedReference known=preferred.get(family.getKey());if(known!=null)selected.add(known);
            List<ReferenceDatabase.PreparedReference> reviewed=new ArrayList<>();for(ReferenceDatabase.PreparedReference r:family.getValue())if(dev.chyken.assembl_ar.camera.Phase789VideoReferences.annotation(r.spec.getJpegAssetPath())!=null)reviewed.add(r);
            for(int i=0;i<Math.min(3,reviewed.size());i++)selected.add(reviewed.get(Math.floorMod(searchCursor+i,reviewed.size())));
            List<ReferenceDatabase.PreparedReference> all=family.getValue();for(int i=0;i<Math.min(DiagnosticHammingMatcher.snapshot().fastLive?2:4,all.size());i++)selected.add(all.get(Math.floorMod(searchCursor*4+i,all.size())));window.addAll(selected);
        }
        try{for(ReferenceDatabase.PreparedReference ref:window){
            String role=Phase8AssetCatalog.referenceRole(ref.spec.getReferenceId());if(role==null||(step==1)==Phase8AssetCatalog.installed(role))continue;
            int score=0;for(ReferenceDatabase.PreparedOrbVariant variant:ref.recoveryVariants()){
                if(!variant.hasOrbData())continue;Split split=splits.computeIfAbsent(variant,v->new Split(ref,v));
                // Installed refinement starts from independent fixed-PCB pose.
                // Shiny caps have very few stable descriptors; weighting those
                // first can repeatedly schedule the wrong lighting viewpoint
                // while the correct wired-board context waits past the budget.
                int local=probe(split.local.sparse,descriptors);
                score=step==1?local*3:probe(split.context.sparse,descriptors)*3+local;break;
            }
            scores.put(ref,score);families.computeIfAbsent(role,k->new ArrayList<>()).add(ref);
        }}finally{DiagnosticHammingMatcher.snapshot().matchingNs+=android.os.SystemClock.elapsedRealtimeNanos()-start;}
        List<List<ReferenceDatabase.PreparedReference>> lists=new ArrayList<>();
        for(List<ReferenceDatabase.PreparedReference> family:families.values()){
            ReferenceDatabase.PreparedReference rotating=family.get(Math.floorMod(searchCursor,family.size()));
            family.sort((a,b)->Integer.compare(scores.get(b),scores.get(a)));
            List<ReferenceDatabase.PreparedReference> selected=new ArrayList<>(family.subList(0,Math.min(3,family.size())));
            if(!selected.contains(rotating))selected.add(rotating);lists.add(selected);
        }
        searchCursor++;List<ReferenceDatabase.PreparedReference> result=new ArrayList<>();
        for(int slot=0;slot<4;slot++)for(List<ReferenceDatabase.PreparedReference> family:lists)if(slot<family.size())result.add(family.get(slot));
        return result;
    }
    private static int probe(Mat source,Mat live){
        if(source.empty()||live.rows()<2)return 0;NearestDescriptorPairs pairs=NearestDescriptorPairs.compute(source,live);int n=0;
        for(int i=0;i<pairs.size();i++)if(pairs.hasTwo(i)&&OrbMatchPolicy.accepts(pairs.bestDistance(i),pairs.secondDistance(i)))n++;return n;
    }
    private Match match(ReferenceDatabase.PreparedReference ref,ReferenceDatabase.PreparedOrbVariant variant,KeyPoint[] live,Mat descriptors,double scale,boolean installed){DiagnosticHammingMatcher.context(ref);
        Split split=splits.get(variant);if(split==null){split=new Split(ref,variant);splits.put(variant,split);}
        List<VisionPoint> c=ref.spec.getPlanarCornersNormalized();double l=c.get(0).getX()*ref.imageWidth,t=c.get(0).getY()*ref.imageHeight,r=c.get(2).getX()*ref.imageWidth,b=c.get(2).getY()*ref.imageHeight;
        List<Point> module=new ArrayList<>(),moduleLive=new ArrayList<>(),board=new ArrayList<>(),boardLive=new ArrayList<>();
        collect(split.local,live,descriptors,module,moduleLive);
        if(installed)collect(split.context,live,descriptors,board,boardLive);
        Mat mh=homography(module,moduleLive,10,Math.max(15,(r-l)*.45),Math.max(12,(b-t)*(sensorCaps(ref)?.08:Phase8AssetCatalog.installed(Phase8AssetCatalog.referenceRole(ref.spec.getReferenceId()))?.20:.45))),bh=installed?homography(board,boardLive,14,15,15):null;
        try{
            if(mh==null||installed&&bh==null||!bothCaps(ref,module,moduleLive,mh))return null;
            List<VisionPoint> actual=project(c,ref.imageWidth,ref.imageHeight,mh,scale);
            if(actual.size()!=4)return null;
            return new Match(inliers(module,moduleLive,mh),actual,installed?project(c,ref.imageWidth,ref.imageHeight,bh,scale):actual);
        }finally{if(mh!=null)mh.release();if(bh!=null)bh.release();}
    }
    private Match refine(ReferenceDatabase.PreparedReference ref,ReferenceDatabase.PreparedOrbVariant variant,KeyPoint[] live,Mat descriptors,Mat raw,double scale){
        DiagnosticHammingMatcher.context(ref);
        Split split=splits.computeIfAbsent(variant,v->new Split(ref,v));
        List<Point> a=new ArrayList<>(),b=new ArrayList<>();collect(split.context,live,descriptors,a,b);
        Mat board=homography(a,b,14,15,15);if(board==null)return null;
        try{
            List<VisionPoint> c=ref.spec.getPlanarCornersNormalized(),search=project(c,ref.imageWidth,ref.imageHeight,board,1);
            double width=(c.get(2).getX()-c.get(0).getX())*ref.imageWidth,height=(c.get(2).getY()-c.get(0).getY())*ref.imageHeight;
            List<List<VisionPoint>> barrels=null;
            dev.chyken.assembl_ar.camera.Phase789VideoReferences.Entry authored=dev.chyken.assembl_ar.camera.Phase789VideoReferences.annotation(ref.spec.getJpegAssetPath());
            if(authored!=null){barrels=new ArrayList<>();for(List<VisionPoint> cap:authored.identity){List<VisionPoint> projected=project(cap,ref.imageWidth,ref.imageHeight,board,1);if(projected.size()!=4)continue;double cx=0,cy=0;for(VisionPoint p:projected){cx+=p.getX()/4;cy+=p.getY()/4;}List<VisionPoint> padded=new ArrayList<>();for(VisionPoint p:projected)padded.add(new VisionPoint(cx+(p.getX()-cx)*1.4,cy+(p.getY()-cy)*1.4));barrels.add(padded);}}
            for(int extent:new int[]{0,360})try(ComponentDetailExtractor.Sample detail=details.extract(raw,search,extent,barrels)){
                if(detail==null)continue;
                boolean reviewed=dev.chyken.assembl_ar.camera.Phase789VideoReferences.annotation(ref.spec.getJpegAssetPath())!=null;
                for(int guidance=0;guidance<(reviewed?2:1);guidance++){
                a.clear();b.clear();
                if(reviewed){
                    // The eyes sit above the board plane: the guidance radius
                    // scales with their measured footprint to allow parallax.
                    // This only widens candidate search; it is never an inlier.
                    double diagonal=Math.hypot(search.get(2).getX()-search.get(0).getX(),search.get(2).getY()-search.get(0).getY());
                    double radius=guidance==0?12:Math.max(12,Math.min(32,diagonal*.16));if(guidance==1&&radius<=12)continue;
                    Mat domain=GeometricMatchDomain.mask(split.local.keys,detail.keys,board,radius);
                    try{if(domain.empty())continue;collect(split.local,detail.keys,detail.descriptors,domain,a,b);}finally{domain.release();}
                }
                else collect(split.local,detail.keys,detail.descriptors,a,b);
                double minWidth=Math.max(15,width*.45),minHeight=Math.max(12,height*(sensorCaps(ref)?.08:.20));
                Mat module=homography(a,b,10,minWidth,minHeight);
                // Cylinders protrude above the PCB and their reflective side edges
                // are nearly collinear. A projective fit can overfit one eye. Try
                // a constrained affine fit with the SAME count/coverage gates;
                // both eyes and independent board placement remain compulsory.
                if(reviewed&&(module==null||!bothCaps(ref,a,b,module))){
                    if(module!=null)module.release();module=affine(a,b,10,minWidth,minHeight);
                }
                if(module==null)continue;
                try{if(!bothCaps(ref,a,b,module))continue;List<VisionPoint> actual=project(c,ref.imageWidth,ref.imageHeight,module,scale);
                    if(actual.size()==4)return new Match(inliers(a,b,module),actual,project(c,ref.imageWidth,ref.imageHeight,board,scale));
                }finally{module.release();}
                }
            }
            return null;
        }finally{board.release();}
    }
    private void collect(Features source,KeyPoint[] live,Mat descriptors,List<Point> reference,List<Point> target){
        collect(source,live,descriptors,null,reference,target);
    }
    private void collect(Features source,KeyPoint[] live,Mat descriptors,Mat domain,List<Point> reference,List<Point> target){
        if(source.descriptors.empty())return;List<DMatch> good=new ArrayList<>();
        good.addAll(matcher.ratioMatches(source.descriptors,descriptors,domain));
        good.sort(Comparator.comparingDouble(p->p.distance));Set<Integer> used=new HashSet<>();for(DMatch m:good)if(used.add(m.trainIdx)){reference.add(source.keys[m.queryIdx].pt);target.add(live[m.trainIdx].pt);}
    }
    private static final class Features{
        final Mat descriptors,sparse;final KeyPoint[] keys;
        Features(ReferenceDatabase.PreparedOrbVariant v,List<Integer> indexes,int limit){
            if(indexes.size()>limit){List<Integer> selected=new ArrayList<>();for(int i=0;i<limit;i++)selected.add(indexes.get(i*indexes.size()/limit));indexes=selected;}
            keys=new KeyPoint[indexes.size()];descriptors=indexes.isEmpty()?new Mat():new Mat(indexes.size(),v.descriptors.cols(),v.descriptors.type());int count=Math.min(24,indexes.size());sparse=count==0?new Mat():new Mat(count,v.descriptors.cols(),v.descriptors.type());if(indexes.isEmpty())return;
            try{
            int stride=v.descriptors.cols()*(int)v.descriptors.elemSize();byte[] source=new byte[v.descriptors.rows()*stride],selected=new byte[indexes.size()*stride];v.descriptors.get(0,0,source);
            for(int i=0;i<indexes.size();i++){int index=indexes.get(i);keys[i]=v.keypoints[index];System.arraycopy(source,index*stride,selected,i*stride,stride);}descriptors.put(0,0,selected);
            byte[] sample=new byte[count*stride];for(int i=0;i<count;i++)System.arraycopy(selected,(i*indexes.size()/count)*stride,sample,i*stride,stride);sparse.put(0,0,sample);
            }catch(RuntimeException|Error failure){descriptors.release();sparse.release();throw failure;}
        }
    }
    private static boolean sensorCaps(ReferenceDatabase.PreparedReference ref){
        return "assembly".equals(Phase8AssetCatalog.referenceRole(ref.spec.getReferenceId()))
                ||Phase8AssetCatalog.inwardReference(ref.spec.getJpegAssetPath());
    }
    private static final class Split{
        final Features local,context;
        Split(ReferenceDatabase.PreparedReference ref,ReferenceDatabase.PreparedOrbVariant v){
            dev.chyken.assembl_ar.camera.Phase789VideoReferences.Entry reviewed=dev.chyken.assembl_ar.camera.Phase789VideoReferences.annotation(ref.spec.getJpegAssetPath());
            List<VisionPoint> c=ref.spec.getPlanarCornersNormalized();
            double l=c.get(0).getX()*ref.imageWidth,t=c.get(0).getY()*ref.imageHeight,r=c.get(2).getX()*ref.imageWidth,b=c.get(2).getY()*ref.imageHeight;
            boolean cylinders=sensorCaps(ref),inward=Phase8AssetCatalog.inwardReference(ref.spec.getJpegAssetPath());
            List<Integer> a=new ArrayList<>(),other=new ArrayList<>();
            for(int i=0;i<v.keypoints.length;i++){
                Point p=v.keypoints[i].pt;double u=(p.x-l)/(r-l),vertical=(p.y-t)/(b-t);
                if(reviewed!=null){
                    if(inRegions(p,reviewed.identity,ref))a.add(i);
                    else if(inRegions(p,reviewed.context,ref))other.add(i);
                    continue;
                }
                if(p.x>=l&&p.x<=r&&p.y>=t&&p.y<=b){
                    if(!cylinders||vertical>.08&&vertical<(inward?.90:.72)&&(u>.08&&u<.45||u>.55&&u<.92))a.add(i);
                }else if(p.x<l-(r-l)*.15||p.x>r+(r-l)*.15||p.y<t-(b-t)*.15||p.y>b+(b-t)*.15)other.add(i);
            }
            local=new Features(v,a,reviewed==null?Integer.MAX_VALUE:700);try{context=new Features(v,other,reviewed==null?Integer.MAX_VALUE:1000);}catch(RuntimeException|Error failure){local.descriptors.release();local.sparse.release();throw failure;}
        }
    }
    /** Each real cylinder needs fresh inliers. A shiny edge or the unchanged
     * driver/Nano alone cannot prove the presence and direction of both eyes. */
    private static boolean bothCaps(ReferenceDatabase.PreparedReference ref,List<Point> source,List<Point> live,Mat h){
        dev.chyken.assembl_ar.camera.Phase789VideoReferences.Entry e=dev.chyken.assembl_ar.camera.Phase789VideoReferences.annotation(ref.spec.getJpegAssetPath());
        if(e==null||e.phase!=8)return true;
        MatOfPoint2f a=new MatOfPoint2f(),b=new MatOfPoint2f();
        try{a.fromList(source);Core.perspectiveTransform(a,b,h);Point[] projected=b.toArray();
            for(List<VisionPoint> cap:e.identity){int count=0;
                for(int i=0;i<source.size();i++)if(inRegions(source.get(i),Collections.singletonList(cap),ref)&&Math.hypot(projected[i].x-live.get(i).x,projected[i].y-live.get(i).y)<=3.5)count++;
                if(count<3)return false;
            }return true;
        }finally{a.release();b.release();}
    }
    private static boolean inRegions(Point p,List<List<VisionPoint>> regions,ReferenceDatabase.PreparedReference ref){
        for(List<VisionPoint> r:regions)if(p.x>=r.get(0).getX()*ref.imageWidth&&p.x<=r.get(2).getX()*ref.imageWidth&&p.y>=r.get(0).getY()*ref.imageHeight&&p.y<=r.get(2).getY()*ref.imageHeight)return true;
        return false;
    }
    private static final class Candidate{final ReferenceDatabase.PreparedReference ref;final ReferenceDatabase.PreparedOrbVariant variant;final String role;final Match local;final boolean refined;Candidate(ReferenceDatabase.PreparedReference ref,ReferenceDatabase.PreparedOrbVariant variant,String role,Match local){this(ref,variant,role,local,false);}Candidate(ReferenceDatabase.PreparedReference ref,ReferenceDatabase.PreparedOrbVariant variant,String role,Match local,boolean refined){this.ref=ref;this.variant=variant;this.role=role;this.local=local;this.refined=refined;}}
    private static int inliers(List<Point> reference,List<Point> live,Mat h){
        MatOfPoint2f src=new MatOfPoint2f(),dst=new MatOfPoint2f();
        try{src.fromList(reference);Core.perspectiveTransform(src,dst,h);Point[] projected=dst.toArray();int count=0;
            for(int i=0;i<projected.length;i++)if(Math.hypot(projected[i].x-live.get(i).x,projected[i].y-live.get(i).y)<=3.5)count++;return count;
        }finally{src.release();dst.release();}
    }
    private Mat homography(List<Point> a,List<Point> b,int minimum,double minimumWidth,double minimumHeight){
        if(a.size()<minimum)return null;
        double l=Double.MAX_VALUE,t=l,r=-l,bt=-l;for(Point p:a){l=Math.min(l,p.x);r=Math.max(r,p.x);t=Math.min(t,p.y);bt=Math.max(bt,p.y);}if(r-l<minimumWidth||bt-t<minimumHeight)return null;
        MatOfPoint2f src=new MatOfPoint2f(),dst=new MatOfPoint2f();Mat mask=new Mat(),h=null;
        try{src.fromList(a);dst.fromList(b);h=DiagnosticHammingMatcher.homography(src,dst,Calib3d.RANSAC,3.5,mask,1800,.995);
            int n=h.empty()?0:Core.countNonZero(mask);if(n<minimum||n<a.size()*.5){return null;}
            double minX=Double.MAX_VALUE,minY=minX,maxX=-minX,maxY=-minX;
            for(int i=0;i<a.size();i++)if(mask.get(i,0)[0]!=0){Point p=a.get(i);minX=Math.min(minX,p.x);maxX=Math.max(maxX,p.x);minY=Math.min(minY,p.y);maxY=Math.max(maxY,p.y);}
            // A terminal row or a small unchanged patch alone cannot certify the whole module.
            if(maxX-minX<minimumWidth||maxY-minY<minimumHeight){return null;}Mat owned=h;h=null;return owned;
        }finally{src.release();dst.release();mask.release();if(h!=null)h.release();}
    }
    private Mat affine(List<Point> a,List<Point> b,int minimum,double width,double height){
        if(a.size()<minimum)return null;MatOfPoint2f src=new MatOfPoint2f(),dst=new MatOfPoint2f();Mat mask=new Mat(),fit=null,h=null;
        long started=android.os.SystemClock.elapsedRealtimeNanos();
        try{src.fromList(a);dst.fromList(b);fit=Calib3d.estimateAffine2D(src,dst,mask,Calib3d.RANSAC,3.5,1200,.995,10);
            if(fit.empty()||Core.countNonZero(mask)<minimum||Core.countNonZero(mask)<a.size()*.5)return null;
            double l=Double.MAX_VALUE,t=l,r=-l,bt=-l;for(int i=0;i<a.size();i++)if(mask.get(i,0)[0]!=0){Point p=a.get(i);l=Math.min(l,p.x);r=Math.max(r,p.x);t=Math.min(t,p.y);bt=Math.max(bt,p.y);}
            if(r-l<width||bt-t<height)return null;h=Mat.eye(3,3,CvType.CV_64F);Mat rows=h.rowRange(0,2);try{fit.copyTo(rows);DiagnosticHammingMatcher.recordFit(src,dst,h,mask);}finally{rows.release();}Mat result=h;h=null;return result;
        }finally{src.release();dst.release();mask.release();if(fit!=null)fit.release();if(h!=null)h.release();DiagnosticHammingMatcher.snapshot().geometryNs+=android.os.SystemClock.elapsedRealtimeNanos()-started;}
    }
    private List<VisionPoint> project(List<VisionPoint> corners,int width,int height,Mat h,double scale){
        MatOfPoint2f src=new MatOfPoint2f(),dst=new MatOfPoint2f();
        try{List<Point> p=new ArrayList<>();for(VisionPoint c:corners)p.add(new Point(c.getX()*width,c.getY()*height));src.fromList(p);Core.perspectiveTransform(src,dst,h);
            Point[] q=dst.toArray();double area=Imgproc.contourArea(dst,true);if(!Double.isFinite(area)||area<100)return Collections.emptyList();
            List<VisionPoint> result=new ArrayList<>();for(Point c:q){if(!Double.isFinite(c.x+c.y))return Collections.emptyList();result.add(new VisionPoint(c.x/scale,c.y/scale));}return result;
        }finally{src.release();dst.release();}
    }
    private static final class Match{final double score;final List<VisionPoint> actual,expected;Match(double score,List<VisionPoint> actual,List<VisionPoint> expected){this.score=score;this.actual=actual;this.expected=expected;}}
    public void close(){for(Split split:splits.values()){split.local.descriptors.release();split.context.descriptors.release();split.local.sparse.release();split.context.sparse.release();}splits.clear();details.close();orb.clear();clahe.clear();}
}
