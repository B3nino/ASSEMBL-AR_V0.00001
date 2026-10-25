package dev.chyken.assembl_ar.diagnostics;
import java.util.*;

/** Bounded, deterministic trial accumulator. Four lighting conditions and three motions are
 * separate strata. A trial is one fresh detection, never an intermediate predicted pose. */
public final class AcademicBenchmarkRunner {
    public static final String[] LIGHTS={"Normal","Bright","Weak","Uneven"};
    public static final String[] MOTIONS={"Static","Pan","Approach / retreat","Rotation"};
    private static final int FRAME_HISTORY=2048;
    private final Set<Long> capturedFrames=new LinkedHashSet<>(),computedFrames=new HashSet<>();
    private long newestFrame=Long.MIN_VALUE;
    public synchronized void frame(long timestamp){
        if(!running||timestamp<acceptAfter||timestamp<=newestFrame)return;
        newestFrame=timestamp;capturedFrames.add(timestamp);frame(false);
        if(capturedFrames.size()>FRAME_HISTORY){Iterator<Long> oldest=capturedFrames.iterator();Long expired=oldest.next();oldest.remove();computedFrames.remove(expired);}
    }
    synchronized int retainedFrameCount(){return capturedFrames.size();}
    public synchronized void computedFrame(long timestamp){if(running&&capturedFrames.contains(timestamp)&&computedFrames.add(timestamp))computedFrame();}
    private final List<DiagnosticSample> trials=new ArrayList<>();
    private final Map<String,int[]> lights=new LinkedHashMap<>();
    private final Map<String,double[]> motionError=new LinkedHashMap<>();
    public synchronized void overlay(double errorPx,double offsetMm){if(!running)return;double[] e=motionError.computeIfAbsent(motion,k->new double[]{0,0,0,Double.NaN});if(Double.isFinite(errorPx)){e[0]+=errorPx;e[1]++;e[2]=Math.max(e[2],errorPx);}if(Double.isFinite(offsetMm))e[3]=offsetMm;}
    private final Map<String,long[]> motionFrames=new LinkedHashMap<>();
    private String activeTarget;
    private final Map<String,String> reports=new LinkedHashMap<>();
    private boolean running;private int limit=30;private String motion=MOTIONS[0];private long last=Long.MIN_VALUE,acceptAfter=Long.MIN_VALUE;
    public synchronized void start(int trialsPerCondition,String motion){start(trialsPerCondition,motion,Long.MIN_VALUE);}
    public synchronized void start(int trialsPerCondition,String motion,long captureCutoff){acceptAfter=captureCutoff;limit=Math.max(1,Math.min(1000,trialsPerCondition));this.motion=motion;running=true;last=Long.MIN_VALUE;}
    public synchronized void stop(){running=false;}
    public synchronized boolean running(){return running;}
    public synchronized void clear(){activeTarget=null;reports.clear();trials.clear();lights.clear();motionFrames.clear();motionError.clear();capturedFrames.clear();computedFrames.clear();newestFrame=Long.MIN_VALUE;last=Long.MIN_VALUE;}
    public synchronized void computedFrame(){if(running)motionFrames.computeIfAbsent(motion,k->new long[2])[1]++;}
    public synchronized void frame(boolean computedPose){if(running){long[] f=motionFrames.computeIfAbsent(motion,k->new long[2]);f[0]++;if(computedPose)f[1]++;}}
    public synchronized void observe(DiagnosticSample s){if(!running||s.frameNanos<acceptAfter||s.frameNanos<=last)return;
        if(activeTarget!=null&&!activeTarget.equals(s.target)){reports.put(activeTarget,reportCurrent());trials.clear();lights.clear();motionFrames.clear();motionError.clear();capturedFrames.clear();computedFrames.clear();}
        activeTarget=s.target;last=s.frameNanos;
        int[] counts=lights.computeIfAbsent(s.lighting,k->new int[2]);if(counts[0]>=limit){running=false;return;}
        counts[0]++;if(s.pass&&s.method.equals("ORB_HOMOGRAPHY")&&s.accuracy()>=85)counts[1]++;if(trials.size()<12000)trials.add(s);
        if(counts[0]>=limit)running=false;
    }
    public synchronized String report(){StringBuilder all=new StringBuilder();for(Map.Entry<String,String> e:reports.entrySet())all.append("Target: ").append(e.getKey()).append('\n').append(e.getValue()).append('\n');return all.append("Current target: ").append(activeTarget==null?"none":activeTarget).append('\n').append(reportCurrent()).toString();}
    private String reportCurrent(){
        StringBuilder b=new StringBuilder("Academic diagnostic report\nA trial is a fresh analyzed scan. Missing metrics remain UNMEASURED.\n");
        double acc=0,extract=0,match=0,tre=0;int na=0,nt=0,np=0,correct=0,wrong=0,nTime=0;
        for(DiagnosticSample s:trials){if(Double.isFinite(s.accuracy())){acc+=s.accuracy();na++;}if(Double.isFinite(s.extractionMs)&&Double.isFinite(s.matchingMs)&&s.method.startsWith("ORB")){extract+=s.extractionMs;match+=s.matchingMs;nTime++;}if(s.correctPose!=null){np++;if(s.correctPose)correct++;else wrong++;}if(Double.isFinite(s.treMm)){tre+=s.treMm;nt++;}}
        b.append("Trials: ").append(trials.size()).append("\n\nA · ORB performance\n");
        metric(b,"Mean deduplicated match yield",na==0?Double.NaN:acc/na,85,true,"%");
        for(String l:LIGHTS){int[] n=lights.get(l);double rate=n==null||n[0]<limit?Double.NaN:n[1]*100.0/n[0];metric(b,l+" recognition ("+(n==null?0:n[0])+"/"+limit+" trials)",rate,80,true,"%");}
        metric(b,"Feature extraction",nTime==0?Double.NaN:extract/nTime,300,false,"ms");metric(b,"Matching",nTime==0?Double.NaN:match/nTime,200,false,"ms");
        b.append("\nB · Checkerboard ground truth\n");metric(b,"Correct pose",np==0?Double.NaN:100.0*correct/np,85,true,"%");metric(b,"TRE",nt==0?Double.NaN:tre/nt,10,false,"mm");metric(b,"Orientation mismatch",np==0?Double.NaN:100.0*wrong/np,15,false,"%");
        b.append("\nC · Overlay precision\n");
        for(Map.Entry<String,long[]> e:motionFrames.entrySet()){long[] f=e.getValue();double[] err=motionError.get(e.getKey());b.append(e.getKey()).append('\n');
            metric(b,"Mean per-frame anchor registration error",err==null||err[1]==0?Double.NaN:err[0]/err[1],15,false,"px");metric(b,"Maximum anchor registration error",err==null||err[1]==0?Double.NaN:err[2],30,false,"px");metric(b,"Last measured static offset",err==null?Double.NaN:err[3],10,false,"mm");
            metric(b,"Computed pose coverage ("+f[0]+" delivered frames)",f[0]==0?Double.NaN:100.0*f[1]/f[0],90,true,"%");
        }
        b.append("\nPredicted holds are excluded from computed pose coverage. Millimeter errors require an independently measured checkerboard anchor. Sensor frames dropped by CameraX are not counted as delivered frames.\n");return b.toString();
    }
    private static void metric(StringBuilder b,String title,double value,double threshold,boolean high,String unit){b.append(title).append(": ");if(!Double.isFinite(value))b.append("UNMEASURED / INCOMPLETE");else b.append(String.format(Locale.US,"%.2f %s · %s",value,unit,(high?value>=threshold:value<=threshold)?"PASS":"FAIL"));b.append('\n');}
}
