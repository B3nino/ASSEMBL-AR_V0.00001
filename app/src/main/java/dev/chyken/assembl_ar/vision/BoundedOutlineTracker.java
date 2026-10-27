package dev.chyken.assembl_ar.vision;

import java.util.ArrayList;
import java.util.List;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

/** Keeps the existing LK/outline engine at <=640 pixels while detail inspection
 * consumes original camera pixels. All public geometry remains in camera coordinates.
 * No tracking result is promoted to assembly verification. */
final class BoundedOutlineTracker implements AutoCloseable {
    private final TargetBoundingTracker delegate=new TargetBoundingTracker();
    private final Mat sampled=new Mat();
    private int sourceWidth,sourceHeight;
    private boolean closed;
    private double sx=1,sy=1;
    long epoch(){return delegate.epoch();}
    synchronized boolean hasTarget(){return delegate.hasTarget();}
    synchronized void reset(){delegate.reset();sampled.release();sourceWidth=sourceHeight=0;sx=sy=1;}
    private Mat sample(Mat gray){
        if(sourceWidth!=0&&(sourceWidth!=gray.cols()||sourceHeight!=gray.rows()))reset();
        sourceWidth=gray.cols();sourceHeight=gray.rows();
        double scale=Math.min(1,LiveVisionPolicy.MAX_ANALYSIS_EDGE/(double)Math.max(gray.cols(),gray.rows()));
        if(scale==1){sx=sy=1;return gray;}
        Imgproc.resize(gray,sampled,new Size(Math.round(gray.cols()*scale),Math.round(gray.rows()*scale)),0,0,Imgproc.INTER_AREA);
        sx=gray.cols()/(double)sampled.cols();sy=gray.rows()/(double)sampled.rows();return sampled;
    }
    synchronized TargetBoundingTracker.Target detect(Mat gray,long time,String id,String name,String expected,TargetBoundingTracker.State state,List<List<VisionPoint>> regions,boolean verified){
        if(closed)return null;
        Mat input=sample(gray);
        return restore(delegate.detect(input,time,id,name,expected,state,sx==1&&sy==1?regions:map(regions,1/sx,1/sy),verified));
    }
    synchronized TargetBoundingTracker.Target frame(Mat gray,long time,OpticalFlowTrackerBridge.Motion motion){
        if(closed)return null;
        Mat input=sample(gray);
        OpticalFlowTrackerBridge.Motion adjusted=motion==null||sx==1&&sy==1?motion:new OpticalFlowTrackerBridge.Motion(motion.at,motion.dxPerSecond/sx,motion.dyPerSecond/sy,motion.rollPerSecond);
        return restore(delegate.frame(input,time,adjusted));
    }
    private TargetBoundingTracker.Target restore(TargetBoundingTracker.Target target){
        if(target==null||sx==1&&sy==1)return target;
        return new TargetBoundingTracker.Target(target.componentId,target.name,target.expectedName,target.state,map(target.regions,sx,sy),target.predicted,target.verified,target.timestamp,target.epoch);
    }
    private static List<List<VisionPoint>> map(List<List<VisionPoint>> regions,double x,double y){
        List<List<VisionPoint>> result=new ArrayList<>(regions.size());
        for(List<VisionPoint> region:regions){List<VisionPoint> points=new ArrayList<>(region.size());for(VisionPoint p:region)points.add(new VisionPoint(p.getX()*x,p.getY()*y));result.add(points);}return result;
    }
    @Override public synchronized void close(){closed=true;delegate.close();sampled.release();}
}
