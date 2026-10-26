package dev.chyken.assembl_ar.vision;

import android.content.res.AssetManager;
import android.os.Looper;
import org.json.*;
import org.opencv.core.*;
import org.opencv.dnn.*;
import org.opencv.imgproc.Imgproc;
import java.io.*;
import java.util.*;

/** One process-owned native CPU detector. Neural output proposes a crop only;
 * photo matching, RANSAC, orientation and mounting-context checks still verify it. */
public final class OpenCvDnnRoiLocator {
    public static final OpenCvDnnRoiLocator INSTANCE=new OpenCvDnnRoiLocator();
    public static final class Proposal {
        public final Rect anchor,search;public final float confidence;public final double inferenceMs;public final String kind;
        Proposal(Rect anchor,Rect search,float confidence,double ms,String kind){this.anchor=anchor;this.search=search;this.confidence=confidence;inferenceMs=ms;this.kind=kind;}
    }
    private Net network;
    private boolean attempted,disabled,chip;
    private int size;private String format,reason="Not initialized";
    private double scale,threshold;private Scalar mean;
    private final Map<Integer,String> labels=new HashMap<>();
    private Mat resized,letterbox;
    private float[] values=new float[0];
    private OpenCvDnnRoiLocator() { }
    public synchronized boolean available(){return network!=null&&!disabled;}
    public synchronized boolean supports(String component){return available()&&labels.containsValue(component);}
    public synchronized String status(){return reason;}
    /** Startup worker only; no new dependency, model download or camera session. */
    public synchronized void prepare(AssetManager assets){
        if(attempted)return;
        offMain();attempted=true;
        try(InputStream manifest=assets.open("models/roi_locator.json")){
            JSONObject c=new JSONObject(new String(read(manifest,65536),java.nio.charset.StandardCharsets.UTF_8));
            if(!c.optBoolean("enabled",false)){reason="Disabled in model manifest";return;}
            format=c.getString("output");
            if(!format.equals("ssd_xyxy_normalized")&&!format.equals("yolo_cxcywh"))throw new IOException("Unsupported detector output format");
            String model=c.getString("model");
            if(!model.matches("models/[A-Za-z0-9_.-]+\\.onnx"))throw new IOException("Expected a packaged ONNX asset");
            size=c.getInt("inputSize");scale=c.getDouble("scale");threshold=c.getDouble("confidence");chip=c.optString("anchorKind","component").equals("chip");
            if(size<96||size>640||!Double.isFinite(scale)||scale<=0||!Double.isFinite(threshold)||threshold<.8||threshold>1)throw new IOException("Invalid detector input configuration");
            JSONArray means=c.getJSONArray("meanRgb");mean=new Scalar(means.getDouble(0),means.getDouble(1),means.getDouble(2));
            for(double v:mean.val)if(!Double.isFinite(v))throw new IOException("Finite input means required");
            JSONObject names=c.getJSONObject("labels");for(Iterator<String> i=names.keys();i.hasNext();){String k=i.next();int index=Integer.parseInt(k);if(index<0||index>=32)throw new IOException("Invalid class index");labels.put(index,names.getString(k));}
            if(labels.isEmpty())throw new IOException("Kit component labels required");
            byte[] bytes;try(InputStream input=assets.open(model)){bytes=read(input,32*1024*1024);}
            MatOfByte encoded=new MatOfByte(bytes);
            try{network=Dnn.readNetFromONNX(encoded);}finally{encoded.release();}
            if(network.empty())throw new IOException("Empty detector");
            network.setPreferableBackend(Dnn.DNN_BACKEND_OPENCV);network.setPreferableTarget(Dnn.DNN_TARGET_CPU);
            reason="Ready: "+(chip?"ATmega chip ROI":"component ROI")+" / OpenCV CPU";
            android.util.Log.i("DnnRoiLocator",reason);
        }catch(FileNotFoundException absent){disabled=true;reason="No packaged detector";}
        catch(IOException|JSONException|RuntimeException invalid){disabled=true;reason="Unavailable: "+invalid.getClass().getSimpleName();android.util.Log.w("DnnRoiLocator","Detector unavailable; photo/geometry remain active",invalid);}
    }
    public synchronized Rect locate(Mat rgb,String component){Proposal p=locateDetailed(rgb,component);return p==null?null:p.search;}
    /** RGB analyzed-camera coordinates. No previous-frame coordinates are returned. */
    public synchronized Proposal locateDetailed(Mat rgb,String component){
        if(!supports(component)||rgb==null||rgb.empty()||rgb.type()!=CvType.CV_8UC3)return null;
        offMain();Mat blob=null,output=null,rows=null;long start=android.os.SystemClock.elapsedRealtimeNanos();
        try{
            if(resized==null){resized=new Mat();letterbox=new Mat();}
            DnnRoiDecoder.Letterbox transform=new DnnRoiDecoder.Letterbox(rgb.cols(),rgb.rows(),size);
            Mat input=rgb;
            if(format.equals("yolo_cxcywh")){
                Imgproc.resize(rgb,resized,new Size(transform.resizedWidth,transform.resizedHeight),0,0,Imgproc.INTER_LINEAR);
                Core.copyMakeBorder(resized,letterbox,transform.top,size-transform.resizedHeight-transform.top,transform.left,size-transform.resizedWidth-transform.left,Core.BORDER_CONSTANT,new Scalar(114,114,114));input=letterbox;
            }
            blob=Dnn.blobFromImage(input,scale,new Size(size,size),mean,false,false,CvType.CV_32F);
            network.setInput(blob);output=network.forward();
            if(output.type()!=CvType.CV_32FC1||output.total()>1_000_000)throw new IllegalArgumentException("Invalid detector output");
            int count=(int)output.total();if(values.length!=count)values=new float[count];
            rows=output.reshape(1,1);rows.get(0,0,values);
            Rect anchor=null;float score=0;
            if(format.equals("yolo_cxcywh")){
                if(output.dims()!=3||output.size(0)!=1||output.size(1)<5||output.size(1)>36)throw new IllegalArgumentException("Expected YOLO [1,4+classes,anchors]");
                for(Map.Entry<Integer,String> entry:labels.entrySet())if(component.equals(entry.getValue())){
                    DnnRoiDecoder.Box candidate=DnnRoiDecoder.yolo(values,output.size(1),output.size(2),entry.getKey(),Math.max(threshold,score),transform);
                    if(candidate!=null){anchor=candidate.bounds;score=candidate.confidence;}
                }
            }else{
                if(count%7!=0||count>7*256)throw new IllegalArgumentException("Invalid SSD output");
                for(int n=0;n<count;n+=7){float id=values[n+1],p=values[n+2];if(!Float.isFinite(id)||id!=Math.rint(id)||!Float.isFinite(p)||p<Math.max(threshold,score)||p>1||!Objects.equals(component,labels.get((int)id)))continue;
                    Rect box=decodeBounds(values[n+3],values[n+4],values[n+5],values[n+6],rgb.cols(),rgb.rows());if(box!=null){anchor=box;score=p;}}
            }
            double elapsed=(android.os.SystemClock.elapsedRealtimeNanos()-start)/1e6;
            android.util.Log.i("DnnRoiLocator","component="+component+" anchor="+(anchor==null?"none":anchor)+" confidence="+score+" inferenceMs="+elapsed);
            return anchor==null?null:new Proposal(anchor,DnnRoiDecoder.searchArea(anchor,rgb.cols(),rgb.rows(),chip),score,elapsed,chip?"ATmega chip":"component");
        }catch(RuntimeException bad){disabled=true;reason="Inference unavailable: "+bad.getClass().getSimpleName();android.util.Log.w("DnnRoiLocator","Detector disabled; photo/geometry remain active",bad);return null;}
        finally{if(rows!=null)rows.release();if(output!=null)output.release();if(blob!=null)blob.release();if(resized!=null)resized.release();if(letterbox!=null)letterbox.release();}
    }
    static Rect decodeBounds(double left,double top,double right,double bottom,int width,int height){
        if(width<=0||height<=0||!Double.isFinite(left+top+right+bottom)||left<0||top<0||right>1||bottom>1||right<=left||bottom<=top)return null;
        int x=(int)Math.floor(left*width),y=(int)Math.floor(top*height),r=(int)Math.ceil(right*width),b=(int)Math.ceil(bottom*height);
        if(r-x<12||b-y<12)return null;
        int pad=Math.max(8,Math.min(r-x,b-y)/10);x=Math.max(0,x-pad);y=Math.max(0,y-pad);r=Math.min(width,r+pad);b=Math.min(height,b+pad);
        return new Rect(x,y,r-x,b-y);
    }
    private static void offMain(){if(Looper.myLooper()==Looper.getMainLooper())throw new IllegalStateException("DNN work must run off the UI thread");}
    private static byte[] read(InputStream input,int max)throws IOException{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;
        while((n=input.read(buffer))!=-1){if(bytes.size()+n>max)throw new IOException("Model exceeds configured memory budget");bytes.write(buffer,0,n);}return bytes.toByteArray();
    }
}
