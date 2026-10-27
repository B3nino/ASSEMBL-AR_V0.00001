package dev.chyken.assembl_ar.vision;

import org.opencv.core.*;
import org.opencv.calib3d.Calib3d;
import java.util.*;

/** Per-detector-thread evidence recorder. Search probes cannot publish candidate telemetry. */
final class DiagnosticHammingMatcher {
    private static final ThreadLocal<State> STATE=ThreadLocal.withInitial(State::new);
    static final class State {
        ComponentReferenceSpec current;int width,height,uniqueLive=-1;double scale=1;
        long started=android.os.SystemClock.elapsedRealtimeNanos(),featureNs,matchingNs,geometryNs;
        Mat live;KeyPoint[] liveKeys;boolean recovery,edgeRecovery;int attempted=-1,evidenceLiveCount=-1;
        Rect proposedRoi;String scope;boolean structuralInput,edgeWinner,fastLive,structuralFirst,liveStructural,liveFast,sceneFeatures,adaptiveDetail;
        EdgeGradientDescriptorExtractor.Workspace workspace;
        TrainedModelAssist.Hints trainedHints;TrainedModelAssist trainedOwner;boolean trainedPass;
        final List<Fit> fits=new ArrayList<>();
    }
    static final class Fit {
        final ComponentReferenceSpec source;final int width,height,good,total,liveCount;final double scale;final boolean structural;
        final float[] from,to;final byte[] keep;final double[] h;
        List<Point> reference;List<VisionPoint> observed,corners;List<List<VisionPoint>> outlines;
        double bodyFraction;
        Fit(State s,MatOfPoint2f src,MatOfPoint2f dst,byte[] mask,double[] homography){
            source=s.current;width=s.width;height=s.height;total=s.attempted;good=(int)src.total();scale=s.scale;keep=mask;h=homography;liveCount=s.evidenceLiveCount>=0?s.evidenceLiveCount:s.uniqueLive;structural=s.structuralInput;
            from=new float[good*2];to=new float[good*2];src.get(0,0,from);dst.get(0,0,to);
        }
        // Defer object/ROI materialization until AFTER the bounded detector search.
        // Logging must not consume its camera-recognition search deadline.
        void geometry(){if(reference!=null)return;reference=new ArrayList<>();observed=new ArrayList<>();outlines=new ArrayList<>();int inside=0;
            for(int i=0;i<good;i++)if(keep[i]!=0){reference.add(new Point(from[i*2],from[i*2+1]));observed.add(new VisionPoint(to[i*2]/scale,to[i*2+1]/scale));if(inPolygon(from[i*2]/width,from[i*2+1]/height,source.getPlanarCornersNormalized()))inside++;}
            bodyFraction=reference.isEmpty()?0:inside/(double)reference.size();corners=project(source.getPlanarCornersNormalized());outlines.add(corners);
            List<List<VisionPoint>> regions=new ArrayList<>(source.getFeatureRegionsNormalized());regions.addAll(ComponentContourTemplates.forReference(source));
            double l=1,t=1,r=0,b=0;for(List<VisionPoint> region:source.getFeatureRegionsNormalized())for(VisionPoint p:region){l=Math.min(l,p.getX());t=Math.min(t,p.getY());r=Math.max(r,p.getX());b=Math.max(b,p.getY());}
            if(r>l&&b>t)regions.add(Arrays.asList(new VisionPoint(l,t),new VisionPoint(r,t),new VisionPoint(r,b),new VisionPoint(l,b)));
            regions.add(Arrays.asList(new VisionPoint(0,0),new VisionPoint(1,0),new VisionPoint(1,1),new VisionPoint(0,1)));
            for(List<VisionPoint> region:regions)if(region.size()==4)outlines.add(project(region));
        }
        private List<VisionPoint> project(List<VisionPoint> region){List<VisionPoint> out=new ArrayList<>();for(VisionPoint p:region){double x=p.getX()*width,y=p.getY()*height,z=h[6]*x+h[7]*y+h[8];out.add(new VisionPoint((h[0]*x+h[1]*y+h[2])/z/scale,(h[3]*x+h[4]*y+h[5])/z/scale));}return out;}
    }
    static void recovery(boolean enabled){STATE.get().recovery=enabled;}
    static void begin(){end();STATE.set(new State());}
    static void end(){State s=STATE.get();discardCandidateEvidence();s.workspace=null;s.fastLive=false;s.structuralInput=false;s.edgeWinner=false;s.trainedHints=null;s.trainedOwner=null;s.trainedPass=false;}
    /** An unsuccessful proposal cannot own a later full-frame fit or telemetry. */
    static void discardCandidateEvidence(){State s=STATE.get();if(s.live!=null)s.live.release();s.live=null;s.liveKeys=null;s.fits.clear();s.current=null;s.uniqueLive=-1;s.evidenceLiveCount=-1;s.attempted=-1;}
    static State snapshot(){return STATE.get();}
    static void captureLive(KeyPoint[] keys,Mat descriptors,double scale){
        State s=STATE.get();s.scale=scale;if(descriptors.empty()||s.live!=null&&s.liveStructural==s.structuralInput&&s.liveFast==s.fastLive)return;
        if(s.live!=null)s.live.release();s.live=descriptors.clone();s.liveKeys=keys;s.liveStructural=s.structuralInput;s.liveFast=s.fastLive;
        Set<Long> unique=new HashSet<>();for(KeyPoint k:keys)unique.add(((long)Float.floatToIntBits((float)k.pt.x)<<32)|(Float.floatToIntBits((float)k.pt.y)&0xffffffffL));s.uniqueLive=unique.size();
        if(scale!=1){s.liveKeys=new KeyPoint[keys.length];for(int i=0;i<keys.length;i++){KeyPoint k=keys[i];s.liveKeys[i]=new KeyPoint((float)(k.pt.x/scale),(float)(k.pt.y/scale),k.size,k.angle,k.response,k.octave,k.class_id);}}
    }
    static void normalize(org.opencv.imgproc.CLAHE clahe,Mat src,Mat dst){long t=android.os.SystemClock.elapsedRealtimeNanos();Mat balanced=null;try{if(STATE.get().fastLive&&STATE.get().workspace!=null&&!STATE.get().trainedPass){STATE.get().workspace.normalize(src,dst,clahe,STATE.get().structuralInput,STATE.get().recovery);}else if(STATE.get().structuralInput){EdgeGradientDescriptorExtractor.normalize(src,dst);}else if(STATE.get().recovery){balanced=new Mat();IlluminationRecovery.balance(src,balanced);clahe.apply(balanced,dst);}else clahe.apply(src,dst);}finally{if(balanced!=null)balanced.release();STATE.get().featureNs+=android.os.SystemClock.elapsedRealtimeNanos()-t;}}
    static void extract(org.opencv.features2d.ORB orb,Mat src,Mat mask,MatOfKeyPoint keys,Mat descriptors){long t=android.os.SystemClock.elapsedRealtimeNanos();try{State s=STATE.get();if(s.fastLive&&s.workspace!=null){boolean first=s.live==null||s.liveStructural!=s.structuralInput;
        if(first&&s.trainedPass&&s.trainedHints!=null){
            List<Rect> windows=s.trainedHints.windows(src.cols(),src.rows());
            if(!windows.isEmpty())s.workspace.extractProposals(src,windows,orb,keys,descriptors,s.sceneFeatures);else s.workspace.extract(src,mask,orb,keys,descriptors);
        }else if(s.sceneFeatures&&(mask==null||mask.empty())&&first)s.workspace.extractScene(src,orb,keys,descriptors);else if(first&&s.proposedRoi!=null&&(mask==null||mask.empty()))s.workspace.extractProposal(src,s.proposedRoi,orb,keys,descriptors);else s.workspace.extract(src,mask,orb,keys,descriptors,s.adaptiveDetail&&!first?LiveVisionPolicy.ADAPTIVE_DETAIL_KEYPOINTS:LiveVisionPolicy.MAX_ROI_KEYPOINTS);}else orb.detectAndCompute(src,mask,keys,descriptors);}finally{STATE.get().featureNs+=android.os.SystemClock.elapsedRealtimeNanos()-t;}}
    static void context(ReferenceDatabase.PreparedReference ref){State s=STATE.get();s.current=ref.spec;s.width=ref.imageWidth;s.height=ref.imageHeight;}
    static void context(ComponentReferenceSpec spec){State s=STATE.get();s.current=spec;s.width=s.height=0;s.attempted=-1;}
    static void context(ComponentReferenceSpec spec,int width,int height){State s=STATE.get();s.current=spec;s.width=width;s.height=height;s.scale=1;s.attempted=-1;}
    static void attempted(int rows){STATE.get().attempted=rows;}
    static Mat homography(MatOfPoint2f src,MatOfPoint2f dst,int method,double threshold,Mat mask,int iterations,double confidence){
        long t=android.os.SystemClock.elapsedRealtimeNanos();
        Mat h=null;boolean transferred=false;
        try{h=Calib3d.findHomography(src,dst,method,threshold,mask,iterations,confidence);recordFit(src,dst,h,mask);transferred=true;return h;
        }finally{if(!transferred&&h!=null)h.release();STATE.get().geometryNs+=android.os.SystemClock.elapsedRealtimeNanos()-t;}
    }
    static void recordFit(MatOfPoint2f src,MatOfPoint2f dst,Mat h,Mat mask){State s=STATE.get();
            if(!h.empty()&&s.current!=null&&s.width>0&&s.height>0&&s.attempted>0&&mask.total()==src.total()){
                int n=Core.countNonZero(mask);if(n>=8&&n>=src.total()*.45){byte[] keep=new byte[(int)mask.total()];mask.get(0,0,keep);double[] matrix=new double[9];h.get(0,0,matrix);
                    Fit fit=new Fit(s,src,dst,keep,matrix);if(fit.good<=fit.total)s.fits.add(fit);if(s.fits.size()>64)s.fits.remove(0);
                }
            }}
    static Mat homography(MatOfPoint2f src,MatOfPoint2f dst,int method,double threshold,Mat mask){return homography(src,dst,method,threshold,mask,2000,.995);}
    List<DMatch> ratioMatches(Mat query,Mat train){return ratioMatches(query,train,null);}
    List<DMatch> ratioMatches(Mat query,Mat train,Mat mask){
        State s=STATE.get();long t=android.os.SystemClock.elapsedRealtimeNanos();
        try{NearestDescriptorPairs pairs=NearestDescriptorPairs.compute(query,train,mask);s.attempted=pairs.size();List<DMatch> accepted=new ArrayList<>();
            for(int i=0;i<pairs.size();i++)if(pairs.hasTwo(i)&&OrbMatchPolicy.accepts(pairs.bestDistance(i),pairs.secondDistance(i)))accepted.add(pairs.best(i));
            accepted.sort(Comparator.comparingDouble((DMatch m)->m.distance).thenComparingInt(m->m.queryIdx));
            return ComponentRecognizer.uniqueFrameMatches(accepted);
        }finally{s.matchingNs+=android.os.SystemClock.elapsedRealtimeNanos()-t;}
    }
    static VerifiedScanEvidence bind(RecognitionResult result,CameraCalibration calibration){
        if(result.getDetectionMethod()!=RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY||!result.hasLocatedComponent())return null;
        State s=STATE.get();Fit selected=null;double best=Double.POSITIVE_INFINITY;
        for(Fit f:s.fits){
            if(s.fastLive&&f.structural!=s.structuralInput)continue;
            boolean component=Objects.equals(f.source.getComponentId(),result.getComponentId());
            boolean trackerAssembly=result.getPhase7Observation()!=null&&"line_tracker_module".equals(result.getComponentId())&&"trackerwork".equals(dev.chyken.assembl_ar.camera.Phase7AssetCatalog.referenceRole(f.source.getReferenceId()));
            if(!component&&!trackerAssembly)continue;
            String id=result.getReferenceId();if(id!=null&&!id.startsWith("phase")&&!id.equals(f.source.getReferenceId()))continue;
            f.geometry();
            double distance=Double.POSITIVE_INFINITY;for(List<VisionPoint> outline:f.outlines){distance=Math.min(distance,distance(outline,result.getProjectedCorners()));
                for(RecognitionResult.InstanceObservation instance:result.getInstanceObservations())if(Objects.equals(instance.getComponentId(),result.getComponentId()))distance=Math.min(distance,distance(outline,instance.getProjectedCorners()));}
            // Exact output geometry binds evidence. A nearby unrelated fit is not a candidate.
            if(distance<.025&&(distance<best||Math.abs(distance-best)<1e-6&&selected!=null&&f.bodyFraction>selected.bodyFraction)){best=distance;selected=f;}
        }
        if(selected==null)return null;Fit context=null;
        for(Fit f:s.fits)if(f!=selected&&f.structural==selected.structural&&f.source.getReferenceId().equals(selected.source.getReferenceId())){
            f.geometry();
            if(f.bodyFraction<.2&&f.observed.size()>=14&&(context==null||f.observed.size()>context.observed.size()))context=f;
        }
        return new VerifiedScanEvidence(selected,s,Objects.equals(selected.source.getComponentId(),result.getComponentId())?calibration:null,context,result.getDisplayName());
    }
    static VerifiedScanEvidence bindGlobal(GlobalComponentRegistry.Match candidate,CameraCalibration calibration){
        if(candidate==null||!candidate.identityCertain)return null;
        return bind(RecognitionResult.builder(0).identity(candidate.referenceId,candidate.componentId,candidate.name)
                .method(RecognitionResult.DetectionMethod.ORB_HOMOGRAPHY).corners(candidate.targetCorners).build(),calibration);
    }
    private static double distance(List<VisionPoint> a,List<VisionPoint> b){if(a.size()!=4||b.size()!=4)return Double.POSITIVE_INFINITY;double sum=0;for(int i=0;i<4;i++)sum+=Math.hypot(a.get(i).getX()-b.get(i).getX(),a.get(i).getY()-b.get(i).getY());double diagonal=Math.hypot(b.get(0).getX()-b.get(2).getX(),b.get(0).getY()-b.get(2).getY());return diagonal>0?sum/(4*diagonal):Double.POSITIVE_INFINITY;}
    private static boolean inPolygon(double x,double y,List<VisionPoint> p){boolean inside=false;for(int i=0,j=p.size()-1;i<p.size();j=i++){VisionPoint a=p.get(i),b=p.get(j);if((a.getY()>y)!=(b.getY()>y)&&x<(b.getX()-a.getX())*(y-a.getY())/(b.getY()-a.getY())+a.getX())inside=!inside;}return inside;}
}
