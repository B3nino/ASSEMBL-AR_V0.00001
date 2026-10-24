package dev.chyken.assembl_ar.vision;

import dev.chyken.assembl_ar.camera.Phase9AssetCatalog;
import org.opencv.core.*;
import org.opencv.calib3d.Calib3d;
import org.opencv.features2d.*;
import org.opencv.imgproc.*;
import java.util.*;

/** Per-object identity pools plus independent board context. Counts never accumulate across frames. */
final class Phase9ReferenceMatcher implements AutoCloseable {
    private static final long FRAME_BUDGET_NS=850_000_000L;
    private static final long LOOSE_WINDOW_BUDGET_NS=200_000_000L;
    private final ReferenceDatabase database;private final ORB orb=ORB.create(3200,1.2f,8,12,0,2,ORB.HARRIS_SCORE,31,7);
    private final CLAHE clahe;private final DiagnosticHammingMatcher matcher=new DiagnosticHammingMatcher();
    private final Map<ReferenceDatabase.PreparedOrbVariant,Map<Integer,Features>> pools=new IdentityHashMap<>();
    private int frameKeypoints;
    private final int[] cursors=new int[5];private ReferenceDatabase.PreparedReference preferredLoose,preferredScene;
    Phase9ReferenceMatcher(ReferenceDatabase database,VisionConfig config){this.database=database;clahe=OpenCvVisionUtils.createClahe(config);}

