package dev.chyken.assembl_ar.vision;

import android.content.Context;import android.hardware.*;import android.os.*;import java.util.*;import org.opencv.core.Mat;

/** Visual-only hybrid bridge. Predictions never enter recognition or completion evidence.
 * LK carries measured corners; a fresh gyroscope bounds last-flow velocity prediction on dropout.
 * This is short-term screen-space prediction, not an inertial world anchor or SLAM system. */
public final class OpticalFlowTrackerBridge implements AutoCloseable,SensorEventListener {
    public static final long MAX_PERSISTENCE_NS=1_500_000_000L;
    private final AssemblyAnchorTracker tracker=new AssemblyAnchorTracker();
    private Mat latestGray;private long latestTime=Long.MIN_VALUE;private CameraCalibration latestCalibration;
    private RecognitionResult.SpatialAnchor last;private long measuredAt=Long.MIN_VALUE,outputAt,detectedAt=Long.MIN_VALUE;private boolean prediction;
    private double vx,vy;
    private static final class GyroSample {
        static final GyroSample EMPTY=new GyroSample(0,0,0,0);
        final double x,y,z,speed;final long at;
        GyroSample(double x,double y,double z,long at){this.x=x;this.y=y;this.z=z;this.at=at;speed=Math.sqrt(x*x+y*y+z*z);}
    }
    private volatile GyroSample gyro=GyroSample.EMPTY;
    private volatile boolean imuEnabled;
    private volatile long imuSessionStart=Long.MAX_VALUE;
    private GyroSample motionSample(){GyroSample sample=gyro;return imuEnabled&&sample.at>=imuSessionStart?sample:GyroSample.EMPTY;}

