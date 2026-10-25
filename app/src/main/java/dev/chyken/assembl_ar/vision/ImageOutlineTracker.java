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
    private Workspace workspace;
    /** Detector/tracker-owned reusable native headers and packed point buffers. */
    private static final class Workspace implements AutoCloseable {
        final MatOfPoint2f forward=new MatOfPoint2f(),back=new MatOfPoint2f(),src=new MatOfPoint2f(),dst=new MatOfPoint2f(),qsrc=new MatOfPoint2f(),qdst=new MatOfPoint2f();
        final MatOfByte sf=new MatOfByte(),sb=new MatOfByte();
        final MatOfFloat ef=new MatOfFloat(),eb=new MatOfFloat();final Mat inliers=new Mat();
        float[] old=new float[160],next=new float[160],round=new float[160],error=new float[80],a=new float[160],b=new float[160],keep=new float[160];
        byte[] ok=new byte[80],reverse=new byte[80];final float[] quad=new float[32],moved=new float[32];
        void capacity(int n){if(old.length>=n*2)return;old=new float[n*2];next=new float[n*2];round=new float[n*2];error=new float[n];a=new float[n*2];b=new float[n*2];keep=new float[n*2];ok=new byte[n];reverse=new byte[n];}
        public void close(){forward.release();back.release();src.release();dst.release();qsrc.release();qdst.release();sf.release();sb.release();ef.release();eb.release();inliers.release();}
    }
    private static void points(Mat mat,float[] values,int count){mat.create(count,1,CvType.CV_32FC2);mat.put(0,0,values);}

    public synchronized boolean hasOutline(){return id!=null;}
    synchronized long latestTimestampNanos(){return latestAt;}
    public synchronized void reset(){releaseTracking();if(latest!=null)latest.release();latest=null;latestAt=Long.MIN_VALUE;if(workspace!=null)workspace.close();workspace=null;}
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
                // Small/dark PCBs can have strong ORB geometry but fewer than eight
                // widely spaced Shi-Tomasi corners. Denser seeds remain subject to
                // the identical forward/backward LK and RANSAC checks below.
                if(found.rows()<8)Imgproc.goodFeaturesToTrack(gray,found,80,.0025,1,mask,3,false,.04);
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
        if(latest==null)latest=new Mat();gray.copyTo(latest);latestAt=time;
        return id==null?null:advance(gray,time);
    }
    private Outline advance(Mat gray,long time){
        return advance(gray,time,false);
    }
    private Outline advance(Mat gray,long time,boolean catchingUp){
        if(previous==null||points==null||points.rows()<8||time<=at||!catchingUp&&time-at>600_000_000L||previous.size().equals(gray.size())==false){releaseTracking();return null;}
        if(workspace==null)workspace=new Workspace();Workspace w=workspace;Mat h=null;
        try{
            int count=points.rows();w.capacity(count);
            TermCriteria criteria=new TermCriteria(TermCriteria.COUNT|TermCriteria.EPS,20,.02);
            Video.calcOpticalFlowPyrLK(previous,gray,points,w.forward,w.sf,w.ef,new Size(21,21),3,criteria,0,1e-4);
            if(w.forward.empty()){releaseTracking();return null;}
            Video.calcOpticalFlowPyrLK(gray,previous,w.forward,w.back,w.sb,w.eb,new Size(21,21),3,criteria,0,1e-4);
            count=Math.min(count,Math.min(w.forward.rows(),Math.min(w.back.rows(),Math.min((int)w.sf.total(),Math.min((int)w.sb.total(),(int)w.ef.total())))));
            if(count<8){releaseTracking();return null;}
            points.get(0,0,w.old);w.forward.get(0,0,w.next);w.back.get(0,0,w.round);w.sf.get(0,0,w.ok);w.sb.get(0,0,w.reverse);w.ef.get(0,0,w.error);
            int good=0;
            for(int i=0;i<count;i++){
                int k=i*2;float x=w.next[k],y=w.next[k+1];
                if(w.ok[i]!=0&&w.reverse[i]!=0&&w.error[i]<40&&Math.hypot(w.old[k]-w.round[k],w.old[k+1]-w.round[k+1])<2&&x>=0&&y>=0&&x<gray.cols()&&y<gray.rows()){
                    w.a[good*2]=w.old[k];w.a[good*2+1]=w.old[k+1];w.b[good*2]=x;w.b[good*2+1]=y;good++;
                }
            }
            if(good<8){releaseTracking();return null;}
            points(w.src,w.a,good);points(w.dst,w.b,good);h=Calib3d.findHomography(w.src,w.dst,Calib3d.RANSAC,2.5,w.inliers,500,.99);
            int required=catchingUp?Math.min(12,good):8,in=h.empty()?0:Core.countNonZero(w.inliers);
            if(in<required||in<good*(catchingUp?.7:.6)){releaseTracking();return null;}
            for(int i=0;i<quad.size();i++){w.quad[i*2]=(float)quad.get(i).getX();w.quad[i*2+1]=(float)quad.get(i).getY();}
            points(w.qsrc,w.quad,quad.size());Core.perspectiveTransform(w.qsrc,w.qdst,h);w.qdst.get(0,0,w.moved);
            for(int first=0;first<quad.size();first+=4){
                double oldArea=area(quad,first),newArea=area(w.moved,first);
                if(!Double.isFinite(newArea)||oldArea<=0||newArea/oldArea<.5||newArea/oldArea>2){releaseTracking();return null;}
            }
            List<VisionPoint> output=new ArrayList<>(quad.size());
            for(int i=0;i<quad.size();i++){
                double x=w.moved[i*2],y=w.moved[i*2+1];
                if(!Double.isFinite(x+y)||x< -gray.cols()*.5||y< -gray.rows()*.5||x>=gray.cols()*1.5||y>=gray.rows()*1.5||Math.hypot(x-quad.get(i).getX(),y-quad.get(i).getY())>Math.min(gray.cols(),gray.rows())*.35){releaseTracking();return null;}
                output.add(new VisionPoint(x,y));
            }
            w.inliers.get(0,0,w.ok);int kept=0;for(int i=0;i<good;i++)if(w.ok[i]!=0){w.keep[kept*2]=w.b[i*2];w.keep[kept*2+1]=w.b[i*2+1];kept++;}
            points(points,w.keep,kept);gray.copyTo(previous);quad=output;at=time;
            return new Outline(id,label,quad,time);
        }catch(RuntimeException failure){releaseTracking();throw failure;}
        finally{if(h!=null)h.release();}
    }
    private static double area(List<VisionPoint> p,int first){double a=0;for(int i=0;i<4;i++){VisionPoint x=p.get(first+i),y=p.get(first+(i+1)%4);a+=x.getX()*y.getY()-y.getX()*x.getY();}return Math.abs(a)/2;}
    private static double area(float[] p,int first){double a=0;for(int i=0;i<4;i++){int x=(first+i)*2,y=(first+(i+1)%4)*2;a+=(double)p[x]*p[y+1]-(double)p[y]*p[x+1];}return Math.abs(a)/2;}
    private static Point[] toPoints(List<VisionPoint> p){Point[] out=new Point[p.size()];for(int i=0;i<out.length;i++)out[i]=new Point(p.get(i).getX(),p.get(i).getY());return out;}
    @Override public synchronized void close(){closed=true;reset();}
}
