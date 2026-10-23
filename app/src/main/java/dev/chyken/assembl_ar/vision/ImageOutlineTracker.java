package dev.chyken.assembl_ar.vision;

import java.util.*;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;
import org.opencv.calib3d.Calib3d;
import org.opencv.video.Video;

/** Image-space tracking for photo references that deliberately have NO metric pose.
 * Every moving outline requires measured forward/backward LK support. No static or IMU
 * screen-space hold is displayed after flow fails, and no flow result verifies a step. */
public final class ImageOutlineTracker implements AutoCloseable {
    public static final class Outline {
        public final String componentId,label;
        public final List<VisionPoint> corners;
        public final List<List<VisionPoint>> regions;
        public final long timestamp;
        Outline(String id,String label,List<VisionPoint> corners,long time){componentId=id;this.label=label;List<List<VisionPoint>> boxes=new ArrayList<>();for(int i=0;i<corners.size();i+=4)boxes.add(Collections.unmodifiableList(new ArrayList<>(corners.subList(i,i+4))));regions=Collections.unmodifiableList(boxes);this.corners=regions.get(0);timestamp=time;}
    }
    private Mat previous,latest;
    private MatOfPoint2f points;
    private List<VisionPoint> quad=Collections.emptyList();
    private String id,label;
    private long at=Long.MIN_VALUE,latestAt=Long.MIN_VALUE;
    private boolean closed;
    public synchronized boolean hasOutline(){return id!=null;}
    public synchronized void reset(){releaseTracking();if(latest!=null)latest.release();latest=null;latestAt=Long.MIN_VALUE;}
    private void releaseTracking(){if(previous!=null)previous.release();if(points!=null)points.release();previous=null;points=null;quad=Collections.emptyList();id=null;at=Long.MIN_VALUE;}
    public synchronized Outline detect(Mat gray,long time,String component,String name,List<VisionPoint> corners){
        return corners.size()!=4?null:detectRegions(gray,time,component,name,Collections.singletonList(corners));
    }
    /** Multiple rigidly mounted components share camera-plane motion, but retain distinct outlines. */
    public synchronized Outline detectRegions(Mat gray,long time,String component,String name,List<List<VisionPoint>> regions){
        if(closed||component==null||regions.isEmpty()||regions.size()>4)return null;
        List<VisionPoint> flattened=new ArrayList<>();for(List<VisionPoint> region:regions){if(region.size()!=4)return null;flattened.addAll(region);}
        releaseTracking();id=component;label=name==null?"Component":name;quad=flattened;at=time;
        Mat mask=Mat.zeros(gray.size(),CvType.CV_8UC1);MatOfPoint found=new MatOfPoint();
        try{
            for(List<VisionPoint> region:regions){MatOfPoint polygon=new MatOfPoint(toPoints(region));try{Imgproc.fillConvexPoly(mask,polygon,new Scalar(255));}finally{polygon.release();}}
            Mat solid=new Mat(),filtered=new Mat(),kernel=Imgproc.getStructuringElement(Imgproc.MORPH_RECT,new Size(3,3));
            try{
                Imgproc.threshold(gray,solid,0,255,Imgproc.THRESH_BINARY_INV|Imgproc.THRESH_OTSU);
                Imgproc.morphologyEx(solid,solid,Imgproc.MORPH_OPEN,kernel);Imgproc.morphologyEx(solid,solid,Imgproc.MORPH_CLOSE,kernel);Imgproc.dilate(solid,solid,kernel);
                Core.bitwise_and(mask,solid,filtered);
                Imgproc.goodFeaturesToTrack(gray,found,80,.012,3,filtered,5,false,.04);
                if(found.rows()<8)Imgproc.goodFeaturesToTrack(gray,found,80,.012,3,mask,5,false,.04);
            }finally{solid.release();filtered.release();kernel.release();}
            points=new MatOfPoint2f(found.toArray());if(points.rows()<8){releaseTracking();return null;}previous=gray.clone();
            // ORB runs on a detached frame. Its result may be several seconds old,
            // although intermediate camera frames have continued arriving. Re-localize
            // that measured body in the newest image instead of discarding every seed
            // at the normal 600 ms inter-frame tracking limit. This still requires
            // forward/backward LK and a fresh RANSAC fit; it is never a static hold.
            if(latest!=null&&latestAt>time)return advance(latest,latestAt,true);
            return new Outline(id,label,quad,time);
        }finally{mask.release();found.release();}
    }
    public synchronized Outline frame(Mat gray,long time){
        if(closed||time<=latestAt)return null;
        if(latest!=null)latest.release();latest=gray.clone();latestAt=time;
        return id==null?null:advance(gray,time);
    }
    private Outline advance(Mat gray,long time){
        return advance(gray,time,false);
    }
    private Outline advance(Mat gray,long time,boolean catchingUp){
        if(previous==null||points==null||points.rows()<8||time<=at||!catchingUp&&time-at>600_000_000L||previous.size().equals(gray.size())==false){releaseTracking();return null;}
        MatOfPoint2f forward=new MatOfPoint2f(),back=new MatOfPoint2f();MatOfByte sf=new MatOfByte(),sb=new MatOfByte();MatOfFloat ef=new MatOfFloat(),eb=new MatOfFloat();MatOfPoint2f src=new MatOfPoint2f(),dst=new MatOfPoint2f(),qsrc=new MatOfPoint2f(),qdst=new MatOfPoint2f();Mat inliers=new Mat(),h=null;
        try{
            TermCriteria criteria=new TermCriteria(TermCriteria.COUNT|TermCriteria.EPS,20,.02);
            Video.calcOpticalFlowPyrLK(previous,gray,points,forward,sf,ef,new Size(21,21),3,criteria,0,1e-4);
            if(forward.empty()){releaseTracking();return null;}
            Video.calcOpticalFlowPyrLK(gray,previous,forward,back,sb,eb,new Size(21,21),3,criteria,0,1e-4);
            Point[] old=points.toArray(),next=forward.toArray(),round=back.toArray();byte[] ok=sf.toArray(),reverse=sb.toArray();float[] error=ef.toArray();
            List<Point> a=new ArrayList<>(),b=new ArrayList<>();
            for(int i=0;i<Math.min(old.length,Math.min(next.length,round.length));i++)if(ok[i]!=0&&reverse[i]!=0&&error[i]<40&&Math.hypot(old[i].x-round[i].x,old[i].y-round[i].y)<2&&next[i].x>=0&&next[i].y>=0&&next[i].x<gray.cols()&&next[i].y<gray.rows()){a.add(old[i]);b.add(next[i]);}
            if(a.size()<8){releaseTracking();return null;}
            src.fromList(a);dst.fromList(b);h=Calib3d.findHomography(src,dst,Calib3d.RANSAC,2.5,inliers,500,.99);
            if(h.empty()||Core.countNonZero(inliers)<(catchingUp?12:8)||Core.countNonZero(inliers)<a.size()*(catchingUp?.7:.6)){releaseTracking();return null;}
            qsrc.fromArray(toPoints(quad));Core.perspectiveTransform(qsrc,qdst,h);Point[] moved=qdst.toArray();
            for(int first=0;first<quad.size();first+=4){
                double oldArea=area(toPoints(quad.subList(first,first+4))),newArea=area(Arrays.copyOfRange(moved,first,first+4));
                if(!Double.isFinite(newArea)||oldArea<=0||newArea/oldArea<.5||newArea/oldArea>2){releaseTracking();return null;}
            }
            List<VisionPoint> output=new ArrayList<>();
            for(int i=0;i<quad.size();i++){
                Point p=moved[i];if(!Double.isFinite(p.x+p.y)||p.x< -gray.cols()*.5||p.y< -gray.rows()*.5||p.x>=gray.cols()*1.5||p.y>=gray.rows()*1.5||Math.hypot(p.x-quad.get(i).getX(),p.y-quad.get(i).getY())>Math.min(gray.cols(),gray.rows())*.35){releaseTracking();return null;}
                output.add(new VisionPoint(p.x,p.y));
            }
            List<Point> keep=new ArrayList<>();for(int i=0;i<b.size();i++)if(inliers.get(i,0)[0]!=0)keep.add(b.get(i));
            points.release();points=new MatOfPoint2f();points.fromList(keep);previous.release();previous=gray.clone();quad=output;at=time;
            return new Outline(id,label,quad,time);
        }finally{forward.release();back.release();sf.release();sb.release();ef.release();eb.release();src.release();dst.release();qsrc.release();qdst.release();inliers.release();if(h!=null)h.release();}
    }
    private static Point[] toPoints(List<VisionPoint> p){Point[] out=new Point[p.size()];for(int i=0;i<out.length;i++)out[i]=new Point(p.get(i).getX(),p.get(i).getY());return out;}
    private static double area(Point[] p){double a=0;for(int i=0;i<p.length;i++){Point n=p[(i+1)%p.length];a+=p[i].x*n.y-n.x*p[i].y;}return Math.abs(a)/2;}
    @Override public synchronized void close(){closed=true;reset();}
}
