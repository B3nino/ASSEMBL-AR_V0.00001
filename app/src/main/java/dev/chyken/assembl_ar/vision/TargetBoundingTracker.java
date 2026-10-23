package dev.chyken.assembl_ar.vision;

import java.util.*;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;

/** Shared visual tracker for both cameras. Identity is supplied by geometric reference
 * matching; morphology/flow/prediction can never supply assembly completion evidence. */
public final class TargetBoundingTracker implements AutoCloseable {
    public enum State { ACQUIRING, LOCKED, WRONG }
    public static final long MAX_PERSISTENCE_NS=1_500_000_000L;
    public static final class Target {
        public final String componentId,name,expectedName;public final State state;
        public final List<List<VisionPoint>> regions;public final boolean predicted,verified;
        public final long timestamp,epoch;
        Target(String id,String name,String expected,State state,List<List<VisionPoint>> regions,boolean predicted,boolean verified,long time,long epoch){
            componentId=id;this.name=name;expectedName=expected;this.state=state;this.regions=freeze(regions);this.predicted=predicted;this.verified=verified;timestamp=time;this.epoch=epoch;
        }
        private static List<List<VisionPoint>> freeze(List<List<VisionPoint>> input){List<List<VisionPoint>> out=new ArrayList<>();for(List<VisionPoint> p:input)out.add(Collections.unmodifiableList(new ArrayList<>(p)));return Collections.unmodifiableList(out);}
    }
    private final ImageOutlineTracker flow=new ImageOutlineTracker();
    private Target measured,displayed;private volatile long epoch;private double vx,vy;private boolean closed;
    // Called by the UI for stale-frame rejection. Never wait for native LK/morphology.
    public long epoch(){return epoch;}
    public synchronized void reset(){epoch++;flow.reset();measured=displayed=null;vx=vy=0;}
    public synchronized boolean hasTarget(){return measured!=null;}
    public synchronized Target detect(Mat gray,long time,String id,String name,String expected,State state,List<List<VisionPoint>> regions,boolean verified){
        if(closed||regions.isEmpty())return null;
        // Slow ORB results must not drag a live LK outline back to an old camera frame.
        if(measured!=null&&measured.timestamp>time){
            if(measured.componentId.equals(id)){
                measured=new Target(id,name,expected,state,measured.regions,false,verified,measured.timestamp,epoch);
                displayed=new Target(id,name,expected,state,displayed==null?measured.regions:displayed.regions,false,verified,measured.timestamp,epoch);
            }return displayed;
        }
        List<List<VisionPoint>> bodies=new ArrayList<>();for(List<VisionPoint> region:regions)if(region.size()==4&&bodies.size()<4)bodies.add(LoosePartShapeRecognizer.contourProfile(id)==ComponentContourTemplates.Body.CABLE?region:rigidBodyBounds(gray,region));
        if(bodies.isEmpty())return null;
        ImageOutlineTracker.Outline located=flow.detectRegions(gray,time,id,name,bodies);
        if(located==null)return null;
        if(measured!=null&&(!id.equals(measured.componentId)||measured.regions.size()!=located.regions.size())){displayed=null;vx=vy=0;}
        return accept(new Target(id,name,expected,state,located.regions,false,verified,located.timestamp,epoch));
    }
    public synchronized Target frame(Mat gray,long time,OpticalFlowTrackerBridge.Motion motion){
        if(closed)return null;
        ImageOutlineTracker.Outline moved=flow.frame(gray,time);
        if(moved!=null&&measured!=null)return accept(new Target(measured.componentId,measured.name,measured.expectedName,measured.state,moved.regions,false,measured.verified,time,epoch));
        if(measured==null||time<measured.timestamp)return null;
        long age=time-measured.timestamp;
        boolean imu=motion!=null&&motion.fresh(time);
        if(age>MAX_PERSISTENCE_NS||!imu&&age>250_000_000L){measured=displayed=null;return null;}
        double dt=age/1e9,seconds=(1-Math.exp(-dt*2))/2,dx=vx*seconds,dy=vy*seconds,roll=0;
        if(imu){dx=.5*dx+.5*motion.dxPerSecond*dt;dy=.5*dy+.5*motion.dyPerSecond*dt;roll=motion.rollPerSecond*dt;}
        if(Math.abs(roll)>.55){measured=displayed=null;return null;}
        List<List<VisionPoint>> predicted=new ArrayList<>();double cx=gray.cols()/2.0,cy=gray.rows()/2.0,c=Math.cos(roll),s=Math.sin(roll);
        for(List<VisionPoint> region:measured.regions){List<VisionPoint> p=new ArrayList<>();for(VisionPoint v:region){double x=v.getX()-cx,y=v.getY()-cy;p.add(new VisionPoint(cx+c*x-s*y+dx,cy+s*x+c*y+dy));}predicted.add(p);}
        displayed=new Target(measured.componentId,measured.name,measured.expectedName,measured.state,predicted,true,false,time,epoch);return displayed;
    }
    private Target accept(Target next){
        if(measured!=null&&next.timestamp>measured.timestamp&&next.componentId.equals(measured.componentId)){
            double dt=(next.timestamp-measured.timestamp)/1e9;VisionPoint a=center(measured.regions.get(0)),b=center(next.regions.get(0));vx=.5*vx+.5*(b.getX()-a.getX())/dt;vy=.5*vy+.5*(b.getY()-a.getY())/dt;
        }
        measured=next;List<List<VisionPoint>> smooth=next.regions;
        if(displayed!=null&&!displayed.predicted&&next.componentId.equals(displayed.componentId)&&next.regions.size()==displayed.regions.size()){
            smooth=new ArrayList<>();for(int r=0;r<next.regions.size();r++){List<VisionPoint> p=new ArrayList<>();for(int i=0;i<4;i++){
                VisionPoint old=displayed.regions.get(r).get(i),now=next.regions.get(r).get(i);double delta=Math.hypot(now.getX()-old.getX(),now.getY()-old.getY());
                // Ordinary corner noise uses alpha=.25; fast pans snap to measured geometry.
                double alpha=delta>20?1:.25;p.add(new VisionPoint(old.getX()+alpha*(now.getX()-old.getX()),old.getY()+alpha*(now.getY()-old.getY())));
            }smooth.add(p);}
        }
        displayed=new Target(next.componentId,next.name,next.expectedName,next.state,smooth,false,next.verified,next.timestamp,epoch);return displayed;
    }
    private static VisionPoint center(List<VisionPoint> p){double x=0,y=0;for(VisionPoint v:p){x+=v.getX()/p.size();y+=v.getY()/p.size();}return new VisionPoint(x,y);}
    /** Hull and rotated bounds of an ORB-supported cluster, constrained to an authored body ROI. */
    public static List<VisionPoint> clusterBounds(List<VisionPoint> cluster){
        if(cluster.size()<3)return Collections.emptyList();MatOfPoint input=new MatOfPoint();MatOfInt indices=new MatOfInt();MatOfPoint2f hull=new MatOfPoint2f();
        try{Point[] p=new Point[cluster.size()];for(int i=0;i<p.length;i++)p[i]=new Point(cluster.get(i).getX(),cluster.get(i).getY());input.fromArray(p);Imgproc.convexHull(input,indices);int[] ids=indices.toArray();Point[] h=new Point[ids.length];for(int i=0;i<ids.length;i++)h[i]=p[ids[i]];hull.fromArray(h);Point[] corners=new Point[4];Imgproc.minAreaRect(hull).points(corners);return ordered(corners);
        }finally{input.release();indices.release();hull.release();}
    }
    /** Thin wires never expand the reference-projected rigid-body boundary. */
    public static List<VisionPoint> rigidBodyBounds(Mat gray,List<VisionPoint> authored){
        if(authored.size()!=4)return authored;
        double l=gray.cols(),t=gray.rows(),r=0,b=0;for(VisionPoint p:authored){l=Math.min(l,p.getX());t=Math.min(t,p.getY());r=Math.max(r,p.getX());b=Math.max(b,p.getY());}
        int x=Math.max(0,(int)l-3),y=Math.max(0,(int)t-3),w=Math.min(gray.cols(),(int)Math.ceil(r)+4)-x,h=Math.min(gray.rows(),(int)Math.ceil(b)+4)-y;
        if(w<8||h<8)return authored;
        Mat crop=gray.submat(new Rect(x,y,w,h)),blur=new Mat(),solid=new Mat(),edges=new Mat(),hierarchy=new Mat(),mask=Mat.zeros(h,w,CvType.CV_8UC1);
        Mat kernel=Imgproc.getStructuringElement(Imgproc.MORPH_RECT,new Size(3,3));List<MatOfPoint> contours=new ArrayList<>();MatOfPoint roi=new MatOfPoint();
        try{
            Point[] vertices=new Point[4];for(int i=0;i<4;i++)vertices[i]=new Point(authored.get(i).getX()-x,authored.get(i).getY()-y);roi.fromArray(vertices);Imgproc.fillConvexPoly(mask,roi,new Scalar(255));
            Imgproc.GaussianBlur(crop,blur,new Size(3,3),.7);Imgproc.threshold(blur,solid,0,255,Imgproc.THRESH_BINARY_INV|Imgproc.THRESH_OTSU);
            Imgproc.morphologyEx(solid,solid,Imgproc.MORPH_OPEN,kernel);Imgproc.morphologyEx(solid,solid,Imgproc.MORPH_CLOSE,kernel);
            Imgproc.Canny(blur,edges,50,130);Core.bitwise_and(edges,solid,edges);Core.bitwise_and(edges,mask,edges);Imgproc.morphologyEx(edges,edges,Imgproc.MORPH_CLOSE,kernel);
            Imgproc.findContours(edges,contours,hierarchy,Imgproc.RETR_EXTERNAL,Imgproc.CHAIN_APPROX_SIMPLE);
            double area=Math.abs(Imgproc.contourArea(roi));MatOfPoint best=null;double largest=0;
            for(MatOfPoint c:contours){double a=Math.abs(Imgproc.contourArea(c));if(a>largest&&a>=area*.70&&a<=area*1.02){largest=a;best=c;}}
            if(best!=null){List<VisionPoint> points=new ArrayList<>();for(Point p:best.toArray())points.add(new VisionPoint(p.x+x,p.y+y));List<VisionPoint> bounds=clusterBounds(points);
                // Keep perspective registration whenever a rotated rectangle would change it.
                double deviation=0;for(int i=0;i<4;i++)deviation+=Math.hypot(bounds.get(i).getX()-authored.get(i).getX(),bounds.get(i).getY()-authored.get(i).getY());
                if(deviation<Math.sqrt(area)*.25)return bounds;
            }
            return authored;
        }finally{crop.release();blur.release();solid.release();edges.release();hierarchy.release();mask.release();kernel.release();roi.release();for(Mat c:contours)c.release();}
    }
    private static List<VisionPoint> ordered(Point[] points){Point center=new Point();for(Point p:points){center.x+=p.x/4;center.y+=p.y/4;}Arrays.sort(points,Comparator.comparingDouble(p->Math.atan2(p.y-center.y,p.x-center.x)));int start=0;for(int i=1;i<4;i++)if(points[i].x+points[i].y<points[start].x+points[start].y)start=i;List<VisionPoint> out=new ArrayList<>();for(int i=0;i<4;i++){Point p=points[(start+i)%4];out.add(new VisionPoint(p.x,p.y));}return out;}
    @Override public synchronized void close(){closed=true;reset();flow.close();}
}
