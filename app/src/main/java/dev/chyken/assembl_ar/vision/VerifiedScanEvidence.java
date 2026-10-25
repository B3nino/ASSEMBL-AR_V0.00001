package dev.chyken.assembl_ar.vision;

import java.util.*;
import org.opencv.core.*;
import org.opencv.calib3d.Calib3d;
import org.opencv.imgproc.Imgproc;

/** Values belonging to ONE selected RANSAC fit, never a shortlist/probe winner. */
public final class VerifiedScanEvidence {
    public final ComponentReferenceSpec source;
    public final String componentName;
    public final int good,total,inliers,liveKeypoints;
    public final RecognitionResult.Pose pose;
    public final PoseOrientation orientation;
    public final VisionPoint projectedOrigin,observedCentroid;
    public final double extractionMs,matchingMs;
    VerifiedScanEvidence(DiagnosticHammingMatcher.Fit fit,DiagnosticHammingMatcher.State state,
            CameraCalibration calibration,DiagnosticHammingMatcher.Fit context,String name){
        source=fit.source;componentName=name==null?source.getDisplayName():name;good=fit.good;total=fit.total;inliers=fit.observed.size();
        liveKeypoints=state.uniqueLive;extractionMs=state.featureNs/1e6;
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