    RecognitionResult inspect(Mat gray,Mat rgb,long timestamp,int stage){
        frameKeypoints=0;
        long start=android.os.SystemClock.elapsedRealtimeNanos(),deadline=System.nanoTime()+FRAME_BUDGET_NS;
        Mat color=new Mat(),bgr=new Mat();List<VisionPoint> corners=Collections.emptyList();List<RecognitionResult.InstanceObservation> instances=new ArrayList<>();boolean stand=false,left=false,right=false,context=false,invalid=false;int count=0;double score=0;
        database.acquireForRecognition();
        try{
            if(rgb!=null&&!rgb.empty()){Imgproc.resize(rgb,color,gray.size(),0,0,Imgproc.INTER_AREA);Imgproc.cvtColor(color,bgr,Imgproc.COLOR_RGB2BGR);}
            if(stage<=2){
                List<Rect> windows=proposals(gray,bgr,stage);List<List<VisionPoint>> bodies=new ArrayList<>();
                List<ReferenceDatabase.PreparedReference> refs=references(stage,false);
                for(Rect window:windows){
                    if(System.nanoTime()>deadline&&count>0)break;Found best=null;ReferenceDatabase.PreparedReference selected=null;
                    // Native sampling comes first, then an independent detail pass. Both
                    // instances use fresh local descriptor pools, even for identical wheels.
                    for(int extent:new int[]{0,512}){
                        try(Live live=extract(gray,window,extent)){
                            List<ReferenceDatabase.PreparedReference> ordered=ranked(refs,live,false,preferredLoose,cursors[stage],stage);int visited=0;
                            long slice=Math.min(deadline,System.nanoTime()+LOOSE_WINDOW_BUDGET_NS);
                            for(ReferenceDatabase.PreparedReference ref:ordered){
                                if(visited>0&&System.nanoTime()>slice)break;visited++;
                                for(ReferenceDatabase.PreparedOrbVariant v:ref.orbVariants)for(int part=0;part<ref.spec.getFeatureRegionsNormalized().size();part++){
                                    Found f=match(ref,v,part,live,stage==1?8:10,false);
                                    if(f!=null){if(distinct(f.corners,bodies)&&(best==null||f.score>best.score)){if(best!=null)best.close();best=f;selected=ref;}else f.close();}
                                }
                                if(best!=null)break;
                            }
                            cursors[stage]+=Math.max(1,visited);
                        }
                        if(best!=null)break;
                    }
                    if(best!=null){bodies.add(best.corners);count++;score+=best.score;corners=best.corners;instances.add(new RecognitionResult.InstanceObservation("phase9:loose:"+count,stage==1?"center_standoff":"drive_wheel",RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY,best.corners,.85));preferredLoose=selected;best.close();}
                    if(count==(stage==1?1:2))break;
                }
                if(stage==1){stand=count==1;count=0;}
            }else{
                List<ReferenceDatabase.PreparedReference> refs=references(stage,true);int visited=0;
                try(Live whole=extract(gray,new Rect(0,0,gray.cols(),gray.rows()),0)){
                    for(ReferenceDatabase.PreparedReference ref:ranked(refs,whole,true,preferredScene,cursors[stage],stage)){
                        if(visited>0&&System.nanoTime()>deadline)break;visited++;
                        boolean bad="invalid".equals(Phase9AssetCatalog.referenceRole(ref.spec.getReferenceId()));
                        for(ReferenceDatabase.PreparedOrbVariant v:ref.orbVariants){
                            Found board=match(ref,v,-1,whole,20,true);if(board==null)continue;
                            context=true;List<List<VisionPoint>> regions=ref.spec.getFeatureRegionsNormalized();
                            List<VisionPoint> predictedStand=project(regions.get(2),ref,board.h);
                            boolean visibleStand=brassAt(bgr,predictedStand);boolean l=false,r=false;double value=board.score;
                            if(!visibleStand){
                                // Specular brass can appear white/gray. Require an independent
                                // local standoff homography when its gold appearance disappears.
                                Rect standCrop=bounds(predictedStand,gray,.25);
                                if(standCrop!=null)for(int extent:new int[]{0,256})try(Live detail=extract(gray,standCrop,extent)){
                                    Found local=match(ref,v,2,detail,6,false);
                                    if(local!=null){try{visibleStand=aligned(predictedStand,local.corners);if(visibleStand)value+=local.score;}finally{local.close();}}
                                    if(visibleStand)break;
                                }
                            }
                            List<RecognitionResult.InstanceObservation> visible=new ArrayList<>();
                            if(visibleStand)visible.add(new RecognitionResult.InstanceObservation("phase9:standoff","center_standoff",RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY,predictedStand,.85));
                            if(stage==4&&!bad){
                                boolean[] seen=new boolean[2];
                                for(int side=0;side<2;side++){
                                    List<VisionPoint> expected=project(regions.get(side),ref,board.h);Rect crop=bounds(expected,gray,.16);
                                    if(crop==null)continue;
                                    for(int extent:new int[]{0,512})try(Live local=extract(gray,crop,extent)){
                                        Found wheel=match(ref,v,side,local,10,false);
                                        if(wheel!=null){seen[side]=aligned(expected,wheel.corners);if(seen[side]){value+=wheel.score;visible.add(new RecognitionResult.InstanceObservation("phase9:wheel:"+side,"drive_wheel",RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY,wheel.corners,.85));}wheel.close();if(seen[side])break;}
                                    }
                                }
                                l=seen[0];r=seen[1];
                            }
                            // Missing-wheel references locate the board/standoff at 9.3.
                            // Their unchanged board is never positive wheel evidence at 9.4.
                            boolean pass=visibleStand&&(stage==3||!bad&&l&&r);
                            int quality=(visibleStand?1:0)+(l?2:0)+(r?2:0);
                            int previous=(stand?1:0)+(left?2:0)+(right?2:0);
                            if(quality>previous||quality==previous&&value>score){stand=visibleStand;left=l;right=r;score=value;corners=visible.isEmpty()?Collections.emptyList():visible.get(0).getProjectedCorners();instances=visible;}
                            board.close();
                            if(pass){preferredScene=ref;stand=true;left=l;right=r;invalid=false;break;}
                        }
                        if(stand&&(stage==3||left&&right))break;
                    }
                    cursors[stage]+=Math.max(1,visited);
                }
                invalid=context&&stage==4&&!(stand&&left&&right);
            }
            Phase9Observation o=new Phase9Observation(stand,count,left,right,context,invalid,score);
            if(!o.accepted(stage)){if(stage<=2)preferredLoose=null;else preferredScene=null;}
            String component=stage==1?"center_standoff":stage==2?"drive_wheel":"main_board";
            return RecognitionResult.builder(timestamp).identity("phase9:photo",component,"Standoff and wheels")
                .method(corners.size()==4?RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY:RecognitionResult.DetectionMethod.NONE)
                .validation(o.accepted(stage)?RecognitionResult.ValidationState.EXPECTED_COMPONENT:invalid?RecognitionResult.ValidationState.WRONG_ORIENTATION:RecognitionResult.ValidationState.NO_COMPONENT)
                .corners(corners).instances(instances).matches(frameKeypoints,(int)score,(int)score,score>0?1:0).confidence(o.accepted(stage)?.85:0)
                .latency(new RecognitionResult.Latency(0,0,0,0,(android.os.SystemClock.elapsedRealtimeNanos()-start)/1e6)).build().withPhase9Observation(o);
        }finally{database.releaseAfterRecognition();color.release();bgr.release();}
    }
    private List<ReferenceDatabase.PreparedReference> references(int stage,boolean assembly){
        List<ReferenceDatabase.PreparedReference> result=new ArrayList<>();for(ReferenceDatabase.PreparedReference r:database.preparedReferences()){
            String role=Phase9AssetCatalog.referenceRole(r.spec.getReferenceId());if(role==null)continue;
            if(assembly?Phase9AssetCatalog.installed(role):stage==1?role.equals("standoff"):role.equals("wheel")||role.equals("pair"))result.add(r);
        }
        if(!assembly&&stage==2)result.sort(Comparator.comparingInt(r->"wheel".equals(Phase9AssetCatalog.referenceRole(r.spec.getReferenceId()))?0:1));
        if(assembly&&stage==4)result.sort(Comparator.comparingInt(r->"assembly".equals(Phase9AssetCatalog.referenceRole(r.spec.getReferenceId()))?0:1));return result;
    }
    private static List<ReferenceDatabase.PreparedReference> ordered(List<ReferenceDatabase.PreparedReference> refs,int cursor,ReferenceDatabase.PreparedReference preferred){DiagnosticHammingMatcher.context(preferred==null?null:preferred.spec);
        List<ReferenceDatabase.PreparedReference> result=new ArrayList<>();if(refs.contains(preferred))result.add(preferred);for(int i=0;i<refs.size();i++){ReferenceDatabase.PreparedReference r=refs.get(Math.floorMod(cursor+i,refs.size()));if(r!=preferred)result.add(r);}return result;
    }
    /** Cheap sparse descriptor ranking only schedules candidates; it never certifies a part. */
    private List<ReferenceDatabase.PreparedReference> ranked(List<ReferenceDatabase.PreparedReference> refs,Live live,boolean board,ReferenceDatabase.PreparedReference preferred,int cursor,int stage){DiagnosticHammingMatcher.context(preferred==null?null:preferred.spec);
        Map<ReferenceDatabase.PreparedReference,Integer> scores=new IdentityHashMap<>();
        for(ReferenceDatabase.PreparedReference ref:refs){int best=0;
            for(ReferenceDatabase.PreparedOrbVariant v:ref.orbVariants){
                if(board){best=sparseScore(pool(ref,v,-1),live);
                    if(stage==4){int left=sparseScore(pool(ref,v,0),live),right=sparseScore(pool(ref,v,1),live);best+=left+right+3*Math.min(left,right);}
                }else for(int part=0;part<ref.spec.getFeatureRegionsNormalized().size();part++)best=Math.max(best,sparseScore(pool(ref,v,part),live));
                break;
            }scores.put(ref,best);
        }
        List<ReferenceDatabase.PreparedReference> result=ordered(refs,cursor,preferred);
        result.sort((a,b)->Integer.compare(scores.get(b),scores.get(a)));
        if(result.remove(preferred))result.add(0,preferred);
        if(stage==4)result.sort(Comparator.comparingInt(r->"invalid".equals(Phase9AssetCatalog.referenceRole(r.spec.getReferenceId()))?1:0));
        // If the strongest context view keeps failing local identity, periodically try a
        // different positive viewpoint first. Low sparse scores cannot starve a photo forever.
        if(preferred==null&&cursor>0&&!result.isEmpty()){
            List<ReferenceDatabase.PreparedReference> eligible=new ArrayList<>();for(ReferenceDatabase.PreparedReference r:result)if(stage!=4||!"invalid".equals(Phase9AssetCatalog.referenceRole(r.spec.getReferenceId())))eligible.add(r);
            if(!eligible.isEmpty()){ReferenceDatabase.PreparedReference r=eligible.get(Math.floorMod(cursor,eligible.size()));result.remove(r);result.add(0,r);}
        }
        return result;
    }
    private int sparseScore(Features f,Live live){
        if(f.sparse.empty()||live.rows.rows()<2)return 0;
        return matcher.ratioMatches(f.sparse,live.rows,.80,64).size();
    }
    private List<Rect> proposals(Mat gray,Mat bgr,int stage){
        List<Rect> result=new ArrayList<>();if(!bgr.empty())for(Rect r:Phase9PhotoGeometry.colored(bgr,new Scalar(12,stage==1?40:80,45),new Scalar(42,255,255),5)){
            double aspect=Math.max(r.width,r.height)/(double)Math.max(1,Math.min(r.width,r.height));
            if(r.area()<gray.total()*(stage==1?.0005:.006)||stage==1&&aspect<2.2||stage==2&&aspect>2.1)continue;
            result.add(Phase9PhotoGeometry.pad(r,stage==1?.35:.25,gray));if(result.size()==3)break;
        }
        // Both rubber faces remain eligible when yellow hubs are shaded. Independent
        // descriptor pools prevent one identical wheel stealing the other's ratio matches.
        if(stage==2){
            result.add(new Rect(0,0,(int)(gray.cols()*.56),gray.rows()));
            result.add(new Rect((int)(gray.cols()*.44),0,gray.cols()-(int)(gray.cols()*.44),gray.rows()));
            result.add(new Rect(0,0,gray.cols(),(int)(gray.rows()*.56)));
            result.add(new Rect(0,(int)(gray.rows()*.44),gray.cols(),gray.rows()-(int)(gray.rows()*.44)));
        }
        result.add(new Rect(0,0,gray.cols(),gray.rows()));return result;
    }
    private Live extract(Mat gray,Rect window,int extent){
        Mat crop=gray.submat(window),work=new Mat(),mask=new Mat(),rows=new Mat();MatOfKeyPoint keypoints=new MatOfKeyPoint();
        try{
            double scale=extent==0?1:Math.min(4,extent/(double)Math.max(window.width,window.height));Imgproc.resize(crop,work,new Size(),scale,scale,scale<1?Imgproc.INTER_AREA:Imgproc.INTER_LINEAR);
            DiagnosticHammingMatcher.normalize(clahe,work,work);DiagnosticHammingMatcher.extract(orb,work,mask,keypoints,rows);KeyPoint[] keys=keypoints.toArray();frameKeypoints+=keys.length;for(KeyPoint k:keys){k.pt.x=k.pt.x/scale+window.x;k.pt.y=k.pt.y/scale+window.y;}
            return new Live(keys,rows);
        }catch(RuntimeException failure){rows.release();throw failure;}finally{crop.release();work.release();mask.release();keypoints.release();}
    }
    private Features pool(ReferenceDatabase.PreparedReference ref,ReferenceDatabase.PreparedOrbVariant v,int part){DiagnosticHammingMatcher.context(ref.spec);
        Map<Integer,Features> cache=pools.computeIfAbsent(v,key->new HashMap<>());Features found=cache.get(part);if(found!=null)return found;
        List<Integer> ids=new ArrayList<>();List<List<VisionPoint>> regions=ref.spec.getFeatureRegionsNormalized();
        for(int i=0;i<v.keypoints.length;i++){
            Point p=v.keypoints[i].pt;
            if(part>=0?inside(p,regions.get(part),ref):!inside(p,regions.get(0),ref)&&!inside(p,regions.get(1),ref)&&!inside(p,regions.get(2),ref))ids.add(i);
        }
        KeyPoint[] keys=new KeyPoint[ids.size()];Mat rows=ids.isEmpty()?new Mat():new Mat(ids.size(),v.descriptors.cols(),v.descriptors.type());
        if(!ids.isEmpty()){int stride=v.descriptors.cols();byte[] source=new byte[v.descriptors.rows()*stride],selected=new byte[ids.size()*stride];v.descriptors.get(0,0,source);for(int i=0;i<ids.size();i++){keys[i]=v.keypoints[ids.get(i)];System.arraycopy(source,ids.get(i)*stride,selected,i*stride,stride);}rows.put(0,0,selected);}
        found=new Features(keys,rows);cache.put(part,found);return found;
    }
    private Found match(ReferenceDatabase.PreparedReference ref,ReferenceDatabase.PreparedOrbVariant v,int part,Live live,int minimum,boolean board){
        Features source=pool(ref,v,part);if(source.rows.empty()||live.rows.rows()<2)return null;List<DMatch> good=new ArrayList<>();
        good.addAll(matcher.ratioMatches(source.rows,live.rows,.76,64));
        good=ComponentRecognizer.uniqueFrameMatches(good);if(good.size()<minimum)return null;
        List<Point> a=new ArrayList<>(),b=new ArrayList<>();for(DMatch m:good){a.add(source.keys[m.queryIdx].pt);b.add(live.keys[m.trainIdx].pt);}
        MatOfPoint2f from=new MatOfPoint2f(),to=new MatOfPoint2f();Mat inliers=new Mat(),h=null;
        try{
            from.fromList(a);to.fromList(b);h=DiagnosticHammingMatcher.homography(from,to,Calib3d.RANSAC,3.5,inliers,1600,.995);int n=h.empty()?0:Core.countNonZero(inliers);
            if(n<minimum||n<a.size()*.5)return null;
            double l=Double.MAX_VALUE,t=l,r=-l,bt=-l;for(int i=0;i<a.size();i++)if(inliers.get(i,0)[0]!=0){Point p=a.get(i);l=Math.min(l,p.x);r=Math.max(r,p.x);t=Math.min(t,p.y);bt=Math.max(bt,p.y);}
            List<VisionPoint> region=board?Arrays.asList(new VisionPoint(0,0),new VisionPoint(1,0),new VisionPoint(1,1),new VisionPoint(0,1)):ref.spec.getFeatureRegionsNormalized().get(part);
            double width=(region.get(2).getX()-region.get(0).getX())*ref.imageWidth,height=(region.get(2).getY()-region.get(0).getY())*ref.imageHeight;
            if(r-l<width*(board?.28:.30)||bt-t<height*(board?.20:.30))return null;
            List<VisionPoint> quad=project(region,ref,h);if(quad.size()!=4)return null;Found found=new Found(n,quad,h);h=null;return found;
        }finally{from.release();to.release();inliers.release();if(h!=null)h.release();}
    }
    private static boolean inside(Point p,List<VisionPoint> r,ReferenceDatabase.PreparedReference ref){return p.x>=r.get(0).getX()*ref.imageWidth&&p.x<=r.get(2).getX()*ref.imageWidth&&p.y>=r.get(0).getY()*ref.imageHeight&&p.y<=r.get(2).getY()*ref.imageHeight;}
    private static List<VisionPoint> project(List<VisionPoint> region,ReferenceDatabase.PreparedReference ref,Mat h){DiagnosticHammingMatcher.context(ref.spec);
        MatOfPoint2f a=new MatOfPoint2f(),b=new MatOfPoint2f();try{List<Point> points=new ArrayList<>();for(VisionPoint p:region)points.add(new Point(p.getX()*ref.imageWidth,p.getY()*ref.imageHeight));a.fromList(points);Core.perspectiveTransform(a,b,h);Point[] q=b.toArray();double area=Imgproc.contourArea(b,true);
            if(!Double.isFinite(area)||area<12)return Collections.emptyList();MatOfPoint convex=new MatOfPoint(q);try{if(!Imgproc.isContourConvex(convex))return Collections.emptyList();}finally{convex.release();}
            List<VisionPoint> result=new ArrayList<>();for(Point p:q){if(!Double.isFinite(p.x+p.y))return Collections.emptyList();result.add(new VisionPoint(p.x,p.y));}return result;
        }finally{a.release();b.release();}
    }
    private static Rect bounds(List<VisionPoint> q,Mat image,double pad){if(q.size()!=4)return null;double l=Double.MAX_VALUE,t=l,r=-l,b=-l;for(VisionPoint p:q){l=Math.min(l,p.getX());r=Math.max(r,p.getX());t=Math.min(t,p.getY());b=Math.max(b,p.getY());}double dx=(r-l)*pad,dy=(b-t)*pad;if(r<0||b<0||l>=image.cols()||t>=image.rows())return null;return Phase9PhotoGeometry.bounded(l-dx,t-dy,r+dx,b+dy,image);}
    private static boolean brassAt(Mat bgr,List<VisionPoint> q){
        if(bgr.empty())return false;Rect crop=bounds(q,bgr,.15);if(crop==null||crop.width<3||crop.height<3)return false;Mat image=bgr.submat(crop),hsv=new Mat(),mask=new Mat();
        try{Imgproc.cvtColor(image,hsv,Imgproc.COLOR_BGR2HSV);Core.inRange(hsv,new Scalar(10,45,45),new Scalar(43,255,255),mask);int gold=Core.countNonZero(mask);return gold>=4&&gold>=crop.area()*.07&&gold<crop.area()*.85;}finally{image.release();hsv.release();mask.release();}
    }
    private static boolean aligned(List<VisionPoint> expected,List<VisionPoint> actual){if(expected.size()!=4||actual.size()!=4)return false;double diagonal=Math.hypot(expected.get(0).getX()-expected.get(2).getX(),expected.get(0).getY()-expected.get(2).getY()),sum=0;for(int i=0;i<4;i++)sum+=Math.hypot(expected.get(i).getX()-actual.get(i).getX(),expected.get(i).getY()-actual.get(i).getY());return diagonal>15&&sum/(4*diagonal)<.25;}
    private static boolean distinct(List<VisionPoint> a,List<List<VisionPoint>> known){for(List<VisionPoint> b:known){double ax=0,ay=0,bx=0,by=0;for(int i=0;i<4;i++){ax+=a.get(i).getX();ay+=a.get(i).getY();bx+=b.get(i).getX();by+=b.get(i).getY();}double span=Math.hypot(a.get(0).getX()-a.get(2).getX(),a.get(0).getY()-a.get(2).getY());if(Math.hypot(ax-bx,ay-by)/4<span*.45)return false;}return true;}
    private static class Features implements AutoCloseable{
        final KeyPoint[] keys;final Mat rows,sparse;
        Features(KeyPoint[] keys,Mat rows){this(keys,rows,true);}
        Features(KeyPoint[] keys,Mat rows,boolean sample){this.keys=keys;this.rows=rows;
            int count=sample?Math.min(48,rows.rows()):0;sparse=count==0?new Mat():new Mat(count,rows.cols(),rows.type());
            if(count>0){int stride=rows.cols();byte[] source=new byte[rows.rows()*stride],selected=new byte[count*stride];rows.get(0,0,source);for(int i=0;i<count;i++)System.arraycopy(source,(i*rows.rows()/count)*stride,selected,i*stride,stride);sparse.put(0,0,selected);}
        }
        public void close(){rows.release();sparse.release();}
    }
    private static final class Live extends Features{Live(KeyPoint[] keys,Mat rows){super(keys,rows,false);}}
    private static final class Found implements AutoCloseable{final double score;final List<VisionPoint> corners;final Mat h;Found(double score,List<VisionPoint> corners,Mat h){this.score=score;this.corners=corners;this.h=h;}public void close(){h.release();}}
    public void close(){for(Map<Integer,Features> map:pools.values())for(Features f:map.values())f.close();pools.clear();orb.clear();clahe.clear();matcher.clear();}
}