    private boolean closed;
    private SensorManager sensors;private HandlerThread thread;
    public synchronized void attach(Context c){
        if(closed||sensors!=null)return;sensors=(SensorManager)c.getSystemService(Context.SENSOR_SERVICE);Sensor sensor=sensors==null?null:sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        if(sensor!=null){
            imuSessionStart=SystemClock.elapsedRealtimeNanos();gyro=GyroSample.EMPTY;
            thread=new HandlerThread("ar-motion");thread.start();
            imuEnabled=sensors.registerListener(this,sensor,SensorManager.SENSOR_DELAY_GAME,new Handler(thread.getLooper()));
            if(!imuEnabled){thread.quitSafely();thread=null;}
        }
    }
    public synchronized void reset(){if(closed)return;tracker.reset();last=null;measuredAt=Long.MIN_VALUE;detectedAt=Long.MIN_VALUE;prediction=false;vx=vy=0;}
    public synchronized boolean hasAnchor(){return last!=null;}
    public synchronized boolean predicted(){return prediction;}
    public synchronized RecognitionResult.SpatialAnchor update(Mat gray,long time,RecognitionResult.SpatialAnchor detection,CameraCalibration calibration){
        if(closed)return null;
        if(detection!=null&&latestGray!=null&&latestTime>time){
            tracker.reset();tracker.update(gray,time,detection,calibration);detectedAt=time;
            RecognitionResult.SpatialAnchor carried=tracker.update(latestGray,latestTime,null,latestCalibration);
            if(carried!=null&&!tracker.isHeld()){last=carried;measuredAt=latestTime;outputAt=latestTime;prediction=false;return carried;}
            last=detection;measuredAt=time;outputAt=time;
            gray=latestGray;time=latestTime;calibration=latestCalibration;detection=null;
        }
        prediction=false;if(detection!=null)detectedAt=time;
        if(detectedAt!=Long.MIN_VALUE&&time-detectedAt>MAX_PERSISTENCE_NS){reset();return null;}
        RecognitionResult.SpatialAnchor next=tracker.update(gray,time,detection,calibration);
        if(next!=null&&!tracker.isHeld()){
            if(last!=null&&time>measuredAt&&last.getComponentId().equals(next.getComponentId())){double dt=(time-measuredAt)/1e9;VisionPoint a=center(last),b=center(next);vx=.5*vx+.5*(b.getX()-a.getX())/dt;vy=.5*vy+.5*(b.getY()-a.getY())/dt;}
            last=next;measuredAt=time;outputAt=time;return next;
        }
        if(last==null||time<=outputAt||time-measuredAt>MAX_PERSISTENCE_NS){reset();return null;}
        // Without a recent gyro sample use a short static hold, not invented inertial motion.
        GyroSample motion=motionSample();boolean imu=motion.at>0&&Math.abs(time-motion.at)<250_000_000L;
        if(!imu&&time-measuredAt>500_000_000L||imu&&motion.speed>1.2){reset();return null;}
        prediction=true;
        double dt=(time-measuredAt)/1e9;double decayedSeconds=(1-Math.exp(-dt*2))/2;double dx=imu?vx*decayedSeconds:0,dy=imu?vy*decayedSeconds:0;
        double max=Math.min(gray.cols(),gray.rows())*.15;double length=Math.hypot(dx,dy);if(length>max){dx*=max/length;dy*=max/length;}
        List<VisionPoint> corners=new ArrayList<>();for(VisionPoint p:last.getProjectedCorners())corners.add(new VisionPoint(p.getX()+dx,p.getY()+dy));
        RecognitionResult.Pose pose=last.getPose();double[] t=pose.getTranslationVector();float[] matrix=pose.getOpenGlModelViewMatrix();if(calibration!=null){double[] k=calibration.getCameraMatrixRowMajor();t[0]+=dx*t[2]/k[0];t[1]+=dy*t[2]/k[4];matrix[12]=(float)t[0];matrix[13]=(float)-t[1];}
        outputAt=time;return new RecognitionResult.SpatialAnchor(last.getReferenceId(),last.getComponentId(),corners,new RecognitionResult.Pose(pose.getRotationVector(),t,matrix,Double.NaN));
    }
    public synchronized RecognitionResult.SpatialAnchor trackLatest(Mat gray,long time,CameraCalibration calibration){
        if(closed||time<=latestTime)return null;
        if(latestGray==null)latestGray=new Mat();gray.copyTo(latestGray);latestTime=time;latestCalibration=calibration;
        if(last==null)return null;
        return update(gray,time,null,calibration);
    }
    /** Backgrounding releases camera images and unregisters motion, preserving the reusable bridge. */
    public synchronized void pause(){
        reset();if(latestGray!=null)latestGray.release();latestGray=null;latestTime=Long.MIN_VALUE;latestCalibration=null;imuEnabled=false;imuSessionStart=Long.MAX_VALUE;gyro=GyroSample.EMPTY;
        if(sensors!=null)sensors.unregisterListener(this);if(thread!=null)thread.quitSafely();thread=null;sensors=null;
    }
    private static VisionPoint center(RecognitionResult.SpatialAnchor a){double x=0,y=0;for(VisionPoint p:a.getProjectedCorners()){x+=p.getX()/4;y+=p.getY()/4;}return new VisionPoint(x,y);}
    public static final class Motion {
        public final long at;public final double dxPerSecond,dyPerSecond,rollPerSecond;
        public Motion(long at,double dx,double dy,double roll){this.at=at;dxPerSecond=dx;dyPerSecond=dy;rollPerSecond=roll;}
        public boolean fresh(long now){return at>0&&Math.abs(now-at)<250_000_000L;}
    }
    /** Gyro projection is a short image prediction, never a metric world anchor. */
    public Motion imageMotion(CameraCalibration calibration,int rotation){
        double focal=calibration==null?640:calibration.getCameraMatrixRowMajor()[0];
        GyroSample motion=motionSample();double x=-motion.y*focal,y=motion.x*focal,angle=Math.toRadians(rotation),c=Math.cos(angle),s=Math.sin(angle);
        return new Motion(motion.at,c*x-s*y,s*x+c*y,-motion.z);
    }
    @Override public void onSensorChanged(SensorEvent e){
        if(!imuEnabled||e.timestamp<imuSessionStart||e.values.length<3)return;
        GyroSample sample=new GyroSample(e.values[0],e.values[1],e.values[2],e.timestamp);
        if(imuEnabled&&sample.at>=imuSessionStart)gyro=sample;
    }
    @Override public void onAccuracyChanged(Sensor s,int accuracy){}
    @Override public synchronized void close(){if(closed)return;closed=true;imuEnabled=false;imuSessionStart=Long.MAX_VALUE;gyro=GyroSample.EMPTY;tracker.close();if(latestGray!=null)latestGray.release();latestGray=null;if(sensors!=null)sensors.unregisterListener(this);if(thread!=null)thread.quitSafely();thread=null;sensors=null;last=null;}
}
