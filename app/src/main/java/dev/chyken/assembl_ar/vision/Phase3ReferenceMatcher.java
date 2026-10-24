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
    private int refinementCursor;
    private int viewpointCursor;
    private int contextRefinements;
    private final ReferenceDatabase database;
    private final ORB orb;
    private final CLAHE clahe;
    private final ComponentDetailExtractor details;
    private final DiagnosticHammingMatcher matcher=new DiagnosticHammingMatcher();
    Phase3ReferenceMatcher(ReferenceDatabase database,VisionConfig config){this.database=database;details=new ComponentDetailExtractor(config);orb=ORB.create(3200,1.2f,8,31,0,2,ORB.HARRIS_SCORE,31,10);clahe=OpenCvVisionUtils.createClahe(config);}
    RecognitionResult inspect(Mat gray,long time,int step){
        long start=android.os.SystemClock.elapsedRealtimeNanos();Mat raw=new Mat(),work=new Mat(),descriptors=new Mat(),empty=new Mat();MatOfKeyPoint keypoints=new MatOfKeyPoint();
        double scale=Math.min(1,1280.0/Math.max(gray.cols(),gray.rows()));
        Evidence evidence=new Evidence();
        database.acquireForRecognition();
        try{
            refinements.clear();extractionNanos=0;extractedPoints=0;contextRefinements=0;
            long extractStart=android.os.SystemClock.elapsedRealtimeNanos();
            if(scale<1)Imgproc.resize(gray,raw,new Size(),scale,scale,Imgproc.INTER_AREA);else gray.copyTo(raw);
            DiagnosticHammingMatcher.normalize(clahe,raw,work);DiagnosticHammingMatcher.extract(orb,work,empty,keypoints,descriptors);KeyPoint[] live=keypoints.toArray();DiagnosticHammingMatcher.captureLive(live,descriptors,scale);
            extractionNanos+=android.os.SystemClock.elapsedRealtimeNanos()-extractStart;extractedPoints+=live.length;
            Set<String> seen=new HashSet<>();
            List<ReferenceDatabase.PreparedReference> ordered=new ArrayList<>(database.preparedReferences());
            // Try the closest acquired pixel scale first. Tablet-authored views must
            // not wait behind dozens of high-resolution phone/wire photographs.
            if(step!=1)ordered.sort(Comparator.comparingDouble(r->Math.abs(Math.log(
                    Math.max(r.imageWidth,r.imageHeight)/(double)Math.max(raw.cols(),raw.rows())))));
            if(step!=1)ordered=rankedWindow(ordered,descriptors);
            if(preferredReference!=null)for(int i=0;i<ordered.size();i++)if(preferredReference.equals(ordered.get(i).spec.getReferenceId())){ordered.add(0,ordered.remove(i));break;}
            int directRefinements=0;
            if(!descriptors.empty())for(ReferenceDatabase.PreparedReference ref:ordered){
                String role=Phase3AssetCatalog.referenceRole(ref.spec.getReferenceId());
                if(role==null||((step==1)==Phase3AssetCatalog.installed(role)))continue;
                // A strong positive can prune positive duplicates, but never skip
                // the actual backward-installation reference family.
                if(step!=1&&role.equals("assembly")&&evidence.strong())continue;
                if(role.equals("invalid")&&evidence.invalidStrong)continue;
                String path=ref.spec.getJpegAssetPath(),name=path.substring(path.lastIndexOf('/')+1);
                if(!seen.add(role+":"+name))continue;
                boolean refineFirst=step!=1&&directRefinements++<2;
                for(ReferenceDatabase.PreparedOrbVariant variant:ref.orbVariants){
                    if(!variant.hasOrbData())continue;
                    Match found=match(ref,variant,live,descriptors,raw,scale,Phase3AssetCatalog.installed(role),refineFirst,evidence.score(Phase3Observation.State.PLACED)<16);if(found==null)continue;
                    evidence.add(role,found);
                    if(role.equals("assembly")&&evidence.strong()||role.equals("invalid")&&evidence.invalidStrong)preferredReference=ref.spec.getReferenceId();
                }
            }
            // At 640×400 a small driver can lose its ORB feature budget to board printing.
            // Use the independently verified board only to locate a bounded search window.
            // Fresh driver inliers must STILL prove its identity and orientation; a board
            // homography, wire bundle, or tracking prediction can never pass this gate.
            if(step!=1){
                refinements.sort((a,b)->Double.compare(b.contextScore,a.contextScore));
                // Keep strong candidates first, while also visiting newly added viewpoints.
                // A constant top-four list used to starve other lighting/wire references.
                List<Candidate> ranked=new ArrayList<>(refinements);
                if(ranked.size()>2){List<Candidate> rest=new ArrayList<>(ranked.subList(2,ranked.size()));Collections.rotate(rest,-Math.floorMod(refinementCursor,rest.size()));ranked.subList(2,ranked.size()).clear();ranked.addAll(rest);refinementCursor+=2;}
                Set<String> refined=new HashSet<>();int attempts=0,negativeAttempts=0;
                for(Candidate c:ranked){
                    String role=Phase3AssetCatalog.referenceRole(c.ref.spec.getReferenceId());
                    boolean negative="invalid".equals(role);
                    if(negative?(evidence.invalidStrong||negativeAttempts>=1):(evidence.strong()||attempts>=4))continue;
                    if(!refined.add(c.ref.spec.getReferenceId()))continue;if(negative)negativeAttempts++;else attempts++;
                    Match found=match(c.ref,c.variant,live,descriptors,raw,scale,true,true,evidence.score(Phase3Observation.State.PLACED)<16);if(found==null)continue;
                    evidence.add(role,found);
                    if(!negative&&evidence.strong())preferredReference=c.ref.spec.getReferenceId();
                }
            }
            Phase3Observation.State state=evidence.state(step);Match selected=evidence.selected(state);
            double score=selected==null?0:selected.score;List<VisionPoint> corners=selected==null?Collections.emptyList():selected.actual;
            Phase3Observation result=new Phase3Observation(state,score);
            return RecognitionResult.builder(time).identity(selected==null?"phase3:photo":selected.referenceId,"dual_motor_driver","Motor Driver")
                    .method(corners.size()==4?RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY:RecognitionResult.DetectionMethod.NONE)
                    .validation(result.accepted(step)?RecognitionResult.ValidationState.EXPECTED_COMPONENT:state==Phase3Observation.State.FLIPPED||state==Phase3Observation.State.BACKWARD||state==Phase3Observation.State.MISALIGNED?RecognitionResult.ValidationState.WRONG_ORIENTATION:RecognitionResult.ValidationState.NO_COMPONENT)
                    .corners(corners).matches(extractedPoints,(int)score,(int)score,score>0?1:0).confidence(result.accepted(step)?.85:0)
                    .latency(new RecognitionResult.Latency(extractionNanos/1e6,Math.max(0,android.os.SystemClock.elapsedRealtimeNanos()-start-extractionNanos)/1e6,0,0,(android.os.SystemClock.elapsedRealtimeNanos()-start)/1e6)).build().withPhase3Observation(result);
        }finally{database.releaseAfterRecognition();raw.release();work.release();descriptors.release();empty.release();keypoints.release();}
    }
    private Match match(ReferenceDatabase.PreparedReference ref,ReferenceDatabase.PreparedOrbVariant variant,KeyPoint[] live,Mat descriptors,Mat raw,double scale,boolean installed,boolean refine,boolean recoverContext){DiagnosticHammingMatcher.context(ref.spec);
        Split split=splits.get(variant);if(split==null){split=new Split(ref,variant);splits.put(variant,split);}
        List<Point> module=new ArrayList<>(),moduleLive=new ArrayList<>(),board=new ArrayList<>(),boardLive=new ArrayList<>();
        List<VisionPoint> c=ref.spec.getPlanarCornersNormalized();
        double[] bounds=Phase3ReferenceAnnotations.bounds(Phase3ReferenceAnnotations.forPath(ref.spec.getJpegAssetPath())==null?c:ref.spec.getFeatureRegionsNormalized().get(0));
        double l=bounds[0]*ref.imageWidth,t=bounds[1]*ref.imageHeight,r=bounds[2]*ref.imageWidth,b=bounds[3]*ref.imageHeight;
        collect(split.local,live,descriptors,module,moduleLive);
        Mat mh=homography(module,moduleLive,installed?8:12,Math.max(15,(r-l)*.45),Math.max(15,(b-t)*.45));
        // Rank cheap local candidates first. Do not BF-match the entire board for every
        // reference on every frame, especially with the new wire/perspective dataset.
        if(installed&&mh==null&&!refine){refinements.add(new Candidate(ref,variant,module.size()));return null;}
        if(installed)collect(split.context,live,descriptors,board,boardLive);
        Mat bh=installed?homography(board,boardLive,16,15,15):null;
        try{
            // The body may be clear while small PCB lettering loses its camera
            // sampling scale. A measured module pose only proposes a rectification.
            // BOTH board hypotheses still need 16 independent PCB inliers; the
            // module pose itself is never substituted for the board homography.
            if(installed&&mh!=null&&bh==null&&recoverContext&&contextRefinements<3
                    &&Phase3ReferenceAnnotations.forPath(ref.spec.getJpegAssetPath())!=null){
                contextRefinements++;
                bh=rectifiedBoard(ref,split.context,raw,mh,false);
                if(bh==null)bh=rectifiedBoard(ref,split.context,raw,mh,true);
            }
            if(installed&&mh==null&&bh!=null){
                if(refine){
                    // Correct camera scale/perspective using ONLY the independent PCB
                    // pose. Match fresh module descriptors in that image, then map
                    // their measured coordinates back before solving the module pose.
                    // A reversed module remains reversed; PCB pixels cannot prove it.
                    rectifiedModule(ref,split.local,raw,bh,module,moduleLive);
                    mh=homography(module,moduleLive,8,Math.max(15,(r-l)*.45),Math.max(15,(b-t)*.45));
                    List<VisionPoint> search=project(c,ref.imageWidth,ref.imageHeight,bh,1);
                    for(int extent:new int[]{0,360,720}){
                        if(mh!=null)break;
                        module.clear();moduleLive.clear();
                        long detailStarted=android.os.SystemClock.elapsedRealtimeNanos();
                        try(ComponentDetailExtractor.Sample detail=details.extract(raw,search,extent)){
                            extractionNanos+=android.os.SystemClock.elapsedRealtimeNanos()-detailStarted;
                            if(detail==null)continue;extractedPoints+=detail.keys.length;
                            collect(split.local,detail.keys,detail.descriptors,module,moduleLive);
                            mh=homography(module,moduleLive,8,Math.max(15,(r-l)*.45),Math.max(15,(b-t)*.45));
                        }
                        if(mh!=null)break;
                    }
                }
            }
            if(mh==null||installed&&bh==null)return null;
            List<VisionPoint> actual=project(c,ref.imageWidth,ref.imageHeight,mh,scale);
            if(actual.size()!=4)return null;
            return new Match(ref.spec.getReferenceId(),inliers(module,moduleLive,mh),actual,installed?project(c,ref.imageWidth,ref.imageHeight,bh,scale):actual);
        }finally{if(mh!=null)mh.release();if(bh!=null)bh.release();}
    }
    private Mat rectifiedBoard(ReferenceDatabase.PreparedReference ref,Features source,Mat gray,Mat module,boolean reverse){
        Mat guess=new Mat(),turn=Mat.eye(3,3,CvType.CV_64F),zero=new Mat(),inverse=null,aligned=new Mat(),normalized=new Mat(),mask=new Mat(),rows=new Mat();
        MatOfKeyPoint keys=new MatOfKeyPoint();MatOfPoint2f before=new MatOfPoint2f(),after=new MatOfPoint2f();
        long started=android.os.SystemClock.elapsedRealtimeNanos();
        try{
            Phase3ReferenceAnnotations.Regions regions=Phase3ReferenceAnnotations.forPath(ref.spec.getJpegAssetPath());
            double[] box=Phase3ReferenceAnnotations.bounds(regions.outline);
            if(reverse){turn.put(0,0,-1,0,(box[0]+box[2])*ref.imageWidth,0,-1,(box[1]+box[3])*ref.imageHeight,0,0,1);Core.gemm(module,turn,1,zero,0,guess);}else module.copyTo(guess);
            inverse=guess.inv();Imgproc.warpPerspective(gray,aligned,inverse,new Size(ref.imageWidth,ref.imageHeight),Imgproc.INTER_LINEAR,Core.BORDER_CONSTANT,new Scalar(128));
            DiagnosticHammingMatcher.normalize(clahe,aligned,normalized);
            mask.create(aligned.rows(),aligned.cols(),CvType.CV_8UC1);mask.setTo(new Scalar(0));
            for(List<VisionPoint> region:regions.context)fill(mask,region,255);
            fill(mask,regions.outline,0);
            DiagnosticHammingMatcher.extract(orb,normalized,mask,keys,rows);KeyPoint[] points=keys.toArray();extractedPoints+=points.length;
            extractionNanos+=android.os.SystemClock.elapsedRealtimeNanos()-started;
            List<Point> a=new ArrayList<>(),b=new ArrayList<>();collect(source,points,rows,a,b);
            // Reject remote repeated connector/through-hole matches before RANSAC.
            // This is a search radius, not a change to the inlier acceptance gates.
            double radius=Math.hypot((box[2]-box[0])*ref.imageWidth,(box[3]-box[1])*ref.imageHeight)*.35;
            for(int i=a.size()-1;i>=0;i--)if(Math.hypot(a.get(i).x-b.get(i).x,a.get(i).y-b.get(i).y)>radius){a.remove(i);b.remove(i);}
            if(a.size()<16)return null;
            before.fromList(b);Core.perspectiveTransform(before,after,guess);
            return homography(a,Arrays.asList(after.toArray()),16,15,15);
        }finally{guess.release();turn.release();zero.release();if(inverse!=null)inverse.release();aligned.release();normalized.release();mask.release();rows.release();keys.release();before.release();after.release();}
    }
    private static void fill(Mat mask,List<VisionPoint> polygon,double value){
        MatOfPoint contour=new MatOfPoint();try{Point[] points=new Point[polygon.size()];for(int i=0;i<points.length;i++)points[i]=new Point(polygon.get(i).getX()*mask.cols(),polygon.get(i).getY()*mask.rows());contour.fromArray(points);Imgproc.fillConvexPoly(mask,contour,new Scalar(value));}finally{contour.release();}
    }
    private void rectifiedModule(ReferenceDatabase.PreparedReference ref,Features source,Mat gray,Mat boardHomography,List<Point> reference,List<Point> live){
        Mat inverse=boardHomography.inv(),aligned=new Mat(),normalized=new Mat(),mask=new Mat(),rows=new Mat();
        MatOfKeyPoint keys=new MatOfKeyPoint();MatOfPoint2f before=new MatOfPoint2f(),after=new MatOfPoint2f();
        reference.clear();live.clear();long started=android.os.SystemClock.elapsedRealtimeNanos();
        try{
            Imgproc.warpPerspective(gray,aligned,inverse,new Size(ref.imageWidth,ref.imageHeight),Imgproc.INTER_LINEAR,Core.BORDER_CONSTANT,new Scalar(128));
            DiagnosticHammingMatcher.normalize(clahe,aligned,normalized);
            mask.create(aligned.rows(),aligned.cols(),CvType.CV_8UC1);mask.setTo(new Scalar(0));
            double[] bounds=Phase3ReferenceAnnotations.bounds(ref.spec.getPlanarCornersNormalized());
            double l=bounds[0]*aligned.cols(),t=bounds[1]*aligned.rows(),r=bounds[2]*aligned.cols(),b=bounds[3]*aligned.rows();
            Imgproc.rectangle(mask,new Point(Math.max(0,l-(r-l)*.30),Math.max(0,t-(b-t)*.30)),new Point(Math.min(aligned.cols()-1,r+(r-l)*.30),Math.min(aligned.rows()-1,b+(b-t)*.30)),new Scalar(255),-1);
            DiagnosticHammingMatcher.extract(orb,normalized,mask,keys,rows);KeyPoint[] points=keys.toArray();extractedPoints+=points.length;
            Point[] locations=new Point[points.length];for(int i=0;i<points.length;i++)locations[i]=points[i].pt;
            before.fromArray(locations);if(points.length>0){Core.perspectiveTransform(before,after,boardHomography);Point[] projected=after.toArray();for(int i=0;i<points.length;i++)points[i].pt=projected[i];}
            extractionNanos+=android.os.SystemClock.elapsedRealtimeNanos()-started;
            collect(source,points,rows,reference,live);
        }finally{inverse.release();aligned.release();normalized.release();mask.release();rows.release();keys.release();before.release();after.release();}
    }
    private void collect(Features source,KeyPoint[] live,Mat descriptors,List<Point> reference,List<Point> target){
        if(source.descriptors.empty()||descriptors.rows()<2)return;
        List<DMatch> good=new ArrayList<>();
        good.addAll(matcher.ratioMatches(source.descriptors,descriptors,.76,64));
        good.sort(Comparator.comparingDouble(p->p.distance));Set<Integer> used=new HashSet<>();
        for(DMatch m:good)if(used.add(m.trainIdx)){reference.add(source.keys[m.queryIdx].pt);target.add(live[m.trainIdx].pt);}
    }
    /** A small probe only schedules full geometry work. Probe matches are never
     * reported as accepted matches, keypoint evidence, or a benchmark trial. */
    private List<ReferenceDatabase.PreparedReference> rankedWindow(List<ReferenceDatabase.PreparedReference> ordered,Mat live){
        List<ReferenceDatabase.PreparedReference> positive=new ArrayList<>(),negative=new ArrayList<>();
        Map<ReferenceDatabase.PreparedReference,Integer> scores=new IdentityHashMap<>();
        for(ReferenceDatabase.PreparedReference ref:ordered){
            String role=Phase3AssetCatalog.referenceRole(ref.spec.getReferenceId());if(!Phase3AssetCatalog.installed(role))continue;
            int best=0;
            for(ReferenceDatabase.PreparedOrbVariant variant:ref.orbVariants){
                if(!variant.hasOrbData())continue;Split split=splits.get(variant);if(split==null){split=new Split(ref,variant);splits.put(variant,split);}
                if(split.probe.empty()||live.rows()<2)continue;
                long started=android.os.SystemClock.elapsedRealtimeNanos();NearestDescriptorPairs pairs;
                try{pairs=NearestDescriptorPairs.compute(split.probe,live);}finally{DiagnosticHammingMatcher.snapshot().matchingNs+=android.os.SystemClock.elapsedRealtimeNanos()-started;}
                int good=0;for(int i=0;i<pairs.size();i++)if(pairs.hasTwo(i)&&pairs.bestDistance(i)<=64&&pairs.bestDistance(i)<.76*pairs.secondDistance(i))good++;
                best=Math.max(best,good);
            }
            scores.put(ref,best);("invalid".equals(role)?negative:positive).add(ref);
        }
        Comparator<ReferenceDatabase.PreparedReference> ranking=Comparator.comparingInt(r->-scores.get(r));positive.sort(ranking);negative.sort(ranking);
        LinkedHashSet<ReferenceDatabase.PreparedReference> selected=new LinkedHashSet<>();
        selected.addAll(positive.subList(0,Math.min(4,positive.size())));selected.addAll(negative.subList(0,Math.min(2,negative.size())));
        if(preferredReference!=null)for(ReferenceDatabase.PreparedReference ref:positive)if(preferredReference.equals(ref.spec.getReferenceId())){selected.add(ref);break;}
        // Rotating alternatives remain eligible even when a wire or shadow makes
        // their cheap probe weak. Counts never accumulate across camera frames.
        appendRotating(selected,positive,2);appendRotating(selected,negative,1);viewpointCursor+=2;
        List<ReferenceDatabase.PreparedReference> result=new ArrayList<>(selected);
        // A clear negative must not wait behind four weak positive viewpoints.
        // Both families remain in the window and still require full geometry.
        result.sort(ranking);return result;
    }
    private void appendRotating(Set<ReferenceDatabase.PreparedReference> selected,List<ReferenceDatabase.PreparedReference> candidates,int count){
        int added=0;for(int i=0;i<candidates.size()&&added<count;i++)if(selected.add(candidates.get(Math.floorMod(viewpointCursor+i,candidates.size()))))added++;
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
        final Features local,context;final Mat probe;
        Split(ReferenceDatabase.PreparedReference ref,ReferenceDatabase.PreparedOrbVariant v){
            Phase3ReferenceAnnotations.Regions authored=Phase3ReferenceAnnotations.forPath(ref.spec.getJpegAssetPath());
            List<VisionPoint> c=ref.spec.getPlanarCornersNormalized();double l=c.get(0).getX()*ref.imageWidth,t=c.get(0).getY()*ref.imageHeight,r=c.get(2).getX()*ref.imageWidth,b=c.get(2).getY()*ref.imageHeight;
            List<Integer> a=new ArrayList<>(),other=new ArrayList<>();for(int i=0;i<v.keypoints.length;i++){
                Point p=v.keypoints[i].pt;
                if(authored!=null){double x=p.x/ref.imageWidth,y=p.y/ref.imageHeight;
                    if(Phase3ReferenceAnnotations.contains(authored.identity,x,y))a.add(i);
                    else if(!Phase3ReferenceAnnotations.contains(authored.outline,x,y))for(List<VisionPoint> context:authored.context)if(Phase3ReferenceAnnotations.contains(context,x,y)){other.add(i);break;}
                    continue;
                }
                if(p.x>=l&&p.x<=r&&p.y>=t&&p.y<=b)a.add(i);
                else if(p.x<l-(r-l)*.15||p.x>r+(r-l)*.15||p.y<t-(b-t)*.15||p.y>b+(b-t)*.15)other.add(i);
            }
            local=new Features(v,a);context=new Features(v,other);
            int count=Math.min(32,local.descriptors.rows());probe=count==0?new Mat():new Mat(count,local.descriptors.cols(),local.descriptors.type());
            if(count>0){int stride=local.descriptors.cols();byte[] all=new byte[local.descriptors.rows()*stride],sample=new byte[count*stride];local.descriptors.get(0,0,all);for(int i=0;i<count;i++)System.arraycopy(all,(i*local.descriptors.rows()/count)*stride,sample,i*stride,stride);probe.put(0,0,sample);}
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
    private static final class Evidence {
        final EnumMap<Phase3Observation.State,Match> matches=new EnumMap<>(Phase3Observation.State.class);boolean invalidStrong;
        double score(Phase3Observation.State state){Match m=matches.get(state);return m==null?0:m.score;}
        boolean strong(){return Math.max(score(Phase3Observation.State.PLACED),score(Phase3Observation.State.BACKWARD))>=16;}
        void add(String role,Match match){
            Phase3Observation.State state="front".equals(role)?Phase3Observation.State.FRONT:"back".equals(role)?Phase3Observation.State.FLIPPED:Phase3Observation.placement(match.expected,match.actual);
            if("invalid".equals(role)){
                // Matching this actual backward installation relative to its PCB
                // is negative evidence. It can never confirm correct placement.
                if(state!=Phase3Observation.State.PLACED)return;
                state=Phase3Observation.State.BACKWARD;invalidStrong|=match.score>=16;
            }
            if(match.score>score(state))matches.put(state,match);
        }
        Phase3Observation.State state(int step){return Phase3Observation.classify(step,score(Phase3Observation.State.FRONT),score(Phase3Observation.State.FLIPPED),score(Phase3Observation.State.PLACED),score(Phase3Observation.State.BACKWARD),score(Phase3Observation.State.MISALIGNED));}
        Match selected(Phase3Observation.State state){Match selected=matches.get(state);if(selected==null)for(Match m:matches.values())if(selected==null||m.score>selected.score)selected=m;return selected;}
    }
    private static final class Match{final String referenceId;final double score;final List<VisionPoint> actual,expected;Match(String id,double score,List<VisionPoint> actual,List<VisionPoint> expected){referenceId=id;this.score=score;this.actual=actual;this.expected=expected;}}
    public void close(){for(Split s:splits.values()){s.local.descriptors.release();s.context.descriptors.release();s.probe.release();}splits.clear();refinements.clear();details.close();orb.clear();clahe.clear();matcher.clear();}
}
