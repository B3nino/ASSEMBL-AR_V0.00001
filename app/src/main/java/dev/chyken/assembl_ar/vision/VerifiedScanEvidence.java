package dev.chyken.assembl_ar.vision;

import java.util.*;
import org.opencv.core.*;
import org.opencv.calib3d.Calib3d;
import org.opencv.imgproc.Imgproc;

/** Values belonging to ONE selected RANSAC fit, never a shortlist/probe winner. */
public final class VerifiedScanEvidence {
    /** A located shape can own a coarse PnP solve without owning ORB inliers. This
     * does not upgrade its synthetic rectangle corners into measured 3D landmarks. */
    public static RecognitionResult.Pose coarsePose(RecognitionResult r){
        if(r==null||!r.hasLocatedComponent()||r.getDetectionMethod()==RecognitionResult.DetectionMethod.NONE
                ||r.getDetectionMethod()==RecognitionResult.DetectionMethod.ORB_DESCRIPTOR_ONLY)return null;
        RecognitionResult.Pose p=r.getPose();
        return p!=null&&p.getTranslationVector()[2]>0?p:null;
    }
    /** Existing spatial/phase decisions only; never shortlist scores or a rendered outline. */
    public static boolean workflowAccepted(RecognitionResult r,RecognitionRequest request){
        if(r==null||request==null||!r.hasLocatedComponent()
                ||r.getDiagnosticMetrics()!=null&&r.getDiagnosticMetrics().mismatch!=null)return false;
        RecognitionResult.ValidationState state=r.getValidationState();
        if(state!=RecognitionResult.ValidationState.EXPECTED_COMPONENT
                &&state!=RecognitionResult.ValidationState.RECOGNIZED)return false;
        RecognitionRequest.AssemblyExpectation target=request.getExpectation();
        boolean trackerAssembly=request.getPhase7Stage()==4&&r.getPhase7Observation()!=null
                &&r.getPhase7Observation().accepted(4)&&target!=null&&"main_board".equals(target.getComponentId())
                &&"line_tracker_module".equals(r.getComponentId());
        if(target!=null&&!Objects.equals(target.getComponentId(),r.getComponentId())&&!trackerAssembly)return false;
        if((request.getPhase2Stage()==1||request.getPhase2Stage()==2)&&target!=null
                &&r.getDetectedInstanceCount(target.getComponentId())<request.getExpectedInstanceCount())return false;
        if(r.getPhase2Observation()!=null&&request.getPhase2Stage()==5)return r.getPhase2Observation().referenceSupported;
        if(r.getPhase3Observation()!=null)return r.getPhase3Observation().accepted(request.getPhase3Stage());
        if(r.getPhase5Observation()!=null)return r.getPhase5Observation().accepted(request.getPhase5Stage());
        if(r.getPhase7Observation()!=null)return r.getPhase7Observation().accepted(request.getPhase7Stage());
        if(r.getPhase8Observation()!=null)return r.getPhase8Observation().accepted(request.getPhase8Stage());
        if(r.getPhase9Observation()!=null)return r.getPhase9Observation().accepted(request.getPhase9Stage());
        if(r.getPhase10Observation()!=null)return r.getPhase10Observation().accepted();
        if(r.getPhase11Observation()!=null)return r.getPhase11Observation().accepted();
        return state==RecognitionResult.ValidationState.EXPECTED_COMPONENT;
    }
    public static String verificationSource(RecognitionResult r){
        if(r.getTrainedEvidence()!=null&&r.getDetectionMethod()!=RecognitionResult.DetectionMethod.TRAINED_MODEL)
            return r.getTrainedEvidence().identityAccepted?"ONNX identity + placement/orientation verifier":"ONNX search + ORB/geometry verifier";
        switch(r.getDetectionMethod()){
            case TRAINED_MODEL:return "ONNX (two fresh predictions)"+(r.getPhase3Observation()!=null||r.getPhase5Observation()!=null||r.getPhase8Observation()!=null?" + front-surface geometry":"");
            case COLOR_SHAPE_FALLBACK:return r.getReferenceId()!=null&&r.getReferenceId().startsWith("phase")?"Photo + geometry":"Geometry/shape";
            case MODEL_EDGE_MATCH:return "Model edges";
            case CANNY_HOUGH_FALLBACK:return "Edge/circle geometry";
            case ORB_HOMOGRAPHY:return "ORB + RANSAC";
            default:return "Unmeasured";
        }
    }
    public final ComponentReferenceSpec source;
    public final String componentName;
    public final int good,total,inliers,liveKeypoints,activeKeypoints;
    public final RecognitionResult.Pose pose;
    public final PoseOrientation orientation;
    public final VisionPoint projectedOrigin,observedCentroid;
    public final double extractionMs,matchingMs;
    VerifiedScanEvidence(DiagnosticHammingMatcher.Fit fit,DiagnosticHammingMatcher.State state,
            CameraCalibration calibration,DiagnosticHammingMatcher.Fit context,String name){
        source=fit.source;componentName=name==null?source.getDisplayName():name;good=fit.good;total=fit.total;inliers=fit.observed.size();
        activeKeypoints=fit.total;liveKeypoints=fit.liveCount;extractionMs=state.featureNs/1e6;
        long started=android.os.SystemClock.elapsedRealtimeNanos();
        PoseFit p=estimate(fit,calibration),expected=context==null?null:estimate(context,calibration);
        state.geometryNs+=android.os.SystemClock.elapsedRealtimeNanos()-started;
        matchingMs=(state.matchingNs+state.geometryNs)/1e6;
        pose=p==null?null:p.pose;orientation=p==null?null:PoseOrientation.fromRotation(p.rotation,expected==null?null:expected.rotation);
        projectedOrigin=p==null?null:p.origin;
        double x=0,y=0;for(VisionPoint point:fit.observed){x+=point.getX()/fit.observed.size();y+=point.getY()/fit.observed.size();}
        observedCentroid=fit.observed.isEmpty()?null:new VisionPoint(x,y);
    }
    private static final class PoseFit {final RecognitionResult.Pose pose;final double[] rotation;final VisionPoint origin;PoseFit(RecognitionResult.Pose p,double[] r,VisionPoint o){pose=p;rotation=r;origin=o;}}
    private static PoseFit estimate(DiagnosticHammingMatcher.Fit fit,CameraCalibration c){
        if(c==null||fit.observed.size()<6)return null;
        MatOfPoint2f image=new MatOfPoint2f(),from=new MatOfPoint2f(),to=new MatOfPoint2f(),source=new MatOfPoint2f(),plane=new MatOfPoint2f(),projected=new MatOfPoint2f(),origin=new MatOfPoint2f();
        MatOfPoint3f object=new MatOfPoint3f(),zero=new MatOfPoint3f(new Point3(0,0,0));Mat transform=null,camera=c.toOpenCvCameraMatrix(),rotation=new Mat(),translation=new Mat(),matrix=new Mat();MatOfDouble distortion=c.toDistortionMatrix();
        try{
            List<VisionPoint> corners=fit.source.getPlanarCornersNormalized();Point[] quad=new Point[4];for(int i=0;i<4;i++)quad[i]=new Point(corners.get(i).getX()*fit.width,corners.get(i).getY()*fit.height);
            double w=fit.source.getPhysicalWidthMm()/2,h=fit.source.getPhysicalHeightMm()/2;
            from.fromArray(quad);to.fromArray(new Point(-w,h),new Point(w,h),new Point(w,-h),new Point(-w,-h));transform=Imgproc.getPerspectiveTransform(from,to);
            List<Point> src=new ArrayList<>(),dst=new ArrayList<>();for(int i=0;i<fit.reference.size();i++){src.add(fit.reference.get(i));VisionPoint p=c.deskew(fit.observed.get(i));dst.add(new Point(p.getX(),p.getY()));}
            source.fromList(src);Core.perspectiveTransform(source,plane,transform);List<Point3> xyz=new ArrayList<>();for(Point p:plane.toArray())xyz.add(new Point3(p.x,p.y,0));object.fromList(xyz);image.fromList(dst);
            if(!Calib3d.solvePnP(object,image,camera,distortion,rotation,translation,false,Calib3d.SOLVEPNP_ITERATIVE))return null;
            double[] r=new double[3],t=new double[3],m=new double[9];rotation.get(0,0,r);translation.get(0,0,t);Calib3d.Rodrigues(rotation,matrix);matrix.get(0,0,m);
            for(double v:r)if(!Double.isFinite(v))return null;for(double v:t)if(!Double.isFinite(v))return null;
            for(Point3 p:xyz)if(m[6]*p.x+m[7]*p.y+t[2]<=0)return null;
            Calib3d.projectPoints(object,rotation,translation,camera,distortion,projected);Point[] predicted=projected.toArray();double sum=0;
            for(int i=0;i<predicted.length;i++){double dx=predicted[i].x-dst.get(i).x,dy=predicted[i].y-dst.get(i).y;sum+=dx*dx+dy*dy;}
            double rms=Math.sqrt(sum/predicted.length);if(!Double.isFinite(rms)||rms>VisionConfig.defaults().getMaximumPoseReprojectionErrorPx())return null;
            Calib3d.projectPoints(zero,rotation,translation,camera,distortion,origin);Point o=origin.toArray()[0];double[] k=c.getCameraMatrixRowMajor();double rawX=o.x+k[1]*(o.y-k[5])/k[4];
            float[] gl={(float)m[0],(float)-m[3],(float)-m[6],0,(float)m[1],(float)-m[4],(float)-m[7],0,(float)m[2],(float)-m[5],(float)-m[8],0,(float)t[0],(float)-t[1],(float)-t[2],1};
            return new PoseFit(new RecognitionResult.Pose(r,t,gl,rms),m,new VisionPoint(rawX,o.y));
        }catch(RuntimeException unsupported){return null;}
        finally{image.release();from.release();to.release();source.release();plane.release();projected.release();origin.release();object.release();zero.release();if(transform!=null)transform.release();camera.release();rotation.release();translation.release();matrix.release();distortion.release();}
    }
}
