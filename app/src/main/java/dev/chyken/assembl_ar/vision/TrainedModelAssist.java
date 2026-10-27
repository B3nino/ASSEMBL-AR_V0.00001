package dev.chyken.assembl_ar.vision;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.SystemClock;
import android.util.Log;
import dev.chyken.assembl_ar.camera.ActiveReferenceScope;
import dev.chyken.assembl_ar.diagnostics.PreferencesManager;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;
import org.opencv.core.*;
import org.opencv.dnn.*;
import org.opencv.imgproc.Imgproc;

/** Single-flight model worker. Qualified temporal predictions can own 2D identity.
 * Native network access and model switching belong exclusively to this worker. */
final class TrainedModelAssist implements AutoCloseable {
    static final class Hints {
        final int width,height; final long sourceNanos; final double inferenceMs;
        private final List<Rect> boxes;
        final List<TrainedDetection> detections;
        Hints(int width,int height,long source,double ms,List<Rect> boxes){
            this(width,height,source,ms,boxes,Collections.emptyList());
        }
        Hints(int width,int height,long source,double ms,List<Rect> boxes,List<TrainedDetection> detections){
            this.width=width;this.height=height;sourceNanos=source;inferenceMs=ms;
            List<Rect> owned=new ArrayList<>();for(Rect r:boxes)owned.add(new Rect(r.x,r.y,r.width,r.height));
            this.boxes=Collections.unmodifiableList(owned);
            this.detections=Collections.unmodifiableList(new ArrayList<>(detections));
        }
        List<Rect> windows(int w,int h){
            List<Rect> result=new ArrayList<>();
            for(Rect r:boxes){
                double pad=Math.max(8,Math.max(r.width,r.height)*.22);
                int l=Math.max(0,(int)Math.floor((r.x-pad)*w/width)),t=Math.max(0,(int)Math.floor((r.y-pad)*h/height));
                int right=Math.min(w,(int)Math.ceil((r.x+r.width+pad)*w/width)),bottom=Math.min(h,(int)Math.ceil((r.y+r.height+pad)*h/height));
                if(right-l>=16&&bottom-t>=16)result.add(new Rect(l,t,right-l,bottom-t));
            }
            return result;
        }
    }
    private static final class Completed {
        final String scope;final long generation;final Hints hints;final byte[] luminance,detail;
        Completed(String scope,long generation,Hints hints,byte[] luminance,byte[] detail){this.scope=scope;this.generation=generation;this.hints=hints;this.luminance=luminance;this.detail=detail;}
    }
    private final Context context;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{
        Thread t=new Thread(r,"trained-component-detector");
        t.setDaemon(true);return t;
    });
    private final AtomicBoolean busy=new AtomicBoolean(),closed=new AtomicBoolean();
    private final AtomicLong generation=new AtomicLong();
    private final AtomicReference<Completed> completed=new AtomicReference<>();
    private volatile String scope;
    private volatile Object requestTag;
    private volatile String geometry="";
    private volatile long lastQuery=Long.MIN_VALUE;
    private volatile long inferenceNanos,backoffUntil;
    private volatile int preparedPhase=-1;
    private volatile int unavailablePhases;
    volatile long consumedHints;
    private int failedProposals,consecutiveFailures;
    // Worker-owned: one strongly referenced network, no model bank in native memory.
    private Net network;private int loadedPhase=-1;private String[] components;
    private final Set<Integer> disabled=new HashSet<>();
    private List<TrainedDetection> previousDetections=Collections.emptyList();
    private long previousSource,previousGeneration=-1;
    private String[] labels;
    TrainedModelAssist(Context context){this.context=context.getApplicationContext();}
    boolean enabled(){return !closed.get()&&PreferencesManager.trainedModels(context);}
    boolean handles(RecognitionRequest request){return enabled()&&TrainedModelPolicy.assisted(request)
            &&(unavailablePhases&(1<<TrainedModelPolicy.modelPhase(request)))==0;}
    /** Import the current phase while its instruction/reference bank is being displayed. */
    synchronized void preload(int phase){
        if(!enabled()||!TrainedModelPolicy.packaged(phase)||preparedPhase==phase||!busy.compareAndSet(false,true))return;
        try{worker.execute(()->{try{if(!closed.get()){
            prepare(phase);Mat warm=new Mat(512,512,CvType.CV_8UC3,Scalar.all(114));
            try{infer(warm,"warmup-only");}finally{warm.release();}
        }}catch(IOException|JSONException|RuntimeException failure){Log.w("TrainedAssist","Model preload will retry on scan",failure);}finally{busy.set(false);}});}
        catch(RuntimeException failure){busy.set(false);}
    }
    private String geometryKey(AssemblyVisionAnalyzer.FrameMetadata f){
        return f.getSourceWidth()+":"+f.getSourceHeight()+":"+f.getCropLeft()+":"+f.getCropTop()+":"+f.getCropWidth()+":"+f.getCropHeight()+":"+f.getRotationDegrees();
    }
    synchronized void frame(AssemblyVisionAnalyzer.FrameMetadata frame){
        String key=geometryKey(frame);if(!geometry.equals(key)){generation.incrementAndGet();completed.set(null);geometry=key;lastQuery=Long.MIN_VALUE;backoffUntil=0;}
    }
    boolean geometryMatches(AssemblyVisionAnalyzer.FrameMetadata frame){return geometry.equals(geometryKey(frame));}
    private String key(RecognitionRequest request){return ActiveReferenceScope.key(request)+":"+request.getPhase2VerificationView()+":"
            +(request.getExpectation()==null?"":request.getExpectation().getComponentId())+":"+request.getExpectedInstanceCount();}
    synchronized void scope(RecognitionRequest request){
        String next=key(request);
        if(!Objects.equals(scope,next)||!Objects.equals(requestTag,request.getRequestTag())){
            scope=next;requestTag=request.getRequestTag();generation.incrementAndGet();completed.set(null);lastQuery=Long.MIN_VALUE;backoffUntil=0;failedProposals=0;
            Log.i("TrainedAssist","routing=full-components-v2 scope="+next+" modelPhase="+TrainedModelPolicy.modelPhase(request)+" enabled="+enabled());
        }
    }
    Hints take(RecognitionRequest request,Mat gray,long timestamp){
        scope(request);
        if(!handles(request)){completed.set(null);return null;}
        Completed c=completed.getAndSet(null);if(c==null)return null;
        boolean same=c.generation==generation.get()&&Objects.equals(c.scope,scope);
        if(same&&timestamp>=c.hints.sourceNanos&&timestamp-c.hints.sourceNanos>TrainedModelPolicy.MAX_AGE_NS){
            // A cold import/forward can outlive the hint. Keep the freshness gate,
            // but do not make the now-warm network wait a cold-duration cooldown.
            lastQuery=Long.MIN_VALUE;
            Log.i("TrainedAssist","discard=expired retry=current-frame scope="+scope);return null;
        }
        if(c.hints.windows(gray.cols(),gray.rows()).isEmpty())return null;
        double difference=Double.POSITIVE_INFINITY;
        if(same&&timestamp>=c.hints.sourceNanos&&timestamp-c.hints.sourceNanos<=TrainedModelPolicy.MAX_AGE_NS){
            byte[] current=fingerprint(gray);long sum=0;
            for(int i=0;i<current.length;i++)sum+=Math.abs((current[i]&255)-(c.luminance[i]&255));
            difference=sum/(double)current.length;
        }
        if(!TrainedModelPolicy.fresh(c.hints.sourceNanos,timestamp,same,gray.cols(),gray.rows(),c.hints.width,c.hints.height,difference))return null;
        // Search hints tolerate more age than identity. A neural acceptance must
        // still describe the current physical body, even if the background is static.
        byte[] detail=fingerprint(gray,128,96);List<TrainedDetection> current=new ArrayList<>();
        for(TrainedDetection d:c.hints.detections){
            boolean appearance=timestamp-c.hints.sourceNanos<=1_250_000_000L
                    &&TrainedDetection.sameAppearance(c.detail,detail,128,96,d.bounds,c.hints.width,c.hints.height);
            if(d.confidence>=.85)Log.i("TrainedAssist","candidate="+d.component+" confidence="+d.confidence+" predictions="+d.confirmations
                    +" currentAppearance="+appearance+" ageMs="+(timestamp-c.hints.sourceNanos)/1e6+" bounds="+d.bounds);
            current.add(appearance?d:new TrainedDetection(d.component,d.label,d.bounds,d.confidence,d.margin,1));
        }
        consumedHints++;return new Hints(c.hints.width,c.hints.height,c.hints.sourceNanos,c.hints.inferenceMs,c.hints.boxes,current);
    }
    boolean due(RecognitionRequest request){
        if(!handles(request)||busy.get())return false;
        long now=SystemClock.elapsedRealtimeNanos();
        long interval=Math.max(TrainedModelPolicy.QUERY_INTERVAL_NS,Math.min(1_500_000_000L,inferenceNanos+100_000_000L));
        return now>=backoffUntil&&(lastQuery==Long.MIN_VALUE||now-lastQuery>=interval);
    }
    /** Caller retains RGB/gray. This method detaches one owned frame and never queues a backlog. */
    synchronized void submit(RecognitionRequest request,Mat rgb,Mat gray,long timestamp){
        // Only the detector advances scope. A late tracking callback cannot switch it back.
        if(!Objects.equals(scope,key(request))||!Objects.equals(requestTag,request.getRequestTag())
                ||!due(request)||!busy.compareAndSet(false,true))return;
        Mat owned=null;
        try{
            final String requestedScope=scope;final long version=generation.get();
            final int phase=TrainedModelPolicy.modelPhase(request);final String component=request.getExpectation().getComponentId();
            final byte[] luminance=fingerprint(gray),detail=fingerprint(gray,128,96);final int width=gray.cols(),height=gray.rows();
            owned=rgb.clone();final Mat input=owned;
            lastQuery=SystemClock.elapsedRealtimeNanos();
            worker.execute(()->{
                try{
                    if(closed.get()||version!=generation.get()||!enabled())return;
                    long start=SystemClock.elapsedRealtimeNanos();List<Rect> boxes=new ArrayList<>();String source="trained";
                    prepare(phase);if(network==null||disabled.contains(phase))return;
                    if(closed.get()||version!=generation.get()||!enabled())return;
                    long forwardStarted=SystemClock.elapsedRealtimeNanos();
                    List<TrainedDetection> detections=inferDetections(input);
                    detections=TrainedDetection.confirm(detections,previousGeneration==version?previousDetections:Collections.emptyList(),timestamp,previousSource);
                    previousDetections=detections;previousSource=timestamp;previousGeneration=version;
                    for(TrainedDetection d:detections)if(TrainedModelPolicy.proposes(request,d.component)&&boxes.size()<6)boxes.add(d.bounds);
                    consecutiveFailures=0;
                    long finished=SystemClock.elapsedRealtimeNanos();inferenceNanos=finished-forwardStarted;double ms=(finished-start)/1e6;
                    if(!closed.get()&&version==generation.get()&&enabled())
                        completed.set(new Completed(requestedScope,version,new Hints(width,height,timestamp,ms,boxes,detections),luminance,detail));
                    Log.i("TrainedAssist","phase="+phase+" frame="+timestamp+" target="+component+" source="+source+" inferenceMs="+ms+" proposals="+boxes.size()+" verification=unmeasured");
                }catch(IOException|JSONException|IllegalArgumentException failure){
                    disabled.add(phase);unavailablePhases|=1<<phase;network=null;loadedPhase=-1;
                    Log.w("TrainedAssist","Phase "+phase+" assistance unavailable; original verification continues",failure);
                }catch(RuntimeException failure){
                    network=null;loadedPhase=-1;
                    if(version==generation.get())backoffUntil=SystemClock.elapsedRealtimeNanos()+Math.min(30_000_000_000L,2_000_000_000L*++consecutiveFailures);
                    Log.w("TrainedAssist","Trained search will retry automatically; original verification continues",failure);
                }finally{input.release();busy.set(false);}
            });
            owned=null;
        }catch(RuntimeException failure){busy.set(false);Log.w("TrainedAssist","Recovery frame unavailable",failure);}
        finally{if(owned!=null)owned.release();}
    }
    private void prepare(int phase)throws IOException,JSONException {
        if(disabled.contains(phase)||loadedPhase==phase&&network!=null)return;
        network=null;loadedPhase=-1;components=null;
        if(!TrainedModelPolicy.packaged(phase))throw new IOException("No model for phase");
        AssetManager assets=context.getAssets();String stem="models/trained/phase"+phase;
        JSONObject contract;try(InputStream in=assets.open(stem+".json")){contract=new JSONObject(new String(read(in,65536),StandardCharsets.UTF_8));}
        if(!("phase"+phase+".onnx").equals(contract.getString("model"))||!"float32".equals(contract.getString("precision"))
                ||!"RGB".equals(contract.getJSONObject("input").getString("color"))
                ||!"float32".equals(contract.getJSONObject("input").getString("dtype")))throw new IOException("Mismatched model metadata");
        JSONArray shape=contract.getJSONObject("input").getJSONArray("shape");
        if(shape.length()!=4||shape.getInt(0)!=1||shape.getInt(1)!=3||shape.getInt(2)!=512||shape.getInt(3)!=512
                ||contract.getBoolean("dynamic")||contract.getBoolean("embedded_nms")||contract.getInt("opset")!=12)
            throw new IOException("Unsupported trained input contract");
        JSONObject classes=contract.getJSONObject("classes");components=new String[classes.length()];labels=new String[classes.length()];
        if(components.length<1||components.length>32)throw new IOException("Invalid class count");
        for(int i=0;i<components.length;i++){labels[i]=classes.getString(Integer.toString(i));components[i]=TrainedModelPolicy.component(labels[i]);}
        byte[] bytes;try(InputStream in=assets.open(stem+".onnx")){bytes=read(in,16*1024*1024);}
        try{
            byte[] hash=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder hex=new StringBuilder();
            for(byte b:hash)hex.append(String.format(Locale.ROOT,"%02x",b&255));
            if(!hex.toString().equals(contract.getString("sha256")))throw new IOException("Model checksum mismatch");
        }catch(java.security.NoSuchAlgorithmException impossible){throw new IOException(impossible);}
        MatOfByte encoded=new MatOfByte(bytes);
        try{network=Dnn.readNetFromONNX(encoded);}finally{encoded.release();}
        if(network.empty())throw new IOException("Empty model");
        network.setPreferableBackend(Dnn.DNN_BACKEND_OPENCV);network.setPreferableTarget(Dnn.DNN_TARGET_CPU);loadedPhase=phase;preparedPhase=phase;
        Log.i("TrainedAssist","loaded phase="+phase+" sha256="+contract.getString("sha256")+" input=RGB/512/255 runtime=OpenCV-CPU");
    }
    private List<Rect> infer(Mat rgb,String component){return infer(rgb,component,null);}
    private List<Rect> infer(Mat rgb,String component,RecognitionRequest request){
        List<Rect> boxes=new ArrayList<>();
        for(TrainedDetection d:inferDetections(rgb))if((request==null?component.equals(d.component):TrainedModelPolicy.proposes(request,d.component))&&boxes.size()<6)boxes.add(d.bounds);
        return boxes;
    }
    private List<TrainedDetection> inferDetections(Mat rgb){
        DnnRoiDecoder.Letterbox transform=new DnnRoiDecoder.Letterbox(rgb.cols(),rgb.rows(),512);
        Mat resized=new Mat(),padded=new Mat(),blob=null,output=null,flat=null;
        try{
            Imgproc.resize(rgb,resized,new Size(transform.resizedWidth,transform.resizedHeight),0,0,Imgproc.INTER_LINEAR);
            Core.copyMakeBorder(resized,padded,transform.top,512-transform.resizedHeight-transform.top,transform.left,512-transform.resizedWidth-transform.left,Core.BORDER_CONSTANT,new Scalar(114,114,114));
            blob=Dnn.blobFromImage(padded,1.0/255,new Size(512,512),Scalar.all(0),false,false,CvType.CV_32F);
            network.setInput(blob);output=network.forward();
            if(output.type()!=CvType.CV_32FC1||output.dims()!=3||output.size(0)!=1||output.size(1)!=4+components.length||output.size(2)!=5376)
                throw new IllegalArgumentException("Unexpected trained output shape");
            float[] values=new float[(int)output.total()];flat=output.reshape(1,1);flat.get(0,0,values);
            List<TrainedDetection> detections=new ArrayList<>();
            for(DnnRoiDecoder.Detection d:DnnRoiDecoder.detections(values,components.length,5376,.35,TrainedModelPolicy.NMS_IOU,transform))
                detections.add(new TrainedDetection(components[d.label],labels[d.label],d.bounds,d.confidence,d.margin,1));
            return detections;
        }finally{resized.release();padded.release();if(flat!=null)flat.release();if(output!=null)output.release();if(blob!=null)blob.release();}
    }
    private static byte[] fingerprint(Mat gray){
        return fingerprint(gray,32,24);
    }
    private static byte[] fingerprint(Mat gray,int width,int height){
        Mat small=new Mat();try{Imgproc.resize(gray,small,new Size(width,height),0,0,Imgproc.INTER_AREA);byte[] bytes=new byte[width*height];small.get(0,0,bytes);return bytes;}finally{small.release();}
    }
    private static byte[] read(InputStream in,int maximum)throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[16384];
        for(int n;(n=in.read(buffer))!=-1;){if(out.size()+n>maximum)throw new IOException("Asset exceeds model budget");out.write(buffer,0,n);}return out.toByteArray();
    }
    synchronized void outcome(boolean verified){
        if(verified)failedProposals=0;
        // A photo-verifier miss must not suspend the primary component detector.
        else failedProposals++;
    }
    synchronized void reset(){generation.incrementAndGet();completed.set(null);scope=null;requestTag=null;lastQuery=Long.MIN_VALUE;backoffUntil=0;failedProposals=0;}
    @Override public void close(){
        if(!closed.compareAndSet(false,true))return;reset();
        try{worker.execute(()->{network=null;components=null;loadedPhase=-1;});}finally{worker.shutdown();}
    }
}
