package dev.chyken.assembl_ar.vision;

import dev.chyken.assembl_ar.camera.Phase10AssetCatalog;
import org.opencv.core.*;
import org.opencv.features2d.*;
import org.opencv.imgproc.*;
import org.opencv.calib3d.Calib3d;
import java.util.*;

/** CLAHE/ORB with independent distributed part matches; board texture alone cannot pass hardware. */
final class Phase10ReferenceMatcher implements AutoCloseable {
    private final ReferenceDatabase database;private final ORB orb=ORB.create(3200,1.2f,8,12,0,2,ORB.HARRIS_SCORE,31,7);
    private final ORB cableOrb=ORB.create(3200,1.2f,8,31,0,2,ORB.HARRIS_SCORE,31,10);
    private final CLAHE clahe;private final DiagnosticHammingMatcher matcher=new DiagnosticHammingMatcher();
    private final ComponentDetailExtractor details;
    private final Map<ReferenceDatabase.PreparedOrbVariant,List<PhotoFeaturePartition.Rows>> partRows=new IdentityHashMap<>();
    private final Phase1011ReferenceSearch search=new Phase1011ReferenceSearch();
    private final int[] cursors=new int[6];
    private final DetailSamplingSchedule recoverySchedule=new DetailSamplingSchedule();private int lastStage;
    private final ReferenceDatabase.PreparedReference[] preferred=new ReferenceDatabase.PreparedReference[6];
    Phase10ReferenceMatcher(ReferenceDatabase database,VisionConfig config){this.database=database;clahe=OpenCvVisionUtils.createClahe(config);details=new ComponentDetailExtractor(config);}
    RecognitionResult inspect(Mat gray,long time,int stage){return inspect(gray,null,time,stage);}
    RecognitionResult inspect(Mat gray,Mat rgb,long time,int stage){
        if(lastStage!=stage){lastStage=stage;recoverySchedule.reset();}
        boolean requestedRecovery=DiagnosticHammingMatcher.snapshot().recovery;
        long start=android.os.SystemClock.elapsedRealtimeNanos();Mat raw=new Mat(),work=new Mat(),descriptors=new Mat(),empty=new Mat();MatOfKeyPoint keys=new MatOfKeyPoint();double scale=Math.min(1,1280.0/Math.max(gray.cols(),gray.rows())),positive=0,negative=0;List<VisionPoint> corners=Collections.emptyList();boolean usedGeometry=false;
        List<ComponentDetailExtractor.Sample> samples=new ArrayList<>();boolean sampled=false;int detailBudget=2;
        database.acquireForRecognition();
        try{
            DiagnosticHammingMatcher.recovery(recoverySchedule.recoveryFor(requestedRecovery));
            if(scale<1)Imgproc.resize(gray,work,new Size(),scale,scale,Imgproc.INTER_AREA);else gray.copyTo(work);work.copyTo(raw);DiagnosticHammingMatcher.normalize(clahe,work,work);DiagnosticHammingMatcher.extract(stage==1?cableOrb:orb,work,empty,keys,descriptors);KeyPoint[] live=keys.toArray();DiagnosticHammingMatcher.captureLive(live,descriptors,scale);
            List<ReferenceDatabase.PreparedReference> positiveRefs=new ArrayList<>(),negativeRefs=new ArrayList<>();
            for(ReferenceDatabase.PreparedReference ref:database.preparedReferences()){String role=Phase10AssetCatalog.referenceRole(ref.spec.getReferenceId());if(Phase10AssetCatalog.eligible(role,stage))(role.endsWith("bad")?negativeRefs:positiveRefs).add(ref);}
            List<ReferenceDatabase.PreparedReference> candidates=new ArrayList<>();
            int cursor=cursors[stage]++;positiveRefs=search.order(positiveRefs,descriptors,cursor,preferred[stage]);negativeRefs=search.order(negativeRefs,descriptors,cursor,null);
            for(int i=0;i<4;i++){
                if(i<positiveRefs.size())candidates.add(positiveRefs.get(i));
                if(i<negativeRefs.size())candidates.add(negativeRefs.get(i));
            }
            // Rotate unmatched angles under a frame budget; keep the last good view for
            // the next confirmation frame. Negative evidence receives the same opportunity.
            long deadline=System.nanoTime()+850_000_000L;int tried=0;
            if(!descriptors.empty())for(ReferenceDatabase.PreparedReference ref:candidates){
                if(tried>=Math.min(negativeRefs.isEmpty()?1:2,candidates.size())&&System.nanoTime()>deadline)break;tried++;
                String role=Phase10AssetCatalog.referenceRole(ref.spec.getReferenceId());
                for(ReferenceDatabase.PreparedOrbVariant variant:ref.recoveryVariants()){if(!variant.hasOrbData())continue;Match found=match(ref,variant,live,descriptors,scale,stage,gray);
                    if(found==null&&(stage==1||stage==3||stage>=4)&&detailBudget>0&&System.nanoTime()<deadline){
                        detailBudget--;
                        if(!sampled){sampled=true;List<List<VisionPoint>> proposals=stage>=4?Collections.singletonList(Arrays.asList(new VisionPoint(0,0),new VisionPoint(raw.cols(),0),new VisionPoint(raw.cols(),raw.rows()),new VisionPoint(0,raw.rows()))):ComponentDetailExtractor.proposals(raw);for(List<VisionPoint> proposal:proposals){ComponentDetailExtractor.Sample detail=details.extract(raw,proposal,stage>=4?960:512);if(detail!=null)samples.add(detail);}}
                        for(ComponentDetailExtractor.Sample detail:samples){if(System.nanoTime()>deadline)break;found=match(ref,variant,detail.keys,detail.descriptors,scale,stage,gray);if(found!=null)break;}
                    }
                    if(found!=null&&stage==1){boolean complete=true;for(VisionPoint p:found.corners)if(p.getX()<2||p.getY()<2||p.getX()>=gray.cols()-2||p.getY()>=gray.rows()-2)complete=false;if(!complete)found=null;}
                    if(found==null)continue;
                    if(role.endsWith("bad")){if(found.score>negative){negative=found.score;if(negative>positive)corners=found.corners;}}
                    else if(found.score>positive){positive=found.score;corners=found.corners;usedGeometry=found.geometry;preferred[stage]=ref;}
                    break;
                }
                if(negativeRefs.isEmpty()&&positive>=12)break;
            }
            boolean shape=false;
            if(stage==1&&positive<12){RibbonCableGeometryVerifier.Detection cable=RibbonCableGeometryVerifier.find(gray,rgb);if(cable!=null){shape=true;corners=cable.corners;}}
            Phase10Observation result=new Phase10Observation(shape?Phase10Observation.State.FRONT_CONFIRMED:Phase10Observation.classify(stage,positive,negative),Math.max(positive,negative));
            recoverySchedule.observe(result.accepted(),DiagnosticHammingMatcher.snapshot().recovery);
            if(positive<12)preferred[stage]=null;
            return RecognitionResult.builder(time).identity(shape?"shape:kit-idc-ribbon-v1":"phase10:photo",Phase10AssetCatalog.target(stage),stage==1?"Data extension cable":"Extension board installation")
                .method(shape||usedGeometry?RecognitionResult.DetectionMethod.COLOR_SHAPE_FALLBACK:corners.size()==4?RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY:RecognitionResult.DetectionMethod.NONE)
                .validation(result.accepted()?RecognitionResult.ValidationState.EXPECTED_COMPONENT:result.flipped()?RecognitionResult.ValidationState.WRONG_ORIENTATION:RecognitionResult.ValidationState.NO_COMPONENT)
                .corners(corners).matches(live.length,0,0,0).confidence(result.accepted()?.85:0)
                .latency(new RecognitionResult.Latency(0,0,0,0,(android.os.SystemClock.elapsedRealtimeNanos()-start)/1e6)).build().withPhase10Observation(result);
        }finally{DiagnosticHammingMatcher.recovery(requestedRecovery);database.releaseAfterRecognition();for(ComponentDetailExtractor.Sample detail:samples)detail.close();raw.release();work.release();descriptors.release();keys.release();empty.release();}
    }
    private Match match(ReferenceDatabase.PreparedReference ref,ReferenceDatabase.PreparedOrbVariant variant,KeyPoint[] live,Mat descriptors,double scale,int stage,Mat gray){DiagnosticHammingMatcher.context(ref);
        List<List<VisionPoint>> regions=ref.spec.getFeatureRegionsNormalized();int parts=stage==5?4:stage==4?2:1;List<Mat> transforms=new ArrayList<>();double score=0;boolean chassis=false,geometry=false;Mat context=null;
        List<PhotoFeaturePartition.Rows> pools=partRows.get(variant);
        if(pools==null){pools=new ArrayList<>();try{for(int part=0;part<parts;part++){
            List<Integer> selected=new ArrayList<>();for(int k=0;k<variant.keypoints.length;k++){Point p=variant.keypoints[k].pt;if(!inside(p,regions.get(part),ref))continue;
                boolean excluded=false;if(part==0&&parts>1)for(int other=1;other<parts;other++)if(inside(p,regions.get(other),ref)){excluded=true;break;}
                if(!excluded)selected.add(k);
            }pools.add(new PhotoFeaturePartition.Rows(variant,selected));
        }partRows.put(variant,pools);}catch(RuntimeException|Error failure){for(PhotoFeaturePartition.Rows rows:pools)rows.rows.release();throw failure;}}
        try{
            for(int i=0;i<parts;i++){
                List<VisionPoint> box=regions.get(i);PhotoFeaturePartition.Rows pool=pools.get(i);List<Point> source=new ArrayList<>(),target=new ArrayList<>();
                // Each rigid body's descriptors compete independently. Board rows
                // must not consume the live protector/wheel matches before splitting.
                DiagnosticHammingMatcher.context(ref);
                for(DMatch m:matcher.ratioMatches(pool.rows,descriptors)){source.add(pool.keys[m.queryIdx].pt);target.add(live[m.trainIdx].pt);}
                boolean partGeometry=false;
                int minimum=i==0?12:i==1?10:8;Mat h=homography(source,target,minimum,box,ref,i>=2);
                if(i==1&&(h==null||!coherent(project(box,ref,context,scale),project(box,ref,h,scale),.10))){
                    if(h!=null){h.release();h=null;}
                    // Board pose proposes only the crop. New protector matches
                    // still need their own unchanged RANSAC and coverage gates.
                    ComponentDetailExtractor.Sample detail=details.extract(gray,project(box,ref,context,scale),320);
                    if(detail!=null)try{source.clear();target.clear();for(DMatch m:matcher.ratioMatches(pool.rows,detail.descriptors)){source.add(pool.keys[m.queryIdx].pt);Point p=detail.keys[m.trainIdx].pt;target.add(new Point(p.x*scale,p.y*scale));}h=homography(source,target,minimum,box,ref,false);}finally{detail.close();}
                }
                if(i==1&&h==null){dev.chyken.assembl_ar.camera.Phase1011VideoReferences.Entry reviewed=dev.chyken.assembl_ar.camera.Phase1011VideoReferences.annotation(ref.spec.getJpegAssetPath());
                    if(reviewed!=null&&reviewed.identity.size()==3){double top=0;for(VisionPoint p:reviewed.identity.get(2))top+=p.getY()/4;double mid=(box.get(0).getY()+box.get(2).getY())*.5;
                        List<VisionPoint> expected=project(box,ref,context,scale);
                        if(MountedHardwareGeometryVerifier.protector(gray,expected,top<mid)){h=context.clone();partGeometry=true;geometry=true;}
                        List<VisionPoint> localized=h==null?MountedHardwareGeometryVerifier.locateProtector(gray,expected,top<mid):Collections.emptyList();
                        if(localized.size()==4){MatOfPoint2f a=new MatOfPoint2f(),b=new MatOfPoint2f();try{Point[] src=new Point[4],dst=new Point[4];for(int k=0;k<4;k++){src[k]=new Point(box.get(k).getX()*ref.imageWidth,box.get(k).getY()*ref.imageHeight);dst[k]=new Point(localized.get(k).getX()*scale,localized.get(k).getY()*scale);}a.fromArray(src);b.fromArray(dst);h=Imgproc.getPerspectiveTransform(a,b);partGeometry=true;geometry=true;}finally{a.release();b.release();}}
                    }
                }
                if(h==null){if(i<2)return null;else continue;}transforms.add(h);
                if(i==0)context=h;
                if(i>0){List<VisionPoint> expected=project(box,ref,context,scale),actual=project(box,ref,h,scale);if(!coherent(expected,actual,i==1?.10:.25)){if(i<2)return null;else continue;}}
                if(i==1&&!protectorMaterial(box,ref,h,gray,scale)){return null;}
                if(!partGeometry)score+=inliers(source,target,h);if(i>=2)chassis=true;
            }
            if(stage==5&&!chassis){if(!MountedHardwareGeometryVerifier.dualWheels(gray,project(regions.get(2),ref,context,scale),project(regions.get(3),ref,context,scale)))return null;geometry=true;}
            List<VisionPoint> quad=stage==4?project(regions.get(1),ref,transforms.get(1),scale):project(ref.spec.getPlanarCornersNormalized(),ref,transforms.get(0),scale);return quad.size()==4?new Match(score,quad,geometry):null;
        }finally{for(Mat h:transforms)h.release();}
    }
    private static boolean inside(Point p,List<VisionPoint> box,ReferenceDatabase.PreparedReference ref){double sign=0,x=p.x/ref.imageWidth,y=p.y/ref.imageHeight;for(int i=0;i<4;i++){VisionPoint a=box.get(i),b=box.get((i+1)%4);double cross=(b.getX()-a.getX())*(y-a.getY())-(b.getY()-a.getY())*(x-a.getX());if(Math.abs(cross)<1e-10)continue;if(sign==0)sign=Math.signum(cross);else if(sign*cross<0)return false;}return true;}
    private static int inliers(List<Point> source,List<Point> target,Mat h){MatOfPoint2f a=new MatOfPoint2f(),b=new MatOfPoint2f();try{a.fromList(source);Core.perspectiveTransform(a,b,h);Point[] projected=b.toArray();int count=0;for(int i=0;i<projected.length;i++)if(Math.hypot(projected[i].x-target.get(i).x,projected[i].y-target.get(i).y)<=3.5)count++;return count;}finally{a.release();b.release();}}
    private static Mat homography(List<Point> source,List<Point> target,int minimum,List<VisionPoint> region,ReferenceDatabase.PreparedReference ref,boolean wheel){
        if(source.size()<minimum)return null;MatOfPoint2f a=new MatOfPoint2f(),b=new MatOfPoint2f();Mat mask=new Mat(),h=null;
        try{a.fromList(source);b.fromList(target);h=DiagnosticHammingMatcher.homography(a,b,Calib3d.RANSAC,3.5,mask,1800,.995);int n=h.empty()?0:Core.countNonZero(mask);if(n<minimum||n<source.size()*.5){h.release();return null;}
            double l=Double.MAX_VALUE,t=l,r=-l,bottom=-l;for(int i=0;i<source.size();i++)if(mask.get(i,0)[0]!=0){Point p=source.get(i);l=Math.min(l,p.x);r=Math.max(r,p.x);t=Math.min(t,p.y);bottom=Math.max(bottom,p.y);}
            double x0=1,y0=1,x1=0,y1=0;for(VisionPoint p:region){x0=Math.min(x0,p.getX());x1=Math.max(x1,p.getX());y0=Math.min(y0,p.getY());y1=Math.max(y1,p.getY());}
            dev.chyken.assembl_ar.camera.Phase1011VideoReferences.Entry authored=dev.chyken.assembl_ar.camera.Phase1011VideoReferences.annotation(ref.spec.getJpegAssetPath());
            if(authored!=null&&authored.role.equals("extension")){
                // The white board perimeter is not the component identity mask.
                // Validate distributed headers/buttons, including rotated views,
                // without requiring correspondences on blank PCB margins.
                x0=1;y0=1;x1=0;y1=0;int supported=0;
                for(List<VisionPoint> anchor:authored.identity){int local=0;for(int i=0;i<source.size();i++)if(mask.get(i,0)[0]!=0&&inside(source.get(i),anchor,ref))local++;if(local>=3)supported++;
                    for(VisionPoint p:anchor){x0=Math.min(x0,p.getX());x1=Math.max(x1,p.getX());y0=Math.min(y0,p.getY());y1=Math.max(y1,p.getY());}
                }
                if(supported<2){h.release();return null;}
            }
            if(r-l<(x1-x0)*ref.imageWidth*(wheel?.18:.30)||bottom-t<(y1-y0)*ref.imageHeight*(wheel?.25:.30)){h.release();return null;}return h;
        }finally{a.release();b.release();mask.release();}
    }
    private static List<VisionPoint> project(List<VisionPoint> box,ReferenceDatabase.PreparedReference ref,Mat h,double scale){DiagnosticHammingMatcher.context(ref);MatOfPoint2f src=new MatOfPoint2f(),dst=new MatOfPoint2f();try{
        Point[] p=new Point[4];for(int i=0;i<4;i++)p[i]=new Point(box.get(i).getX()*ref.imageWidth,box.get(i).getY()*ref.imageHeight);src.fromArray(p);Core.perspectiveTransform(src,dst,h);if(Imgproc.contourArea(dst,true)<100)return Collections.emptyList();List<VisionPoint> result=new ArrayList<>();for(Point q:dst.toArray()){if(!Double.isFinite(q.x+q.y))return Collections.emptyList();result.add(new VisionPoint(q.x/scale,q.y/scale));}return result;
    }finally{src.release();dst.release();}}
    /** Both plastic side rails must be visible; silkscreen through the open center is insufficient. */
    private static boolean protectorMaterial(List<VisionPoint> box,ReferenceDatabase.PreparedReference ref,Mat h,Mat gray,double scale){
        double l=box.get(0).getX()*ref.imageWidth,t=box.get(0).getY()*ref.imageHeight,w=(box.get(2).getX()-box.get(0).getX())*ref.imageWidth,height=(box.get(2).getY()-box.get(0).getY())*ref.imageHeight;
        MatOfPoint2f src=new MatOfPoint2f(),dst=new MatOfPoint2f();
        try{
            List<Point> aperture=new ArrayList<>();for(int x=0;x<10;x++)for(int y=0;y<16;y++)aperture.add(new Point(l+(.40+.20*x/9)*w,t+(.30+.40*y/15)*height));
            src.fromList(aperture);Core.perspectiveTransform(src,dst,h);List<Double> backing=new ArrayList<>();
            for(Point p:dst.toArray()){int x=(int)(p.x/scale),y=(int)(p.y/scale);if(x>=0&&y>=0&&x<gray.cols()&&y<gray.rows())backing.add(gray.get(y,x)[0]);}
            if(backing.size()<100)return false;Collections.sort(backing);
            // Compare both black rails with the local PCB seen through the aperture.
            // A global 110 gray-level cutoff changes meaning under exposure/glare.
            double shadow=backing.get((int)(backing.size()*.75))-8;
            dev.chyken.assembl_ar.camera.Phase1011VideoReferences.Entry reviewed=dev.chyken.assembl_ar.camera.Phase1011VideoReferences.annotation(ref.spec.getJpegAssetPath());
            if(reviewed!=null){
                for(List<VisionPoint> rail:reviewed.identity){List<Point> samples=new ArrayList<>();for(int x=0;x<5;x++)for(int y=0;y<8;y++){double u=(x+.5)/5,v=(y+.5)/8;samples.add(new Point((rail.get(0).getX()+u*(rail.get(1).getX()-rail.get(0).getX())+v*(rail.get(3).getX()-rail.get(0).getX()))*ref.imageWidth,(rail.get(0).getY()+u*(rail.get(1).getY()-rail.get(0).getY())+v*(rail.get(3).getY()-rail.get(0).getY()))*ref.imageHeight));}src.fromList(samples);Core.perspectiveTransform(src,dst,h);int n=0,dark=0;for(Point p:dst.toArray()){int x=(int)(p.x/scale),y=(int)(p.y/scale);if(x>=0&&y>=0&&x<gray.cols()&&y<gray.rows()){n++;if(gray.get(y,x)[0]<shadow)dark++;}}if(n<24||dark<n*.35)return false;}return true;
            }
            for(double start:new double[]{.15,.70}){List<Point> samples=new ArrayList<>();for(int x=0;x<10;x++)for(int y=0;y<16;y++)samples.add(new Point(l+(start+.20*x/9)*w,t+(.30+.40*y/15)*height));src.fromList(samples);Core.perspectiveTransform(src,dst,h);int count=0,dark=0;for(Point p:dst.toArray()){int x=(int)(p.x/scale),y=(int)(p.y/scale);if(x<0||y<0||x>=gray.cols()||y>=gray.rows())continue;count++;if(gray.get(y,x)[0]<shadow)dark++;}if(count<100||dark<count*.35)return false;}return true;
        }finally{src.release();dst.release();}
    }
    private static boolean coherent(List<VisionPoint> expected,List<VisionPoint> actual,double tolerance){if(expected.size()!=4||actual.size()!=4)return false;double size=Math.hypot(expected.get(0).getX()-expected.get(2).getX(),expected.get(0).getY()-expected.get(2).getY());if(size<10)return false;for(int i=0;i<4;i++)if(Math.hypot(expected.get(i).getX()-actual.get(i).getX(),expected.get(i).getY()-actual.get(i).getY())>size*tolerance)return false;return true;}
    private static final class Match{final double score;final List<VisionPoint> corners;final boolean geometry;Match(double score,List<VisionPoint> corners,boolean geometry){this.score=score;this.corners=corners;this.geometry=geometry;}}
    public void close(){for(List<PhotoFeaturePartition.Rows> pools:partRows.values())for(PhotoFeaturePartition.Rows rows:pools)rows.rows.release();partRows.clear();search.close();details.close();orb.clear();cableOrb.clear();clahe.clear();}
}
