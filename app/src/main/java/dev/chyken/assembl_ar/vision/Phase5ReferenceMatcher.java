package dev.chyken.assembl_ar.vision;

import dev.chyken.assembl_ar.camera.Phase5AssetCatalog;
import dev.chyken.assembl_ar.vision.PhotoFeaturePartition.Rows;
import org.opencv.core.*;
import org.opencv.features2d.*;
import org.opencv.imgproc.*;
import org.opencv.calib3d.Calib3d;
import java.util.*;

/** Bounded body-local identity and independent PCB geometry. Tracking predictions
 * and unchanged board pixels never supply Nano installation confirmation. */
final class Phase5ReferenceMatcher implements AutoCloseable {
    private final ReferenceDatabase database;
    private final ORB orb=ORB.create(3200,1.2f,8,31,0,2,ORB.HARRIS_SCORE,31,10);
    private final CLAHE clahe;
    private final DiagnosticHammingMatcher matcher=new DiagnosticHammingMatcher();
    private final Map<ReferenceDatabase.PreparedOrbVariant,PhotoFeaturePartition> splits=new IdentityHashMap<>();
    private final ComponentDetailExtractor details;
    private final LocalPhotometricRefiner local;
    private final Mat guidanceMask=new Mat();
    private byte[] guidanceBytes=new byte[0];
    private final Map<String,ReferenceDatabase.PreparedReference> preferred=new HashMap<>();
    private int cursor=-1,bodyRecoveries,boardProposals,contextRecoveries,extracted;
    private long searchFrame=Long.MIN_VALUE;
    private boolean frameBoardSeen,frameBodySeen;
    private ComponentDetailExtractor.Sample neuralProposal;
    void releaseCameraBuffers(){details.releaseWorkspace();guidanceMask.release();guidanceBytes=new byte[0];}
    Phase5ReferenceMatcher(ReferenceDatabase database,VisionConfig config){this.database=database;clahe=OpenCvVisionUtils.createClahe(config);details=new ComponentDetailExtractor(config);local=new LocalPhotometricRefiner(config);}
    RecognitionResult inspect(Mat gray,long time,int step){
        long start=android.os.SystemClock.elapsedRealtimeNanos();Mat raw=new Mat(),work=new Mat(),rows=new Mat(),empty=new Mat();MatOfKeyPoint keys=new MatOfKeyPoint();
        double scale=Math.min(1,1280.0/Math.max(gray.cols(),gray.rows()));Evidence evidence=new Evidence();database.acquireForRecognition();
        try{
            bodyRecoveries=0;boardProposals=0;contextRecoveries=0;extracted=0;frameBoardSeen=frameBodySeen=false;
            if(DiagnosticHammingMatcher.snapshot().workspace==null||searchFrame!=time){searchFrame=time;cursor=cursor==Integer.MAX_VALUE?0:cursor+1;}
            if(scale<1)Imgproc.resize(gray,raw,new Size(),scale,scale,Imgproc.INTER_AREA);else gray.copyTo(raw);
            DiagnosticHammingMatcher.normalize(clahe,raw,work);DiagnosticHammingMatcher.extract(orb,work,empty,keys,rows);KeyPoint[] live=keys.toArray();extracted+=live.length;DiagnosticHammingMatcher.captureLive(live,rows,scale);
            Rect proposal=DiagnosticHammingMatcher.snapshot().proposedRoi;
            if(proposal!=null){
                List<VisionPoint> region=Arrays.asList(new VisionPoint(proposal.x*scale,proposal.y*scale),new VisionPoint((proposal.x+proposal.width)*scale,proposal.y*scale),new VisionPoint((proposal.x+proposal.width)*scale,(proposal.y+proposal.height)*scale),new VisionPoint(proposal.x*scale,(proposal.y+proposal.height)*scale));
                neuralProposal=details.extract(raw,region,0);if(neuralProposal!=null)extracted+=neuralProposal.keys.length;
            }
            if(!rows.empty())for(ReferenceDatabase.PreparedReference ref:window(rows,step)){
                String role=Phase5AssetCatalog.referenceRole(ref.spec.getReferenceId());
                if(role.equals("assembly")&&evidence.score(Phase5Observation.State.PLACED)>=20)continue;
                for(ReferenceDatabase.PreparedOrbVariant variant:ref.recoveryVariants())if(variant.hasOrbData()){
                    Match found=match(ref,variant,live,rows,raw,scale,step!=1);if(found!=null)evidence.add(role,found);
                }
            }
            Phase5Observation.State state=Phase5Observation.classify(step,evidence.score(Phase5Observation.State.FRONT),evidence.score(Phase5Observation.State.FLIPPED),evidence.score(Phase5Observation.State.PLACED),evidence.score(Phase5Observation.State.BACKWARD),evidence.score(Phase5Observation.State.MISALIGNED));
            // Disagreeing positive photo geometry still vetoes completion, but it
            // does not prove bent/misaligned pins under perspective or glare.
            // Reserve that correction for an actual invalid-placement witness.
            if(state==Phase5Observation.State.MISALIGNED&&!evidence.explicitInvalid)state=Phase5Observation.State.SEARCHING;
            Match selected=evidence.matches.get(state);if(selected==null)for(Match m:evidence.matches.values())if(selected==null||m.score>selected.score)selected=m;
            Phase5Observation observation=new Phase5Observation(state,selected==null?0:selected.score);List<VisionPoint> corners=selected==null?Collections.emptyList():selected.actual;
            if(observation.accepted(step)||state==Phase5Observation.State.FLIPPED||state==Phase5Observation.State.BACKWARD||state==Phase5Observation.State.MISALIGNED)for(ReferenceDatabase.PreparedReference ref:database.preparedReferences())if(ref.spec.getReferenceId().equals(selected.id)){preferred.put(Phase5AssetCatalog.referenceRole(selected.id),ref);break;}
            DiagnosticHammingMatcher.State timing=DiagnosticHammingMatcher.snapshot();
            InstalledScanDiagnostics.log(5,step,gray,state.name(),corners,frameBoardSeen,frameBodySeen,start);
            return RecognitionResult.builder(time).identity(selected==null?"phase5:photo":selected.id,"arduino_nano_microcontroller","Arduino Nano")
                .method(corners.size()==4?RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY:RecognitionResult.DetectionMethod.NONE)
                .validation(observation.accepted(step)?RecognitionResult.ValidationState.EXPECTED_COMPONENT:state==Phase5Observation.State.FLIPPED||state==Phase5Observation.State.BACKWARD||state==Phase5Observation.State.MISALIGNED?RecognitionResult.ValidationState.WRONG_ORIENTATION:RecognitionResult.ValidationState.NO_COMPONENT)
                .corners(corners).matches(extracted,0,0,0).confidence(observation.accepted(step)?.85:0)
                .latency(new RecognitionResult.Latency(timing.featureNs/1e6,timing.matchingNs/1e6,timing.geometryNs/1e6,0,(android.os.SystemClock.elapsedRealtimeNanos()-start)/1e6)).build().withPhase5Observation(observation);
        }finally{if(neuralProposal!=null){neuralProposal.close();neuralProposal=null;}database.releaseAfterRecognition();raw.release();work.release();rows.release();empty.release();keys.release();}
    }
    private PhotoFeaturePartition split(ReferenceDatabase.PreparedReference ref,ReferenceDatabase.PreparedOrbVariant v){PhotoFeaturePartition s=splits.get(v);if(s==null){s=new PhotoFeaturePartition(ref,v);splits.put(v,s);}return s;}
    private List<ReferenceDatabase.PreparedReference> window(Mat live,int step){
        if(!DiagnosticHammingMatcher.snapshot().fastLive)return detailedWindow(live,step);
        Map<String,List<ReferenceDatabase.PreparedReference>> families=new LinkedHashMap<>();
        for(ReferenceDatabase.PreparedReference ref:database.preparedReferences()){
            String role=Phase5AssetCatalog.referenceRole(ref.spec.getReferenceId());if(role==null||((step==1)==Phase5AssetCatalog.installed(role)))continue;
            families.computeIfAbsent(role,k->new ArrayList<>()).add(ref);
        }
        List<List<ReferenceDatabase.PreparedReference>> ranked=new ArrayList<>();Map<ReferenceDatabase.PreparedReference,Integer> scores=new IdentityHashMap<>();
        for(Map.Entry<String,List<ReferenceDatabase.PreparedReference>> family:families.entrySet()){
            List<ReferenceDatabase.PreparedReference> pool=InspectionReferenceSchedule.window(family.getValue(),preferred.get(family.getKey()),cursor);
            for(ReferenceDatabase.PreparedReference ref:pool){
            int score=0;for(ReferenceDatabase.PreparedOrbVariant v:ref.recoveryVariants())if(v.hasOrbData()){
                PhotoFeaturePartition s=split(ref,v);long began=android.os.SystemClock.elapsedRealtimeNanos();NearestDescriptorPairs pairs;
                try{pairs=NearestDescriptorPairs.compute(s.probe,live);}finally{DiagnosticHammingMatcher.snapshot().matchingNs+=android.os.SystemClock.elapsedRealtimeNanos()-began;}
                int n=0;for(int i=0;i<pairs.size();i++)if(pairs.hasTwo(i)&&OrbMatchPolicy.accepts(pairs.bestDistance(i),pairs.secondDistance(i)))n++;score=Math.max(score,n);
                if(step!=1&&!s.contextProbe.empty()){
                    began=android.os.SystemClock.elapsedRealtimeNanos();try{pairs=NearestDescriptorPairs.compute(s.contextProbe,live);}finally{DiagnosticHammingMatcher.snapshot().matchingNs+=android.os.SystemClock.elapsedRealtimeNanos()-began;}
                    n=0;for(int i=0;i<pairs.size();i++)if(pairs.hasTwo(i)&&OrbMatchPolicy.accepts(pairs.bestDistance(i),pairs.secondDistance(i)))n++;
                    score=Math.max(score,n);
                }
            }
            scores.put(ref,score);
            }
            pool.sort(Comparator.comparingInt(r->-scores.get(r)));
            LinkedHashSet<ReferenceDatabase.PreparedReference> chosen=new LinkedHashSet<>(pool.subList(0,Math.min(DiagnosticHammingMatcher.snapshot().fastLive?1:2,pool.size())));
            ReferenceDatabase.PreparedReference known=preferred.get(family.getKey());if(known!=null)chosen.add(known);
            // Probe ambiguity never hides an entire positive or rejection family.
            if(!pool.isEmpty())chosen.add(pool.get(Math.floorMod(cursor,pool.size())));
            ranked.add(new ArrayList<>(chosen));
        }
        List<ReferenceDatabase.PreparedReference> result=new ArrayList<>();
        for(int slot=0;slot<4;slot++)for(List<ReferenceDatabase.PreparedReference> group:ranked)if(slot<group.size())result.add(group.get(slot));return result;
    }
    /** Preserve the established high-detail search order and work count. */
    private List<ReferenceDatabase.PreparedReference> detailedWindow(Mat live,int step){
        List<ReferenceDatabase.PreparedReference> positive=new ArrayList<>(),negative=new ArrayList<>();Map<ReferenceDatabase.PreparedReference,Integer> scores=new IdentityHashMap<>();
        for(ReferenceDatabase.PreparedReference ref:database.preparedReferences()){
            String role=Phase5AssetCatalog.referenceRole(ref.spec.getReferenceId());if(role==null||((step==1)==Phase5AssetCatalog.installed(role)))continue;
            int score=0;for(ReferenceDatabase.PreparedOrbVariant v:ref.recoveryVariants())if(v.hasOrbData()){
                PhotoFeaturePartition s=split(ref,v);long began=android.os.SystemClock.elapsedRealtimeNanos();NearestDescriptorPairs pairs;
                try{pairs=NearestDescriptorPairs.compute(s.probe,live);}finally{DiagnosticHammingMatcher.snapshot().matchingNs+=android.os.SystemClock.elapsedRealtimeNanos()-began;}
                int n=0;for(int i=0;i<pairs.size();i++)if(pairs.hasTwo(i)&&OrbMatchPolicy.accepts(pairs.bestDistance(i),pairs.secondDistance(i)))n++;score=Math.max(score,n);
            }
            scores.put(ref,score);((role.equals("back")||role.equals("invalid"))?negative:positive).add(ref);
        }
        Comparator<ReferenceDatabase.PreparedReference> order=Comparator.comparingInt(r->-scores.get(r));positive.sort(order);negative.sort(order);LinkedHashSet<ReferenceDatabase.PreparedReference> chosen=new LinkedHashSet<>();
        for(List<ReferenceDatabase.PreparedReference> group:Arrays.asList(positive,negative)){chosen.addAll(group.subList(0,Math.min(2,group.size())));for(int i=0;i<group.size();i++)if(chosen.add(group.get(Math.floorMod(cursor+i,group.size()))))break;}
        ReferenceDatabase.PreparedReference known=preferred.get(step==1?"front":"assembly");if(known!=null&&positive.contains(known))chosen.add(known);
        List<ReferenceDatabase.PreparedReference> result=new ArrayList<>(chosen);result.sort(order);return result;
    }
    private Match match(ReferenceDatabase.PreparedReference ref,ReferenceDatabase.PreparedOrbVariant v,KeyPoint[] live,Mat rows,Mat raw,double scale,boolean installed){
        DiagnosticHammingMatcher.context(ref);PhotoFeaturePartition s=split(ref,v);double[] b=LocalPhotometricRefiner.bounds(ref.spec,ref.imageWidth,ref.imageHeight);double w=b[2]-b[0],h=b[3]-b[1];
        List<Point> a=new ArrayList<>(),z=new ArrayList<>();collect(s.body,live,rows,a,z);Mat mh=homography(a,z,installed?10:12,Math.max(15,w*.45),Math.max(15,h*.45)),bh=null;int contextInliers=Integer.MAX_VALUE;
        try{
            if(mh==null&&neuralProposal!=null){a.clear();z.clear();collect(s.body,neuralProposal.keys,neuralProposal.descriptors,a,z);mh=homography(a,z,installed?10:12,Math.max(15,w*.45),Math.max(15,h*.45));}
            if(DiagnosticHammingMatcher.snapshot().fastLive&&installed&&mh==null&&a.size()<6&&boardProposals<1){
                // Register independent fixed PCB features BEFORE looking for a small Nano.
                // The board only proposes a crop; ten distributed fresh body inliers and
                // the unchanged relative-placement/orientation gates remain compulsory.
                List<Point> ba=new ArrayList<>(),bz=new ArrayList<>();collect(s.context,live,rows,ba,bz);bh=homography(ba,bz,16,15,15);
                if(bh!=null){
                    boardProposals++;contextInliers=inliers(ba,bz,bh);
                    try(ComponentDetailExtractor.Sample sample=details.extract(raw,project(ref,bh,1),480)){
                        if(sample!=null){extracted+=sample.keys.length;a.clear();z.clear();collect(s.body,sample.keys,sample.descriptors,a,z);mh=homography(a,z,10,Math.max(15,w*.45),Math.max(15,h*.45));}
                    }
                }
            }
            if(mh==null&&a.size()>=6&&bodyRecoveries<2){bodyRecoveries++;Mat seed=proposal(a,z);
                // A few full-frame matches only propose a crop. Fresh local inliers
                // must still meet the original loose/installed identity thresholds.
                if(seed!=null)try{for(int extent:installed?new int[]{480}:new int[]{0,480}){
                    try(ComponentDetailExtractor.Sample sample=details.extract(raw,project(ref,seed,1),extent)){
                        if(sample!=null){extracted+=sample.keys.length;a.clear();z.clear();collect(s.body,sample.keys,sample.descriptors,a,z);mh=homography(a,z,installed?10:12,Math.max(15,w*.45),Math.max(15,h*.45));}
                    }if(mh!=null)break;
                }}finally{seed.release();}
            }
            if(bh!=null)frameBoardSeen=true;
            if(mh==null)return null;
            frameBodySeen=true;
            if(installed){
                // Seed geometry proposes two local photometric windows. Fresh,
                // independently matched PCB pixels still have to prove orientation.
                if(bh==null&&contextRecoveries<4){contextRecoveries++;Context chosen=bestContext(ref,s.context,raw,mh);
                    if(chosen!=null){if(chosen.pose==null)return partial(ref,mh,scale,a,z);bh=chosen.pose;contextInliers=chosen.inliers;}
                }
                if(bh==null){List<Point> ba=new ArrayList<>(),bz=new ArrayList<>();collect(s.context,live,rows,ba,bz);bh=homography(ba,bz,16,15,15);if(bh!=null)contextInliers=inliers(ba,bz,bh);}
                if(bh==null)return partial(ref,mh,scale,a,z);
                frameBoardSeen=true;
            }
            List<VisionPoint> actual=project(ref,mh,scale);if(actual.size()!=4)return null;List<VisionPoint> expected=installed?project(ref,bh,scale):actual;if(expected.size()!=4)return null;
            // A large body cluster cannot overpower weak/contradictory PCB evidence.
            // This is geometric support, separate from the raw diagnostic Lowe ratio.
            return new Match(ref.spec.getReferenceId(),Math.min(inliers(a,z,mh),contextInliers),actual,expected);
        }finally{if(mh!=null)mh.release();if(bh!=null)bh.release();}
    }
    private Match partial(ReferenceDatabase.PreparedReference ref,Mat mh,double scale,List<Point> a,List<Point> z){List<VisionPoint> body=project(ref,mh,scale);return body.size()!=4?null:new Match(ref.spec.getReferenceId(),inliers(a,z,mh),body,Collections.emptyList());}
    private Context bestContext(ReferenceDatabase.PreparedReference ref,Rows source,Mat raw,Mat mh){
        Context first=null,second=null,chosen=null;
        try{first=context(ref,source,raw,mh,false);second=context(ref,source,raw,mh,true);
            if(first==null)chosen=second;else if(second==null)chosen=first;
            else if(first.inliers>second.inliers*1.15)chosen=first;
            else if(second.inliers>first.inliers*1.15)chosen=second;
            else chosen=new Context(null,0);
            return chosen;
        }finally{if(first!=null&&first!=chosen)first.pose.release();if(second!=null&&second!=chosen)second.pose.release();}
    }
    private Context context(ReferenceDatabase.PreparedReference ref,Rows source,Mat raw,Mat mh,boolean reverse){
        Mat guess=new Mat(),turn=Mat.eye(3,3,CvType.CV_64F),empty=new Mat();double[] b=LocalPhotometricRefiner.bounds(ref.spec,ref.imageWidth,ref.imageHeight);
        try{if(reverse){turn.put(0,0,-1,0,b[0]+b[2],0,-1,b[1]+b[3],0,0,1);Core.gemm(mh,turn,1,empty,0,guess);}else mh.copyTo(guess);
            try(ComponentDetailExtractor.Sample sample=local.context(raw,ref.spec,ref.imageWidth,ref.imageHeight,guess)){
                extracted+=sample.keys.length;List<Point> a=new ArrayList<>(),z=new ArrayList<>();guided(source,sample.keys,sample.descriptors,Math.hypot(b[2]-b[0],b[3]-b[1])*.12,a,z);if(a.size()<16)return null;
                MatOfPoint2f before=new MatOfPoint2f(),after=new MatOfPoint2f();try{before.fromList(z);Core.perspectiveTransform(before,after,guess);z=Arrays.asList(after.toArray());Mat pose=homography(a,z,16,15,15);return pose==null?null:new Context(pose,inliers(a,z,pose));}finally{before.release();after.release();}
            }
        }finally{guess.release();turn.release();empty.release();}
    }
    private void collect(Rows source,KeyPoint[] live,Mat rows,List<Point> a,List<Point> z){if(source.rows.empty()||rows.rows()<2)return;append(source,live,new ArrayList<>(matcher.ratioMatches(source.rows,rows)),a,z);}
    private void guided(Rows source,KeyPoint[] live,Mat rows,double radius,List<Point> a,List<Point> z){
        if(source.rows.empty()||rows.rows()<2)return;long geometryStart=android.os.SystemClock.elapsedRealtimeNanos();int length=source.keys.length*live.length;
        guidanceMask.create(source.keys.length,live.length,CvType.CV_8UC1);if(guidanceBytes.length<length)guidanceBytes=new byte[length];else Arrays.fill(guidanceBytes,0,length,(byte)0);double limit=radius*radius;
        for(int i=0;i<source.keys.length;i++)for(int j=0;j<live.length;j++){double dx=source.keys[i].pt.x-live[j].pt.x,dy=source.keys[i].pt.y-live[j].pt.y;if(dx*dx+dy*dy<limit)guidanceBytes[i*live.length+j]=1;}
        DiagnosticHammingMatcher.snapshot().geometryNs+=android.os.SystemClock.elapsedRealtimeNanos()-geometryStart;
        long start=android.os.SystemClock.elapsedRealtimeNanos();List<DMatch> good=new ArrayList<>();
        try{guidanceMask.put(0,0,guidanceBytes,0,length);NearestDescriptorPairs pairs=NearestDescriptorPairs.compute(source.rows,rows,guidanceMask);for(int i=0;i<pairs.size();i++)if(pairs.hasTwo(i)&&OrbMatchPolicy.accepts(pairs.bestDistance(i),pairs.secondDistance(i)))good.add(pairs.best(i));}
        finally{DiagnosticHammingMatcher.snapshot().matchingNs+=android.os.SystemClock.elapsedRealtimeNanos()-start;}
        // Guided geometry does not replace Test A's unconstrained Lowe probe.
        append(source,live,good,a,z);
    }
    private static void append(Rows source,KeyPoint[] live,List<DMatch> good,List<Point> a,List<Point> z){good.sort(Comparator.comparingDouble(m->m.distance));Set<Integer> used=new HashSet<>();for(DMatch m:good)if(used.add(m.trainIdx)){a.add(source.keys[m.queryIdx].pt);z.add(live[m.trainIdx].pt);}}
    private static Mat proposal(List<Point> a,List<Point> z){MatOfPoint2f src=new MatOfPoint2f(),dst=new MatOfPoint2f();Mat mask=new Mat(),affine=null;
        try{src.fromList(a);dst.fromList(z);affine=Calib3d.estimateAffinePartial2D(src,dst,mask,Calib3d.RANSAC,3.5,600,.99,5);if(affine.empty()||Core.countNonZero(mask)<4)return null;double[] values=new double[6];affine.get(0,0,values);Mat h=Mat.eye(3,3,CvType.CV_64F);h.put(0,0,values);return h;}
        finally{src.release();dst.release();mask.release();if(affine!=null)affine.release();}
    }
    private List<VisionPoint> project(ReferenceDatabase.PreparedReference ref,Mat h,double scale){return project(ref.spec.getPlanarCornersNormalized(),ref.imageWidth,ref.imageHeight,h,scale);}
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
    private static final class Evidence {
        boolean explicitInvalid;
        final EnumMap<Phase5Observation.State,Match> matches=new EnumMap<>(Phase5Observation.State.class);
        double score(Phase5Observation.State s){Match m=matches.get(s);return m==null?0:m.score;}
        void add(String role,Match m){Phase5Observation.State state=role.equals("front")?Phase5Observation.State.FRONT:role.equals("back")?Phase5Observation.State.FLIPPED:Phase5Observation.placement(m.expected,m.actual);if(role.equals("invalid")&&state!=Phase5Observation.State.SEARCHING){if(state!=Phase5Observation.State.PLACED)return;state=Phase5AssetCatalog.reversedInstallationReference(m.id)?Phase5Observation.State.BACKWARD:Phase5Observation.State.MISALIGNED;explicitInvalid=true;}if(m.score>score(state))matches.put(state,m);}
    }
    private static final class Context{final Mat pose;final int inliers;Context(Mat pose,int inliers){this.pose=pose;this.inliers=inliers;}}
    private static final class Match{final String id;final int score;final List<VisionPoint> actual,expected;Match(String id,int score,List<VisionPoint> actual,List<VisionPoint> expected){this.id=id;this.score=score;this.actual=actual;this.expected=expected;}}
    public void close(){for(PhotoFeaturePartition s:splits.values())s.close();splits.clear();guidanceMask.release();guidanceBytes=new byte[0];details.close();local.close();orb.clear();clahe.clear();}
}
